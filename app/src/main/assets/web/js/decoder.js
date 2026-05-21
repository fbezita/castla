/**
 * WebCodecs H.264 Decoder
 * Decodes raw H.264 NAL units using hardware-accelerated VideoDecoder
 */

const DecoderState = {
  UNINITIALIZED: "UNINITIALIZED",
  WAITING_SPS_PPS: "WAITING_SPS_PPS",
  WAITING_KEYFRAME: "WAITING_KEYFRAME",
  DECODING: "DECODING",
  RECOVERING: "RECOVERING",
  ERROR: "ERROR",
};

class H264Decoder {
  // Profile-dependent backlog thresholds.
  // Higher buffer profiles tolerate more decode queue depth
  // before dropping, since the pacer absorbs the extra latency.

  // Profile-dependent backlog thresholds.
  // Greatly expanded to prevent transient GPU spikes from triggering destructive
  // delta frame drops, which cause immediate stream corruption and keyframe storm stall loops.
  static BACKLOG_THRESHOLDS = {
    low_latency: 12,
    balanced: 24,
    smooth: 40,
  };
  

  constructor(onFrame, onError) {
    this.onFrame = onFrame;
    this.onError = onError;
    this.onFrameGap = null; // Callback when frame gap is detected
    this.decoder = null;
    this.configured = false;
    this.frameCount = 0;
    this.startTime = 0;
    this.codecString = null; // dynamically detected from SPS
    this._backlogProfile = "balanced";
    this.state = DecoderState.UNINITIALIZED;
    this._lastSeqNum = undefined;
    this._cachedSpsPps = null;
    this._lastGapRequestTime = 0; // Throttle frame gap recovery requests to prevent congestion collapse

    // Backlog metrics
    this._backlogHits = 0;
    this._backlogDrops = 0;
    this._lastBacklogWarn = 0;
  }

  _transitionTo(newState) {
    if (this.state === newState) return;
    // console.log(`[Decoder] State transition: ${this.state} -> ${newState}`);
    this.state = newState;
  }

  setBacklogProfile(profileName) {
    if (H264Decoder.BACKLOG_THRESHOLDS[profileName] !== undefined) {
      this._backlogProfile = profileName;
    }
  }

  static isSupported() {
    return typeof VideoDecoder !== "undefined";
  }

  async init() {
    if (!H264Decoder.isSupported()) {
      this._transitionTo(DecoderState.ERROR);
      throw new Error("WebCodecs VideoDecoder not available");
    }

    // Check H.264 support — try High Profile first (better compression),
    // then Baseline as fallback. Actual codec string will be updated from SPS.
    const codecs = ["avc1.640028", "avc1.42001e"];
    let supportedCodec = null;
    for (const codec of codecs) {
      const support = await VideoDecoder.isConfigSupported({
        codec,
        optimizeForLatency: true,
        hardwareAcceleration: "prefer-hardware",
      });
      if (support.supported) {
        supportedCodec = codec;
        break;
      }
    }

    if (!supportedCodec) {
      this._transitionTo(DecoderState.ERROR);
      throw new Error("H.264 not supported");
    }

    this.codecString = supportedCodec;

    // Clean up previous decoder instance if exists to prevent hardware leak
    if (this.decoder && this.decoder.state !== "closed") {
      try {
        this.decoder.close();
      } catch (_) {}
    }

    this.decoder = new VideoDecoder({
      output: (frame) => {
        this.frameCount++;
        // if (this.frameCount % 60 === 0) {
        //     console.log(`[DecoderTelemetry] VideoDecoder decoded frame successfully. Total decoded frames=${this.frameCount}`);
        // }
        this.onFrame(frame);
      },
      error: (e) => {
        console.error("[Decoder] Hardware decoder error:", e);
        this.onError(e);
        this._transitionTo(DecoderState.ERROR);
        this._handleAutoRecovery();
      },
    });

    this.decoder.configure({
      codec: supportedCodec,
      optimizeForLatency: true,
      hardwareAcceleration: "prefer-hardware",
    });

    this.configured = true;
    this.startTime = performance.now();

    // Advance state smoothly based on config availability
    if (this._cachedSpsPps) {
      this._transitionTo(DecoderState.WAITING_KEYFRAME);
    } else {
      this._transitionTo(DecoderState.WAITING_SPS_PPS);
    }

    // console.log('[Decoder] Initialized with WebCodecs, codec:', supportedCodec);
  }

  _handleAutoRecovery() {
    console.warn("[Decoder] Initiating automatic hardware decoder recovery...");
    this.configured = false;
    this._transitionTo(DecoderState.UNINITIALIZED);
    this.init().catch((err) => {
      console.error("[Decoder] Automatic recovery failed:", err);
      this._transitionTo(DecoderState.ERROR);
    });
  }

  /**
   * Decode a frame received from WebSocket
   * @param {ArrayBuffer} data - 8-byte header + H.264 NAL units
   *   header: [flags:u8][seqLo:u8][seqHi:u8][tsMs0:u8][tsMs1:u8][tsMs2:u8][tsMs3:u8][reserved:u8]
   *   flags: 0x00=delta, 0x01=keyframe, 0x02=codec config (SPS/PPS)
   */
  decode(data) {
    if (!this.configured || !this.decoder || this.decoder.state === "closed") {
      if (this.decoder && this.decoder.state === "closed") {
        console.warn(
          "[Decoder] VideoDecoder is closed, attempting automatic recovery",
        );
        this._handleAutoRecovery();
      }
      return;
    }

    const view = new DataView(data);
    if (data.byteLength < 9) return;

    const flags = view.getUint8(0);
    const seqNum = view.getUint16(1, true); // LE
    const serverTsMs = view.getUint32(3, true); // LE

    // 0x02 = SPS/PPS config — cache and detect codec, don't decode
    if (flags === 0x02) {
      this._cachedSpsPps = data.slice(8);
      this._detectCodecFromSps(new Uint8Array(this._cachedSpsPps));
      if (this.state === DecoderState.WAITING_SPS_PPS) {
        this._transitionTo(DecoderState.WAITING_KEYFRAME);
      }
      return;
    }

    const isKeyFrame = flags === 0x01;

    // Discard delta frames if we are not in active DECODING state.
    // We MUST start with a keyframe after configure or flush to protect hardware decoder.

    if (this.state !== DecoderState.DECODING) {
      if (!isKeyFrame) {
        // if (seqNum % 30 === 0) {
        //     console.log(`[DecoderTelemetry] Discarding delta frame: seqNum=${seqNum} due to state=${this.state}`);
        // }
        // Keep sequence tracker updated to prevent false positive gaps on sync recovery
        this._lastSeqNum = seqNum;
        return;
      } else {
        if (this._cachedSpsPps) {
          // console.log(`[Decoder] Anchor/Recovery keyframe received: seqNum=${seqNum}. Transitioning to DECODING`);
          this._transitionTo(DecoderState.DECODING);
        } else {
          console.warn(
            `[Decoder] Received keyframe but missing SPS/PPS in state=${this.state}. Dropping.`,
          );
          this._lastSeqNum = seqNum;
          return;
        }
      }
    }
    

    // Detect frame drops via sequence gap
    if (this._lastSeqNum !== undefined) {
      const expected = (this._lastSeqNum + 1) & 0xffff;

      if (seqNum !== expected) {
        console.warn(
          `[Decoder] Frame gap detected: expected ${expected} got ${seqNum}. Transitioning to RECOVERING.`,
        );
        this._transitionTo(DecoderState.RECOVERING);

        const now = performance.now();
        if (now - this._lastGapRequestTime > 1500) {
          this._lastGapRequestTime = now;
          if (this.onFrameGap) {
            this.onFrameGap();
          }
        }

        // Discard invalid delta immediately
        if (!isKeyFrame) {
          this._lastSeqNum = seqNum;
          return;
        } else {
          // Recover instantly if this is a keyframe
          this._transitionTo(DecoderState.DECODING);
        }
      }
    }
    this._lastSeqNum = seqNum;

    const nalData = data.slice(8); // Remove 8-byte header

    // On keyframes, prepend cached SPS/PPS for decoder
    let frameData = nalData;
    if (isKeyFrame && this._cachedSpsPps) {
      const spsPps = new Uint8Array(this._cachedSpsPps);
      const combined = new Uint8Array(spsPps.length + nalData.byteLength);
      combined.set(spsPps);
      combined.set(new Uint8Array(nalData), spsPps.length);
      frameData = combined.buffer;
    }

    try {
      const chunk = new EncodedVideoChunk({
        type: isKeyFrame ? "key" : "delta",
        timestamp: serverTsMs * 1000, // server timestamp in microseconds
        data: frameData,
      });

      const queueSize = this.decoder.decodeQueueSize;
      const threshold =
        H264Decoder.BACKLOG_THRESHOLDS[this._backlogProfile] || 5;

      // Delta frames dropped when queue exceeds profile threshold to prevent latency accumulation
      if (!isKeyFrame && queueSize > threshold) {
        this._backlogHits++;
        this._backlogDrops++;
        const now = performance.now();
        if (now - this._lastBacklogWarn > 10000) {
          this._lastBacklogWarn = now;
          console.warn(
            `[Decoder] Backlog: queueSize=${queueSize} threshold=${threshold} totalDrops=${this._backlogDrops}`,
          );
        }
        this._lastSeqNum = seqNum;
        return;
      }

      this.decoder.decode(chunk);
    } catch (e) {
      console.error("[Decoder] Decode error:", e);
      this.onError(e);
      this._transitionTo(DecoderState.ERROR);
      this._handleAutoRecovery();
    }
  }
  

  /**
   * Parse SPS NAL from keyframe to detect actual codec string (avc1.XXYYZZ).
   * Reconfigure decoder if profile changed (e.g. High ↔ Baseline fallback).
   */
  _detectCodecFromSps(nalData) {
    // Find SPS NAL unit (type 7) after start code 0x00000001
    for (let i = 0; i < nalData.length - 7; i++) {
      if (
        nalData[i] === 0 &&
        nalData[i + 1] === 0 &&
        nalData[i + 2] === 0 &&
        nalData[i + 3] === 1
      ) {
        const nalType = nalData[i + 4] & 0x1f;
        if (nalType === 7 && i + 7 < nalData.length) {
          const profile = nalData[i + 5];
          const compat = nalData[i + 6];
          const level = nalData[i + 7];
          const newCodec =
            "avc1." +
            profile.toString(16).padStart(2, "0") +
            compat.toString(16).padStart(2, "0") +
            level.toString(16).padStart(2, "0");
          if (newCodec !== this.codecString) {
            console.log(
              "[Decoder] Codec changed:",
              this.codecString,
              "->",
              newCodec,
            );
            this.codecString = newCodec;
            try {
              this.decoder.configure({
                codec: newCodec,
                optimizeForLatency: true,
                hardwareAcceleration: "prefer-hardware",
              });
            } catch (e) {
              console.warn("[Decoder] Reconfigure failed for", newCodec, e);
            }
          }
          return;
        }
      }
    }
  }

  getFps() {
    const elapsed = (performance.now() - this.startTime) / 1000;
    if (elapsed < 1) return 0;
    return Math.round(this.frameCount / elapsed);
  }

  resetStats() {
    this.frameCount = 0;
    this.startTime = performance.now();
  }

  getBacklogMetrics() {
    return {
      backlogHits: this._backlogHits,
      backlogDrops: this._backlogDrops,
      decodeQueueSize: this.decoder ? this.decoder.decodeQueueSize : 0,
    };
  }

  destroy() {
    if (this.decoder && this.decoder.state !== "closed") {
      try {
        this.decoder.close();
      } catch (_) {}
    }
    this.decoder = null;
    this.configured = false;
  }
}

import type { EncodedFrame } from "../protocol";
import type { DecoderBackend } from "./DecoderBackend";
import { PreDecodeDelayQueue } from "./PreDecodeDelayQueue";
import { clampVideoLatencyMs } from "./videoLatency";

/**
 * WebCodecs backend.
 *
 * Important ordering rule copied from the stable JMuxer flow:
 *
 * 1. Config frame(SPS/PPS) is metadata. Cache it and configure the decoder.
 *    Do not decode it as a video chunk.
 *    Do not request recovery just because a config frame arrived.
 *
 * 2. Delta frames before the first accepted keyframe are stale. Drop quietly.
 *
 * 3. A keyframe starts or restarts the stream. If cached config exists, use it
 *    as VideoDecoderConfig.description, then decode the keyframe.
 */
export class WebCodecsBackend implements DecoderBackend {
  private canvas?: HTMLCanvasElement;
  private decoder?: VideoDecoder;
  private ctx?: CanvasRenderingContext2D;
  private configPayload?: ArrayBuffer;
  private configuredCodec = "";
  private hasDecodedKeyframe = false;
  private pendingConfigure = false;
  private destroyed = false;
  private renderedFrames = 0;
  private droppedDeltaBeforeKeyframe = 0;
  private lastDropLogAt = 0;
  private lastKeyframeRequestAt = 0;
  private onFrame?: () => void;
  private requestKeyframe?: () => void;
  private onStatus?: (event: string, detail?: string) => void;
  private isScreenOff?: () => boolean;
  private isVideoFrozen?: () => boolean;
  private videoLatencyMs = 0;
  private readonly delayedDecodeQueue = new PreDecodeDelayQueue<EncodedFrame>(
    (frame) => this.decodeNow(frame),
  );

  private lastLatencyRecoveryAt = 0;

  private static readonly MAX_DECODE_QUEUE_SIZE = 4;
  private static readonly LATENCY_RECOVERY_COOLDOWN_MS = 1000;

  constructor(
    onFrame?: () => void,
    requestKeyframe?: () => void,
    onStatus?: (event: string, detail?: string) => void,
    isScreenOff?: () => boolean,
    isVideoFrozen?: () => boolean,
  ) {
    this.onFrame = onFrame;
    this.requestKeyframe = requestKeyframe;
    this.onStatus = onStatus;
    this.isScreenOff = isScreenOff;
    this.isVideoFrozen = isVideoFrozen;
  }

  async initialize(
    target: HTMLCanvasElement | HTMLVideoElement,
  ): Promise<void> {
    if (!(target instanceof HTMLCanvasElement)) {
      throw new Error("WebCodecs backend requires canvas");
    }
    if (!window.isSecureContext || typeof VideoDecoder === "undefined") {
      throw new Error("WebCodecs unavailable");
    }

    this.destroyed = false;
    this.canvas = target;
    this.ctx = target.getContext("2d") ?? undefined;
    if (!this.ctx) throw new Error("2D canvas context unavailable");

    this.createDecoder();
    this.onStatus?.("webcodecsReady", `secure=${window.isSecureContext}`);
  }

  setVideoLatencyMs(latencyMs: number): void {
    const next = clampVideoLatencyMs(latencyMs);
    if (next === this.videoLatencyMs) return;
    const cleared = this.delayedDecodeQueue.clear();
    this.videoLatencyMs = next;
    if (cleared > 0) {
      this.hasDecodedKeyframe = false;
      this.requestKeyframeThrottled("latency_changed");
    }
  }

  decode(frame: EncodedFrame): void {
    if (this.destroyed) return;

    // SPS/PPS/config frame: cache and configure only.
    // This is equivalent to JMuxerBackend storing configPayload and returning.
    if (frame.config) {
      this.configPayload = frame.payload;
      this.configureDecoderIfNeeded("config_frame");
      this.onStatus?.("webcodecsConfig", `bytes=${frame.payload.byteLength}`);
      return;
    }

    this.delayedDecodeQueue.enqueue(frame, this.videoLatencyMs);
  }

  private decodeNow(frame: EncodedFrame): void {
    if (this.destroyed) return;

    if (!this.decoder) {
      this.createDecoder();
    }

    // Delta frames are not useful until a keyframe has been accepted after
    // configuration. Drop quietly to avoid recovery/keyframe loops.
    if (!frame.keyFrame && !this.hasDecodedKeyframe) {
      this.droppedDeltaBeforeKeyframe += 1;
      this.throttledStatus(
        "webcodecsDropDeltaBeforeKeyframe",
        `seq=${frame.sequence} dropped=${this.droppedDeltaBeforeKeyframe}`,
        3000,
      );
      return;
    }

    if (frame.keyFrame) {
      this.configureDecoderIfNeeded("keyframe");
      this.hasDecodedKeyframe = true;
      this.droppedDeltaBeforeKeyframe = 0;
    }

    const decoder = this.decoder;
    if (!decoder || decoder.state === "closed") return;

    // WebCodecs does not apply backpressure. Some Android encoders publish at
    // the display refresh rate even when configured for a lower FPS, so the
    // decoder queue can grow indefinitely and make touch feedback appear
    // increasingly late. Prefer the live edge over decoding stale frames.
    if (
      !frame.keyFrame &&
      decoder.decodeQueueSize > WebCodecsBackend.MAX_DECODE_QUEUE_SIZE
    ) {
      this.recoverFromDecodeBacklog(decoder.decodeQueueSize);
      return;
    }

    // If no config has arrived yet, wait for a keyframe/config sequence instead
    // of trying to decode and triggering an exception loop.
    if (!this.configuredCodec) {
      if (frame.keyFrame) {
        this.requestKeyframeThrottled("keyframe_without_config");
      }
      return;
    }

    try {
      const timestampMs =
        frame.timestampMs ?? frame.serverTimestampMs ?? performance.now();
      let payload = frame.payload;
      if (frame.keyFrame && this.configPayload) {
        // Concatenate SPS/PPS config payload before the keyframe in Annex-B mode
        const combined = new Uint8Array(
          this.configPayload.byteLength + frame.payload.byteLength,
        );
        combined.set(new Uint8Array(this.configPayload), 0);
        combined.set(
          new Uint8Array(frame.payload),
          this.configPayload.byteLength,
        );
        payload = combined.buffer;
      }

      const chunk = new EncodedVideoChunk({
        type: frame.keyFrame ? "key" : "delta",
        timestamp: timestampMs * 1000,
        data: payload,
      });
      decoder.decode(chunk);
    } catch (error) {
      // A decode exception usually means stale stream data. Do not reset on every
      // packet; mark that we need the next keyframe/config pair.
      this.hasDecodedKeyframe = false;
      this.throttledStatus(
        "webcodecsDecodeError",
        error instanceof Error ? error.message : String(error),
        3000,
      );
      this.requestKeyframeThrottled("decode_error");
    }
  }

  reset(): void {
    // Soft reset only. Do not close/recreate the decoder repeatedly; doing so
    // caused generation/config-frame recovery loops.
    this.hasDecodedKeyframe = false;
    this.droppedDeltaBeforeKeyframe = 0;
    this.delayedDecodeQueue.clear();
    this.requestKeyframeThrottled("soft_reset");
  }

  destroy(): void {
    this.destroyed = true;
    this.delayedDecodeQueue.clear();
    try {
      this.decoder?.close();
    } catch {
      // ignore
    }
    this.decoder = undefined;
    this.canvas = undefined;
    this.ctx = undefined;
    this.configPayload = undefined;
    this.configuredCodec = "";

    this.hasDecodedKeyframe = false;
  }

  private createDecoder(): void {
    if (this.decoder && this.decoder.state !== "closed") return;

    this.decoder = new VideoDecoder({
      output: (frame) => this.renderFrame(frame),
      error: (error) => {
        console.error(`[WebCodecs] VideoDecoder error: ${error.message}`);
        this.hasDecodedKeyframe = false;
        this.configuredCodec = "";
        try {
          this.decoder?.close();
        } catch {}
        this.decoder = undefined;
        this.throttledStatus("webcodecsDecoderError", error.message, 3000);
        this.requestKeyframeThrottled("decoder_error");
      },
    });

    if (this.configPayload) {
      this.configureDecoderIfNeeded("decoder_created");
    }
  }

  private configureDecoderIfNeeded(reason: string): void {
    if (!this.decoder || this.decoder.state === "closed") return;
    if (!this.configPayload) return;
    if (this.pendingConfigure) return;

    const codec =
      this.resolveCodecFromAvcConfig(this.configPayload) ?? "avc1.64002a";

    // Avoid reconfiguring the decoder for the same SPS/PPS repeatedly.
    if (this.configuredCodec === codec) return;

    this.pendingConfigure = true;
    try {
      const config: VideoDecoderConfig = {
        codec,
        optimizeForLatency: true,
      };

      this.decoder.configure(config);
      this.configuredCodec = codec;
      this.hasDecodedKeyframe = false;
      this.onStatus?.("webcodecsConfigured", `${codec} reason=${reason}`);
      console.warn(`[WebCodecs] configured ${codec}`);
    } catch (error) {
      this.configuredCodec = "";
      this.throttledStatus(
        "webcodecsConfigureError",
        error instanceof Error ? error.message : String(error),
        3000,
      );
    } finally {
      this.pendingConfigure = false;
    }
  }

  private renderFrame(frame: VideoFrame): void {
    try {
      const canvas = this.canvas;
      const ctx = this.ctx;
      if (!canvas || !ctx) return;

      // The server stops publishing frames before announcing freezeVideo.
      // Keeping the existing canvas untouched is sufficient to preserve the
      // last frame until screen-on stability and a fresh keyframe are confirmed.
      if (this.isVideoFrozen?.() || this.isScreenOff?.()) {
        this.throttledStatus("webcodecsFreeze", "frame_gate_active", 3000);
        return;
      }
      if (
        canvas.width !== frame.displayWidth ||
        canvas.height !== frame.displayHeight
      ) {
        canvas.width = frame.displayWidth;
        canvas.height = frame.displayHeight;
      }

      ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
      this.renderedFrames += 1;

      // if (this.renderedFrames === 1 || this.renderedFrames % 120 === 0) {
      //   this.onStatus?.(
      //     "webcodecsFrame",
      //     `rendered=${this.renderedFrames} ${canvas.width}x${canvas.height}`,
      //   );
      // }

      this.onFrame?.();
    } finally {
      frame.close();
    }
  }

  private recoverFromDecodeBacklog(queueSize: number): void {
    const now = performance.now();
    if (
      now - this.lastLatencyRecoveryAt <
      WebCodecsBackend.LATENCY_RECOVERY_COOLDOWN_MS
    ) {
      return;
    }
    this.lastLatencyRecoveryAt = now;
    this.hasDecodedKeyframe = false;
    this.configuredCodec = "";
    try {
      this.decoder?.reset();
      this.configureDecoderIfNeeded("latency_recovery");
    } catch {
      try {
        this.decoder?.close();
      } catch {}
      this.decoder = undefined;
      this.createDecoder();
    }
    this.throttledStatus(
      "webcodecsLatencyRecovery",
      `decodeQueueSize=${queueSize}`,
      1000,
    );
    this.requestKeyframeThrottled("decode_backlog");
  }

  private requestKeyframeThrottled(reason: string): void {
    const now = performance.now();
    if (now - this.lastKeyframeRequestAt < 1500) return;
    this.lastKeyframeRequestAt = now;
    this.onStatus?.("webcodecsRequestKeyframe", reason);
    this.requestKeyframe?.();
  }

  private throttledStatus(
    event: string,
    detail: string,
    intervalMs: number,
  ): void {
    const now = performance.now();
    if (now - this.lastDropLogAt < intervalMs) return;
    this.lastDropLogAt = now;
    this.onStatus?.(event, detail);
  }

  /**
   * Convert AVCDecoderConfigurationRecord bytes to avc1.PPCCLL.
   * If parsing fails, caller falls back to High profile level 4.2.
   */
  private resolveCodecFromAvcConfig(config: ArrayBuffer): string | undefined {
    const bytes = new Uint8Array(config);
    if (bytes.byteLength < 4) return undefined;

    // AVCDecoderConfigurationRecord:
    // byte[1] profile_idc, byte[2] compatibility, byte[3] level_idc
    // Some pipelines may send Annex-B SPS/PPS instead. For Annex-B, scan SPS.
    if (bytes[0] === 1 && bytes.byteLength >= 4) {
      return `avc1.${hex(bytes[1])}${hex(bytes[2])}${hex(bytes[3])}`;
    }

    const spsStart = findAnnexBSps(bytes);
    if (spsStart >= 0 && spsStart + 4 < bytes.length) {
      const profile = bytes[spsStart + 1];
      const compat = bytes[spsStart + 2];
      const level = bytes[spsStart + 3];
      return `avc1.${hex(profile)}${hex(compat)}${hex(level)}`;
    }

    return undefined;
  }
}

function hex(value: number): string {
  return value.toString(16).padStart(2, "0");
}

function findAnnexBSps(bytes: Uint8Array): number {
  for (let i = 0; i < bytes.length - 5; i += 1) {
    const isStart3 = bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1;
    const isStart4 =
      bytes[i] === 0 &&
      bytes[i + 1] === 0 &&
      bytes[i + 2] === 0 &&
      bytes[i + 3] === 1;

    const nalIndex = isStart3 ? i + 3 : isStart4 ? i + 4 : -1;
    if (nalIndex < 0) continue;

    const nalType = bytes[nalIndex] & 0x1f;
    if (nalType === 7) return nalIndex;
  }
  return -1;
}

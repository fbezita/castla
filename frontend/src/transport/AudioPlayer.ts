import { AudioStreamProtocol, audioDelaySeconds, audioOutputBufferSeconds, audioSignalPeak, buildAudioCapabilities, clampAudioOutputDelayMs, shouldFallbackFromOpus } from "./audioProtocol";

/**
 * Castla - Audio Player (Opus via WebCodecs + raw PCM fallback)
 *
 * Ported to strict TypeScript for optimal build stability and modern runes support.
 */

export class AudioPlayer {
  private audioCtx: AudioContext | null = null;
  private decoder: any = null; // AudioDecoder from WebCodecs
  private socket: WebSocket | null = null;
  private sampleRate = 48000;
  private channels = 2;
  private nextPlayTime = 0;
  private timestampUs = 0;
  private mode: 'opus' | 'pcm' | null = null;
  
  private readonly MAX_EXCESS_LATENCY_SEC = 0.4;
  private readonly OPUS_FRAME_DURATION_US = 20000; // 20ms
  
  private clockOffset: number | null = null;
  private readonly protocol = new AudioStreamProtocol();
  private currentStreamId: number | null = null;
  private opusPacketsReceived = 0;
  private decodedOpusFrames = 0;
  private pcmFallbackRequested = false;
  private loggedFirstOpusPacket = false;
  private loggedFirstDecodedFrame = false;
  private outputDelayMs = 0;
  private audioDelayNode: DelayNode | null = null;
  private audioAnalyser: AnalyserNode | null = null;
  private readonly diagnosticTimers = new Set<number>();
  private readonly scheduledSources = new Set<AudioBufferSourceNode>();

  constructor() {}

  setOutputDelayMs(value: number): void {
    this._setOutputDelayMs(value);
  }

  async startFromUserGesture(wsUrl: string): Promise<boolean> {
    try {
      const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
      if (!AudioContextClass) {
        throw new Error('AudioContext not supported in this browser environment');
      }

      this.audioCtx = new AudioContextClass({
        sampleRate: this.sampleRate,
        latencyHint: 'interactive'
      });

      if (this.audioCtx.state === 'suspended') {
        await this.audioCtx.resume();
      }
      this._connectAudioOutput();

      console.log('[Audio] AudioContext ready, state:', this.audioCtx.state);
      this.nextPlayTime = 0;
      this.timestampUs = 0;
      this.mode = null;
      this._connectSocket(wsUrl);
      return true;
    } catch (e) {
      console.error('[Audio] Failed to start:', e);
      this.stop();
      return false;
    }
  }

  private _configureOpus(): boolean {
    if (typeof (window as any).AudioDecoder === 'undefined') {
      console.warn('[Audio] WebCodecs AudioDecoder not available');
      return false;
    }
    try {
      if (this.decoder && this.decoder.state !== 'closed') {
        this.decoder.close();
      }
      this.decoder = new (window as any).AudioDecoder({
        output: (audioData: any) => this._handleDecodedAudio(audioData),
        error: (e: any) => {
          console.error('[Audio] Opus decoder error:', e);
          this.decoder = null;
          this._requestPcmFallback('decoder-runtime-error');
        }
      });
      this.decoder.configure({
        codec: 'opus',
        sampleRate: this.sampleRate,
        numberOfChannels: this.channels
      });
      this.mode = 'opus';
      console.log('[Audio] Opus decoder configured');
      return true;
    } catch (e) {
      console.error('[Audio] Opus decoder failed:', e);
      this.decoder = null;
      return false;
    }
  }

  private _handleDecodedAudio(audioData: any) {
    if (!this.audioCtx || this.audioCtx.state === 'closed') {
      audioData.close();
      return;
    }
    try {
      this.decodedOpusFrames += 1;
      if (!this.loggedFirstDecodedFrame) {
        this.loggedFirstDecodedFrame = true;
        console.log('[Audio] First Opus frame decoded', {
          format: audioData.format,
          frames: audioData.numberOfFrames,
          channels: audioData.numberOfChannels,
          sampleRate: audioData.sampleRate,
        });
      }
      const ch = audioData.numberOfChannels;
      const frames = audioData.numberOfFrames;
      const sr = audioData.sampleRate;
      const buf = this.audioCtx.createBuffer(ch, frames, sr);
      for (let c = 0; c < ch; c++) {
        const cd = new Float32Array(frames);
        audioData.copyTo(cd, { planeIndex: c, format: 'f32-planar' });
        buf.copyToChannel(cd, c);
      }
      this._scheduleBuffer(buf);
    } catch (e) {
      console.error('[Audio] Failed to copy decoded Opus audio:', e);
      this._requestPcmFallback('decoded-audio-copy-error');
    } finally {
      audioData.close();
    }
  }

  private _requestPcmFallback(reason: string) {
    if (this.pcmFallbackRequested) return;
    this.pcmFallbackRequested = true;
    console.warn(`[Audio] Requesting PCM fallback: ${reason}`);
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify({ type: 'requestPcm', streamId: this.currentStreamId, reason }));
    }
  }

  private _playPCM(arrayBuffer: ArrayBuffer) {
    if (!this.audioCtx || this.audioCtx.state === 'closed') return;
    const int16 = new Int16Array(arrayBuffer);
    const frameCount = Math.floor(int16.length / this.channels);
    if (frameCount === 0) return;
    const buf = this.audioCtx.createBuffer(this.channels, frameCount, this.sampleRate);
    for (let ch = 0; ch < this.channels; ch++) {
      const cd = buf.getChannelData(ch);
      for (let i = 0; i < frameCount; i++) {
        cd[i] = int16[i * this.channels + ch] / 32768.0;
      }
    }
    this._scheduleBuffer(buf);
  }

  private _scheduleBuffer(audioBuffer: AudioBuffer) {
    if (!this.audioCtx) return;
    const source = this.audioCtx.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(this.audioDelayNode ?? this.audioCtx.destination);
    const now = this.audioCtx.currentTime;
    const targetBufferSeconds = audioOutputBufferSeconds(0);
    if (this.nextPlayTime < now) {
      this.nextPlayTime = now + targetBufferSeconds;
    } else if (this.nextPlayTime > now + targetBufferSeconds + this.MAX_EXCESS_LATENCY_SEC) {
      this.nextPlayTime = now + targetBufferSeconds;
    }
    this.scheduledSources.add(source);
    source.onended = () => this.scheduledSources.delete(source);
    source.start(this.nextPlayTime);
    this.nextPlayTime += audioBuffer.duration;
  }

  private _connectAudioOutput() {
    if (!this.audioCtx) return;
    this.audioDelayNode?.disconnect();
    this.audioAnalyser?.disconnect();
    const node = this.audioCtx.createDelay(1.1);
    const analyser = this.audioCtx.createAnalyser();
    analyser.fftSize = 2048;
    node.delayTime.value = audioDelaySeconds(this.outputDelayMs);
    node.connect(analyser);
    analyser.connect(this.audioCtx.destination);
    this.audioDelayNode = node;
    this.audioAnalyser = analyser;
  }

  private _setOutputDelayMs(value: number) {
    const next = clampAudioOutputDelayMs(value);
    if (next === this.outputDelayMs) return;
    this.outputDelayMs = next;
    if (this.audioCtx && this.audioDelayNode) {
      try {
        const now = this.audioCtx.currentTime;
        const delayTime = this.audioDelayNode.delayTime;
        delayTime.cancelScheduledValues(now);
        delayTime.value = audioDelaySeconds(next);
      } catch (error) {
        console.error('[Audio] Failed to update DelayNode:', error);
        if (this.socket?.readyState === WebSocket.OPEN) {
          this.socket.send(JSON.stringify({
            type: 'audioDiagnostics',
            event: 'delay-update-error',
            outputDelayMs: next,
            error: error instanceof Error ? `${error.name}: ${error.message}` : String(error),
          }));
        }
      }
    }
    console.log(`[Audio] Output delay updated: ${next}ms`);
    this._scheduleDiagnosticsAfterDelayChange();
  }

  private _scheduleDiagnosticsAfterDelayChange() {
    this.diagnosticTimers.forEach((timer) => window.clearTimeout(timer));
    this.diagnosticTimers.clear();
    for (const elapsedMs of [0, 250, 1200, 2500]) {
      const timer = window.setTimeout(() => {
        this.diagnosticTimers.delete(timer);
        this._sendDiagnostics(`delay+${elapsedMs}ms`);
      }, elapsedMs);
      this.diagnosticTimers.add(timer);
    }
  }

  private _sendDiagnostics(event: string) {
    if (!this.audioCtx || !this.audioAnalyser || this.socket?.readyState !== WebSocket.OPEN) return;
    const samples = new Float32Array(this.audioAnalyser.fftSize);
    this.audioAnalyser.getFloatTimeDomainData(samples);
    this.socket.send(JSON.stringify({
      type: 'audioDiagnostics',
      event,
      contextState: this.audioCtx.state,
      codec: this.mode,
      outputDelayMs: this.outputDelayMs,
      delayNodeSeconds: this.audioDelayNode?.delayTime.value ?? -1,
      packets: this.opusPacketsReceived,
      decodedFrames: this.decodedOpusFrames,
      scheduledSources: this.scheduledSources.size,
      scheduleLeadMs: Math.round((this.nextPlayTime - this.audioCtx.currentTime) * 1000),
      outputPeak: audioSignalPeak(samples),
    }));
  }

  private _connectSocket(wsUrl: string) {
    if (this.socket) {
      this.socket.onclose = null;
      this.socket.close();
    }
    this.socket = new WebSocket(wsUrl);
    this.socket.binaryType = 'arraybuffer';

    this.socket.onopen = async () => {
      console.log('[Audio] WebSocket connected');
      const capabilities = await buildAudioCapabilities(async () => {
        if (!window.isSecureContext || typeof (window as any).AudioDecoder === 'undefined') return { supported: false };
        return (window as any).AudioDecoder.isConfigSupported({
          codec: 'opus', sampleRate: 48000, numberOfChannels: 2,
        });
      });
      if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(capabilities));
      console.log('[Audio] Capabilities:', capabilities);
    };

    this.socket.onmessage = (event: MessageEvent) => {
      if (typeof event.data === 'string') {
        try {
          const control = JSON.parse(event.data);
          if (control.type === 'audioDelay') this._setOutputDelayMs(control.outputDelayMs);
        } catch (e) {
          console.warn('[Audio] Bad audio control message:', e);
        }
        return;
      }
      if (!(event.data instanceof ArrayBuffer) || event.data.byteLength < 2) return;
      const view = new Uint8Array(event.data);
      const type = view[0];

      if (type === 0x00) {
        // JSON config
        try {
          const json = new TextDecoder().decode(view.subarray(1));
          const config = JSON.parse(json);
          this.sampleRate = config.sampleRate || 48000;
          this.channels = config.channels || 2;
          this.currentStreamId = Number(config.streamId);
          this._setOutputDelayMs(config.outputDelayMs ?? 0);
          this.protocol.acceptConfig(this.currentStreamId);
          this.opusPacketsReceived = 0;
          this.decodedOpusFrames = 0;
          this.pcmFallbackRequested = false;
          this.loggedFirstOpusPacket = false;
          this.loggedFirstDecodedFrame = false;
          console.log('[Audio] Config:', json);

          // Recreate AudioContext if sample rate changed
          if (this.audioCtx && this.audioCtx.sampleRate !== this.sampleRate) {
            this.audioCtx.close().catch(() => {});
            const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
            this.audioCtx = new AudioContextClass({
              sampleRate: this.sampleRate,
              latencyHint: 'interactive'
            });
            this.audioCtx.resume().catch(() => {});
            this.nextPlayTime = 0;
            this._connectAudioOutput();
          }

          if (config.codec === 'opus') {
            if (!this._configureOpus()) {
              // Opus not available — ask server to switch to PCM
              console.warn('[Audio] Opus not supported, requesting PCM fallback');
              if (this.socket && this.socket.readyState === WebSocket.OPEN) {
                this.socket.send('requestPcm');
              }
              // mode stays null until server sends new PCM config
              return;
            }
          } else if (config.codec === 'pcm' || config.codec === 'pcm_s16le') {
            if (this.decoder && this.decoder.state !== 'closed') {
              this.decoder.close();
            }
            this.decoder = null;
            this.mode = 'pcm';
            console.log('[Audio] PCM mode');
          }
        } catch (e) {
          console.error('[Audio] Bad config:', e);
        }
        return;
      }

      // type === 0x01: [type][streamId i64][sequence i32][timestampUs i64] + payload
      if (event.data.byteLength < 22) return;
      const dv = new DataView(event.data);
      const streamId = Number(dv.getBigInt64(1, true));
      if (!this.protocol.acceptPacket(streamId)) return;
      const timestampUs = Number(dv.getBigInt64(13, true));
      const serverTsMs = timestampUs / 1000;
      const audioPayload = event.data.slice(21);

      // EMA clock offset for A/V sync
      const clientNow = performance.now();
      const currentOffset = clientNow - serverTsMs;
      if (this.clockOffset === null) {
        this.clockOffset = currentOffset;
      } else {
        this.clockOffset = this.clockOffset * 0.95 + currentOffset * 0.05;
      }

      if (this.mode === 'opus' && this.decoder && this.decoder.state === 'configured') {
        try {
          const chunk = new (window as any).EncodedAudioChunk({
            type: 'key',
            timestamp: timestampUs,
            data: audioPayload
          });
          this.decoder.decode(chunk);
          this.opusPacketsReceived += 1;
          if (!this.loggedFirstOpusPacket) {
            this.loggedFirstOpusPacket = true;
            console.log('[Audio] First Opus packet received', { bytes: audioPayload.byteLength, timestampUs });
          }
          if (shouldFallbackFromOpus(this.opusPacketsReceived, this.decodedOpusFrames)) {
            this._requestPcmFallback('opus-packets-without-decoded-output');
          }
        } catch (e) {
          console.error('[Audio] Opus packet decode failed:', e);
          this._requestPcmFallback('opus-packet-decode-error');
        }
      } else if (this.mode === 'pcm') {
        this._playPCM(audioPayload);
      }
    };

    this.socket.onclose = () => {
      this.protocol.reset();
      this.currentStreamId = null;
      console.log('[Audio] WebSocket disconnected');
    };
  }

  stop() {
    if (this.decoder && this.decoder.state !== 'closed') {
      try {
        this.decoder.close();
      } catch (_) {}
    }
    this.decoder = null;
    if (this.socket) {
      this.socket.onclose = null;
      this.socket.close();
      this.socket = null;
    }
    if (this.audioCtx && this.audioCtx.state !== 'closed') {
      this.audioCtx.close().catch(() => {});
    }
    this.audioCtx = null;
    this.audioDelayNode?.disconnect();
    this.audioDelayNode = null;
    this.audioAnalyser?.disconnect();
    this.audioAnalyser = null;
    this.diagnosticTimers.forEach((timer) => window.clearTimeout(timer));
    this.diagnosticTimers.clear();
    this.nextPlayTime = 0;
    this.timestampUs = 0;
    this.mode = null;
    this.protocol.reset();
    this.currentStreamId = null;
    this.opusPacketsReceived = 0;
    this.decodedOpusFrames = 0;
    this.pcmFallbackRequested = false;
    this.loggedFirstOpusPacket = false;
    this.loggedFirstDecodedFrame = false;
    this.outputDelayMs = 0;
    this.scheduledSources.forEach((source) => {
      try { source.stop(); } catch (_) {}
    });
    this.scheduledSources.clear();
    console.log('[Audio] Stopped');
  }

  static isSupported(): boolean {
    return !!(window.AudioContext || (window as any).webkitAudioContext);
  }
}

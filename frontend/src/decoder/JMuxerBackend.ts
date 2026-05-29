import type { EncodedFrame } from '../protocol';
import type { DecoderBackend } from './DecoderBackend';

type JMuxerCtor = new (options: Record<string, unknown>) => { feed(data: Record<string, Uint8Array>): void; destroy(): void };

declare global {
  interface Window {
    JMuxer?: JMuxerCtor;
  }
}

export class JMuxerBackend implements DecoderBackend {
  private muxer?: { feed(data: Record<string, Uint8Array>): void; destroy(): void };
  private configPayload?: ArrayBuffer;
  private video?: HTMLVideoElement;
  private onFrame?: () => void;
  private onStatus?: (event: string, detail?: string) => void;
  private mseReady = false;
  private pendingPayloads: Uint8Array[] = [];
  private fedFrames = 0;
  private destroyed = false;
  private detachVideoListeners: Array<() => void> = [];
  private lastSyncTime = 0;

  constructor(onFrame?: () => void, onStatus?: (event: string, detail?: string) => void) {
    this.onFrame = onFrame;
    this.onStatus = onStatus;
  }

  async initialize(target: HTMLCanvasElement | HTMLVideoElement): Promise<void> {
    if (!(target instanceof HTMLVideoElement)) throw new Error('JMuxer backend requires video');
    if (!window.JMuxer) throw new Error('JMuxer unavailable');
    this.destroyed = false;
    this.video = target;
    target.muted = true;
    target.playsInline = true;
    target.autoplay = true;
    target.setAttribute('muted', '');
    target.setAttribute('playsinline', '');
    target.style.display = 'block';
    target.style.opacity = '1';
    target.style.backgroundColor = '#000';
    this.detachVideoListeners = [
      bindEvent(target, 'loadedmetadata', () => this.reportVideoState('videoLoadedMetadata')),
      bindEvent(target, 'loadeddata', () => this.reportVideoState('videoLoadedData')),
      bindEvent(target, 'canplay', () => this.reportVideoState('videoCanPlay')),
      bindEvent(target, 'playing', () => this.reportVideoState('videoPlaying')),
      bindEvent(target, 'error', () => this.onStatus?.('videoElementError', target.error ? `${target.error.code}:${target.error.message}` : 'unknown'))
    ];
    this.onStatus?.('jmuxerReady', `hasJMuxer=${Boolean(window.JMuxer)}`);
  }

  decode(frame: EncodedFrame): void {
    if (frame.config) {
      this.configPayload = frame.payload;
      this.onStatus?.('jmuxerConfig', `bytes=${frame.payload.byteLength}`);
      return;
    }
    if (!this.video) return;
    if (!this.muxer) {
      const JMuxer = window.JMuxer;
      if (!JMuxer) return;
      this.muxer = new JMuxer({
        node: this.video,
        mode: 'video',
        flushingTime: 30, // Optimized flush interval for automotive webview
        maxDelay: 1000,   // Expanded delay limit to prevent aggressive buffer drops in Tesla Chromium
        clearBuffer: true,
        fps: 60,
        debug: false,
        onReady: () => {
          this.mseReady = true;
          this.onStatus?.('jmuxerMseReady', `pending=${this.pendingPayloads.length}`);
          this.flushPending();
        },
        onError: (error: unknown) => this.onStatus?.('jmuxerError', String(error)),
        onUnsupportedCodec: (codec: unknown) => this.onStatus?.('jmuxerUnsupportedCodec', String(codec)),
        onMissingVideoFrames: () => this.onStatus?.('jmuxerMissingVideoFrames')
      });
      this.onStatus?.('jmuxerCreated', `key=${frame.keyFrame} hasConfig=${Boolean(this.configPayload)}`);
    }
    const payload = frame.keyFrame && this.configPayload
      ? concat(this.configPayload, frame.payload)
      : frame.payload;
    this.enqueueOrFeed(new Uint8Array(payload));
  }

  reset(): void {
    this.configPayload = undefined;
  }

  destroy(): void {
    this.destroyed = true;
    this.muxer?.destroy();
    this.muxer = undefined;
    this.pendingPayloads = [];
    this.mseReady = false;
    this.configPayload = undefined;
    this.fedFrames = 0;
    this.rendered = false;
    this.detachVideoListeners.splice(0).forEach((detach) => detach());
    if (this.video) {
      try { this.video.pause(); } catch {}
      try { this.video.removeAttribute('src'); } catch {}
      try { this.video.load(); } catch {}
    }
    this.video = undefined;
  }

  private enqueueOrFeed(payload: Uint8Array): void {
    if (!this.mseReady) {
      this.pendingPayloads.push(payload);
      if (this.pendingPayloads.length === 1 || this.pendingPayloads.length % 30 === 0) {
        this.onStatus?.('jmuxerQueue', `pending=${this.pendingPayloads.length}`);
      }
      return;
    }
    this.feedPayload(payload);
  }

  private flushPending(): void {
    const pending = this.pendingPayloads.splice(0, this.pendingPayloads.length);
    pending.forEach((payload) => this.feedPayload(payload));
  }

  private feedPayload(payload: Uint8Array): void {
    if (!this.muxer || !this.video) return;
    this.muxer.feed({ video: payload });
    this.fedFrames += 1;
    if (this.fedFrames === 1 || this.fedFrames % 120 === 0) {
      this.reportVideoState('jmuxerFeedSummary', `fed=${this.fedFrames} bytes=${payload.byteLength}`);
    }
    this.video.play().catch((error) => {
      if (this.destroyed || isAbortError(error)) return;
      this.onStatus?.('videoPlayError', String(error));
    });

    // Stalled playhead unfreezing mechanism for embedded/automotive Chromium engines
    this.syncPlayheadIfNeeded();

    if (!this.rendered && this.video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
      this.rendered = true;
      this.reportVideoState('videoHasCurrentData');
    }
    this.onFrame?.();
  }

  /**
   * Monitor video buffering status and force-jump playhead to the live edge if stalled.
   * Helps Automotive Chromium recover when transition delays pause the media stream timeline.
   */
  private syncPlayheadIfNeeded(): void {
    const video = this.video;
    if (!video || video.buffered.length === 0) return;

    try {
      const bufferedEnd = video.buffered.end(video.buffered.length - 1);
      const diff = bufferedEnd - video.currentTime;

      // Throttled unfreezing logic: Adjust threshold to 0.3s to allow normal decode margin.
      // Jump immediately when lag exceeds 0.3s, but throttle status logging to every 3 seconds to avoid spam.
      if (diff > 0.3) {
        const now = Date.now();
        if (now - this.lastSyncTime > 3000) {
          this.onStatus?.('playheadSync', `lag=${diff.toFixed(2)}s forced_jump_to=${bufferedEnd.toFixed(2)}s`);
          this.lastSyncTime = now;
        }
        video.currentTime = bufferedEnd;
      }
    } catch (error) {
      this.onStatus?.('playheadSyncError', String(error));
    }
  }

  private reportVideoState(event: string, prefix = ''): void {
    if (!this.video) return;
    const ranges = [];
    for (let i = 0; i < this.video.buffered.length; i += 1) {
      ranges.push(`${this.video.buffered.start(i).toFixed(2)}-${this.video.buffered.end(i).toFixed(2)}`);
    }
    this.onStatus?.(
      event,
      `${prefix} ready=${this.video.readyState} paused=${this.video.paused} t=${this.video.currentTime.toFixed(3)} vw=${this.video.videoWidth} vh=${this.video.videoHeight} buffered=${ranges.join(',')}`
    );
  }
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

function concat(a: ArrayBuffer, b: ArrayBuffer): ArrayBuffer {
  const out = new Uint8Array(a.byteLength + b.byteLength);
  out.set(new Uint8Array(a), 0);
  out.set(new Uint8Array(b), a.byteLength);
  return out.buffer;
}

function bindEvent<K extends keyof HTMLMediaElementEventMap>(
  target: HTMLVideoElement,
  type: K,
  listener: (event: HTMLMediaElementEventMap[K]) => void
): () => void {
  target.addEventListener(type, listener as EventListener);
  return () => target.removeEventListener(type, listener as EventListener);
}

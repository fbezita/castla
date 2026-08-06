import type { EncodedFrame } from '../protocol';
import type { DecoderBackend } from './DecoderBackend';
import { debugLog, triggerDump } from '../utils/debugLogger';
import { compositorStore } from '../stores/compositorStore';
import { get } from 'svelte/store';
import { clampVideoLatencyMs, liveEdgeTargetSeconds } from './videoLatency';

type JMuxerCtor = new (options: Record<string, unknown>) => { feed(data: Record<string, Uint8Array>): void; destroy(): void };
const isVerboseJmuxerDiagnostics = (): boolean =>
  (window as Window & { __CASTLA_VERBOSE_DIAGNOSTICS__?: boolean }).__CASTLA_VERBOSE_DIAGNOSTICS__ === true;
const VIDEO_STATE_DUPLICATE_WINDOW_MS = 250;

type VideoStateSnapshot = {
  readyState: number;
  width: number;
  height: number;
  currentTime: number;
  buffered: number;
  paused: boolean;
};

export const buildVideoStateSnapshot = (video: HTMLVideoElement): VideoStateSnapshot => ({
  readyState: video.readyState,
  width: video.videoWidth,
  height: video.videoHeight,
  currentTime: video.currentTime,
  buffered: video.buffered.length,
  paused: video.paused,
});

export const shouldEmitVideoStateSnapshot = ({
  now,
  lastEmitAt,
  next,
  previous,
}: {
  now: number;
  lastEmitAt: number;
  next: VideoStateSnapshot;
  previous?: VideoStateSnapshot;
}): boolean => {
  if (!previous) return true;
  const stableStateChanged =
    previous.readyState !== next.readyState ||
    previous.width !== next.width ||
    previous.height !== next.height ||
    previous.buffered !== next.buffered ||
    previous.paused !== next.paused;
  return stableStateChanged || now - lastEmitAt >= VIDEO_STATE_DUPLICATE_WINDOW_MS;
};

declare global {
  interface Window {
    JMuxer?: JMuxerCtor;
    __CASTLA_VERBOSE_DIAGNOSTICS__?: boolean;
    castlaRuntime?: {
      control?: {
        sendFrontendDiag?: (tag: string, message: string, data?: Record<string, unknown>) => void;
      };
    };
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
  private rendered = false;
  private destroyed = false;
  private detachVideoListeners: Array<() => void> = [];
  private lastSyncTime = 0;
  private lastFeedTime = 0;
  private firstKeyframeSeen = false;
  private lastVideoStateAt = 0;
  private lastVideoStateSnapshot?: VideoStateSnapshot;
  private videoLatencyMs = 0;

  private emitMirrorDiag(message: string, data: Record<string, unknown>): void {
    window.castlaRuntime?.control?.sendFrontendDiag?.("VIDEO_DEBUG", message, data);
  }

  private emitVerboseMirrorDiag(message: string, data: Record<string, unknown>): void {
    if (!isVerboseJmuxerDiagnostics()) return;
    this.emitMirrorDiag(message, data);
  }

  private emitVideoState(tag: string): void {
    if (!this.video) return;
    if (!isVerboseJmuxerDiagnostics()) return;
    const snapshot = buildVideoStateSnapshot(this.video);
    const now = Date.now();
    if (!shouldEmitVideoStateSnapshot({
      now,
      lastEmitAt: this.lastVideoStateAt,
      next: snapshot,
      previous: this.lastVideoStateSnapshot,
    })) {
      return;
    }
    this.lastVideoStateAt = now;
    this.lastVideoStateSnapshot = snapshot;
    this.emitMirrorDiag(tag, snapshot);
  }

  constructor(onFrame?: () => void, onStatus?: (event: string, detail?: string) => void) {
    this.onFrame = onFrame;
    this.onStatus = onStatus;
  }

  setVideoLatencyMs(latencyMs: number): void {
    this.videoLatencyMs = clampVideoLatencyMs(latencyMs);
  }

  async initialize(target: HTMLCanvasElement | HTMLVideoElement): Promise<void> {
    if (!(target instanceof HTMLVideoElement)) throw new Error('JMuxer backend requires video');
    if (!window.JMuxer) throw new Error('JMuxer unavailable');
    this.destroyed = false;
    this.firstKeyframeSeen = false;
    this.video = target;
    target.muted = true;
    target.playsInline = true;
    target.autoplay = true;
    target.setAttribute('muted', '');
    target.setAttribute('playsinline', '');
    target.style.display = 'block';
    target.style.opacity = '1';
    target.style.backgroundColor = '#000';
    const buildMode = ((import.meta as ImportMeta & { env?: { DEV?: boolean } }).env?.DEV ?? false)
      ? "debug"
      : "release";
    this.emitMirrorDiag("JMX_BUILD", {
      mode: buildMode,
      backend: "jmuxer",
      secureContext: window.isSecureContext,
      hasVideoDecoder: "VideoDecoder" in window,
    });
    this.emitMirrorDiag("JMX_INIT", {
      backend: "jmuxer",
      hasJMuxer: Boolean(window.JMuxer),
      secureContext: window.isSecureContext,
    });
    this.emitVideoState("JMX_VIDEO_STATE");
    
    // Intercept and wrap SourceBuffer.prototype.appendBuffer to trace MSE and SourceBuffer activity
    hookSourceBuffer();

    this.detachVideoListeners = [
      bindEvent(target, 'loadedmetadata', () => {
        debugLog("[VideoElement] loadedmetadata", {
          readyState: target.readyState,
          videoWidth: target.videoWidth,
          videoHeight: target.videoHeight,
        });
        this.emitVideoState("JMX_VIDEO_STATE");
        this.reportVideoState('videoLoadedMetadata');
      }),
      bindEvent(target, 'loadeddata', () => {
        debugLog("[VideoElement] loadeddata", {
          readyState: target.readyState,
        });
        this.emitVideoState("JMX_VIDEO_STATE");
        this.reportVideoState('videoLoadedData');
      }),
      bindEvent(target, 'canplay', () => {
        debugLog("[VideoElement] canplay", {
          readyState: target.readyState,
        });
        this.emitVideoState("JMX_VIDEO_STATE");
        this.reportVideoState('videoCanPlay');
      }),
      bindEvent(target, 'playing', () => {
        debugLog("[VideoElement] playing", {
          currentTime: target.currentTime,
          readyState: target.readyState,
        });
        this.emitVideoState("JMX_VIDEO_STATE");
        this.reportVideoState('videoPlaying');
      }),
      bindEvent(target, 'stalled', () => {
        debugLog("[VideoElement] stalled", {
          currentTime: target.currentTime,
          readyState: target.readyState,
          buffered: getBufferedRanges(target.buffered),
        });
        
        // Auto-trigger debug dump on video element stall
        const runtime = (window as any).castlaRuntime;
        if (runtime) {
          triggerDump(runtime, 'video_element_stalled');
        }
      }),
      bindEvent(target, 'waiting', () => {
        debugLog("[VideoElement] waiting", {
          currentTime: target.currentTime,
          readyState: target.readyState,
          buffered: getBufferedRanges(target.buffered),
        });
        this.emitVideoState("JMX_VIDEO_STATE");
      }),
      bindEvent(target, 'error', () => {
        debugLog("[VideoElement] error", {
          code: target.error?.code,
          message: target.error?.message,
        });
        this.emitMirrorDiag("JMX_VIDEO_ERROR", {
          code: target.error?.code ?? null,
          message: target.error?.message ?? "unknown",
          readyState: target.readyState,
          currentTime: target.currentTime,
          buffered: getBufferedRanges(target.buffered),
        });
        this.emitMirrorDiag("JMX_ERROR", {
          kind: "video",
          code: target.error?.code ?? null,
          message: target.error?.message ?? "unknown",
        });
        this.onStatus?.('videoElementError', target.error ? `${target.error.code}:${target.error.message}` : 'unknown');
        
        // Auto-trigger debug dump on video element error
        const runtime = (window as any).castlaRuntime;
        if (runtime) {
          triggerDump(runtime, 'video_element_error');
        }
      })
    ];
    this.onStatus?.('jmuxerReady', `hasJMuxer=${Boolean(window.JMuxer)}`);
  }

  decode(frame: EncodedFrame): void {
    if (frame.config) {
      this.configPayload = frame.payload;
      this.emitVerboseMirrorDiag("JMX_CONFIG", {
        bytes: frame.payload.byteLength,
      });
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
    if (frame.keyFrame && !this.firstKeyframeSeen) {
      this.firstKeyframeSeen = true;
      this.emitVerboseMirrorDiag("JMX_FIRST_KEYFRAME", {
        sequence: frame.sequence,
        payloadBytes: frame.payload.byteLength,
        configBytes: this.configPayload?.byteLength ?? 0,
      });
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
    this.lastVideoStateAt = 0;
    this.lastVideoStateSnapshot = undefined;
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

    const now = Date.now();
    const interval = this.lastFeedTime > 0 ? now - this.lastFeedTime : 0;
    this.lastFeedTime = now;

    const nalCount = countNals(payload);
    debugLog("[JMuxer] feed", {
      frameSize: payload.byteLength,
      nalCount,
      fedFrames: this.fedFrames + 1,
      feedIntervalMs: interval,
      feedFrequencyFps: interval > 0 ? (1000 / interval).toFixed(1) : "0.0"
    });
    if (this.fedFrames < 3 || this.fedFrames % 300 === 0) {
      const hasVisibleStream = Array.from(get(compositorStore).viewports.values()).some((viewport) => viewport.committed);
      if (isVerboseJmuxerDiagnostics()) console.warn("[FRAME_DEBUG] JMuxer feed", {
        fedFrames: this.fedFrames + 1,
        frameSize: payload.byteLength,
        nalCount,
        readyState: this.video.readyState,
        currentTime: this.video.currentTime,
        paused: this.video.paused,
        videoWidth: this.video.videoWidth,
        videoHeight: this.video.videoHeight,
      });
      this.emitVerboseMirrorDiag("JMX_FEED", {
        fedFrames: this.fedFrames + 1,
        frameSize: payload.byteLength,
        nalCount,
        readyState: this.video.readyState,
        currentTime: this.video.currentTime,
        buffered: getBufferedRanges(this.video.buffered),
        hasVisibleStream,
        paused: this.video.paused,
      });
      this.emitVideoState("JMX_VIDEO_STATE");
      this.emitVerboseMirrorDiag("videoElementState", {
        fedFrames: this.fedFrames + 1,
        frameSize: payload.byteLength,
        nalCount,
        visible: getComputedStyle(this.video).display !== 'none' && getComputedStyle(this.video).visibility !== 'hidden',
        readyState: this.video.readyState,
        currentTime: this.video.currentTime,
        buffered: getBufferedRanges(this.video.buffered),
        hasVisibleStream,
        paused: this.video.paused,
        videoWidth: this.video.videoWidth,
        videoHeight: this.video.videoHeight,
      });
    }

    this.muxer.feed({ video: payload });
    this.fedFrames += 1;
    if (this.fedFrames === 1 || this.fedFrames % 300 === 0) {
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
      const hasVisibleStream = Array.from(get(compositorStore).viewports.values()).some((viewport) => viewport.committed);
      if (isVerboseJmuxerDiagnostics()) console.warn("[DISPLAY_DEBUG] JMuxer first renderable data", {
        readyState: this.video.readyState,
        currentTime: this.video.currentTime,
        paused: this.video.paused,
        videoWidth: this.video.videoWidth,
        videoHeight: this.video.videoHeight,
      });
      this.emitVerboseMirrorDiag("videoElementFirstRenderable", {
        visible: getComputedStyle(this.video).display !== 'none' && getComputedStyle(this.video).visibility !== 'hidden',
        readyState: this.video.readyState,
        currentTime: this.video.currentTime,
        buffered: getBufferedRanges(this.video.buffered),
        hasVisibleStream,
        paused: this.video.paused,
        videoWidth: this.video.videoWidth,
        videoHeight: this.video.videoHeight,
      });
      this.emitVideoState("JMX_VIDEO_STATE");
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
    if (!video || video.seeking || video.buffered.length === 0) return;

    try {
      const bufferedEnd = video.buffered.end(video.buffered.length - 1);
      const diff = bufferedEnd - video.currentTime;
      if (this.videoLatencyMs > 0) {
        const targetTime = liveEdgeTargetSeconds(bufferedEnd, video.buffered.start(0), this.videoLatencyMs);
        if (Math.abs(video.currentTime - targetTime) > 0.08) {
          const now = Date.now();
          if (now - this.lastSyncTime >= 250) {
            this.lastSyncTime = now;
            video.currentTime = targetTime;
            this.onStatus?.('videoLatencySync', `latencyMs=${this.videoLatencyMs} target=${targetTime.toFixed(2)}`);
          }
        }
        return;
      }

      // Relax threshold to 1.5s to prevent constant seek loops during normal decode variance.
      if (diff > 1.5) {
        const now = Date.now();
        // Enforce a strict 2-second cooldown between seeks to prevent back-to-back seek storms
        if (now - this.lastSyncTime < 2000) {
          return;
        }
        this.lastSyncTime = now;

        // Seek to bufferedEnd minus a 0.2s cushion to leave a stable decode buffer
        const targetTime = Math.max(video.buffered.start(0), bufferedEnd - 0.2);
        console.warn(`[JMuxerBackend] Playhead stalled: lag=${diff.toFixed(2)}s, seeking to live edge: ${targetTime.toFixed(2)}s`);
        this.onStatus?.('playheadSync', `lag=${diff.toFixed(2)}s forced_jump_to=${targetTime.toFixed(2)}s`);

        debugLog("[JMuxerBackend] playheadStalledSeek", {
          lag: diff,
          currentTime: video.currentTime,
          targetTime,
          buffered: getBufferedRanges(video.buffered),
        });

        // Trigger debug dump when stall recovery seek occurs
        const runtime = (window as any).castlaRuntime;
        if (runtime) {
          triggerDump(runtime, `playhead_stalled_seek_lag_${diff.toFixed(2)}s`);
        }

        video.currentTime = targetTime;
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

function countNals(bytes: Uint8Array): number {
  let count = 0;
  for (let i = 0; i < bytes.length - 4; i++) {
    if (bytes[i] === 0 && bytes[i + 1] === 0 && (bytes[i + 2] === 1 || (bytes[i + 2] === 0 && bytes[i + 3] === 1))) {
      count++;
    }
  }
  return count;
}

function getBufferedRanges(buffered: TimeRanges): string {
  const ranges: string[] = [];
  for (let i = 0; i < buffered.length; i++) {
    ranges.push(`${buffered.start(i).toFixed(2)}-${buffered.end(i).toFixed(2)}`);
  }
  return ranges.join(",");
}

function hookSourceBuffer() {
  if ((window as any).SourceBuffer_hooked) return;
  (window as any).SourceBuffer_hooked = true;
  const verbose = isVerboseJmuxerDiagnostics();

  const originalAppend = SourceBuffer.prototype.appendBuffer;
  SourceBuffer.prototype.appendBuffer = function(data: ArrayBufferView | ArrayBuffer) {
    const bytes = data instanceof ArrayBuffer ? data.byteLength : data.byteLength;
    
    debugLog("[SourceBuffer] appendBuffer", {
      bytes,
      updating: this.updating,
      buffered: getBufferedRanges(this.buffered),
    });
    if (verbose) {
      window.castlaRuntime?.control?.sendFrontendDiag?.("VIDEO_DEBUG", "JMX_SOURCEBUFFER", {
        phase: "appendBuffer",
        bytes,
        updating: this.updating,
        buffered: this.buffered.length,
      });
    }

    if (!(this as any)._eventsHooked) {
      (this as any)._eventsHooked = true;
      
      this.addEventListener("updatestart", () => {
        debugLog("[SourceBuffer] updatestart", {
          updating: this.updating,
          buffered: getBufferedRanges(this.buffered),
        });
        if (verbose) {
          window.castlaRuntime?.control?.sendFrontendDiag?.("VIDEO_DEBUG", "JMX_SOURCEBUFFER", {
            phase: "updatestart",
            updating: this.updating,
            buffered: this.buffered.length,
          });
        }
      });
      
      this.addEventListener("updateend", () => {
        debugLog("[SourceBuffer] updateend", {
          updating: this.updating,
          buffered: getBufferedRanges(this.buffered),
        });
        if (verbose) {
          window.castlaRuntime?.control?.sendFrontendDiag?.("VIDEO_DEBUG", "JMX_SOURCEBUFFER", {
            phase: "updateend",
            updating: this.updating,
            buffered: this.buffered.length,
          });
        }
      });
      
      this.addEventListener("error", (err: any) => {
        debugLog("[SourceBuffer] error", {
          error: err.message || String(err),
          buffered: getBufferedRanges(this.buffered),
        });
        window.castlaRuntime?.control?.sendFrontendDiag?.("VIDEO_DEBUG", "JMX_ERROR", {
          kind: "sourcebuffer",
          error: err.message || String(err),
        });
        window.castlaRuntime?.control?.sendFrontendDiag?.("VIDEO_DEBUG", "JMX_SOURCEBUFFER_ERROR", {
          error: err.message || String(err),
          buffered: getBufferedRanges(this.buffered),
          updating: this.updating,
        });
      });
      
      this.addEventListener("abort", () => {
        debugLog("[SourceBuffer] abort");
      });
    }

    try {
      originalAppend.call(this, data as BufferSource);
    } catch (error: any) {
      debugLog("[SourceBuffer] appendException", {
        name: error.name,
        message: error.message,
        code: error.code,
      });
      window.castlaRuntime?.control?.sendFrontendDiag?.("VIDEO_DEBUG", "JMX_ERROR", {
        kind: "appendException",
        name: error.name,
        message: error.message,
        code: error.code,
      });
      window.castlaRuntime?.control?.sendFrontendDiag?.("VIDEO_DEBUG", "JMX_SOURCEBUFFER_ERROR", {
        name: error.name,
        message: error.message,
        code: error.code,
      });
      
      // Auto-trigger debug dump on append exception/quota exceptions
      const runtime = (window as any).castlaRuntime;
      if (runtime) {
        triggerDump(runtime, `source_buffer_exception_${error.name}`);
      }
      throw error;
    }
  };
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

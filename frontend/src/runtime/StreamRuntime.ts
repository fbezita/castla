import type { EncodedFrame, PaneId, StreamMetadata } from '../protocol';
import { ControlTransport } from '../transport/ControlTransport';
import { VideoTransport } from '../transport/VideoTransport';
import { GenerationTracker } from './GenerationTracker';
import { StreamHealthMonitor } from './StreamHealthMonitor';

export class StreamRuntime {
  readonly control: ControlTransport;
  readonly generations = new GenerationTracker();
  readonly health = new StreamHealthMonitor();
  private videoTransports = new Map<PaneId, VideoTransport>();
  private frameListeners = new Map<PaneId, Set<(frame: EncodedFrame) => void>>();
  private frameCounts = new Map<PaneId, number>();
  private connectionListeners = new Set<(connected: boolean) => void>();
  private lastLayout: Array<{ id: PaneId; width: number; height: number; visible?: boolean }> = [];
  private seenEvents = new Set<string>();
  private static readonly noisyDecoderEvents = new Set([
    'metadata',
    'configFrame',
    'keyFrame',
    'frameSummary',
    'jmuxerReady',
    'jmuxerConfig',
    'jmuxerCreated',
    'jmuxerMseReady',
    'jmuxerQueue',
    'jmuxerFeedSummary',
    'videoLoadedMetadata',
    'videoLoadedData',
    'videoCanPlay',
    'videoPlaying',
    'videoHasCurrentData'
  ]);

  constructor(private readonly host: string) {
    this.control = new ControlTransport(host);
    this.control.onConnectionChange((connected) => {
      this.connectionListeners.forEach((listener) => listener(connected));
      if (!connected) return;
      if (this.lastLayout.length > 0) {
        this.sendLayout(this.lastLayout);
      }
      for (const pane of this.frameListeners.keys()) {
        this.setCodec(pane, 'h264', 'High');
        this.requestKeyframe(pane);
      }
    });
  }

  start(): void {
    this.control.connect();
    this.control.onMessage((message) => {
      if (message.type === 'streamMetadata') {
        const metadata = message as StreamMetadata;
        this.generations.update(metadata);
      }
    });
  }

  requestKeyframe(pane: PaneId): void {
    this.control.send({ type: 'requestKeyframe', pane });
  }

  setCodec(pane: PaneId, mode: 'h264' | 'mjpeg', profile = 'High'): void {
    this.control.send({ type: 'codec', pane, mode, profile });
  }

  sendLayout(pipelines: Array<{ id: PaneId; width: number; height: number; visible?: boolean }>): void {
    this.lastLayout = pipelines.map((pipeline) => ({ ...pipeline }));
    this.control.send({
      type: 'layout_update',
      pipelines: pipelines.map((pipeline) => ({
        id: pipeline.id,
        width: Math.max(320, align16(pipeline.width)),
        height: Math.max(320, align16(pipeline.height)),
        visible: pipeline.visible ?? true
      }))
    });
  }

  onConnectionChange(listener: (connected: boolean) => void): () => void {
    this.connectionListeners.add(listener);
    return () => this.connectionListeners.delete(listener);
  }

  launchApp(pkg: string, pane: PaneId, componentName?: string, isVideoApp = false): void {
    this.control.send({
      type: 'launchApp',
      pkg,
      pane,
      componentName,
      isVideoApp
    });
  }

  goHome(): void {
    this.control.send({ type: 'goHome' });
  }

  setDisplayDensity(scale: number): void {
    this.control.send({ type: 'displayDensity', scale });
  }

  setPlaybackProfile(profile: string): void {
    this.control.send({ type: 'playbackProfile', profile });
  }

  reportDecoderStatus(pane: PaneId, event: string, detail = ''): void {
    if (StreamRuntime.noisyDecoderEvents.has(event)) return;
    const key = `${pane}:${event}:${detail}`;
    if (this.seenEvents.has(key) && !event.endsWith('summary')) return;
    this.seenEvents.add(key);
    this.control.send({ type: 'decoderStatus', pane, event, detail });
    console.warn(`[CastlaDecoder:${pane}] ${event}`, detail);
  }

  attachVideo(pane: PaneId, onFrame: (frame: EncodedFrame) => void): () => void {
    let listeners = this.frameListeners.get(pane);
    if (!listeners) {
      listeners = new Set();
      this.frameListeners.set(pane, listeners);
    }
    listeners.add(onFrame);
    if (!this.videoTransports.has(pane)) {
      const transport = new VideoTransport(this.host, pane, (frame) => this.dispatchFrame(pane, frame), () => this.health.reconnect(pane));
      this.videoTransports.set(pane, transport);
      transport.connect();
    }
    return () => listeners?.delete(onFrame);
  }

  private dispatchFrame(pane: PaneId, frame: EncodedFrame): void {
    if (!this.generations.acceptFrame(pane, frame)) {
      this.reportDecoderStatus(pane, 'frameRejected', `seq=${frame.sequence} key=${frame.keyFrame} config=${frame.config}`);
      return;
    }
    if (frame.config) this.reportDecoderStatus(pane, 'configFrame', `bytes=${frame.payload.byteLength}`);
    else if (frame.keyFrame) this.reportDecoderStatus(pane, 'keyFrame', `seq=${frame.sequence} bytes=${frame.payload.byteLength}`);
    this.health.frame(pane);
    const count = (this.frameCounts.get(pane) ?? 0) + 1;
    this.frameCounts.set(pane, count);
    this.frameListeners.get(pane)?.forEach((listener) => listener(frame));
  }
}

function align16(value: number): number {
  return (Math.round(value) + 15) & ~15;
}

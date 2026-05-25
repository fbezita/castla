import type { EncodedFrame, PaneId, StreamMetadata } from '../protocol';
import { ControlTransport } from '../transport/ControlTransport';
import { VideoTransport } from '../transport/VideoTransport';
import { GenerationTracker } from './GenerationTracker';
import { StreamHealthMonitor } from './StreamHealthMonitor';

export class StreamRuntime {
  readonly control: ControlTransport;
  readonly generations = new GenerationTracker();
  readonly health = new StreamHealthMonitor();
  private serverInstanceId = 'unknown';
  private controlSessionId = 0;
  private videoTransports = new Map<PaneId, VideoTransport>();
  private frameListeners = new Map<PaneId, Set<(frame: EncodedFrame) => void>>();
  private frameCounts = new Map<PaneId, number>();
  private connectionListeners = new Set<(connected: boolean) => void>();
  private sessionListeners = new Set<(epoch: number, reason: string) => void>();
  private touchStateListeners = new Set<(reason: string) => void>();
  private frontendResetListeners = new Set<(reason: string) => void>();
  private lastLayout: Array<{ id: PaneId; width: number; height: number; visible?: boolean }> = [];
  private lastLayoutVisibility = '';
  private lastLayoutSignature = '';
  private layoutUpdateCount = 0;
  private layoutDedupedCount = 0;
  private lastKeyframeRequestAt = new Map<PaneId, number>();
  private seenEvents = new Set<string>();
  private sessionEpoch = 0;
  private appLaunchSequence = 0;
  private wasConnected = false;
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
      console.info('[CastlaRuntime] control connection', {
        connected,
        sessionEpoch: this.sessionEpoch,
        appLaunchSequence: this.appLaunchSequence,
        controlSessionId: this.controlSessionId,
        control: this.control.debugSnapshot()
      });
      this.connectionListeners.forEach((listener) => listener(connected));
      if (!connected) {
        this.wasConnected = false;
        return;
      }
      this.wasConnected = true;
      this.resetTouchState('control_connected');
      if (this.lastLayout.length > 0) {
        this.sendLayout(this.lastLayout);
      }
      for (const [pane, listeners] of this.frameListeners) {
        if (listeners.size === 0) continue;
        this.setCodec(pane, 'h264', 'High');
        this.requestKeyframe(pane);
      }
    });
  }

  start(): void {
    this.control.connect();
    this.control.onMessage((message) => {
      if ((message as { type?: string }).type === 'serverInit') {
        this.serverInstanceId = String((message as { instanceId?: unknown }).instanceId ?? 'unknown');
        this.controlSessionId = Number((message as { controlSessionId?: unknown }).controlSessionId ?? 0);
        console.info('[CastlaRuntime] serverInit', {
          serverInstanceId: this.serverInstanceId,
          controlSessionId: this.controlSessionId,
          control: this.control.debugSnapshot()
        });
      }
      if (message.type === 'streamMetadata') {
        const metadata = message as StreamMetadata;
        this.generations.update(metadata);
      }
    });
  }

  requestKeyframe(pane: PaneId): void {
    this.generations.resetForKeyframe(pane);
    this.control.send({ type: 'requestKeyframe', pane });
  }

  resetTouchState(reason = 'manual'): void {
    console.info('[CastlaRuntime] resetTouchState', {
      reason,
      sessionEpoch: this.sessionEpoch,
      appLaunchSequence: this.appLaunchSequence,
      controlSessionId: this.controlSessionId,
      control: this.control.debugSnapshot()
    });
    this.control.send({ type: 'touchReset' });
    this.touchStateListeners.forEach((listener) => listener(reason));
  }

  resetTouchSession(reason: string): void {
    this.sessionEpoch += 1;
    this.resetTouchState(`session:${reason}`);
  }

  resetFrontendInteraction(reason: string): void {
    this.resetTouchSession(reason);
    this.frontendResetListeners.forEach((listener) => listener(reason));
  }

  setCodec(pane: PaneId, mode: 'h264' | 'mjpeg', profile = 'High'): void {
    this.control.send({ type: 'codec', pane, mode, profile });
  }

  sendLayout(pipelines: Array<{ id: PaneId; width: number; height: number; visible?: boolean }>): void {
    const normalized = pipelines.map((pipeline) => ({
      id: pipeline.id,
      width: Math.max(320, align16(pipeline.width)),
      height: Math.max(320, align16(pipeline.height)),
      visible: pipeline.visible ?? true
    }));
    const signature = normalized
      .map((pipeline) => `${pipeline.id}:${pipeline.width}x${pipeline.height}:${pipeline.visible ? 1 : 0}`)
      .sort()
      .join('|');
    const nextVisibility = normalized
      .map((pipeline) => `${pipeline.id}:${pipeline.visible ? 1 : 0}`)
      .sort()
      .join('|');
    this.lastLayoutVisibility = nextVisibility;
    this.lastLayout = normalized.map((pipeline) => ({ ...pipeline }));
    if (signature === this.lastLayoutSignature) {
      this.layoutDedupedCount += 1;
      console.info('[CastlaLayout] dedup layout_update', {
        appLaunchSequence: this.appLaunchSequence,
        sessionEpoch: this.sessionEpoch,
        layoutDedupedCount: this.layoutDedupedCount,
        signature
      });
      return;
    }
    this.lastLayoutSignature = signature;
    this.layoutUpdateCount += 1;
    console.info('[CastlaLayout] sendLayout', normalized);
    this.control.send({
      type: 'layout_update',
      pipelines: normalized
    });
  }

  onConnectionChange(listener: (connected: boolean) => void): () => void {
    this.connectionListeners.add(listener);
    return () => this.connectionListeners.delete(listener);
  }

  onSessionChange(listener: (epoch: number, reason: string) => void): () => void {
    this.sessionListeners.add(listener);
    return () => this.sessionListeners.delete(listener);
  }

  onTouchStateReset(listener: (reason: string) => void): () => void {
    this.touchStateListeners.add(listener);
    return () => this.touchStateListeners.delete(listener);
  }

  onFrontendReset(listener: (reason: string) => void): () => void {
    this.frontendResetListeners.add(listener);
    return () => this.frontendResetListeners.delete(listener);
  }

  currentSessionEpoch(): number {
    return this.sessionEpoch;
  }

  currentAppLaunchSequence(): number {
    return this.appLaunchSequence;
  }

  launchApp(pkg: string, pane: PaneId, componentName?: string, isVideoApp = false): void {
    this.appLaunchSequence += 1;
    console.info('[CastlaRuntime] launchApp', {
      appLaunchSequence: this.appLaunchSequence,
      pkg,
      pane,
      componentName: componentName ?? '',
      isVideoApp,
      sessionEpoch: this.sessionEpoch,
      serverInstanceId: this.serverInstanceId,
      controlSessionId: this.controlSessionId,
      control: this.control.debugSnapshot()
    });
    this.resetFrontendInteraction('app_launch');
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
    return () => {
      listeners?.delete(onFrame);
      if (listeners && listeners.size === 0) {
        this.videoTransports.get(pane)?.close();
        this.videoTransports.delete(pane);
        this.frameListeners.delete(pane);
      }
    };
  }

  softReconnect(pane: PaneId): void {
    this.reportDecoderStatus(pane, 'softReconnect');
    this.resetSession(`softReconnect:${pane}`);
    this.generations.resetForKeyframe(pane);
    this.videoTransports.get(pane)?.reconnectNow();
    this.control.reconnectNow();
  }

  recoverPaneStream(pane: PaneId): void {
    this.reportDecoderStatus(pane, 'paneRecover');
    this.health.beginRecovery(pane);
    this.generations.resetForKeyframe(pane);
    this.requestKeyframe(pane);
  }

  private dispatchFrame(pane: PaneId, frame: EncodedFrame): void {
    if (!this.generations.acceptFrame(pane, frame)) {
      this.reportDecoderStatus(pane, 'frameRejected', `seq=${frame.sequence} key=${frame.keyFrame} config=${frame.config}`);
      this.recoverRejectedStream(pane);
      return;
    }
    if (frame.config) this.reportDecoderStatus(pane, 'configFrame', `bytes=${frame.payload.byteLength}`);
    else if (frame.keyFrame) this.reportDecoderStatus(pane, 'keyFrame', `seq=${frame.sequence} bytes=${frame.payload.byteLength}`);
    this.health.frame(pane);
    const count = (this.frameCounts.get(pane) ?? 0) + 1;
    this.frameCounts.set(pane, count);
    this.frameListeners.get(pane)?.forEach((listener) => listener(frame));
  }

  private recoverRejectedStream(pane: PaneId): void {
    const now = performance.now();
    const lastRequestAt = this.lastKeyframeRequestAt.get(pane) ?? 0;
    if (now - lastRequestAt < 400) return;
    this.lastKeyframeRequestAt.set(pane, now);
    this.requestKeyframe(pane);
    this.reportDecoderStatus(pane, 'requestKeyframeAfterReject');
  }

  private resetSession(reason: string): void {
    this.sessionEpoch += 1;
    this.seenEvents.clear();
    this.lastKeyframeRequestAt.clear();
    console.info('[CastlaRuntime] resetSession', {
      reason,
      sessionEpoch: this.sessionEpoch,
      appLaunchSequence: this.appLaunchSequence,
      serverInstanceId: this.serverInstanceId,
      controlSessionId: this.controlSessionId
    });
    this.sessionListeners.forEach((listener) => listener(this.sessionEpoch, reason));
  }
}

function align16(value: number): number {
  return (Math.round(value) + 15) & ~15;
}

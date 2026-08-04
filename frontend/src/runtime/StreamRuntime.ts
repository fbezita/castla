import type { AckMessage, EncodedFrame, PaneId, StreamMetadata } from "../protocol";
import { ControlTransport } from "../transport/ControlTransport";
import { VideoTransport } from "../transport/VideoTransport";
import { AudioPlayer } from "../transport/AudioPlayer";
import { GenerationTracker } from "./GenerationTracker";
import { StreamHealthMonitor } from "./StreamHealthMonitor";

export class StreamRuntime {
  readonly control: ControlTransport;
  readonly audio = new AudioPlayer();
  readonly generations = new GenerationTracker();
  readonly health = new StreamHealthMonitor();
  private serverInstanceId = "unknown";
  private controlSessionId = 0;
  private screenOff = false;
  private videoFrozen = false;
  private screenOffHoldUntil = 0;
  private videoTransports = new Map<PaneId, VideoTransport>();
  private frameListeners = new Map<
    PaneId,
    Set<(frame: EncodedFrame) => void>
  >();
  private frameCounts = new Map<PaneId, number>();
  private preferredProfiles = new Map<PaneId, string>();
  private connectionListeners = new Set<(connected: boolean) => void>();
  private sessionListeners = new Set<(epoch: number, reason: string) => void>();
  private touchStateListeners = new Set<(reason: string) => void>();
  private frontendResetListeners = new Set<(reason: string) => void>();
  private lastLayout: Array<{
    id: PaneId;
    width: number;
    height: number;
    visible?: boolean;
  }> = [];
  private lastLayoutVisibility = "";
  private lastLayoutSignature = "";
  private layoutUpdateCount = 0;
  private layoutDedupedCount = 0;
  private lastKeyframeRequestAt = new Map<PaneId, number>();
  private consecutiveFrameRejects = new Map<PaneId, number>();
  private lastFrameRejectAt = new Map<PaneId, number>();
  private seenEvents = new Set<string>();
  private sessionEpoch = 0;
  private appLaunchSequence = 0;
  private wasConnected = false;
  private started = false;
  private controlMessageCleanup?: () => void;

  get isScreenOff(): boolean {
    return this.screenOff || performance.now() < this.screenOffHoldUntil;
  }
   get isVideoFrozen(): boolean {     return this.videoFrozen;   }
  
  // E2E ACK and handshake capabilities indicators
  private ackListeners = new Set<(message: AckMessage) => void>();
  private supportsAckFeatures = false;
  private protocolVersion = "1.0.0";

  onAckMessage(listener: (message: AckMessage) => void): () => void {
    this.ackListeners.add(listener);
    return () => this.ackListeners.delete(listener);
  }

  setHandshakeInfo(version: string, supportsAck: boolean): void {
    this.protocolVersion = version;
    this.supportsAckFeatures = supportsAck;
    console.info(`[HANDSHAKE] Backend Protocol=${version} AckSupported=${supportsAck}`);
  }

  isAckSupported(): boolean {
    return this.supportsAckFeatures;
  }
  private static readonly noisyDecoderEvents = new Set([
    "metadata",
    "configFrame",
    "keyFrame",
    "frameSummary",
    "jmuxerReady",
    "jmuxerConfig",
    "jmuxerCreated",
    "jmuxerMseReady",
    "jmuxerQueue",
    "jmuxerFeedSummary",
    "videoLoadedMetadata",
    "videoLoadedData",
    "videoCanPlay",
    "videoPlaying",
    "videoHasCurrentData",
  ]);

  constructor(private readonly host: string) {
    this.control = new ControlTransport(host);
    this.control.onConnectionChange((connected) => {
      // console.info("[CastlaSession] control_connection", {
      //   connected,
      //   sessionEpoch: this.sessionEpoch,
      //   controlSessionId: this.controlSessionId,
      //   appLaunchSequence: this.appLaunchSequence,
      // });
      this.connectionListeners.forEach((listener) => listener(connected));
      if (!connected) {
        this.wasConnected = false;
        return;
      }
      this.wasConnected = true;
      this.resetTouchState("control_connected");
      if (this.lastLayout.length > 0) {
        this.sendLayout(this.lastLayout);
      }
      for (const [pane, listeners] of this.frameListeners) {
        if (listeners.size === 0) continue;
        const profile = this.preferredProfiles.get(pane) ?? "High";
        this.setCodec(pane, "h264", profile);
        this.requestKeyframe(pane);
      }
    });
  }

  start(): void {
    if (this.started) return;
    this.started = true;
    this.control.connect();
    this.controlMessageCleanup = this.control.onMessage((message) => {
      const type = (message as { type?: string }).type;

      if (type === "serverInit") {
        this.serverInstanceId = String(
          (message as { instanceId?: unknown }).instanceId ?? "unknown",
        );
        this.controlSessionId = Number(
          (message as { controlSessionId?: unknown }).controlSessionId ?? 0,
        );
        // Extract protocol capability flags from serverInit if present
        const version = String((message as any).protocolVersion ?? "1.0.0");
        const supportsAck = Boolean((message as any).supportsAck ?? (message as any).supportsAckFeatures ?? false);
        this.setHandshakeInfo(version, supportsAck);
      }
      const controlReason = String((message as { reason?: unknown }).reason ?? "unknown");
      const controlTimestamp = String((message as { timestampMs?: unknown }).timestampMs ?? "unknown");
      if (type === "freezeVideo") {
        this.videoFrozen = true;
        console.warn(`[CastlaVideo] frozen reason=${controlReason} ts=${controlTimestamp}`);
      } else if (type === "resumeVideo") {
        this.videoFrozen = false;
        this.screenOff = false;
        this.screenOffHoldUntil = 0;
        console.warn(`[CastlaVideo] resumed reason=${controlReason} ts=${controlTimestamp}`);
        this.requestKeyframe("primary");
      }
      if (type === "diagnostics") {
        const server = (message as { server?: { screenOff?: unknown } }).server;
        if (typeof server?.screenOff === "boolean") {
          const nextScreenOff = server.screenOff;
          if (nextScreenOff !== this.screenOff) {
            console.info("[CastlaScreenOff] " + (this.screenOff ? "OFF" : "ON") + " -> " + (nextScreenOff ? "OFF" : "ON"));
          }
          this.screenOff = nextScreenOff;
          if (nextScreenOff) {
            this.screenOffHoldUntil = performance.now() + 1500;
          } else {
            this.videoFrozen = false;
            this.screenOffHoldUntil = 0;
          }
        }
      }

      if (type === "streamMetadata") {
        const metadata = message as StreamMetadata;
        this.generations.update(metadata);
      }

      // Dispatch E2E ACK control packets directly to active promise listeners
      if (
        type === "layout_ack" ||
        type === "launch_ack" ||
        type === "session_ready" ||
        type === "launch_failed"
      ) {
        this.ackListeners.forEach((listener) => listener(message as AckMessage));
      }

      // Handle remote touchReset commands sent from the server watchdog to break client-side pointer locks
      if (type === "touchReset") {
        const reason =
          (message as { reason?: string }).reason ?? "server_recovery";
        console.warn(
          "[CastlaSession] Received forced touchReset command from server",
          { reason },
        );
        this.touchStateListeners.forEach((listener) => listener(reason));
      }
    });
  }

  dispose(): void {
    this.controlMessageCleanup?.();
    this.controlMessageCleanup = undefined;
    this.started = false;
    this.screenOff = false;
    this.videoFrozen = false; this.screenOff = false; this.screenOffHoldUntil = 0;
    this.screenOffHoldUntil = 0;
    this.control.close();
    this.audio.stop();
    this.videoTransports.forEach((transport) => transport.close());
    this.videoTransports.clear();
    this.frameListeners.clear();
    this.frameCounts.clear();
    this.lastKeyframeRequestAt.clear();
    this.consecutiveFrameRejects.clear();
    this.lastFrameRejectAt.clear();
    this.seenEvents.clear();
  }

  requestKeyframe(pane: PaneId): void {
    this.generations.resetForKeyframe(pane);
    this.control.send({ type: "requestKeyframe", pane });
  }

  resetTouchState(reason = "manual"): void {
    // console.info("[CastlaSession] touch_reset", {
    //   reason,
    //   sessionEpoch: this.sessionEpoch,
    //   controlSessionId: this.controlSessionId,
    //   appLaunchSequence: this.appLaunchSequence,
    // });
    this.control.send({ type: "touchReset" });
    this.touchStateListeners.forEach((listener) => listener(reason));
  }

  resetTouchSession(reason: string): void {
    this.sessionEpoch += 1;
    // console.info("[CastlaSession] touch_session", {
    //   reason,
    //   sessionEpoch: this.sessionEpoch,
    //   controlSessionId: this.controlSessionId,
    //   appLaunchSequence: this.appLaunchSequence,
    // });
    this.resetTouchState(`session:${reason}`);
  }

  resetFrontendInteraction(reason: string): void {
    this.resetTouchSession(reason);
    this.frontendResetListeners.forEach((listener) => listener(reason));
  }

  setCodec(pane: PaneId, mode: "h264" | "mjpeg", profile = "High"): void {
    this.preferredProfiles.set(pane, profile);
    this.control.send({ type: "codec", pane, mode, profile });
  }

  sendLayout(
    layout: Array<{
      id: PaneId;
      width: number;
      height: number;
      visible?: boolean;
    }>,
    seqId?: number,
  ): void {
    const normalized = layout.map((pipeline) => {
      if (pipeline.id === "popup") {
        return {
          id: pipeline.id,
          width: Math.max(320, align16(pipeline.width)),
          height: Math.max(320, align16(pipeline.height)),
          visible: pipeline.visible ?? true,
          committed: true,
        };
      }
      return {
        id: pipeline.id,
        width: Math.max(320, align16(pipeline.width)),
        height: Math.max(320, align16(pipeline.height)),
        visible: pipeline.visible ?? true,
      };
    });
    const signature = normalized
      .map((pipeline) => `${pipeline.id}:${pipeline.width}x${pipeline.height}:${pipeline.visible ? 1 : 0}`)
      .sort()
      .join("|");
    const nextVisibility = normalized
      .map((pipeline) => `${pipeline.id}:${pipeline.visible ? 1 : 0}`)
      .sort()
      .join("|");
    this.lastLayoutVisibility = nextVisibility;
    this.lastLayout = normalized.map((pipeline) => ({ ...pipeline }));
    if (signature === this.lastLayoutSignature && seqId === undefined) {
      this.layoutDedupedCount += 1;
      return;
    }
    this.lastLayoutSignature = signature;
    this.layoutUpdateCount += 1;
    this.control.send({
      type: "layout_update",
      pipelines: normalized,
      seqId: seqId !== undefined ? seqId : undefined,
    });
  }

  updateLayout() {
    // if (this.lastLayout.length > 0) {
    this.sendLayout(this.lastLayout);
    // }
  }

  onConnectionChange(listener: (connected: boolean) => void): () => void {
    this.connectionListeners.add(listener);
    return () => this.connectionListeners.delete(listener);
  }

  onSessionChange(
    listener: (epoch: number, reason: string) => void,
  ): () => void {
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

  hasPendingBufferedAmount(): boolean {
    return this.control.hasPendingBufferedAmount();
  }

  currentAppLaunchSequence(): number {
    return this.appLaunchSequence;
  }

  launchApp(
    pkg: string,
    pane: PaneId,
    componentName?: string,
    isVideoApp = false,
    seqId?: number,
  ): void {
    this.appLaunchSequence += 1;
    // console.info("[CastlaSession] app_launch", {
    //   pkg,
    //   pane,
    //   componentName: componentName ?? null,
    //   isVideoApp,
    //   appLaunchSequence: this.appLaunchSequence,
    //   sessionEpoch: this.sessionEpoch,
    //   controlSessionId: this.controlSessionId,
    // });
    this.resetFrontendInteraction("app_launch");
    this.control.send({
      type: "launchApp",
      pkg,
      pane,
      componentName,
      isVideoApp,
      seqId: seqId !== undefined ? seqId : undefined,
    });
  }

  goHome(): void {
    this.control.send({ type: "goHome" });
  }

  setDisplayDensity(scale: number): void {
    this.control.send({ type: "displayDensity", scale });
  }

  setPlaybackProfile(profile: string): void {
    this.control.send({ type: "playbackProfile", profile });
  }

  reportDecoderStatus(pane: PaneId, event: string, detail = ""): void {
    if (StreamRuntime.noisyDecoderEvents.has(event)) return;
    const key = `${pane}:${event}:${detail}`;
    if (this.seenEvents.has(key) && !event.endsWith("summary")) return;
    this.seenEvents.add(key);
    this.control.send({ type: "decoderStatus", pane, event, detail });
    console.warn(`[CastlaDecoder:${pane}] ${event}`, detail);
  }

  attachVideo(
    pane: PaneId,
    onFrame: (frame: EncodedFrame) => void,
  ): () => void {
    let listeners = this.frameListeners.get(pane);
    if (!listeners) {
      listeners = new Set();
      this.frameListeners.set(pane, listeners);
    }
    listeners.add(onFrame);
    if (!this.videoTransports.has(pane)) {
      const transport = new VideoTransport(
        this.host,
        pane,
        (frame) => this.dispatchFrame(pane, frame),
        () => this.health.reconnect(pane),
      );
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
    this.reportDecoderStatus(pane, "softReconnect");
    this.resetSession(`softReconnect:${pane}`);
    this.generations.resetForKeyframe(pane);
    this.videoTransports.get(pane)?.reconnectNow();
    this.control.reconnectNow();
  }

  recoverPaneStream(pane: PaneId): void {
    this.reportDecoderStatus(pane, "paneRecover");
    this.health.beginRecovery(pane);
    this.generations.resetForKeyframe(pane);
    this.requestKeyframe(pane);
  }

  private dispatchFrame(pane: PaneId, frame: EncodedFrame): void {
    if (!this.generations.acceptFrame(pane, frame)) {
      const now = performance.now();
      const lastRejectAt = this.lastFrameRejectAt.get(pane) ?? 0;
      // Relax interval checking to 3000ms to tolerate low-fps/idle drop recovery loops
      const nextRejectCount =
        now - lastRejectAt <= 3000
          ? (this.consecutiveFrameRejects.get(pane) ?? 0) + 1
          : 1;
      this.lastFrameRejectAt.set(pane, now);
      this.consecutiveFrameRejects.set(pane, nextRejectCount);
      if (nextRejectCount === 1 || nextRejectCount === 3) {
        this.reportDecoderStatus(
          pane,
          "frameRejected",
          `seq=${frame.sequence} key=${frame.keyFrame} config=${frame.config} count=${nextRejectCount}`,
        );
      }
      this.recoverRejectedStream(pane, nextRejectCount);
      return;
    }
    this.consecutiveFrameRejects.delete(pane);
    this.lastFrameRejectAt.delete(pane);
    if (frame.config)
      this.reportDecoderStatus(
        pane,
        "configFrame",
        `bytes=${frame.payload.byteLength}`,
      );
    else if (frame.keyFrame)
      this.reportDecoderStatus(
        pane,
        "keyFrame",
        `seq=${frame.sequence} bytes=${frame.payload.byteLength}`,
      );
    this.health.frame(pane);
    const count = (this.frameCounts.get(pane) ?? 0) + 1;
    this.frameCounts.set(pane, count);
    this.frameListeners.get(pane)?.forEach((listener) => listener(frame));
  }

  private recoverRejectedStream(pane: PaneId, rejectCount: number): void {
    if (rejectCount < 3) return;
    const now = performance.now();
    const lastRequestAt = this.lastKeyframeRequestAt.get(pane) ?? 0;
    if (now - lastRequestAt < 1000) return;
    this.lastKeyframeRequestAt.set(pane, now);
    this.requestKeyframe(pane);
    this.reportDecoderStatus(
      pane,
      "requestKeyframeAfterReject",
      `count=${rejectCount}`,
    );
  }

  private resetSession(reason: string): void {
    this.sessionEpoch += 1;
    this.seenEvents.clear();
    this.lastKeyframeRequestAt.clear();
    this.consecutiveFrameRejects.clear();
    this.lastFrameRejectAt.clear();
    this.sessionListeners.forEach((listener) =>
      listener(this.sessionEpoch, reason),
    );
  }

  startAudio(): void {
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const wsUrl = `${protocol}://${this.host}/ws/audio`;
    void this.audio.startFromUserGesture(wsUrl);
  }
}

function align16(value: number): number {
  return (Math.round(value) + 15) & ~15;
}

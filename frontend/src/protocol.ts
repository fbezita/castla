export type DisplayTier = 'ACTIVE' | 'VISIBLE' | 'SUSPENDED' | 'PARKED';
export type PaneId = 'primary' | 'secondary' | string;

export interface StreamMetadata {
  type: 'streamMetadata';
  sessionId: PaneId;
  vdId: number;
  generation: number;
  width: number;
  height: number;
  streamReady: boolean;
  firstFrameReady: boolean;
}

export interface DiagnosticsDisplay {
  sessionId: PaneId;
  vdId: number;
  tier: DisplayTier;
  generation: number;
  width: number;
  height: number;
  encoderRunning: boolean;
  streamReady: boolean;
  firstFrameReady: boolean;
  reconnectCount: number;
  lastFrameTimestampMs: number;
  droppedFrames: number;
  generationMismatch: number;
}

export interface ServerDiagnostics {
  reason: string;
  browserConnected: boolean;
  serverBrowserConnected: boolean;
  pendingDisconnect: boolean;
  disconnectGraceMs: number;
  screenOff: boolean;
  teardownPhase: string;
  socketSummary: string;
  pipelineSnapshot: string;
  launchSeq: number;
  lastTouchPane: string;
  timestampMs: number;
  touchTrace?: string[];
  injectorSnapshot?: string;
  rejectProbe?: string;
}

export interface DiagnosticsMessage {
  type: 'diagnostics';
  displays?: DiagnosticsDisplay[];
  server?: ServerDiagnostics;
}

export interface ServerInitMessage {
  type: 'serverInit';
  instanceId: string;
  controlSessionId?: number;
}

export type ControlMessage =
  | StreamMetadata
  | DiagnosticsMessage
  | ServerInitMessage
  | Record<string, unknown>;

export interface EncodedFrame {
  flags: number;
  sequence: number;
  serverTimestampMs: number;
  payload: ArrayBuffer;
  keyFrame: boolean;
  config: boolean;
}

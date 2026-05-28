import type { ControlMessage } from "../protocol";

export class ControlTransport {
  private static nextSocketId = 1;
  private socket?: WebSocket;
  private listeners = new Set<(message: ControlMessage) => void>();
  private connectionListeners = new Set<(connected: boolean) => void>();
  private reconnectTimer = 0;
  private heartbeatTimer = 0;
  private pendingMessages: string[] = [];
  private lastPongAt = 0;
  private socketId = 0;
  private connectAttempt = 0;
  private readyForControl = false;
  private controlSessionId = 0;
  private manuallyClosed = false;

  constructor(private readonly host: string) {}

  connect(): void {
    const previous = this.socket;
    if (previous) {
      previous.onclose = null;
      previous.close();
    }
    this.manuallyClosed = false;
    this.connectAttempt += 1;
    this.socketId = ControlTransport.nextSocketId++;
    this.readyForControl = false;
    this.controlSessionId = 0;
    // Enforce plain ws:// connection to bypass redundant secure handshake overheads
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    this.socket = new WebSocket(`${protocol}://${this.host}/ws/control`);
    this.socket.onopen = () => {
      this.lastPongAt = performance.now();
      // console.info('[CastlaControl] open', {
      //   socketId: this.socketId,
      //   connectAttempt: this.connectAttempt
      // });
      this.startHeartbeat();
    };
    this.socket.onmessage = (event) => {
      if (typeof event.data !== "string") return;
      try {
        const message = JSON.parse(event.data) as ControlMessage;
        if ((message as { type?: string }).type === "pong") {
          this.lastPongAt = performance.now();
        }
        if ((message as { type?: string }).type === "serverInit") {
          this.controlSessionId = Number(
            (message as { controlSessionId?: unknown }).controlSessionId ?? 0,
          );
          this.readyForControl = true;
          // console.info('[CastlaControl] serverInit', {
          //   socketId: this.socketId,
          //   connectAttempt: this.connectAttempt,
          //   controlSessionId: this.controlSessionId
          // });
        }
        this.listeners.forEach((listener) => listener(message));
        if ((message as { type?: string }).type === "serverInit") {
          this.connectionListeners.forEach((listener) => listener(true));
          this.flushPending();
        }
      } catch {
        // Ignore malformed control messages from old clients or partial frames.
      }
    };
    this.socket.onclose = () => {
      window.clearInterval(this.heartbeatTimer);
      this.readyForControl = false;
      // console.warn('[CastlaControl] close', {
      //   socketId: this.socketId,
      //   connectAttempt: this.connectAttempt,
      //   controlSessionId: this.controlSessionId,
      //   pendingMessages: this.pendingMessages.length
      // });
      this.connectionListeners.forEach((listener) => listener(false));
      if (!this.manuallyClosed) {
        this.scheduleReconnect();
      }
    };
  }

  close(): void {
    this.manuallyClosed = true;
    window.clearTimeout(this.reconnectTimer);
    window.clearInterval(this.heartbeatTimer);
    this.readyForControl = false;
    this.controlSessionId = 0;
    const socket = this.socket;
    this.socket = undefined;
    if (socket) {
      socket.onclose = null;
      socket.close();
    }
  }

  reconnectNow(): void {
    window.clearTimeout(this.reconnectTimer);
    this.connect();
  }

  onMessage(listener: (message: ControlMessage) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  onConnectionChange(listener: (connected: boolean) => void): () => void {
    this.connectionListeners.add(listener);
    return () => this.connectionListeners.delete(listener);
  }

  send(message: Record<string, unknown>): void {
    const payload = JSON.stringify(message);
    if (this.socket?.readyState === WebSocket.OPEN && this.readyForControl) {
      this.socket.send(payload);
    } else {
      this.pendingMessages.push(payload);
    }
  }

  sendBinary(data: ArrayBuffer): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(data);
    }
  }

  private scheduleReconnect(): void {
    window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = window.setTimeout(() => this.connect(), 750);
  }

  private startHeartbeat(): void {
    window.clearInterval(this.heartbeatTimer);
    this.heartbeatTimer = window.setInterval(() => {
      const socket = this.socket;
      if (!socket || socket.readyState !== WebSocket.OPEN) return;
      const now = performance.now();
      if (now - this.lastPongAt > 120000) {
        this.reconnectNow();
        return;
      }
      socket.send(JSON.stringify({ type: "ping", ts: Date.now() }));
    }, 15000);
  }

  private flushPending(): void {
    const socket = this.socket;
    if (
      !socket ||
      socket.readyState !== WebSocket.OPEN ||
      !this.readyForControl
    )
      return;
    const pending = this.pendingMessages.splice(0, this.pendingMessages.length);
    pending.forEach((payload) => socket.send(payload));
  }

  debugSnapshot(): {
    socketId: number;
    connectAttempt: number;
    readyState: number;
    pendingMessages: number;
    messageListeners: number;
    connectionListeners: number;
  } {
    return {
      socketId: this.socketId,
      connectAttempt: this.connectAttempt,
      readyState: this.socket?.readyState ?? WebSocket.CLOSED,
      readyForControl: this.readyForControl,
      controlSessionId: this.controlSessionId,
      pendingMessages: this.pendingMessages.length,
      messageListeners: this.listeners.size,
      connectionListeners: this.connectionListeners.size,
    };
  }
}

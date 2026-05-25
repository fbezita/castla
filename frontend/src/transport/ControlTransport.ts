import type { ControlMessage } from '../protocol';

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

  constructor(private readonly host: string) {}

  connect(): void {
    const previous = this.socket;
    if (previous) {
      previous.onclose = null;
      previous.close();
    }
    this.connectAttempt += 1;
    this.socketId = ControlTransport.nextSocketId++;
    this.readyForControl = false;
    this.controlSessionId = 0;
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    console.info('[CastlaControl] connect', {
      socketId: this.socketId,
      connectAttempt: this.connectAttempt,
      readyForControl: this.readyForControl,
      listeners: this.listeners.size,
      connectionListeners: this.connectionListeners.size,
      pendingMessages: this.pendingMessages.length
    });
    this.socket = new WebSocket(`${protocol}://${this.host}/ws/control`);
    this.socket.onopen = () => {
      this.lastPongAt = performance.now();
      console.info('[CastlaControl] open', {
        socketId: this.socketId,
        connectAttempt: this.connectAttempt,
        readyForControl: this.readyForControl,
        pendingMessages: this.pendingMessages.length
      });
      this.startHeartbeat();
    };
    this.socket.onmessage = (event) => {
      if (typeof event.data !== 'string') return;
      try {
        const message = JSON.parse(event.data) as ControlMessage;
        if ((message as { type?: string }).type === 'pong') {
          this.lastPongAt = performance.now();
        }
        if ((message as { type?: string }).type === 'serverInit') {
          this.controlSessionId = Number((message as { controlSessionId?: unknown }).controlSessionId ?? 0);
          this.readyForControl = true;
          console.info('[CastlaControl] serverInit ready', {
            socketId: this.socketId,
            connectAttempt: this.connectAttempt,
            controlSessionId: this.controlSessionId,
            pendingMessages: this.pendingMessages.length
          });
        }
        this.listeners.forEach((listener) => listener(message));
        if ((message as { type?: string }).type === 'serverInit') {
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
      console.warn('[CastlaControl] close', {
        socketId: this.socketId,
        connectAttempt: this.connectAttempt,
        controlSessionId: this.controlSessionId,
        pendingMessages: this.pendingMessages.length
      });
      this.connectionListeners.forEach((listener) => listener(false));
      this.scheduleReconnect();
    };
  }

  reconnectNow(): void {
    window.clearTimeout(this.reconnectTimer);
    this.connect();
  }

  onMessage(listener: (message: ControlMessage) => void): () => void {
    this.listeners.add(listener);
    console.info('[CastlaControl] onMessage listener+', { count: this.listeners.size, socketId: this.socketId });
    return () => this.listeners.delete(listener);
  }

  onConnectionChange(listener: (connected: boolean) => void): () => void {
    this.connectionListeners.add(listener);
    console.info('[CastlaControl] onConnectionChange listener+', { count: this.connectionListeners.size, socketId: this.socketId });
    return () => this.connectionListeners.delete(listener);
  }

  send(message: Record<string, unknown>): void {
    const payload = JSON.stringify(message);
    if (this.socket?.readyState === WebSocket.OPEN && this.readyForControl) {
      this.socket.send(payload);
    } else {
      console.info('[CastlaControl] queue outbound', {
        socketId: this.socketId,
        connectAttempt: this.connectAttempt,
        readyForControl: this.readyForControl,
        controlSessionId: this.controlSessionId,
        type: String(message.type ?? 'unknown'),
        pendingBefore: this.pendingMessages.length
      });
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
      socket.send(JSON.stringify({ type: 'ping', ts: Date.now() }));
    }, 15000);
  }

  private flushPending(): void {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN || !this.readyForControl) return;
    const pending = this.pendingMessages.splice(0, this.pendingMessages.length);
    console.info('[CastlaControl] flushPending', {
      socketId: this.socketId,
      connectAttempt: this.connectAttempt,
      controlSessionId: this.controlSessionId,
      pending: pending.length
    });
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
      connectionListeners: this.connectionListeners.size
    };
  }
}

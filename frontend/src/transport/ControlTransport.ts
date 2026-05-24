import type { ControlMessage } from '../protocol';

export class ControlTransport {
  private socket?: WebSocket;
  private listeners = new Set<(message: ControlMessage) => void>();
  private connectionListeners = new Set<(connected: boolean) => void>();
  private reconnectTimer = 0;
  private pendingMessages: string[] = [];

  constructor(private readonly host: string) {}

  connect(): void {
    this.socket?.close();
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    this.socket = new WebSocket(`${protocol}://${this.host}/ws/control`);
    this.socket.onopen = () => {
      this.connectionListeners.forEach((listener) => listener(true));
      this.flushPending();
    };
    this.socket.onmessage = (event) => {
      if (typeof event.data !== 'string') return;
      try {
        const message = JSON.parse(event.data) as ControlMessage;
        this.listeners.forEach((listener) => listener(message));
      } catch {
        // Ignore malformed control messages from old clients or partial frames.
      }
    };
    this.socket.onclose = () => {
      this.connectionListeners.forEach((listener) => listener(false));
      this.scheduleReconnect();
    };
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
    if (this.socket?.readyState === WebSocket.OPEN) {
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

  private flushPending(): void {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    const pending = this.pendingMessages.splice(0, this.pendingMessages.length);
    pending.forEach((payload) => socket.send(payload));
  }
}

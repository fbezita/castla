import type { EncodedFrame, PaneId } from '../protocol';

export class VideoTransport {
  private socket?: WebSocket;
  private reconnectTimer = 0;

  constructor(
    private readonly host: string,
    private readonly pane: PaneId,
    private readonly onFrame: (frame: EncodedFrame) => void,
    private readonly onReconnect: () => void
  ) {}

  connect(): void {
    const previous = this.socket;
    if (previous) {
      previous.onclose = null;
      previous.close();
    }
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    this.socket = new WebSocket(`${protocol}://${this.host}/ws/video?channel=${encodeURIComponent(this.pane)}`);
    this.socket.binaryType = 'arraybuffer';
    this.socket.onmessage = (event) => {
      if (!(event.data instanceof ArrayBuffer) || event.data.byteLength < 8) return;
      this.onFrame(parseFrame(event.data));
    };
    this.socket.onclose = () => this.scheduleReconnect();
  }

  close(): void {
    window.clearTimeout(this.reconnectTimer);
    if (this.socket) {
      this.socket.onclose = null;
      this.socket.close();
    }
    this.socket = undefined;
  }

  reconnectNow(): void {
    window.clearTimeout(this.reconnectTimer);
    this.connect();
  }

  private scheduleReconnect(): void {
    this.onReconnect();
    window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = window.setTimeout(() => this.connect(), 750);
  }
}

function parseFrame(data: ArrayBuffer): EncodedFrame {
  const view = new DataView(data);
  const flags = view.getUint8(0);
  return {
    flags,
    sequence: view.getUint16(1, true),
    serverTimestampMs: view.getUint32(3, true),
    payload: data.slice(8),
    keyFrame: flags === 0x01,
    config: flags === 0x02
  };
}

import type { EncodedFrame, PaneId } from "../protocol";

export class VideoTransport {
  private socket?: WebSocket;
  private reconnectTimer = 0;

  constructor(
    private readonly host: string,
    private readonly pane: PaneId,
    private readonly onFrame: (frame: EncodedFrame) => void,
    private readonly onReconnect: () => void,
  ) {}

  connect(): void {
    const previous = this.socket;
    if (previous) {
      previous.onclose = null;
      previous.close();
    }
    // Enforce plain ws:// connection to bypass redundant secure handshake overheads
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const url = `${protocol}://${this.host}/ws/video?channel=${encodeURIComponent(this.pane)}`;
    this.socket = new WebSocket(url);

    // console.warn("[VideoWS] opening", {
    //   url,
    //   href: window.location.href,
    //   host: this.host,
    //   protocol,
    //   pane: this.pane,
    // });

    this.socket.binaryType = "arraybuffer";

    this.socket.onopen = () => {
      // console.warn("[VideoWS] open", url);
    };

    this.socket.onerror = (event) => {
      // console.warn("[VideoWS] error", url, event);
    };

    this.socket.onclose = (event) => {
      // console.warn("[VideoWS] close", {
      //   url,
      //   code: event.code,
      //   reason: event.reason,
      //   wasClean: event.wasClean,
      // });
      this.scheduleReconnect();
    };

    this.socket.onmessage = (event) => {
      if (!(event.data instanceof ArrayBuffer) || event.data.byteLength < 8)
        return;
      this.onFrame(parseFrame(event.data));
    };
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
  const serverTimestampMs = view.getUint32(3, true);

  return {
    flags,
    sequence: view.getUint16(1, true),
    serverTimestampMs,
    // Keep this alias because WebCodecsBackend historically read timestampMs.
    // Without it EncodedVideoChunk.timestamp becomes NaN and WebCodecs can stay black
    // while MSE still works.
    timestampMs: serverTimestampMs,
    payload: data.slice(8),
    keyFrame: (flags & 0x01) !== 0,
    config: (flags & 0x02) !== 0,
  };
}

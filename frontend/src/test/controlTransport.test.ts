import { afterEach, describe, expect, it, vi } from "vitest";
import { ControlTransport } from "../transport/ControlTransport";

describe("ControlTransport connection state", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("does not report unavailable while the initial connection result is unknown", () => {
    const transport = new ControlTransport("castla.test");
    const listener = vi.fn();

    transport.onConnectionChange(listener);

    expect(listener).not.toHaveBeenCalled();
  });

  it("replays a confirmed connection failure to listeners mounted later", () => {
    vi.useFakeTimers();
    let socket: FakeWebSocket | undefined;
    class FakeWebSocket {
      static OPEN = 1;
      static CLOSED = 3;
      readyState = 0;
      onopen: (() => void) | null = null;
      onmessage: ((event: MessageEvent) => void) | null = null;
      onclose: (() => void) | null = null;

      constructor(_url: string) {
        socket = this;
      }

      close() {
        this.readyState = FakeWebSocket.CLOSED;
      }

      send(_payload: string) {}
    }
    vi.stubGlobal("WebSocket", FakeWebSocket);
    vi.stubGlobal("window", {
      location: { protocol: "http:" },
      clearInterval: vi.fn(),
      clearTimeout: vi.fn(),
      setInterval: vi.fn(() => 1),
      setTimeout: vi.fn(() => 1),
    });
    const transport = new ControlTransport("castla.test");
    const firstListener = vi.fn();
    transport.onConnectionChange(firstListener);

    transport.connect();
    socket?.onclose?.();

    expect(firstListener).toHaveBeenCalledOnce();
    expect(firstListener).toHaveBeenCalledWith(false);

    const lateListener = vi.fn();
    transport.onConnectionChange(lateListener);
    expect(lateListener).toHaveBeenCalledOnce();
    expect(lateListener).toHaveBeenCalledWith(false);
  });

  it("reports connectivity when the control WebSocket opens before serverInit", () => {
    let socket: FakeWebSocket | undefined;
    class FakeWebSocket {
      static OPEN = 1;
      static CLOSED = 3;
      readyState = 0;
      onopen: (() => void) | null = null;
      onmessage: ((event: MessageEvent) => void) | null = null;
      onclose: (() => void) | null = null;

      constructor(_url: string) {
        socket = this;
      }

      close() {}
      send(_payload: string) {}
    }
    vi.stubGlobal("WebSocket", FakeWebSocket);
    vi.stubGlobal("window", {
      location: { protocol: "http:" },
      clearInterval: vi.fn(),
      clearTimeout: vi.fn(),
      setInterval: vi.fn(() => 1),
      setTimeout: vi.fn(() => 1),
    });
    vi.stubGlobal("performance", { now: vi.fn(() => 1) });
    const transport = new ControlTransport("castla.test");
    const listener = vi.fn();
    transport.onConnectionChange(listener);

    transport.connect();
    socket?.onopen?.();

    expect(listener).toHaveBeenCalledOnce();
    expect(listener).toHaveBeenCalledWith(true);
  });
});

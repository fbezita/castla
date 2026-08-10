import { describe, expect, it, vi } from "vitest";
import { ControlTransport } from "../transport/ControlTransport";

describe("ControlTransport connection state", () => {
  it("immediately reports the current disconnected state to a new listener", () => {
    const transport = new ControlTransport("castla.test");
    const listener = vi.fn();

    transport.onConnectionChange(listener);

    expect(listener).toHaveBeenCalledOnce();
    expect(listener).toHaveBeenCalledWith(false);
  });
});

import { describe, expect, it } from "vitest";
import { connectionOverlayDelayMs } from "../lib/connectionUi";

describe("connection UI policy", () => {
  it("gives the initial server handshake more time than a reconnect", () => {
    expect(connectionOverlayDelayMs(false)).toBe(3_000);
    expect(connectionOverlayDelayMs(true)).toBe(600);
  });
});

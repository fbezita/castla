import { describe, expect, it } from "vitest";
import { clampVideoLatencyMs, liveEdgeTargetSeconds } from "../decoder/videoLatency";

describe("video latency", () => {
  it("clamps settings to the supported range", () => {
    expect(clampVideoLatencyMs(-1)).toBe(0);
    expect(clampVideoLatencyMs(500)).toBe(500);
    expect(clampVideoLatencyMs(1500)).toBe(1000);
  });

  it("calculates a delayed MSE live edge", () => {
    expect(liveEdgeTargetSeconds(12, 0, 180)).toBeCloseTo(11.82);
  });
});

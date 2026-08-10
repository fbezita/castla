import { describe, expect, it } from "vitest";
import { AudioStreamProtocol, audioDelaySeconds, audioOutputBufferSeconds, audioSignalPeak, buildAudioCapabilities, clampAudioOutputDelayMs, readAudioDelayControl, shouldFallbackFromOpus } from "../transport/audioProtocol";

describe("audio stream protocol", () => {
  it("ignores data before config and stale stream packets", () => {
    const protocol = new AudioStreamProtocol();
    expect(protocol.acceptPacket(1)).toBe(false);
    protocol.acceptConfig(2);
    expect(protocol.acceptPacket(1)).toBe(false);
    expect(protocol.acceptPacket(2)).toBe(true);
  });

  it("reports opus support and handles capability errors", async () => {
    expect(await buildAudioCapabilities(async () => ({ supported: true }))).toEqual({ type: "audioCapabilities", opus: true });
    expect(await buildAudioCapabilities(async () => { throw new Error("unsupported"); })).toEqual({ type: "audioCapabilities", opus: false });
  });

  it("falls back when opus packets arrive without decoded output", () => {
    expect(shouldFallbackFromOpus(24, 0)).toBe(false);
    expect(shouldFallbackFromOpus(25, 0)).toBe(true);
    expect(shouldFallbackFromOpus(100, 1)).toBe(false);
  });

  it("applies configured delay to browser audio instead of video", () => {
    expect(clampAudioOutputDelayMs(-1)).toBe(0);
    expect(clampAudioOutputDelayMs(1500)).toBe(1000);
    expect(audioOutputBufferSeconds(0)).toBeCloseTo(0.02);
    expect(audioOutputBufferSeconds(300)).toBeCloseTo(0.32);
  });

  it("converts the configured output delay for a Web Audio delay node", () => {
    expect(audioDelaySeconds(0)).toBe(0);
    expect(audioDelaySeconds(300)).toBeCloseTo(0.3);
    expect(audioDelaySeconds(1500)).toBe(1);
  });

  it("measures the signal that reaches the browser audio destination", () => {
    expect(audioSignalPeak(new Float32Array([-0.25, 0.8, -0.5]))).toBeCloseTo(0.8);
    expect(audioSignalPeak(new Float32Array())).toBe(0);
  });

  it("reads audio delay updates from the control websocket", () => {
    expect(readAudioDelayControl({ type: "audioDelay", outputDelayMs: 70 })).toBe(70);
    expect(readAudioDelayControl({ type: "diagnostics", outputDelayMs: 70 })).toBeNull();
  });
});

import { describe, expect, it } from "vitest";

import {
  normalizeOverlayScale,
  toOverlayPoint,
  toOverlayRect,
} from "../lib/overlayCoordinates";

describe("overlayCoordinates", () => {
  it("keeps viewport coordinates unchanged at 100% scale", () => {
    expect(toOverlayPoint({ x: 240, y: 120 }, 1)).toEqual({ x: 240, y: 120 });
    expect(toOverlayRect({ x: 20, y: 40, width: 200, height: 100 }, 1)).toEqual({
      x: 20,
      y: 40,
      width: 200,
      height: 100,
    });
  });

  it("converts viewport coordinates into scaled overlay coordinates", () => {
    expect(toOverlayPoint({ x: 240, y: 120 }, 1.5)).toEqual({ x: 160, y: 80 });
    expect(toOverlayRect({ x: 30, y: 60, width: 300, height: 150 }, 1.5)).toEqual({
      x: 20,
      y: 40,
      width: 200,
      height: 100,
    });
  });

  it("falls back to 100% scale for invalid scale values", () => {
    expect(normalizeOverlayScale(0)).toBe(1);
    expect(normalizeOverlayScale(Number.NaN)).toBe(1);
  });
});

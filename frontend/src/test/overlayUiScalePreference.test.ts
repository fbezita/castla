import { describe, expect, it } from "vitest";

import {
  clampOverlayUiScale,
  OVERLAY_UI_SCALE_DEFAULT,
  normalizeOverlayUiScalePreference,
} from "../utils/overlayUiScalePreference";

describe("overlayUiScalePreference", () => {
  it("defaults invalid values to 100%", () => {
    expect(normalizeOverlayUiScalePreference(null)).toBe(OVERLAY_UI_SCALE_DEFAULT);
    expect(normalizeOverlayUiScalePreference("bogus")).toBe(OVERLAY_UI_SCALE_DEFAULT);
  });

  it("accepts saved numeric values in range", () => {
    expect(normalizeOverlayUiScalePreference("1")).toBe(1);
    expect(normalizeOverlayUiScalePreference("1.2")).toBe(1.2);
    expect(normalizeOverlayUiScalePreference("2")).toBe(2);
  });

  it("clamps to the supported 100%-200% range", () => {
    expect(clampOverlayUiScale(0.7)).toBe(1);
    expect(clampOverlayUiScale(1.5)).toBe(1.5);
    expect(clampOverlayUiScale(2.2)).toBe(2);
  });
});

import { describe, expect, it } from "vitest";
import {
  DEFAULT_POPUP_OPACITY,
  MIN_POPUP_OPACITY,
  MAX_POPUP_OPACITY,
  clampPopupOpacity,
  readStoredPopupOpacity,
} from "../lib/popupOpacity";

describe("popup opacity", () => {
  it("clamps values to the readable transparency range", () => {
    expect(clampPopupOpacity(0)).toBe(MIN_POPUP_OPACITY);
    expect(clampPopupOpacity(0.75)).toBe(0.75);
    expect(clampPopupOpacity(2)).toBe(MAX_POPUP_OPACITY);
  });

  it("uses the default for missing or invalid stored values", () => {
    expect(readStoredPopupOpacity(null)).toBe(DEFAULT_POPUP_OPACITY);
    expect(readStoredPopupOpacity("not-a-number")).toBe(DEFAULT_POPUP_OPACITY);
  });
});

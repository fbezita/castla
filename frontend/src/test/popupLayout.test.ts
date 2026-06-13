import { describe, expect, it } from "vitest";
import {
  STREAM_ALIGNED_POPUP_HEADER_HEIGHT,
  normalizePopupForStreaming,
} from "../lib/popupLayout";

describe("normalizePopupForStreaming", () => {
  it("aligns popup width and content height to stream dimensions", () => {
    const result = normalizePopupForStreaming({
      x: 10,
      y: 20,
      width: 401,
      height: 377,
      visible: true,
      minimized: false,
    });

    expect(result.width).toBe(416);
    expect(result.height - STREAM_ALIGNED_POPUP_HEADER_HEIGHT).toBe(352);
  });

  it("does not alter minimized popup geometry", () => {
    const result = normalizePopupForStreaming({
      x: 10,
      y: 20,
      width: 401,
      height: 377,
      visible: true,
      minimized: true,
    });

    expect(result.width).toBe(401);
    expect(result.height).toBe(377);
  });
});

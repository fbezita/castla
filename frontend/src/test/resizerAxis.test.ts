import { describe, expect, it } from "vitest";
import { getResizerAxis } from "../lib/resizerAxis";

describe("getResizerAxis", () => {
  it("treats left/right placement as a horizontal split boundary", () => {
    expect(getResizerAxis("left")).toBe("horizontal");
    expect(getResizerAxis("right")).toBe("horizontal");
  });

  it("treats top/bottom placement as a vertical split boundary", () => {
    expect(getResizerAxis("top")).toBe("vertical");
    expect(getResizerAxis("bottom")).toBe("vertical");
  });

  it("returns none for popup or empty placement", () => {
    expect(getResizerAxis("popup")).toBe("none");
    expect(getResizerAxis(null)).toBe("none");
  });
});

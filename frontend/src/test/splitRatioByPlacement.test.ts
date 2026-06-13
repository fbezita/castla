import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  persistSplitRatioForPlacement,
  resolveSplitRatioForPlacement,
} from "../lib/splitRatioByPlacement";

const originalLocalStorage = globalThis.localStorage;

describe("splitRatioByPlacement", () => {
  beforeEach(() => {
    const storage = new Map<string, string>();
    Object.defineProperty(globalThis, "localStorage", {
      configurable: true,
      value: {
        getItem: (key: string) => storage.get(key) ?? null,
        setItem: (key: string, value: string) => {
          storage.set(key, value);
        },
        clear: () => {
          storage.clear();
        },
      },
    });
  });

  afterEach(() => {
    if (originalLocalStorage === undefined) {
      delete (globalThis as { localStorage?: Storage }).localStorage;
      return;
    }
    Object.defineProperty(globalThis, "localStorage", {
      configurable: true,
      value: originalLocalStorage,
    });
  });

  it("keeps separate cached ratios for horizontal and vertical placements", () => {
    persistSplitRatioForPlacement(0.3, "right");
    persistSplitRatioForPlacement(0.7, "bottom");

    expect(resolveSplitRatioForPlacement(0.5, "left")).toBe(0.3);
    expect(resolveSplitRatioForPlacement(0.5, "top")).toBe(0.7);
  });

  it("falls back to the current ratio when there is no cached ratio for that axis", () => {
    expect(resolveSplitRatioForPlacement(0.44, "right")).toBe(0.44);
    expect(resolveSplitRatioForPlacement(0.66, "bottom")).toBe(0.66);
  });

  it("clamps per-axis ratios using the correct axis limits", () => {
    expect(persistSplitRatioForPlacement(0.01, "right")).toBe(0.22);
    expect(persistSplitRatioForPlacement(0.99, "top")).toBe(0.9);
  });
});

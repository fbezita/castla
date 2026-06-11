import { describe, expect, it } from "vitest";
import {
  dedupeAppPairs,
  getDefaultAppPairLayoutMode,
  getAppPairKey,
  normalizeAppPair,
  resolveAppPairLayoutMode,
  toStoredAppPair,
} from "../lib/appPair";

describe("appPair helpers", () => {
  it("defaults new app pairs to popup when popup is active", () => {
    expect(getDefaultAppPairLayoutMode("popup")).toBe("popup");
    expect(getDefaultAppPairLayoutMode("split")).toBe("split");
    expect(getDefaultAppPairLayoutMode("single")).toBe("split");
  });

  it("normalizes legacy primary/secondary app pairs", () => {
    expect(
      normalizeAppPair({
        primaryApp: "com.alpha",
        secondaryApp: "com.beta",
        layoutMode: "popup",
      }),
    ).toEqual({
      apps: ["com.alpha", "com.beta"],
      layoutMode: "popup",
    });
  });

  it("normalizes legacy appA/appB app pairs", () => {
    expect(
      normalizeAppPair({
        appA: "com.alpha",
        appB: "com.beta",
        mode: "popup",
      }),
    ).toEqual({
      apps: ["com.alpha", "com.beta"],
      layoutMode: "popup",
    });
  });

  it("falls back invalid legacy modes to split", () => {
    expect(resolveAppPairLayoutMode("single")).toBe("split");
    expect(
      normalizeAppPair({
        left: "com.alpha",
        right: "com.beta",
        layoutMode: "single",
      }),
    ).toEqual({
      apps: ["com.alpha", "com.beta"],
      layoutMode: "split",
    });
  });

  it("uses only the app combination for app pair keys", () => {
    expect(
      getAppPairKey({
        apps: ["com.alpha", "com.beta"],
        layoutMode: "split",
      }),
    ).toBe("com.alpha:com.beta");
    expect(
      getAppPairKey({
        apps: ["com.alpha", "com.beta"],
        layoutMode: "popup",
      }),
    ).toBe("com.alpha:com.beta");
  });

  it("drops layout mode when storing app pairs", () => {
    expect(
      toStoredAppPair({
        apps: ["com.alpha", "com.beta"],
        layoutMode: "popup",
      }),
    ).toEqual({
      apps: ["com.alpha", "com.beta"],
    });
  });

  it("dedupes legacy split and popup entries into one app pair", () => {
    expect(
      dedupeAppPairs([
        { apps: ["com.alpha", "com.beta"], layoutMode: "split" },
        { apps: ["com.alpha", "com.beta"], layoutMode: "popup" },
        { apps: ["com.beta", "com.alpha"], layoutMode: "popup" },
      ]),
    ).toEqual([
      { apps: ["com.alpha", "com.beta"] },
      { apps: ["com.beta", "com.alpha"] },
    ]);
  });
});

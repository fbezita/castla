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
      secondaryPlacement: "popup",
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
      secondaryPlacement: "popup",
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
      secondaryPlacement: "right",
    });
  });

  it("includes placement in app pair keys", () => {
    expect(
      getAppPairKey({
        apps: ["com.alpha", "com.beta"],
        layoutMode: "split",
        secondaryPlacement: "right",
      }),
    ).toBe("com.alpha:com.beta:right");
    expect(
      getAppPairKey({
        apps: ["com.alpha", "com.beta"],
        layoutMode: "split",
        secondaryPlacement: "top",
      }),
    ).toBe("com.alpha:com.beta:top");
  });

  it("stores placement and derived layout mode for app pairs", () => {
    expect(
      toStoredAppPair({
        apps: ["com.alpha", "com.beta"],
        secondaryPlacement: "popup",
      }),
    ).toEqual({
      apps: ["com.alpha", "com.beta"],
      layoutMode: "popup",
      secondaryPlacement: "popup",
    });
  });

  it("keeps app pairs with different placements as separate presets", () => {
    expect(
      dedupeAppPairs([
        { apps: ["com.alpha", "com.beta"], secondaryPlacement: "right" },
        { apps: ["com.alpha", "com.beta"], secondaryPlacement: "popup" },
        { apps: ["com.beta", "com.alpha"], secondaryPlacement: "popup" },
      ]),
    ).toEqual([
      { apps: ["com.alpha", "com.beta"], layoutMode: "split", secondaryPlacement: "right" },
      { apps: ["com.alpha", "com.beta"], layoutMode: "popup", secondaryPlacement: "popup" },
      { apps: ["com.beta", "com.alpha"], layoutMode: "popup", secondaryPlacement: "popup" },
    ]);
  });
});

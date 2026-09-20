import { describe, expect, it } from "vitest";
import {
  buildSelectedAppLaunchRequest,
  resolveConnectionView,
  resolveLaunchStage,
  type LaunchStateLike,
} from "../lib/frontendExperience";

describe("resolveConnectionView", () => {
  it("separates first connection, reconnect, unavailable, and connected", () => {
    expect(resolveConnectionView(true, false, false)).toBe("connected");
    expect(resolveConnectionView(false, true, false)).toBe("connecting");
    expect(resolveConnectionView(false, false, true)).toBe("reconnecting");
    expect(resolveConnectionView(false, false, false)).toBe("unavailable");
    expect(resolveConnectionView(false, false, true, true)).toBe("busy");
  });
});

describe("resolveLaunchStage", () => {
  it.each<readonly [LaunchStateLike, string]>([
    ["IDLE", "idle"],
    ["LAYOUT_ALIGNING", "opening"],
    ["PRIMARY_LAUNCH_ACKED", "opening"],
    ["PRIMARY_SESSION_READY", "connecting"],
    ["STREAM_COMMITTING", "connecting"],
    ["RUNNING", "ready"],
    ["DEGRADED", "ready"],
    ["FAILED", "failed"],
  ])("maps %s to %s", (state, expected) => {
    expect(resolveLaunchStage(state)).toBe(expected);
  });
});

describe("buildSelectedAppLaunchRequest", () => {
  it("keeps the current primary app when an app is selected for the secondary pane", () => {
    expect(buildSelectedAppLaunchRequest("secondary", "com.example.maps", {
      activePrimaryApp: "com.example.video",
      layoutMode: "single",
      secondaryPlacement: null,
    })).toEqual({
      primaryPkg: "com.example.video",
      secondaryPkg: "com.example.maps",
      layoutMode: "split",
      secondaryPlacement: "right",
      forceRelaunch: true,
    });
  });

  it("preserves popup placement for a secondary selection", () => {
    expect(buildSelectedAppLaunchRequest("secondary", "com.example.chat", {
      activePrimaryApp: "com.example.video",
      layoutMode: "popup",
      secondaryPlacement: "popup",
    })).toMatchObject({
      primaryPkg: "com.example.video",
      secondaryPkg: "com.example.chat",
      layoutMode: "popup",
      secondaryPlacement: "popup",
    });
  });

  it("uses the normal single-app request for primary selection or a missing primary", () => {
    const expected = {
      primaryPkg: "com.example.maps",
      secondaryPkg: undefined,
      layoutMode: "single",
      secondaryPlacement: null,
      forceRelaunch: true,
    };
    expect(buildSelectedAppLaunchRequest("primary", "com.example.maps", {
      activePrimaryApp: "com.example.video",
      layoutMode: "split",
      secondaryPlacement: "right",
    })).toEqual(expected);
    expect(buildSelectedAppLaunchRequest("secondary", "com.example.maps", {
      activePrimaryApp: "",
      layoutMode: "single",
      secondaryPlacement: null,
    })).toEqual(expected);
  });
});

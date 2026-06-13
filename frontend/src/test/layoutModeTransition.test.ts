import { describe, expect, it } from "vitest";
import { buildLayoutModeLaunchRequest } from "../lib/layoutModeTransition";
import type { CompositorState } from "../stores/compositorStore";

function createState(): CompositorState {
  return {
    viewports: new Map([
      ["primary", { pane: "primary", width: 368, height: 704, committed: true, generation: 7, visible: true }],
      ["secondary", { pane: "secondary", width: 544, height: 704, committed: true, generation: 5, visible: true }],
    ]),
    diagnostics: [],
    serverDiagnostics: null,
    layoutMode: "split",
    splitRatio: 0.403954802259887,
    activePrimaryApp: "com.google.android.youtube",
    activeSecondaryApp: "com.disney.disneyplus",
    popup: { visible: false, minimized: false, x: 48, y: 72, width: 420, height: 280 },
    launchSequence: {
      id: 4,
      primaryPkg: "com.google.android.youtube",
      secondaryPkg: "com.disney.disneyplus",
      layoutMode: "split",
      state: "RUNNING",
      startedAt: Date.now(),
      primaryStartGen: 7,
      secondaryStartGen: 5,
      degradedReason: "",
    },
    language: "ko",
  };
}

describe("buildLayoutModeLaunchRequest", () => {
  it("relaunches the active pair when switching from split to popup", () => {
    const state = createState();

    expect(buildLayoutModeLaunchRequest("popup", state)).toEqual({
      primaryPkg: "com.google.android.youtube",
      secondaryPkg: "com.disney.disneyplus",
      layoutMode: "popup",
      secondaryPlacement: "popup",
    });
  });

  it("relaunches the active pair when switching from popup to split", () => {
    const state = createState();
    state.layoutMode = "popup";

    expect(buildLayoutModeLaunchRequest("split", state)).toEqual({
      primaryPkg: "com.google.android.youtube",
      secondaryPkg: "com.disney.disneyplus",
      layoutMode: "split",
      secondaryPlacement: "right",
    });
  });

  it("relaunches only primary when switching to single", () => {
    const state = createState();

    expect(buildLayoutModeLaunchRequest("single", state)).toEqual({
      primaryPkg: "com.google.android.youtube",
      layoutMode: "single",
    });
  });

  it("does not relaunch dual-pane layouts without an active secondary app", () => {
    const state = createState();
    state.layoutMode = "single";
    state.activeSecondaryApp = "";

    expect(buildLayoutModeLaunchRequest("popup", state)).toBeNull();
    expect(buildLayoutModeLaunchRequest("split", state)).toBeNull();
  });

  it("does not relaunch when the requested mode is already active", () => {
    const state = createState();

    expect(buildLayoutModeLaunchRequest("split", state)).toBeNull();
  });
});

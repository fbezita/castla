import { describe, expect, it } from "vitest";
import {
  buildDockedPaneStyles,
  buildSecondaryPlacementLaunchRequest,
  computeDockedPaneLayout,
  getPlacementPreviewRect,
  resolveExternalAppDropZone,
} from "../lib/secondaryPlacement";
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
    splitRatio: 0.4,
    activePrimaryApp: "com.google.android.youtube",
    activeSecondaryApp: "com.disney.disneyplus",
    secondaryPlacement: "right",
    popup: { visible: false, minimized: false, x: 48, y: 72, width: 420, height: 280 },
    launchSequence: {
      id: 1,
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

describe("secondaryPlacement", () => {
  it("builds a relaunch request when moving the secondary app to popup", () => {
    const state = createState();

    expect(buildSecondaryPlacementLaunchRequest("popup", state)).toEqual({
      primaryPkg: "com.google.android.youtube",
      secondaryPkg: "com.disney.disneyplus",
      layoutMode: "popup",
      secondaryPlacement: "popup",
    });
  });

  it("does not relaunch when the requested placement is already active", () => {
    const state = createState();

    expect(buildSecondaryPlacementLaunchRequest("right", state)).toBeNull();
  });

  it("computes a top-docked secondary pane using a vertical split", () => {
    const layout = computeDockedPaneLayout(1280, 720, 0.4, "top");

    expect(layout.axis).toBe("vertical");
    expect(layout.secondary.top).toBe(0);
    expect(layout.secondary.height).toBeGreaterThan(0);
    expect(layout.primary.top).toBe(layout.secondary.height);
    expect(layout.primary.width).toBe(1280);
  });

  it("builds percentage-based pane styles for a top dock so visual bounds stay aligned", () => {
    const layout = computeDockedPaneLayout(1280, 720, 0.4, "top");

    expect(buildDockedPaneStyles(layout)).toEqual({
      primary: "left:0;top:60%;width:100%;height:40%;",
      secondary: "left:0;top:0;width:100%;height:60%;",
    });
  });

  it("allows top docking to shrink further than the old horizontal minimum span", () => {
    const layout = computeDockedPaneLayout(1280, 720, 0.1, "top");

    expect(layout.secondary.height).toBeGreaterThan(500);
    expect(layout.primary.height).toBeLessThan(200);
  });

  it("resolves external drag points to the new placement targets", () => {
    const bounds = { width: 1280, height: 720, drawerLeft: 980 };

    expect(resolveExternalAppDropZone(490, 360, bounds)).toBe("popup");
    expect(resolveExternalAppDropZone(360, 360, bounds)).toBe("left");
    expect(resolveExternalAppDropZone(490, 230, bounds)).toBe("top");
  });

  it("prioritizes popup when the pointer is in the center overlap area", () => {
    const bounds = { width: 1280, height: 720, drawerLeft: 980 };

    expect(resolveExternalAppDropZone(455, 360, bounds)).toBe("popup");
  });

  it("builds a full-height preview rect for a bottom dock", () => {
    const preview = getPlacementPreviewRect(
      "bottom",
      { width: 1280, height: 720, drawerLeft: 980 },
      0.4,
    );

    expect(preview).not.toBeNull();
    expect(preview?.top).toBeGreaterThan(0);
    expect(preview?.width).toBe(980);
  });
});

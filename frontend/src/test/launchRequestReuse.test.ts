import { afterEach, describe, expect, it } from "vitest";
import type { CompositorState } from "../stores/compositorStore";
import {
  canKeepCurrentLaunch,
  canReusePrimaryLaunchForRequest,
} from "../lib/launchRequestReuse";

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
    popup: { visible: false, minimized: false, x: 0, y: 0, width: 544, height: 744 },
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

function primaryMetadata(width = 368, height = 704) {
  return {
    type: "streamMetadata" as const,
    sessionId: "primary",
    generation: 7,
    firstFrameReady: true,
    streamReady: true,
    vdId: 1,
    width,
    height,
  };
}

function secondaryMetadata(width = 544, height = 704) {
  return {
    type: "streamMetadata" as const,
    sessionId: "secondary",
    generation: 5,
    firstFrameReady: true,
    streamReady: true,
    vdId: 2,
    width,
    height,
  };
}

const originalWindow = globalThis.window;

afterEach(() => {
  if (originalWindow === undefined) {
    delete (globalThis as { window?: Window }).window;
  } else {
    (globalThis as { window?: Window }).window = originalWindow;
  }
});

describe("canKeepCurrentLaunch", () => {
  it("skips relaunch when the same split pair is already healthy and layout-equivalent", () => {
    const state = createState();

    expect(
      canKeepCurrentLaunch(
        {
          primaryPkg: "com.google.android.youtube",
          secondaryPkg: "com.disney.disneyplus",
          layoutMode: "split",
        },
        state,
        primaryMetadata(),
        secondaryMetadata(),
        {
          splitTargets: {
            primaryWidth: 368,
            secondaryWidth: 544,
            paneHeight: 704,
          },
        },
      ),
    ).toBe(true);
  });

  it("skips relaunch for a healthy single-app launch only when the secondary pane is not visible", () => {
    const state = createState();
    state.layoutMode = "single";
    state.activeSecondaryApp = "";
    state.viewports.set("secondary", {
      pane: "secondary",
      width: 544,
      height: 704,
      committed: true,
      generation: 5,
      visible: false,
    });

    expect(
      canKeepCurrentLaunch(
        {
          primaryPkg: "com.google.android.youtube",
          layoutMode: "single",
        },
        state,
        primaryMetadata(),
        undefined,
        {},
      ),
    ).toBe(true);
  });

  it("does not skip when a different single app is requested", () => {
    const state = createState();
    state.layoutMode = "single";
    state.activeSecondaryApp = "";
    state.viewports.set("secondary", {
      pane: "secondary",
      width: 544,
      height: 704,
      committed: true,
      generation: 5,
      visible: false,
    });

    expect(
      canKeepCurrentLaunch(
        {
          primaryPkg: "com.google.android.apps.maps",
          layoutMode: "single",
        },
        state,
        primaryMetadata(),
        undefined,
        {},
      ),
    ).toBe(false);
  });

  it("skips relaunch for a healthy popup pair only when popup geometry also matches", () => {
    const state = createState();
    state.layoutMode = "popup";
    state.popup.visible = true;
    state.popup.minimized = false;

    expect(
      canKeepCurrentLaunch(
        {
          primaryPkg: "com.google.android.youtube",
          secondaryPkg: "com.disney.disneyplus",
          layoutMode: "popup",
        },
        state,
        primaryMetadata(),
        secondaryMetadata(),
        {
          popup: {
            ...state.popup,
            visible: true,
            minimized: false,
          },
        },
      ),
    ).toBe(true);
  });

  it("does not skip when split pane sizes differ from the expected layout", () => {
    const state = createState();
    state.viewports.set("primary", {
      pane: "primary",
      width: 544,
      height: 704,
      committed: true,
      generation: 7,
      visible: true,
    });

    expect(
      canKeepCurrentLaunch(
        {
          primaryPkg: "com.google.android.youtube",
          secondaryPkg: "com.disney.disneyplus",
          layoutMode: "split",
        },
        state,
        primaryMetadata(544, 704),
        secondaryMetadata(),
        {
          splitTargets: {
            primaryWidth: 368,
            secondaryWidth: 544,
            paneHeight: 704,
          },
        },
      ),
    ).toBe(false);
  });

  it("ignores split pane size differences on the webcodec path", () => {
    (globalThis as { window?: Window & { VideoDecoder?: unknown } }).window = {
      isSecureContext: true,
      location: new URL("https://example.com"),
      VideoDecoder: function VideoDecoder() {},
    } as unknown as Window & { VideoDecoder?: unknown };

    const state = createState();
    state.viewports.set("primary", {
      pane: "primary",
      width: 544,
      height: 704,
      committed: true,
      generation: 7,
      visible: true,
    });

    expect(
      canKeepCurrentLaunch(
        {
          primaryPkg: "com.google.android.youtube",
          secondaryPkg: "com.disney.disneyplus",
          layoutMode: "split",
        },
        state,
        primaryMetadata(544, 704),
        secondaryMetadata(),
        {
          splitTargets: {
            primaryWidth: 368,
            secondaryWidth: 544,
            paneHeight: 704,
          },
        },
      ),
    ).toBe(true);
  });

  it("does not skip when popup geometry differs", () => {
    const state = createState();
    state.layoutMode = "popup";
    state.popup.visible = true;
    state.popup.minimized = false;

    expect(
      canKeepCurrentLaunch(
        {
          primaryPkg: "com.google.android.youtube",
          secondaryPkg: "com.disney.disneyplus",
          layoutMode: "popup",
        },
        state,
        primaryMetadata(),
        secondaryMetadata(),
        {
          popup: {
            ...state.popup,
            x: state.popup.x + 10,
            visible: true,
            minimized: false,
          },
        },
      ),
    ).toBe(false);
  });

  it("does not skip when the current stream is not healthy", () => {
    const state = createState();
    state.viewports.set("primary", {
      pane: "primary",
      width: 368,
      height: 704,
      committed: false,
      generation: 7,
      visible: true,
    });

    expect(
      canKeepCurrentLaunch(
        {
          primaryPkg: "com.google.android.youtube",
          secondaryPkg: "com.disney.disneyplus",
          layoutMode: "split",
        },
        state,
        primaryMetadata(),
        secondaryMetadata(),
        {
          splitTargets: {
            primaryWidth: 368,
            secondaryWidth: 544,
            paneHeight: 704,
          },
        },
      ),
    ).toBe(false);
  });
});

describe("canReusePrimaryLaunchForRequest", () => {
  it("does not reuse a jmuxer primary stream when promoting split layout back to single", () => {
    const state = createState();

    expect(
      canReusePrimaryLaunchForRequest(
        {
          primaryPkg: "com.google.android.youtube",
          layoutMode: "single",
        },
        state,
        primaryMetadata(),
      ),
    ).toBe(false);
  });

  it("still allows single-layout reuse on the webcodec path", () => {
    (globalThis as { window?: Window & { VideoDecoder?: unknown } }).window = {
      isSecureContext: true,
      location: new URL("https://example.com"),
      VideoDecoder: function VideoDecoder() {},
    } as unknown as Window & { VideoDecoder?: unknown };

    const state = createState();
    state.layoutMode = "single";
    state.activeSecondaryApp = "";
    state.viewports.set("secondary", {
      pane: "secondary",
      width: 544,
      height: 704,
      committed: true,
      generation: 5,
      visible: false,
    });

    expect(
      canReusePrimaryLaunchForRequest(
        {
          primaryPkg: "com.google.android.youtube",
          layoutMode: "single",
        },
        state,
        primaryMetadata(),
      ),
    ).toBe(true);
  });
});

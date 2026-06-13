import { describe, expect, it } from "vitest";
import {
  resolveTouchViewportSize,
  shouldEmitPointerRouteTrace,
} from "../touch/TouchRouter";

describe("resolveTouchViewportSize", () => {
  it("prefers canvas intrinsic dimensions for webcodecs surfaces", () => {
    const canvas = {
      tagName: "CANVAS",
      width: 1280,
      height: 800,
    } as HTMLElement & { width: number; height: number };

    expect(
      resolveTouchViewportSize(canvas, { width: 1024, height: 720 }),
    ).toEqual({
      width: 1280,
      height: 800,
      source: "canvas",
    });
  });

  it("prefers video intrinsic dimensions for jmuxer surfaces", () => {
    const video = {
      tagName: "VIDEO",
      videoWidth: 1920,
      videoHeight: 1080,
    } as HTMLElement & { videoWidth: number; videoHeight: number };

    expect(
      resolveTouchViewportSize(video, { width: 1024, height: 720 }),
    ).toEqual({
      width: 1920,
      height: 1080,
      source: "video",
    });
  });

  it("falls back to compositor viewport dimensions when media size is unavailable", () => {
    const host = { tagName: "DIV" } as HTMLElement;

    expect(
      resolveTouchViewportSize(host, { width: 1024, height: 720 }),
    ).toEqual({
      width: 1024,
      height: 720,
      source: "viewport",
    });
  });

  it("uses pane viewport size for fill-mode touch mapping", () => {
    const video = {
      tagName: "VIDEO",
      videoWidth: 1920,
      videoHeight: 1080,
    } as HTMLElement & { videoWidth: number; videoHeight: number };

    expect(
      resolveTouchViewportSize(video, { width: 512, height: 720 }, "fill"),
    ).toEqual({
      width: 512,
      height: 720,
      source: "viewport",
    });
  });
});

describe("shouldEmitPointerRouteTrace", () => {
  it("suppresses orphan move traces before pointer capture starts", () => {
    expect(shouldEmitPointerRouteTrace("move", false)).toBe(false);
  });

  it("keeps down and tracked gesture traces", () => {
    expect(shouldEmitPointerRouteTrace("down", false)).toBe(true);
    expect(shouldEmitPointerRouteTrace("move", true)).toBe(true);
    expect(shouldEmitPointerRouteTrace("up", true)).toBe(true);
  });
});

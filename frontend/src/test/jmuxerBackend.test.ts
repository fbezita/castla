import { describe, expect, it } from "vitest";
import {
  buildVideoStateSnapshot,
  shouldEmitVideoStateSnapshot,
} from "../decoder/JMuxerBackend";

describe("buildVideoStateSnapshot", () => {
  it("captures the stable fields used for verbose video-state logging", () => {
    const buffered = { length: 2 } as TimeRanges;
    const video = {
      readyState: 4,
      videoWidth: 1232,
      videoHeight: 720,
      currentTime: 1.25,
      buffered,
      paused: false,
    } as HTMLVideoElement;

    expect(buildVideoStateSnapshot(video)).toEqual({
      readyState: 4,
      width: 1232,
      height: 720,
      currentTime: 1.25,
      buffered: 2,
      paused: false,
    });
  });
});

describe("shouldEmitVideoStateSnapshot", () => {
  it("suppresses duplicate snapshots within the throttle window", () => {
    const snapshot = {
      readyState: 0,
      width: 0,
      height: 0,
      currentTime: 0,
      buffered: 0,
      paused: true,
    };

    expect(
      shouldEmitVideoStateSnapshot({
        now: 1_000,
        lastEmitAt: 900,
        next: snapshot,
        previous: snapshot,
      }),
    ).toBe(false);
  });

  it("emits quickly when the stable playback state changes", () => {
    expect(
      shouldEmitVideoStateSnapshot({
        now: 1_000,
        lastEmitAt: 900,
        next: {
          readyState: 2,
          width: 1232,
          height: 720,
          currentTime: 0.1,
          buffered: 1,
          paused: false,
        },
        previous: {
          readyState: 0,
          width: 0,
          height: 0,
          currentTime: 0,
          buffered: 0,
          paused: true,
        },
      }),
    ).toBe(true);
  });

  it("re-emits duplicates after the throttle window elapses", () => {
    const snapshot = {
      readyState: 4,
      width: 1232,
      height: 720,
      currentTime: 1.5,
      buffered: 1,
      paused: false,
    };

    expect(
      shouldEmitVideoStateSnapshot({
        now: 1_300,
        lastEmitAt: 1_000,
        next: snapshot,
        previous: snapshot,
      }),
    ).toBe(true);
  });
});

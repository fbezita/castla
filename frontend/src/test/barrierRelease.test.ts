import { describe, expect, it } from "vitest";
import { isPaneBarrierReadyForRelease } from "../lib/barrierRelease";

describe("barrier release helper", () => {
  it("holds the barrier until the viewport reaches the expected split size", () => {
    expect(
      isPaneBarrierReadyForRelease({
        pane: "primary",
        viewport: {
          pane: "primary",
          width: 320,
          height: 704,
          committed: true,
          generation: 19,
          visible: true,
        },
        startGeneration: 17,
        metadataGeneration: 18,
        metadataReady: true,
        expectedWidth: 368,
        expectedHeight: 704,
      }),
    ).toBe(false);
  });

  it("releases once the committed viewport matches the expected split size", () => {
    expect(
      isPaneBarrierReadyForRelease({
        pane: "primary",
        viewport: {
          pane: "primary",
          width: 368,
          height: 704,
          committed: true,
          generation: 20,
          visible: true,
        },
        startGeneration: 17,
        metadataGeneration: 18,
        metadataReady: false,
        expectedWidth: 368,
        expectedHeight: 704,
      }),
    ).toBe(true);
  });

  it("releases on same-generation recommit when the pane was reset and committed again", () => {
    expect(
      isPaneBarrierReadyForRelease({
        pane: "primary",
        viewport: {
          pane: "primary",
          width: 368,
          height: 704,
          committed: true,
          generation: 7,
          visible: true,
        },
        startGeneration: 7,
        metadataGeneration: 7,
        metadataReady: false,
        expectedWidth: 368,
        expectedHeight: 704,
      }),
    ).toBe(true);
  });

  it("still allows single-pane release without an explicit size target", () => {
    expect(
      isPaneBarrierReadyForRelease({
        pane: "primary",
        viewport: {
          pane: "primary",
          width: 912,
          height: 704,
          committed: true,
          generation: 2,
          visible: true,
        },
        startGeneration: 1,
        metadataGeneration: 1,
        metadataReady: false,
      }),
    ).toBe(true);
  });

  it("does not release from stale metadata when a newer viewport generation is still uncommitted", () => {
    expect(
      isPaneBarrierReadyForRelease({
        pane: "secondary",
        viewport: {
          pane: "secondary",
          width: 544,
          height: 704,
          committed: false,
          generation: 4,
          visible: true,
        },
        startGeneration: 0,
        metadataGeneration: 1,
        metadataReady: true,
        expectedWidth: 544,
        expectedHeight: 704,
      }),
    ).toBe(false);
  });
});

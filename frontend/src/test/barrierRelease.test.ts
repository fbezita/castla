import { describe, expect, it } from "vitest";
import { isPaneBarrierReadyForRelease } from "../lib/barrierRelease";

describe("barrier release helper", () => {
  it("releases once the pane recommits on a fresh generation", () => {
    expect(
      isPaneBarrierReadyForRelease({
        pane: "primary",
        viewport: {
          pane: "primary",
          width: 320,
          height: 704,
          committed: true,
          generation: 20,
          visible: true,
        },
        startGeneration: 17,
        metadataGeneration: 18,
        metadataReady: false,
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
      }),
    ).toBe(false);
  });

  it("releases from fresh metadata even when stream dimensions differ from layout size", () => {
    expect(
      isPaneBarrierReadyForRelease({
        pane: "secondary",
        viewport: {
          pane: "secondary",
          width: 608,
          height: 720,
          committed: false,
          generation: 8,
          visible: true,
        },
        startGeneration: 7,
        metadataGeneration: 9,
        metadataReady: true,
      }),
    ).toBe(true);
  });
});

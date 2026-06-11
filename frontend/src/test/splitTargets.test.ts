import { describe, expect, it } from "vitest";
import {
  buildSplitPaneStyles,
  hasCompleteSplitTargets,
  resolveExpectedSplitTargets,
} from "../lib/splitTargets";

describe("resolveExpectedSplitTargets", () => {
  it("prefers primed launch targets over later dispatched state", () => {
    expect(
      resolveExpectedSplitTargets({
        hostWidth: 912,
        hostHeight: 704,
        splitRatio: 0.4,
        primedTargets: {
          primaryWidth: 368,
          secondaryWidth: 544,
          paneHeight: 704,
        },
        dispatchedTargets: {
          primaryWidth: 464,
          secondaryWidth: 464,
          paneHeight: 704,
        },
      }),
    ).toEqual({
      primaryWidth: 368,
      secondaryWidth: 544,
      paneHeight: 704,
    });
  });

  it("prefers the dispatched split targets when they are available", () => {
    expect(
      resolveExpectedSplitTargets({
        hostWidth: 0,
        hostHeight: 0,
        splitRatio: 0.4,
        dispatchedTargets: {
          primaryWidth: 368,
          secondaryWidth: 544,
          paneHeight: 704,
        },
      }),
    ).toEqual({
      primaryWidth: 368,
      secondaryWidth: 544,
      paneHeight: 704,
    });
  });

  it("falls back to host measurements when dispatched targets are unavailable", () => {
    expect(
      resolveExpectedSplitTargets({
        hostWidth: 912,
        hostHeight: 704,
        splitRatio: 0.4,
      }),
    ).toEqual({
      primaryWidth: 368,
      secondaryWidth: 560,
      paneHeight: 704,
    });
  });

  it("ignores incomplete dispatched targets and recomputes from host size", () => {
    expect(
      resolveExpectedSplitTargets({
        hostWidth: 912,
        hostHeight: 704,
        splitRatio: 0.4,
        dispatchedTargets: {
          primaryWidth: 0,
          secondaryWidth: 544,
          paneHeight: 704,
        },
      }),
    ).toEqual({
      primaryWidth: 368,
      secondaryWidth: 560,
      paneHeight: 704,
    });
  });

  it("builds split pane styles from the resolved target widths instead of a temporary 50:50 split", () => {
    expect(
      buildSplitPaneStyles({
        primaryWidth: 368,
        secondaryWidth: 544,
      }),
    ).toEqual({
      primary: "left:0;width:40.35087719298245%;right:auto;",
      secondary: "left:40.35087719298245%;right:0;width:59.64912280701754%;",
    });
  });
});

describe("hasCompleteSplitTargets", () => {
  it("requires all aligned dimensions before treating split targets as stable", () => {
    expect(
      hasCompleteSplitTargets({
        primaryWidth: 368,
        secondaryWidth: 544,
        paneHeight: 704,
      }),
    ).toBe(true);

    expect(
      hasCompleteSplitTargets({
        primaryWidth: 368,
        secondaryWidth: 544,
      }),
    ).toBe(false);
  });
});

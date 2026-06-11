export interface SplitTargets {
  primaryWidth?: number;
  secondaryWidth?: number;
  paneHeight?: number;
}

interface ExpectedSplitTargetsInput {
  hostWidth: number;
  hostHeight: number;
  splitRatio: number;
  primedTargets?: SplitTargets | null;
  dispatchedTargets?: SplitTargets | null;
}

function isPositiveDimension(value: number | undefined): value is number {
  return value !== undefined && Number.isFinite(value) && value > 0;
}

function align16(value: number): number {
  return Math.max(320, (Math.round(value) + 15) & ~15);
}

export function hasCompleteSplitTargets(
  targets?: SplitTargets | null,
): targets is Required<SplitTargets> {
  return (
    isPositiveDimension(targets?.primaryWidth) &&
    isPositiveDimension(targets?.secondaryWidth) &&
    isPositiveDimension(targets?.paneHeight)
  );
}

function computeSplitWidths(width: number, ratio: number) {
  const safeWidth = Math.max(0, Math.round(width));
  if (safeWidth <= 0) {
    return {
      primaryWidth: undefined,
      secondaryWidth: undefined,
    };
  }

  const rawPrimaryWidth = Math.max(320, Math.round(safeWidth * ratio));
  const rawSecondaryWidth = Math.max(320, safeWidth - rawPrimaryWidth);

  return {
    primaryWidth: align16(rawPrimaryWidth),
    secondaryWidth: align16(rawSecondaryWidth),
  };
}

export function resolveExpectedSplitTargets({
  hostWidth,
  hostHeight,
  splitRatio,
  primedTargets,
  dispatchedTargets,
}: ExpectedSplitTargetsInput): SplitTargets {
  if (hasCompleteSplitTargets(primedTargets)) {
    return {
      primaryWidth: primedTargets.primaryWidth,
      secondaryWidth: primedTargets.secondaryWidth,
      paneHeight: primedTargets.paneHeight,
    };
  }

  if (hasCompleteSplitTargets(dispatchedTargets)) {
    return {
      primaryWidth: dispatchedTargets.primaryWidth,
      secondaryWidth: dispatchedTargets.secondaryWidth,
      paneHeight: dispatchedTargets.paneHeight,
    };
  }

  const widths = computeSplitWidths(hostWidth, splitRatio);

  return {
    primaryWidth: widths.primaryWidth,
    secondaryWidth: widths.secondaryWidth,
    paneHeight: hostHeight > 0 ? align16(hostHeight) : undefined,
  };
}

export function buildSplitPaneStyles({
  primaryWidth,
  secondaryWidth,
}: SplitTargets): { primary: string; secondary: string } | null {
  if (!isPositiveDimension(primaryWidth) || !isPositiveDimension(secondaryWidth)) {
    return null;
  }

  const totalWidth = primaryWidth + secondaryWidth;
  if (totalWidth <= 0) {
    return null;
  }

  const leftPercent = (primaryWidth / totalWidth) * 100;
  const rightPercent = (secondaryWidth / totalWidth) * 100;

  return {
    primary: `left:0;width:${leftPercent}%;right:auto;`,
    secondary: `left:${leftPercent}%;right:0;width:${rightPercent}%;`,
  };
}

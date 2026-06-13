export interface StreamCommitCandidate {
  committed: boolean;
  generation: number;
  width?: number;
}

const WIDTH_TOLERANCE_PX = 16;

export function isFreshCommittedViewport(
  candidate: StreamCommitCandidate | undefined,
  startGeneration: number,
  previousCommitted: boolean,
  allowSameGenerationRecommit: boolean,
  expectedWidth?: number,
): boolean {
  if (!candidate?.committed) return false;
  if (
    expectedWidth !== undefined &&
    Number.isFinite(expectedWidth) &&
    expectedWidth > 0 &&
    Math.abs((candidate.width ?? 0) - expectedWidth) > WIDTH_TOLERANCE_PX
  ) {
    return false;
  }
  if (candidate.generation > startGeneration) return true;
  if (!allowSameGenerationRecommit) return false;
  return !previousCommitted && candidate.generation === startGeneration;
}

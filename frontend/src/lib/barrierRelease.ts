import type { PaneId } from "../protocol";
import type { ViewportModel } from "../stores/compositorStore";

export interface PaneReleaseInputs {
  pane: PaneId;
  viewport: ViewportModel | undefined;
  startGeneration: number;
  metadataGeneration: number;
  metadataReady: boolean;
  expectedWidth?: number;
  expectedHeight?: number;
}

function matchesExpectedSize(
  viewport: ViewportModel | undefined,
  expectedWidth?: number,
  expectedHeight?: number,
): boolean {
  if (!viewport) return false;
  const requireWidth = expectedWidth !== undefined && Number.isFinite(expectedWidth) && expectedWidth > 0;
  const requireHeight = expectedHeight !== undefined && Number.isFinite(expectedHeight) && expectedHeight > 0;
  if (requireWidth && viewport.width !== expectedWidth) {
    return false;
  }
  if (requireHeight && viewport.height !== expectedHeight) {
    return false;
  }
  return true;
}

export function isPaneBarrierReadyForRelease({
  viewport,
  startGeneration,
  metadataGeneration,
  metadataReady,
  expectedWidth,
  expectedHeight,
}: PaneReleaseInputs): boolean {
  const viewportGeneration = viewport?.generation ?? -1;
  const committedReady =
    viewport?.committed === true &&
    viewportGeneration >= startGeneration &&
    matchesExpectedSize(viewport, expectedWidth, expectedHeight);
  const frameReady =
    metadataReady === true &&
    metadataGeneration > startGeneration &&
    metadataGeneration >= viewportGeneration &&
    matchesExpectedSize(viewport, expectedWidth, expectedHeight);
  return committedReady || frameReady;
}

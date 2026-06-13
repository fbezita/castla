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

export function isPaneBarrierReadyForRelease({
  viewport,
  startGeneration,
  metadataGeneration,
  metadataReady,
}: PaneReleaseInputs): boolean {
  const viewportGeneration = viewport?.generation ?? -1;
  const committedReady =
    viewport?.committed === true &&
    viewportGeneration >= startGeneration;
  const frameReady =
    metadataReady === true &&
    metadataGeneration > startGeneration &&
    metadataGeneration >= viewportGeneration;
  return committedReady || frameReady;
}

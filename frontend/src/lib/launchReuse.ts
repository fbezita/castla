import type { StreamMetadata } from "../protocol";
import type { ViewportModel } from "../stores/compositorStore";

export function canReuseHotStream(
  viewport: ViewportModel | undefined,
  metadata: StreamMetadata | undefined,
  startGen: number,
): boolean {
  if (!viewport?.visible) return false;
  if (viewport.committed !== true) return false;
  if ((viewport.width ?? 0) <= 0 || (viewport.height ?? 0) <= 0) return false;
  if (startGen <= 0) return false;
  return metadata?.firstFrameReady === true && (metadata.generation ?? -1) >= startGen;
}

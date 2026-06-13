import type { SecondaryPlacement } from "../stores/compositorStore";

export type ResizerAxis = "horizontal" | "vertical" | "none";

export function getResizerAxis(
  placement: SecondaryPlacement | null | undefined,
): ResizerAxis {
  if (placement === "left" || placement === "right") {
    return "horizontal";
  }
  if (placement === "top" || placement === "bottom") {
    return "vertical";
  }
  return "none";
}

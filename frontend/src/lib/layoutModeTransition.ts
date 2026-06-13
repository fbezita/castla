import type { LaunchRequest } from "./launchRequestReuse";
import type { CompositorState, LayoutMode } from "../stores/compositorStore";
import { resolveSecondaryPlacement } from "./secondaryPlacement";

export function buildLayoutModeLaunchRequest(
  mode: LayoutMode,
  state: CompositorState,
): LaunchRequest | null {
  if (mode === state.layoutMode) return null;
  if (!state.activePrimaryApp) return null;

  if (mode === "single") {
    return {
      primaryPkg: state.activePrimaryApp,
      layoutMode: "single",
    };
  }

  if (!state.activeSecondaryApp) return null;

  const secondaryPlacement =
    mode === "popup"
      ? "popup"
      : resolveSecondaryPlacement(state.layoutMode, state.secondaryPlacement) === "popup"
        ? "right"
        : resolveSecondaryPlacement(state.layoutMode, state.secondaryPlacement) ?? "right";

  return {
    primaryPkg: state.activePrimaryApp,
    secondaryPkg: state.activeSecondaryApp,
    layoutMode: mode,
    secondaryPlacement,
  };
}

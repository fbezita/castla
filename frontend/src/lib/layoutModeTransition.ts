import type { LaunchRequest } from "./launchRequestReuse";
import type { CompositorState, LayoutMode } from "../stores/compositorStore";

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

  return {
    primaryPkg: state.activePrimaryApp,
    secondaryPkg: state.activeSecondaryApp,
    layoutMode: mode,
  };
}

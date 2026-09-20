import type { LaunchState } from "../stores/compositorStore";

export type ConnectionView = "connected" | "connecting" | "reconnecting" | "unavailable" | "busy";
export type LaunchStage = "idle" | "opening" | "connecting" | "ready" | "failed";
export type LaunchStateLike = LaunchState;
export type AppSelectionTarget = "primary" | "secondary";

export interface AppSelectionContext {
  activePrimaryApp: string;
  layoutMode: "single" | "split" | "popup";
  secondaryPlacement: "left" | "right" | "top" | "bottom" | "popup" | null;
}

export interface SelectedAppLaunchRequest {
  primaryPkg: string;
  secondaryPkg?: string;
  layoutMode: "single" | "split" | "popup";
  secondaryPlacement: "left" | "right" | "top" | "bottom" | "popup" | null;
  forceRelaunch: boolean;
}

export function buildSelectedAppLaunchRequest(
  target: AppSelectionTarget,
  selectedPackage: string,
  context: AppSelectionContext,
): SelectedAppLaunchRequest {
  if (target === "secondary" && context.activePrimaryApp) {
    const popup = context.layoutMode === "popup" || context.secondaryPlacement === "popup";
    return {
      primaryPkg: context.activePrimaryApp,
      secondaryPkg: selectedPackage,
      layoutMode: popup ? "popup" : "split",
      secondaryPlacement: popup
        ? "popup"
        : context.secondaryPlacement && context.secondaryPlacement !== "popup"
          ? context.secondaryPlacement
          : "right",
      forceRelaunch: true,
    };
  }

  return {
    primaryPkg: selectedPackage,
    secondaryPkg: undefined,
    layoutMode: "single",
    secondaryPlacement: null,
    forceRelaunch: true,
  };
}

export function resolveConnectionView(
  connected: boolean,
  pending: boolean,
  wasConnected: boolean,
  busy = false,
): ConnectionView {
  if (busy) return "busy";
  if (connected) return "connected";
  if (pending) return "connecting";
  if (wasConnected) return "reconnecting";
  return "unavailable";
}

export function resolveLaunchStage(state: LaunchStateLike): LaunchStage {
  if (state === "IDLE") return "idle";
  if (state === "FAILED") return "failed";
  if (state === "RUNNING" || state === "DEGRADED") return "ready";
  if (
    state === "PRIMARY_SESSION_READY" ||
    state === "PRIMARY_LAUNCHED" ||
    state === "SECONDARY_SESSION_READY" ||
    state === "SECONDARY_LAUNCHED" ||
    state === "STREAM_COMMITTING"
  ) {
    return "connecting";
  }
  return "opening";
}

import type { SecondaryPlacement } from "../stores/compositorStore";

export type LayoutMode = "split" | "popup";
export type AppPairPlacement = SecondaryPlacement;

export interface AppPair {
  apps: [string, string];
  layoutMode?: LayoutMode;
  secondaryPlacement?: AppPairPlacement;
}

export function getDefaultAppPairLayoutMode(currentMode?: string | null): LayoutMode {
  return currentMode === "popup" ? "popup" : "split";
}

export function getDefaultAppPairPlacement(currentMode?: string | null): AppPairPlacement {
  return currentMode === "popup" ? "popup" : "right";
}

export function resolveAppPairLayoutMode(layoutMode: unknown, fallbackMode?: string | null): LayoutMode {
  if (layoutMode === "popup") return "popup";
  if (layoutMode === "split") return "split";
  return getDefaultAppPairLayoutMode(fallbackMode);
}

export function resolveAppPairPlacement(
  placement: unknown,
  layoutMode?: unknown,
  fallbackMode?: string | null,
): AppPairPlacement {
  if (
    placement === "left" ||
    placement === "right" ||
    placement === "top" ||
    placement === "bottom" ||
    placement === "popup"
  ) {
    return placement;
  }
  return resolveAppPairLayoutMode(layoutMode, fallbackMode) === "popup"
    ? "popup"
    : getDefaultAppPairPlacement(fallbackMode);
}

export function getAppPairLayoutMode(pair: AppPair): LayoutMode {
  return pair.secondaryPlacement === "popup"
    ? "popup"
    : resolveAppPairLayoutMode(pair.layoutMode);
}

export function toStoredAppPair(pair: AppPair): AppPair {
  const secondaryPlacement = resolveAppPairPlacement(
    pair.secondaryPlacement,
    pair.layoutMode,
  );
  return {
    apps: [pair.apps[0], pair.apps[1]],
    layoutMode: secondaryPlacement === "popup" ? "popup" : "split",
    secondaryPlacement,
  };
}

export function normalizeAppPair(value: unknown): AppPair | null {
  if (!value || typeof value !== "object") return null;
  const candidate = value as any;

  // New Model: { apps: [string, string], layoutMode?: 'split' | 'popup' }
  if (Array.isArray(candidate.apps) && candidate.apps.length === 2) {
    const [app0, app1] = candidate.apps;
    if (
      typeof app0 === "string" &&
      app0.length > 0 &&
      typeof app1 === "string" &&
      app1.length > 0 &&
      app0 !== app1
    ) {
      const mode = candidate.layoutMode;
      const secondaryPlacement = resolveAppPairPlacement(
        candidate.secondaryPlacement,
        mode,
      );
      return {
        apps: [app0, app1],
        layoutMode: mode === "split" || mode === "popup" ? mode : undefined,
        secondaryPlacement,
      };
    }
  }

  // Legacy WorkspaceRecord: { primaryApp: string, secondaryApp?: string, layoutMode: 'split'|'popup'|'single' }
  if (typeof candidate.primaryApp === "string" && candidate.primaryApp.length > 0) {
    const primary = candidate.primaryApp;
    const secondary =
      typeof candidate.secondaryApp === "string" && candidate.secondaryApp.length > 0
        ? candidate.secondaryApp
        : undefined;

    if (secondary && primary !== secondary) {
      return {
        apps: [primary, secondary],
        layoutMode: resolveAppPairLayoutMode(candidate.layoutMode),
        secondaryPlacement: resolveAppPairPlacement(
          candidate.secondaryPlacement,
          candidate.layoutMode,
        ),
      };
    }
  }

  // Legacy Left/Right AppPair: { left?: string, right?: string }
  if (
    typeof candidate.left === "string" &&
    candidate.left.length > 0 &&
    typeof candidate.right === "string" &&
    candidate.right.length > 0
  ) {
    if (candidate.left !== candidate.right) {
      return {
        apps: [candidate.left, candidate.right],
        layoutMode: resolveAppPairLayoutMode(candidate.layoutMode),
        secondaryPlacement: resolveAppPairPlacement(
          candidate.secondaryPlacement,
          candidate.layoutMode,
        ),
      };
    }
  }

  // Legacy App Pair: { appA?: string, appB?: string, mode?: 'split' | 'popup' }
  if (
    typeof candidate.appA === "string" &&
    candidate.appA.length > 0 &&
    typeof candidate.appB === "string" &&
    candidate.appB.length > 0
  ) {
    if (candidate.appA !== candidate.appB) {
      return {
        apps: [candidate.appA, candidate.appB],
        layoutMode: resolveAppPairLayoutMode(candidate.layoutMode ?? candidate.mode),
        secondaryPlacement: resolveAppPairPlacement(
          candidate.secondaryPlacement,
          candidate.layoutMode ?? candidate.mode,
        ),
      };
    }
  }

  return null;
}

export function isValidAppPair(pair: AppPair): boolean {
  if (!pair.apps || pair.apps.length !== 2) return false;
  const [app0, app1] = pair.apps;
  return (
    typeof app0 === "string" &&
    app0.length > 0 &&
    typeof app1 === "string" &&
    app1.length > 0 &&
    app0 !== app1
  );
}

export function swapAppPairApps(pair: AppPair): AppPair {
  return {
    ...pair,
    apps: [pair.apps[1], pair.apps[0]],
  };
}

export function getAppPairKey(pair: AppPair): string {
  return `${pair.apps[0]}:${pair.apps[1]}:${resolveAppPairPlacement(pair.secondaryPlacement, pair.layoutMode)}`;
}

export function dedupeAppPairs(pairs: AppPair[]): AppPair[] {
  const deduped = new Map<string, AppPair>();
  for (const pair of pairs) {
    if (!isValidAppPair(pair)) continue;
    deduped.set(getAppPairKey(pair), toStoredAppPair(pair));
  }
  return Array.from(deduped.values());
}

export function getAppPairPreviewPackages(pair: AppPair): string[] {
  return [pair.apps[0], pair.apps[1]];
}

export function getAppPairModeLabel(mode: LayoutMode): string {
  if (mode === "split") return "Split";
  return "Popup";
}

export function getAppPairPlacementLabel(placement: AppPairPlacement): string {
  if (placement === "left") return "Left";
  if (placement === "right") return "Right";
  if (placement === "top") return "Top";
  if (placement === "bottom") return "Bottom";
  return "Popup";
}

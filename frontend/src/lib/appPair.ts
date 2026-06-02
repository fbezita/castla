export type LayoutMode = "split" | "popup";

export interface AppPair {
  apps: [string, string];
  layoutMode?: LayoutMode;
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
      return {
        apps: [app0, app1],
        layoutMode: mode === "split" || mode === "popup" ? mode : undefined,
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
      const mode = candidate.layoutMode === "popup" ? "popup" : "split";
      return {
        apps: [primary, secondary],
        layoutMode: mode,
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
        layoutMode: "split",
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
  return `${pair.layoutMode ?? "default"}:${pair.apps[0]}:${pair.apps[1]}`;
}

export function getAppPairPreviewPackages(pair: AppPair): string[] {
  return [pair.apps[0], pair.apps[1]];
}

export function getAppPairModeLabel(mode: LayoutMode): string {
  if (mode === "split") return "Split";
  return "Popup";
}

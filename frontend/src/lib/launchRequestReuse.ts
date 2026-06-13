import type { StreamMetadata } from "../protocol";
import type {
  CompositorState,
  LayoutMode,
  PopupLayoutState,
  ViewportModel,
} from "../stores/compositorStore";
import { canReuseHotStream } from "./launchReuse";
import { isJmuxerFrontendPath } from "./decoderPath";
import type { SplitTargets } from "./splitTargets";
import { resolveSecondaryPlacement } from "./secondaryPlacement";

export interface LaunchRequest {
  primaryPkg: string;
  secondaryPkg?: string;
  layoutMode: LayoutMode;
  secondaryPlacement?: CompositorState["secondaryPlacement"];
}

export interface ExpectedLaunchLayout {
  splitTargets?: SplitTargets | null;
  popup?: PopupLayoutState;
}

function isStableLaunchState(state: string): boolean {
  return state === "IDLE" || state === "RUNNING" || state === "DEGRADED";
}

function isPaneHealthy(
  viewport: ViewportModel | undefined,
  metadata: StreamMetadata | undefined,
): boolean {
  const startGen = viewport?.generation ?? 0;
  return canReuseHotStream(viewport, metadata, startGen);
}

function align16(value: number): number {
  return Math.max(320, (Math.round(value) + 15) & ~15);
}

function matchesPaneSize(
  viewport: ViewportModel | undefined,
  expectedWidth?: number,
  expectedHeight?: number,
  strict = true,
): boolean {
  if (!viewport) return false;
  if (!strict) return true;
  if (expectedWidth !== undefined && viewport.width !== expectedWidth) return false;
  if (expectedHeight !== undefined && viewport.height !== expectedHeight) return false;
  return true;
}

function matchesPopupGeometry(
  currentPopup: PopupLayoutState,
  expectedPopup: PopupLayoutState | undefined,
): boolean {
  if (!expectedPopup) return false;
  return (
    currentPopup.visible === expectedPopup.visible &&
    currentPopup.minimized === expectedPopup.minimized &&
    Math.round(currentPopup.x) === Math.round(expectedPopup.x) &&
    Math.round(currentPopup.y) === Math.round(expectedPopup.y) &&
    Math.round(currentPopup.width) === Math.round(expectedPopup.width) &&
    Math.round(currentPopup.height) === Math.round(expectedPopup.height)
  );
}

function shouldUseStrictPaneSize(): boolean {
  if (typeof window === "undefined") {
    return true;
  }
  return isJmuxerFrontendPath();
}

export function canReusePrimaryLaunchForRequest(
  request: LaunchRequest,
  state: CompositorState,
  primaryMetadata: StreamMetadata | undefined,
): boolean {
  const currentPrimaryViewport = state.viewports.get("primary");
  const samePrimaryApp = request.primaryPkg === state.activePrimaryApp;
  const hasHealthyPrimaryStream = canReuseHotStream(
    currentPrimaryViewport,
    primaryMetadata,
    currentPrimaryViewport ? Math.max(1, currentPrimaryViewport.generation) : 1,
  );

  if (!samePrimaryApp || !hasHealthyPrimaryStream) {
    return false;
  }

  if (
    shouldUseStrictPaneSize() &&
    request.layoutMode === "single" &&
    state.layoutMode !== "single"
  ) {
    return false;
  }

  return true;
}

export function canKeepCurrentLaunch(
  request: LaunchRequest,
  state: CompositorState,
  primaryMetadata: StreamMetadata | undefined,
  secondaryMetadata: StreamMetadata | undefined,
  expectedLayout: ExpectedLaunchLayout,
): boolean {
  const strictPaneSize = shouldUseStrictPaneSize();
  if (!isStableLaunchState(state.launchSequence.state)) return false;
  if (state.layoutMode !== request.layoutMode) return false;
  if (state.activePrimaryApp !== request.primaryPkg) return false;

  const requestedSecondary = request.secondaryPkg ?? "";
  if (state.activeSecondaryApp !== requestedSecondary) return false;
  if (request.secondaryPkg) {
    const currentPlacement = resolveSecondaryPlacement(
      state.layoutMode,
      state.secondaryPlacement,
    );
    const requestedPlacement = request.secondaryPlacement
      ?? resolveSecondaryPlacement(request.layoutMode, state.secondaryPlacement);
    if (currentPlacement !== requestedPlacement) return false;
  }

  const primaryViewport = state.viewports.get("primary");
  if (!isPaneHealthy(primaryViewport, primaryMetadata)) return false;

  if (request.layoutMode === "split") {
    const splitTargets = expectedLayout.splitTargets;
    if (!splitTargets?.primaryWidth || !splitTargets?.secondaryWidth || !splitTargets?.paneHeight) {
      return false;
    }
    if (
      !matchesPaneSize(primaryViewport, splitTargets.primaryWidth, splitTargets.paneHeight, strictPaneSize)
    ) {
      return false;
    }
  }

  const secondaryViewport = state.viewports.get("secondary");
  if (!request.secondaryPkg) {
    return secondaryViewport?.visible !== true;
  }

  if (!isPaneHealthy(secondaryViewport, secondaryMetadata)) return false;

  if (request.layoutMode === "split") {
    const splitTargets = expectedLayout.splitTargets;
    return matchesPaneSize(
      secondaryViewport,
      splitTargets?.secondaryWidth,
      splitTargets?.paneHeight,
      strictPaneSize,
    );
  }

  if (request.layoutMode === "popup") {
    const expectedPopup = expectedLayout.popup;
    if (!expectedPopup || !matchesPopupGeometry(state.popup, expectedPopup)) {
      return false;
    }
    const expectedPopupWidth = align16(expectedPopup.width);
    const expectedPopupHeight = align16(Math.max(160, expectedPopup.height - 40));
    if (!matchesPaneSize(secondaryViewport, expectedPopupWidth, expectedPopupHeight, strictPaneSize)) {
      return false;
    }
  }

  return true;
}

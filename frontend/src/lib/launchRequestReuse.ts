import type { StreamMetadata } from "../protocol";
import type {
  CompositorState,
  LayoutMode,
  PopupLayoutState,
  ViewportModel,
} from "../stores/compositorStore";
import { canReuseHotStream } from "./launchReuse";
import type { SplitTargets } from "./splitTargets";

export interface LaunchRequest {
  primaryPkg: string;
  secondaryPkg?: string;
  layoutMode: LayoutMode;
}

export interface ExpectedLaunchLayout {
  splitTargets?: SplitTargets | null;
  popup?: PopupLayoutState;
}

function isStableLaunchState(state: string): boolean {
  return state === "IDLE" || state === "RUNNING" || state === "FAILED" || state === "DEGRADED";
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
): boolean {
  if (!viewport) return false;
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

export function canKeepCurrentLaunch(
  request: LaunchRequest,
  state: CompositorState,
  primaryMetadata: StreamMetadata | undefined,
  secondaryMetadata: StreamMetadata | undefined,
  expectedLayout: ExpectedLaunchLayout,
): boolean {
  if (!isStableLaunchState(state.launchSequence.state)) return false;
  if (state.layoutMode !== request.layoutMode) return false;
  if (state.activePrimaryApp !== request.primaryPkg) return false;

  const requestedSecondary = request.secondaryPkg ?? "";
  if (state.activeSecondaryApp !== requestedSecondary) return false;

  const primaryViewport = state.viewports.get("primary");
  if (!isPaneHealthy(primaryViewport, primaryMetadata)) return false;

  if (request.layoutMode === "split") {
    const splitTargets = expectedLayout.splitTargets;
    if (!splitTargets?.primaryWidth || !splitTargets?.secondaryWidth || !splitTargets?.paneHeight) {
      return false;
    }
    if (
      !matchesPaneSize(primaryViewport, splitTargets.primaryWidth, splitTargets.paneHeight)
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
    );
  }

  if (request.layoutMode === "popup") {
    const expectedPopup = expectedLayout.popup;
    if (!expectedPopup || !matchesPopupGeometry(state.popup, expectedPopup)) {
      return false;
    }
    const expectedPopupWidth = align16(expectedPopup.width);
    const expectedPopupHeight = align16(Math.max(160, expectedPopup.height - 40));
    if (!matchesPaneSize(secondaryViewport, expectedPopupWidth, expectedPopupHeight)) {
      return false;
    }
  }

  return true;
}

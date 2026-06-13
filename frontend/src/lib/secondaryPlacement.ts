import type { LayoutMode, SecondaryPlacement } from "../stores/compositorStore";
import type { LaunchRequest } from "./launchRequestReuse";
import type { CompositorState } from "../stores/compositorStore";

export type ExternalAppDropZone =
  | SecondaryPlacement
  | "remove"
  | "";

export interface PlacementHostBounds {
  width: number;
  height: number;
  drawerLeft: number;
}

export interface PlacementRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface DockedPaneLayout {
  primary: PlacementRect;
  secondary: PlacementRect;
  boundaryPercent: number;
  axis: "horizontal" | "vertical";
}

export interface DockedPaneStyles {
  primary: string;
  secondary: string;
}

const MIN_HORIZONTAL_DOCK_SPAN = 320;
const MIN_VERTICAL_DOCK_SPAN = 160;
const HORIZONTAL_DOCK_RATIO_MIN = 0.22;
const HORIZONTAL_DOCK_RATIO_MAX = 0.78;
const VERTICAL_DOCK_RATIO_MIN = 0.1;
const VERTICAL_DOCK_RATIO_MAX = 0.9;
const DROP_TARGET_SIZE = 92;
const DROP_TARGET_GAP = 28;
const REMOVE_BOTTOM_INSET = 20;
const REMOVE_HEIGHT = 120;
const SCREEN_INSET = 20;

function align16(value: number): number {
  return Math.max(16, (Math.round(value) + 15) & ~15);
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function isDockedPlacement(
  placement: SecondaryPlacement | null | undefined,
): placement is Exclude<SecondaryPlacement, "popup"> {
  return (
    placement === "left" ||
    placement === "right" ||
    placement === "top" ||
    placement === "bottom"
  );
}

export function resolveSecondaryPlacement(
  layoutMode: LayoutMode,
  placement?: SecondaryPlacement | null,
): SecondaryPlacement | null {
  if (layoutMode === "single") {
    return placement ?? null;
  }
  if (layoutMode === "popup") {
    return "popup";
  }
  if (isDockedPlacement(placement)) {
    return placement;
  }
  return "right";
}

export function placementToLayoutMode(
  placement: SecondaryPlacement,
): LayoutMode {
  return placement === "popup" ? "popup" : "split";
}

export function buildSecondaryPlacementLaunchRequest(
  placement: SecondaryPlacement,
  state: CompositorState,
): LaunchRequest | null {
  if (!state.activePrimaryApp || !state.activeSecondaryApp) {
    return null;
  }

  const layoutMode = placementToLayoutMode(placement);
  const currentPlacement = resolveSecondaryPlacement(
    state.layoutMode,
    state.secondaryPlacement,
  );

  if (state.layoutMode === layoutMode && currentPlacement === placement) {
    return null;
  }

  return {
    primaryPkg: state.activePrimaryApp,
    secondaryPkg: state.activeSecondaryApp,
    layoutMode,
    secondaryPlacement: placement,
  };
}

export function computeDockedPaneLayout(
  width: number,
  height: number,
  splitRatio: number,
  placement: Exclude<SecondaryPlacement, "popup">,
): DockedPaneLayout {
  const safeWidth = Math.max(0, Math.round(width));
  const safeHeight = Math.max(0, Math.round(height));
  const axis = placement === "left" || placement === "right"
    ? "horizontal"
    : "vertical";
  const total = axis === "horizontal" ? safeWidth : safeHeight;
  const minSpan =
    axis === "horizontal" ? MIN_HORIZONTAL_DOCK_SPAN : MIN_VERTICAL_DOCK_SPAN;
  const clampedRatio = clamp(
    splitRatio,
    axis === "horizontal" ? HORIZONTAL_DOCK_RATIO_MIN : VERTICAL_DOCK_RATIO_MIN,
    axis === "horizontal" ? HORIZONTAL_DOCK_RATIO_MAX : VERTICAL_DOCK_RATIO_MAX,
  );
  const primarySpan = align16(Math.max(minSpan, total * clampedRatio));
  const secondarySpan = align16(Math.max(minSpan, total - primarySpan));
  const alignedTotal = Math.max(primarySpan + secondarySpan, 1);
  const boundaryPercent =
    axis === "horizontal"
      ? placement === "left"
        ? (secondarySpan / alignedTotal) * 100
        : (primarySpan / alignedTotal) * 100
      : placement === "top"
        ? (secondarySpan / alignedTotal) * 100
        : (primarySpan / alignedTotal) * 100;

  if (axis === "horizontal") {
    if (placement === "left") {
      return {
        axis,
        boundaryPercent,
        secondary: { left: 0, top: 0, width: secondarySpan, height: safeHeight },
        primary: { left: secondarySpan, top: 0, width: primarySpan, height: safeHeight },
      };
    }
    return {
      axis,
      boundaryPercent,
      primary: { left: 0, top: 0, width: primarySpan, height: safeHeight },
      secondary: { left: primarySpan, top: 0, width: secondarySpan, height: safeHeight },
    };
  }

  if (placement === "top") {
    return {
      axis,
      boundaryPercent,
      secondary: { left: 0, top: 0, width: safeWidth, height: secondarySpan },
      primary: { left: 0, top: secondarySpan, width: safeWidth, height: primarySpan },
    };
  }

  return {
    axis,
    boundaryPercent,
    primary: { left: 0, top: 0, width: safeWidth, height: primarySpan },
    secondary: { left: 0, top: primarySpan, width: safeWidth, height: secondarySpan },
  };
}

export function rectToStyle(rect: PlacementRect): string {
  return `left:${rect.left}px;top:${rect.top}px;width:${rect.width}px;height:${rect.height}px;`;
}

export function buildDockedPaneStyles(
  layout: DockedPaneLayout,
): DockedPaneStyles {
  if (layout.axis === "horizontal") {
    const totalWidth = Math.max(1, layout.primary.width + layout.secondary.width);
    const primaryPercent = (layout.primary.width / totalWidth) * 100;
    const secondaryPercent = (layout.secondary.width / totalWidth) * 100;
    if (layout.secondary.left === 0) {
      return {
        primary: `left:${secondaryPercent}%;top:0;width:${primaryPercent}%;height:100%;`,
        secondary: `left:0;top:0;width:${secondaryPercent}%;height:100%;`,
      };
    }
    return {
      primary: `left:0;top:0;width:${primaryPercent}%;height:100%;`,
      secondary: `left:${primaryPercent}%;top:0;width:${secondaryPercent}%;height:100%;`,
    };
  }

  const totalHeight = Math.max(1, layout.primary.height + layout.secondary.height);
  const primaryPercent = (layout.primary.height / totalHeight) * 100;
  const secondaryPercent = (layout.secondary.height / totalHeight) * 100;
  if (layout.secondary.top === 0) {
    return {
      primary: `left:0;top:${secondaryPercent}%;width:100%;height:${primaryPercent}%;`,
      secondary: `left:0;top:0;width:100%;height:${secondaryPercent}%;`,
    };
  }
  return {
    primary: `left:0;top:0;width:100%;height:${primaryPercent}%;`,
    secondary: `left:0;top:${primaryPercent}%;width:100%;height:${secondaryPercent}%;`,
  };
}

export function getDropTargetRect(
  zone: SecondaryPlacement,
  bounds: PlacementHostBounds,
): PlacementRect {
  const usableWidth = Math.max(0, bounds.drawerLeft);
  const centerX = usableWidth / 2;
  const centerY = bounds.height / 2;
  const targetHalf = DROP_TARGET_SIZE / 2;

  if (zone === "popup") {
    return {
      left: centerX - targetHalf,
      top: centerY - targetHalf,
      width: DROP_TARGET_SIZE,
      height: DROP_TARGET_SIZE,
    };
  }

  if (zone === "left") {
    return {
      left: Math.max(SCREEN_INSET, centerX - targetHalf - DROP_TARGET_SIZE - DROP_TARGET_GAP),
      top: centerY - targetHalf,
      width: DROP_TARGET_SIZE,
      height: DROP_TARGET_SIZE,
    };
  }

  if (zone === "right") {
    return {
      left: Math.min(
        usableWidth - SCREEN_INSET - DROP_TARGET_SIZE,
        centerX + targetHalf + DROP_TARGET_GAP,
      ),
      top: centerY - targetHalf,
      width: DROP_TARGET_SIZE,
      height: DROP_TARGET_SIZE,
    };
  }

  if (zone === "top") {
    return {
      left: centerX - targetHalf,
      top: Math.max(SCREEN_INSET, centerY - targetHalf - DROP_TARGET_SIZE - DROP_TARGET_GAP),
      width: DROP_TARGET_SIZE,
      height: DROP_TARGET_SIZE,
    };
  }

  return {
    left: centerX - targetHalf,
    top: Math.min(
      bounds.height - REMOVE_HEIGHT - SCREEN_INSET - DROP_TARGET_SIZE,
      centerY + targetHalf + DROP_TARGET_GAP,
    ),
    width: DROP_TARGET_SIZE,
    height: DROP_TARGET_SIZE,
  };
}

export function getPlacementPreviewRect(
  zone: ExternalAppDropZone,
  bounds: PlacementHostBounds,
  splitRatio: number,
): PlacementRect | null {
  if (zone === "") {
    return null;
  }
  if (zone === "remove") {
    return {
      left: SCREEN_INSET,
      top: bounds.height - REMOVE_HEIGHT - REMOVE_BOTTOM_INSET,
      width: Math.max(0, bounds.drawerLeft - SCREEN_INSET * 2),
      height: REMOVE_HEIGHT,
    };
  }

  if (!isDockedPlacement(zone) && zone !== "popup") {
    return null;
  }

  if (zone === "popup") {
    const width = Math.min(420, Math.max(240, Math.round(bounds.drawerLeft * 0.34)));
    const height = Math.min(320, Math.max(180, Math.round(bounds.height * 0.28)));
    return {
      left: bounds.drawerLeft / 2 - width / 2,
      top: bounds.height / 2 - height / 2,
      width,
      height,
    };
  }

  return computeDockedPaneLayout(
    bounds.drawerLeft,
    bounds.height,
    splitRatio,
    zone,
  ).secondary;
}

function isPointInsideRect(
  x: number,
  y: number,
  rect: PlacementRect,
): boolean {
  return (
    x >= rect.left &&
    x <= rect.left + rect.width &&
    y >= rect.top &&
    y <= rect.top + rect.height
  );
}

export function resolveExternalAppDropZone(
  x: number,
  y: number,
  bounds: PlacementHostBounds,
): ExternalAppDropZone {
  const removeRect = getPlacementPreviewRect("remove", bounds, 0.5);
  if (removeRect && isPointInsideRect(x, y, removeRect)) {
    return "remove";
  }

  const placements: SecondaryPlacement[] = ["popup", "left", "right", "top", "bottom"];
  for (const placement of placements) {
    if (isPointInsideRect(x, y, getDropTargetRect(placement, bounds))) {
      return placement;
    }
  }

  return "";
}

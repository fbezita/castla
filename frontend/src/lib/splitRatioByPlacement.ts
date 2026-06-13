import type { SecondaryPlacement } from "../stores/compositorStore";

const HORIZONTAL_RATIO_KEY = "castla_split_ratio_horizontal";
const VERTICAL_RATIO_KEY = "castla_split_ratio_vertical";
const HORIZONTAL_RATIO_MIN = 0.22;
const HORIZONTAL_RATIO_MAX = 0.78;
const VERTICAL_RATIO_MIN = 0.1;
const VERTICAL_RATIO_MAX = 0.9;

type SplitRatioAxis = "horizontal" | "vertical" | "none";

function getSplitRatioAxis(
  placement: SecondaryPlacement | null | undefined,
): SplitRatioAxis {
  if (placement === "left" || placement === "right") {
    return "horizontal";
  }
  if (placement === "top" || placement === "bottom") {
    return "vertical";
  }
  return "none";
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function clampRatioForAxis(value: number, axis: SplitRatioAxis): number {
  if (axis === "horizontal") {
    return clamp(value, HORIZONTAL_RATIO_MIN, HORIZONTAL_RATIO_MAX);
  }
  if (axis === "vertical") {
    return clamp(value, VERTICAL_RATIO_MIN, VERTICAL_RATIO_MAX);
  }
  return value;
}

export function resolveSplitRatioForPlacement(
  fallbackRatio: number,
  placement: SecondaryPlacement | null | undefined,
): number {
  const axis = getSplitRatioAxis(placement);
  const fallback = clampRatioForAxis(fallbackRatio, axis);
  if (typeof localStorage === "undefined" || axis === "none") {
    return fallback;
  }
  const key = axis === "horizontal" ? HORIZONTAL_RATIO_KEY : VERTICAL_RATIO_KEY;
  const raw = localStorage.getItem(key);
  if (raw === null) {
    return fallback;
  }
  const stored = Number(raw);
  if (!Number.isFinite(stored)) {
    return fallback;
  }
  return clampRatioForAxis(stored, axis);
}

export function persistSplitRatioForPlacement(
  ratio: number,
  placement: SecondaryPlacement | null | undefined,
): number {
  const axis = getSplitRatioAxis(placement);
  const clamped = clampRatioForAxis(ratio, axis);
  if (typeof localStorage === "undefined" || axis === "none") {
    return clamped;
  }
  const key = axis === "horizontal" ? HORIZONTAL_RATIO_KEY : VERTICAL_RATIO_KEY;
  localStorage.setItem(key, String(clamped));
  return clamped;
}

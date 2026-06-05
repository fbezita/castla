export const OVERLAY_UI_SCALE_STORAGE_KEY = "castla_overlay_ui_scale";
export const OVERLAY_UI_SCALE_DEFAULT = 1;
export const OVERLAY_UI_SCALE_MIN = 1;
export const OVERLAY_UI_SCALE_MAX = 2;
export const OVERLAY_UI_SCALE_STEP = 0.05;

export type OverlayUiScalePreference = number;

export function clampOverlayUiScale(value: number): number {
  if (!Number.isFinite(value)) return OVERLAY_UI_SCALE_DEFAULT;
  return Math.min(OVERLAY_UI_SCALE_MAX, Math.max(OVERLAY_UI_SCALE_MIN, value));
}

export function normalizeOverlayUiScalePreference(
  value: string | null | undefined,
): OverlayUiScalePreference {
  return clampOverlayUiScale(Number(value));
}

export function readOverlayUiScalePreference(): OverlayUiScalePreference {
  if (typeof localStorage === "undefined") return OVERLAY_UI_SCALE_DEFAULT;
  return normalizeOverlayUiScalePreference(localStorage.getItem(OVERLAY_UI_SCALE_STORAGE_KEY));
}

export function writeOverlayUiScalePreference(preference: OverlayUiScalePreference): void {
  if (typeof localStorage === "undefined") return;
  localStorage.setItem(
    OVERLAY_UI_SCALE_STORAGE_KEY,
    String(clampOverlayUiScale(preference)),
  );
}

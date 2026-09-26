export const OVERLAY_UI_SCALE_STORAGE_KEY = "castla_overlay_ui_scale";
export const OVERLAY_UI_SCALE_DEFAULT = 1;
export const OVERLAY_UI_SCALE_MIN = 0.8;
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
  if (value == null || value.trim() === "") return OVERLAY_UI_SCALE_DEFAULT;
  return clampOverlayUiScale(Number(value));
}

export function readOverlayUiScalePreference(
  defaultPreference: OverlayUiScalePreference = OVERLAY_UI_SCALE_DEFAULT,
): OverlayUiScalePreference {
  if (typeof localStorage === "undefined") return clampOverlayUiScale(defaultPreference);
  const storedPreference = localStorage.getItem(OVERLAY_UI_SCALE_STORAGE_KEY);
  return storedPreference === null
    ? clampOverlayUiScale(defaultPreference)
    : normalizeOverlayUiScalePreference(storedPreference);
}

export function writeOverlayUiScalePreference(preference: OverlayUiScalePreference): void {
  if (typeof localStorage === "undefined") return;
  localStorage.setItem(
    OVERLAY_UI_SCALE_STORAGE_KEY,
    String(clampOverlayUiScale(preference)),
  );
}

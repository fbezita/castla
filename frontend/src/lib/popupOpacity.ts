export const DEFAULT_POPUP_OPACITY = 0.9;
export const MIN_POPUP_OPACITY = 0.35;
export const MAX_POPUP_OPACITY = 1;

export function clampPopupOpacity(value: number): number {
  if (!Number.isFinite(value)) return DEFAULT_POPUP_OPACITY;
  return Math.min(MAX_POPUP_OPACITY, Math.max(MIN_POPUP_OPACITY, value));
}

export function readStoredPopupOpacity(raw: string | null): number {
  if (raw === null) return DEFAULT_POPUP_OPACITY;
  return clampPopupOpacity(Number(raw));
}

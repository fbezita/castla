import type { PopupLayoutState } from "../stores/compositorStore";

export const STREAM_ALIGNED_POPUP_HEADER_HEIGHT = 40;
export const STREAM_ALIGNED_POPUP_MIN_BODY_HEIGHT = 160;

export function alignPopupDimension16(value: number): number {
  return Math.max(320, (Math.round(value) + 15) & ~15);
}

export function normalizePopupForStreaming(
  popup: PopupLayoutState,
): PopupLayoutState {
  if (popup.minimized) {
    return popup;
  }

  const alignedWidth = alignPopupDimension16(popup.width);
  const rawBodyHeight = Math.max(
    STREAM_ALIGNED_POPUP_MIN_BODY_HEIGHT,
    popup.height - STREAM_ALIGNED_POPUP_HEADER_HEIGHT,
  );
  const alignedBodyHeight = alignPopupDimension16(rawBodyHeight);

  return {
    ...popup,
    width: alignedWidth,
    height: alignedBodyHeight + STREAM_ALIGNED_POPUP_HEADER_HEIGHT,
  };
}

export interface OverlayPoint {
  x: number;
  y: number;
}

export interface OverlayRect extends OverlayPoint {
  width: number;
  height: number;
}

export function normalizeOverlayScale(scale: number): number {
  return Number.isFinite(scale) && scale > 0 ? scale : 1;
}

export function toOverlayPoint(
  point: OverlayPoint,
  scale: number,
): OverlayPoint {
  const normalizedScale = normalizeOverlayScale(scale);
  return {
    x: point.x / normalizedScale,
    y: point.y / normalizedScale,
  };
}

export function toOverlayRect(rect: OverlayRect, scale: number): OverlayRect {
  const normalizedScale = normalizeOverlayScale(scale);
  return {
    x: rect.x / normalizedScale,
    y: rect.y / normalizedScale,
    width: rect.width / normalizedScale,
    height: rect.height / normalizedScale,
  };
}

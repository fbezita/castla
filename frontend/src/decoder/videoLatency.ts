export const MIN_VIDEO_LATENCY_MS = 0;
export const MAX_VIDEO_LATENCY_MS = 1000;

export function clampVideoLatencyMs(value: number): number {
  if (!Number.isFinite(value)) return MIN_VIDEO_LATENCY_MS;
  return Math.min(MAX_VIDEO_LATENCY_MS, Math.max(MIN_VIDEO_LATENCY_MS, Math.round(value)));
}

export function liveEdgeTargetSeconds(bufferedEnd: number, bufferedStart: number, latencyMs: number): number {
  return Math.max(bufferedStart, bufferedEnd - clampVideoLatencyMs(latencyMs) / 1000);
}

const INITIAL_CONNECTION_FAILURE_GRACE_MS = 3_000;
const RECONNECT_FAILURE_GRACE_MS = 600;

export function connectionOverlayDelayMs(hasConnected: boolean): number {
  return hasConnected
    ? RECONNECT_FAILURE_GRACE_MS
    : INITIAL_CONNECTION_FAILURE_GRACE_MS;
}

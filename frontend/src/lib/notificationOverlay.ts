export interface OverlayNotification {
  id: string;
  packageName: string;
  appLabel: string;
  title: string;
  text: string;
  postedAtMs: number;
}

const MAX_OVERLAY_NOTIFICATIONS = 3;
export const NOTIFICATION_OVERLAY_ENABLED_KEY =
  "castla_notification_overlay_enabled";

export function normalizeNotificationOverlayEnabled(
  value: string | null,
): boolean {
  return value !== "0";
}

export function readNotificationOverlayEnabled(): boolean {
  return normalizeNotificationOverlayEnabled(
    localStorage.getItem(NOTIFICATION_OVERLAY_ENABLED_KEY),
  );
}

export function writeNotificationOverlayEnabled(enabled: boolean): void {
  localStorage.setItem(NOTIFICATION_OVERLAY_ENABLED_KEY, enabled ? "1" : "0");
}

export function upsertOverlayNotification(
  queue: OverlayNotification[],
  next: OverlayNotification,
): OverlayNotification[] {
  const deduped = queue.filter((item) => item.id !== next.id);
  return [next, ...deduped].slice(0, MAX_OVERLAY_NOTIFICATIONS);
}

export function pruneOverlayNotifications(
  queue: OverlayNotification[],
  nowMs: number,
  ttlMs: number,
): OverlayNotification[] {
  return queue.filter((item) => nowMs - item.postedAtMs < ttlMs);
}

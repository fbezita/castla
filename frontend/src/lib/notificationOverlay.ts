import { t, type Language } from "./i18n";

export interface OverlayNotification {
  id: string;
  packageName: string;
  appLabel: string;
  title: string;
  text: string;
  sender?: string;
  postedAtMs: number;
  hasImage?: boolean;
}

export const DEFAULT_NOTIFICATION_ALLOWED_PACKAGES = [
  "com.android.phone",
  "com.android.dialer",
  "com.samsung.android.dialer",
  "com.google.android.dialer",
  "com.android.mms",
  "com.android.messaging",
  "com.samsung.android.messaging",
  "com.google.android.apps.messaging",
  "com.kakao.talk",
  "org.telegram.messenger",
] as const;

export function normalizeNotificationAllowedPackages(
  value: string | null,
): string[] {
  if (value === null) return [...DEFAULT_NOTIFICATION_ALLOWED_PACKAGES];
  try {
    const parsed = JSON.parse(value) as unknown;
    if (!Array.isArray(parsed)) return [...DEFAULT_NOTIFICATION_ALLOWED_PACKAGES];
    return [...new Set(parsed.filter((item): item is string => typeof item === "string").map((item) => item.trim()).filter(Boolean))];
  } catch {
    return [...DEFAULT_NOTIFICATION_ALLOWED_PACKAGES];
  }
}

export function isNotificationAllowed(
  notification: OverlayNotification,
  allowedPackages: readonly string[],
): boolean {
  return allowedPackages.includes(notification.packageName);
}

export function shouldDisplayOverlayNotification(
  notification: OverlayNotification,
  enabled: boolean,
  allowedPackages: readonly string[],
): boolean {
  return enabled && isNotificationAllowed(notification, allowedPackages);
}

const MAX_OVERLAY_NOTIFICATIONS = 3;
const MAX_NOTIFICATION_HISTORY = 100;
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

export function formatNotificationText(
  notification: OverlayNotification,
  language: Language,
): string {
  if (!notification.hasImage) return notification.text;

  const imageLabel = t(language, "notificationContainsImage");
  return notification.text ? `${imageLabel} · ${notification.text}` : imageLabel;
}

export interface NotificationConversationGroup {
  title: string;
  items: OverlayNotification[];
}

export interface NotificationHistoryGroup {
  packageName: string;
  appLabel: string;
  count: number;
  conversations: NotificationConversationGroup[];
}

export function groupNotificationHistory(
  history: OverlayNotification[],
): NotificationHistoryGroup[] {
  const groups = new Map<string, NotificationHistoryGroup>();
  const newestFirst = [...history].sort((left, right) => right.postedAtMs - left.postedAtMs);

  for (const item of newestFirst) {
    let appGroup = groups.get(item.packageName);
    if (!appGroup) {
      appGroup = {
        packageName: item.packageName,
        appLabel: item.appLabel,
        count: 0,
        conversations: [],
      };
      groups.set(item.packageName, appGroup);
    }

    appGroup.count += 1;
    let conversation = appGroup.conversations.find((entry) => entry.title === item.title);
    if (!conversation) {
      conversation = { title: item.title, items: [] };
      appGroup.conversations.push(conversation);
    }
    conversation.items.push(item);
  }

  return [...groups.values()];
}

export function upsertNotificationHistory(
  history: OverlayNotification[],
  next: OverlayNotification,
): OverlayNotification[] {
  return [next, ...history].slice(0, MAX_NOTIFICATION_HISTORY);
}

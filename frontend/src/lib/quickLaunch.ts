export interface QuickLaunchApp {
  packageName: string;
  label: string;
  category?: string;
  isWeb?: boolean;
}

export interface RecentQuickLaunch {
  packageName: string;
  lastUsedAt: number;
}

export type LaunchHubTab =
  | "session"
  | "autorun"
  | "starred"
  | "recent"
  | "notifications"
  | "browse";

export function buildQuickLaunchApps<T extends QuickLaunchApp>(
  apps: readonly T[],
  favoritePackages: readonly string[],
  recent: readonly RecentQuickLaunch[],
  limit = 6,
): T[] {
  const byPackage = new Map(apps.map((app) => [app.packageName, app]));
  const orderedPackages = [
    ...favoritePackages,
    ...[...recent]
      .sort((left, right) => right.lastUsedAt - left.lastUsedAt)
      .map((entry) => entry.packageName),
  ];
  const seen = new Set<string>();
  const result: T[] = [];
  for (const packageName of orderedPackages) {
    if (seen.has(packageName)) continue;
    seen.add(packageName);
    const app = byPackage.get(packageName);
    if (app) result.push(app);
    if (result.length >= limit) break;
  }
  return result;
}

export function resolveInitialLaunchHubTab(
  hasVisibleStream: boolean,
  storedTab: LaunchHubTab,
): LaunchHubTab {
  return hasVisibleStream ? storedTab : "session";
}

export function notificationCountsByPackage(
  notifications: readonly { packageName: string }[],
): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const notification of notifications) {
    counts[notification.packageName] = (counts[notification.packageName] ?? 0) + 1;
  }
  return counts;
}

const DISTRACTING_PACKAGE_HINTS = [
  "browser",
  "chrome",
  "youtube",
  "netflix",
  "kakao.talk",
  "messaging",
  "message",
  "telegram",
];

export function requiresParkedUseNotice(app: QuickLaunchApp): boolean {
  if (app.category === "VIDEO" || app.isWeb === true) return true;
  const packageName = app.packageName.toLowerCase();
  return DISTRACTING_PACKAGE_HINTS.some((hint) => packageName.includes(hint));
}

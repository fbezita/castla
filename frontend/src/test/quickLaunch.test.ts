import { describe, expect, it } from "vitest";
import {
  buildQuickLaunchApps,
  notificationCountsByPackage,
  requiresParkedUseNotice,
  resolveInitialLaunchHubTab,
} from "../lib/quickLaunch";

const apps = [
  { packageName: "nav", label: "Navigation", category: "NAVIGATION" },
  { packageName: "video", label: "Video", category: "VIDEO" },
  { packageName: "chat", label: "Chat", category: "OTHER" },
  { packageName: "music", label: "Music", category: "MUSIC" },
];

describe("quick launch home", () => {
  it("places favorites first and fills with deduplicated recent apps", () => {
    expect(
      buildQuickLaunchApps(
        apps,
        ["video", "nav"],
        [
          { packageName: "nav", lastUsedAt: 30 },
          { packageName: "chat", lastUsedAt: 20 },
          { packageName: "missing", lastUsedAt: 10 },
        ],
        3,
      ).map((app) => app.packageName),
    ).toEqual(["video", "nav", "chat"]);
  });

  it("opens the home tab for an empty session instead of restoring a stale tab", () => {
    expect(resolveInitialLaunchHubTab(false, "notifications")).toBe("session");
    expect(resolveInitialLaunchHubTab(true, "recent")).toBe("recent");
  });

  it("counts notification history per package", () => {
    expect(
      notificationCountsByPackage([
        { packageName: "chat" },
        { packageName: "chat" },
        { packageName: "nav" },
      ]),
    ).toEqual({ chat: 2, nav: 1 });
  });
});

describe("parked-use notice", () => {
  it("warns for video, web, browser, and messaging apps", () => {
    expect(requiresParkedUseNotice(apps[1])).toBe(true);
    expect(requiresParkedUseNotice({ packageName: "site", label: "Site", isWeb: true })).toBe(true);
    expect(requiresParkedUseNotice({ packageName: "com.android.chrome", label: "Chrome" })).toBe(true);
    expect(requiresParkedUseNotice({ packageName: "com.kakao.talk", label: "KakaoTalk" })).toBe(true);
  });

  it("does not warn for ordinary navigation or music apps", () => {
    expect(requiresParkedUseNotice(apps[0])).toBe(false);
    expect(requiresParkedUseNotice(apps[3])).toBe(false);
  });
});

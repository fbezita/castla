import { describe, expect, it } from "vitest";
import {
  DEFAULT_NOTIFICATION_ALLOWED_PACKAGES,
  normalizeNotificationAllowedPackages,
  normalizeNotificationOverlayEnabled,
  pruneOverlayNotifications,
  shouldDisplayOverlayNotification,
  upsertOverlayNotification,
  type OverlayNotification,
} from "../lib/notificationOverlay";

describe("notificationOverlay helpers", () => {
  it("defaults overlay visibility to enabled unless explicitly disabled", () => {
    expect(normalizeNotificationOverlayEnabled(null)).toBe(true);
    expect(normalizeNotificationOverlayEnabled("1")).toBe(true);
    expect(normalizeNotificationOverlayEnabled("0")).toBe(false);
  });


  it("defaults allowed packages to current messenger set", () => {
    expect(normalizeNotificationAllowedPackages(null)).toEqual(DEFAULT_NOTIFICATION_ALLOWED_PACKAGES);
  });

  it("normalizes allowed packages while preserving an explicit empty list", () => {
    expect(
      normalizeNotificationAllowedPackages(JSON.stringify(["com.kakao.talk", "", "com.kakao.talk", 3])),
    ).toEqual(["com.kakao.talk"]);
    expect(normalizeNotificationAllowedPackages("[]")).toEqual([]);
  });

  it("filters display by enabled state and allowed packages", () => {
    const notification: OverlayNotification = {
      id: "n1",
      appLabel: "KakaoTalk",
      title: "Alice",
      text: "Hello",
      packageName: "com.kakao.talk",
      postedAtMs: 1,
    };

    expect(shouldDisplayOverlayNotification(notification, true, ["com.kakao.talk"])).toBe(true);
    expect(shouldDisplayOverlayNotification(notification, false, ["com.kakao.talk"])).toBe(false);
    expect(shouldDisplayOverlayNotification(notification, true, ["org.telegram.messenger"])).toBe(false);
  });

  it("prepends new notifications and caps queue size", () => {
    let queue: OverlayNotification[] = [];
    for (let index = 0; index < 4; index += 1) {
      queue = upsertOverlayNotification(queue, {
        id: `n${index}`,
        appLabel: "KakaoTalk",
        title: `Title ${index}`,
        text: "Hello",
        packageName: "com.kakao.talk",
        postedAtMs: index,
      });
    }

    expect(queue.map((item) => item.id)).toEqual(["n3", "n2", "n1"]);
  });

  it("replaces existing notification with matching id", () => {
    const queue = upsertOverlayNotification(
      [
        {
          id: "same",
          appLabel: "KakaoTalk",
          title: "Old",
          text: "Before",
          packageName: "com.kakao.talk",
          postedAtMs: 1,
        },
      ],
      {
        id: "same",
        appLabel: "KakaoTalk",
        title: "New",
        text: "After",
        packageName: "com.kakao.talk",
        postedAtMs: 2,
      },
    );

    expect(queue).toHaveLength(1);
    expect(queue[0].title).toBe("New");
  });

  it("prunes expired notifications by ttl", () => {
    const queue: OverlayNotification[] = [
      {
        id: "fresh",
        appLabel: "Telegram",
        title: "Fresh",
        text: "Hello",
        packageName: "org.telegram.messenger",
        postedAtMs: 9_000,
      },
      {
        id: "old",
        appLabel: "Telegram",
        title: "Old",
        text: "Hello",
        packageName: "org.telegram.messenger",
        postedAtMs: 1_000,
      },
    ];

    expect(pruneOverlayNotifications(queue, 10_000, 5_000).map((item) => item.id)).toEqual([
      "fresh",
    ]);
  });
});

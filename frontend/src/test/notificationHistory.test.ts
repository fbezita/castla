import { describe, expect, it } from "vitest";
import {
  formatNotificationText,
  groupNotificationHistory,
  isNotificationAllowed,
  pruneOverlayNotifications,
  upsertNotificationHistory,
  type OverlayNotification,
} from "../lib/notificationOverlay";

function notification(id: string, postedAtMs: number): OverlayNotification {
  return {
    id,
    appLabel: "Custom app",
    title: id,
    text: "Hello",
    packageName: "com.example.custom",
    postedAtMs,
  };
}

describe("notification history", () => {
  it("uses the frontend-selected package list", () => {
    const item = notification("selected", 1);

    expect(isNotificationAllowed(item, ["com.example.custom"])).toBe(true);
    expect(isNotificationAllowed(item, ["com.kakao.talk"])).toBe(false);
  });

  it("localizes image notification text without changing the payload", () => {
    const imageOnly = { ...notification("photo", 1), text: "", hasImage: true };
    const imageWithText = { ...notification("caption", 2), text: "Caption", hasImage: true };

    expect(formatNotificationText(imageOnly, "ko")).toBe("사진이 포함된 알림");
    expect(formatNotificationText(imageOnly, "en")).toBe("Notification contains an image");
    expect(formatNotificationText(imageWithText, "ko")).toBe("사진이 포함된 알림 · Caption");
    expect(formatNotificationText(notification("plain", 3), "en")).toBe("Hello");
  });

  it("groups notifications by app and conversation in latest order", () => {
    const history = [
      { ...notification("kakao-new", 5), packageName: "com.kakao.talk", appLabel: "KakaoTalk", title: "Family" },
      { ...notification("telegram", 4), packageName: "org.telegram.messenger", appLabel: "Telegram", title: "Team" },
      { ...notification("kakao-other", 3), packageName: "com.kakao.talk", appLabel: "KakaoTalk", title: "Work" },
      { ...notification("kakao-old", 2), packageName: "com.kakao.talk", appLabel: "KakaoTalk", title: "Family" },
    ];

    const groups = groupNotificationHistory(history);

    expect(groups.map((group) => group.packageName)).toEqual([
      "com.kakao.talk",
      "org.telegram.messenger",
    ]);
    expect(groups[0].count).toBe(3);
    expect(groups[0].conversations.map((conversation) => conversation.title)).toEqual(["Family", "Work"]);
    expect(groups[0].conversations[0].items.map((item) => item.id)).toEqual(["kakao-new", "kakao-old"]);
    expect(groups[0].appLabel).toBe("KakaoTalk");
  });

  it("keeps history after the transient overlay expires", () => {
    const history = upsertNotificationHistory([], notification("old", 1_000));

    expect(pruneOverlayNotifications(history, 10_000, 5_000)).toEqual([]);
    expect(history.map((item) => item.id)).toEqual(["old"]);
  });

  it("keeps each notification event with the same Android key", () => {
    const history = upsertNotificationHistory(
      [notification("same", 1)],
      { ...notification("same", 2), title: "Updated" },
    );

    expect(history).toHaveLength(2);
    expect(history[0].title).toBe("Updated");
    expect(history[1].title).toBe("same");
  });
});

import { describe, expect, it, vi } from "vitest";
import {
  formatNotificationText,
  formatNotificationTime,
  groupNotificationHistory,
  isNotificationAllowed,
  notificationConversationKey,
  notificationMetaLayout,
  notificationEventKey,
  pruneOverlayNotifications,
  shouldShowConversationMessageCount,
  stopNotificationPointerPropagation,
  shouldShowNotificationSender,
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
  it("hides a sender that duplicates the personal conversation title", () => {
    expect(shouldShowNotificationSender("이상미", "이상미")).toBe(false);
    expect(shouldShowNotificationSender("이상미", "가족방")).toBe(true);
    expect(shouldShowNotificationSender(undefined, "가족방")).toBe(false);
  });

  it("keeps personal-chat time inline while group-chat metadata uses its own row", () => {
    expect(notificationMetaLayout("이상미", "이상미")).toBe("inline-time");
    expect(notificationMetaLayout("드림/615", "청라센텀대광로제비앙")).toBe("sender-row");
  });

  it("shows conversation counts only when an app contains multiple conversations", () => {
    expect(shouldShowConversationMessageCount(1)).toBe(false);
    expect(shouldShowConversationMessageCount(2)).toBe(true);
  });

  it("builds a stable conversation key without merging rooms from different apps", () => {
    expect(notificationConversationKey("com.kakao.talk", "가족방"))
      .not.toBe(notificationConversationKey("org.telegram.messenger", "가족방"));
    expect(notificationConversationKey("com.kakao.talk", "가족방"))
      .toBe(notificationConversationKey("com.kakao.talk", "가족방"));
  });

  it("formats each notification time using the selected language", () => {
    const postedAtMs = new Date(2026, 7, 10, 12, 14).getTime();

    expect(formatNotificationTime(postedAtMs, "ko")).toMatch(/오전|오후/);
    expect(formatNotificationTime(postedAtMs, "ko")).toContain("12:14");
    expect(formatNotificationTime(postedAtMs, "en")).toMatch(/AM|PM/);
  });

  it("uses a stable event key while distinguishing repeated Android notification keys", () => {
    expect(notificationEventKey(notification("same", 1))).toBe("same:1");
    expect(notificationEventKey(notification("same", 2))).toBe("same:2");
  });

  it("keeps notification controls from reaching the mirrored viewport", () => {
    const stopPropagation = vi.fn();

    stopNotificationPointerPropagation({ stopPropagation });

    expect(stopPropagation).toHaveBeenCalledOnce();
  });

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

<script lang="ts">
  import { t, type Language } from "../lib/i18n";

  let {
    language,
    enabled,
    historyCount,
    onToggle,
    onOpenHistory,
  } = $props<{
    language: Language;
    enabled: boolean;
    historyCount: number;
    onToggle: () => void;
    onOpenHistory: () => void;
  }>();
</script>

<div class="notification-settings">
  <strong>{language === "ko" ? "알림 표시" : "Notifications"}</strong>
  <button class:active={enabled} onclick={onToggle}>
    {enabled ? (language === "ko" ? "켜짐" : "On") : (language === "ko" ? "꺼짐" : "Off")}
  </button>
  <button disabled={historyCount === 0} onclick={onOpenHistory}>
    {t(language, "notificationHistoryOpen")} ({historyCount})
  </button>
</div>

<style>
  .notification-settings {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }

  .notification-settings strong {
    color: rgba(255, 255, 255, 0.78);
    font-size: 12px;
    white-space: nowrap;
  }

  button {
    border: 1px solid rgba(255, 255, 255, 0.14);
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.07);
    color: rgba(255, 255, 255, 0.82);
    padding: 7px 10px;
    font: inherit;
    font-size: 12px;
    font-weight: 700;
  }

  button.active {
    border-color: rgba(105, 240, 174, 0.55);
    color: #69f0ae;
  }

  button:disabled {
    opacity: 0.35;
  }
</style>

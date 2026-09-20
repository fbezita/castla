<script lang="ts">
  import LauncherNotificationSettings from "./LauncherNotificationSettings.svelte";
  import {
    OVERLAY_UI_SCALE_MAX,
    OVERLAY_UI_SCALE_MIN,
    OVERLAY_UI_SCALE_STEP,
    type OverlayUiScalePreference,
  } from "../utils/overlayUiScalePreference";

  const FRONTEND_GUIDE_URL = "https://github.com/fbezita/castla/blob/master/docs/frontend-user-guide.md";

  let {
    language,
    uiScalePreference,
    notificationEnabled,
    notificationHistoryCount,
    onLanguageChange,
    onUiScaleChange,
    onOpenDiagnostics,
    onToggleNotification,
    onOpenNotificationHistory,
  } = $props<{
    language: "ko" | "en";
    uiScalePreference: OverlayUiScalePreference;
    notificationEnabled: boolean;
    notificationHistoryCount: number;
    onLanguageChange: (language: "ko" | "en") => void;
    onUiScaleChange: (preference: OverlayUiScalePreference) => void;
    onOpenDiagnostics: () => void;
    onToggleNotification: () => void;
    onOpenNotificationHistory: () => void;
  }>();

  const label = (ko: string, en: string) => language === "ko" ? ko : en;
</script>

<section class="drawer-settings">
  <div class="settings-section">
    <div class="settings-inline-row">
      <div class="settings-inline-group">
        <strong>{label("언어", "Language")}</strong>
        <div class="lang-switcher">
          <button class:active={language === "ko"} onclick={() => onLanguageChange("ko")}>KO</button>
          <button class:active={language === "en"} onclick={() => onLanguageChange("en")}>EN</button>
        </div>
      </div>
      <div class="settings-inline-group diagnostics-inline-group">
        <strong>{label("진단", "Diagnostics")}</strong>
        <button class="diag-toggle-btn" onclick={onOpenDiagnostics}>{label("열기", "Open")}</button>
      </div>
      <LauncherNotificationSettings
        {language}
        enabled={notificationEnabled}
        historyCount={notificationHistoryCount}
        onToggle={onToggleNotification}
        onOpenHistory={onOpenNotificationHistory}
      />
    </div>
  </div>

  <div class="settings-section">
    <div class="settings-title-row">
      <strong>UI Scale</strong>
      <span>{Math.round(uiScalePreference * 100)}%</span>
    </div>
    <div class="scale-slider">
      <input
        type="range"
        min={OVERLAY_UI_SCALE_MIN}
        max={OVERLAY_UI_SCALE_MAX}
        step={OVERLAY_UI_SCALE_STEP}
        value={uiScalePreference}
        oninput={(event) => onUiScaleChange(Number(event.currentTarget.value))}
      />
      <div class="scale-slider-labels"><span>100%</span><span>150%</span><span>200%</span></div>
    </div>
  </div>

  <div class="settings-section">
    <div class="settings-title-row"><strong>Frontend Guide</strong><span>Launcher help</span></div>
    <a class="settings-link-btn" href={FRONTEND_GUIDE_URL} target="_blank" rel="noopener noreferrer">
      Open Usage Guide
    </a>
  </div>
</section>

<style>
  .drawer-settings { margin: 6px 12px 8px; padding: 10px 12px; border: 1px solid rgba(255,255,255,.06); border-radius: 14px; background: linear-gradient(180deg, rgba(255,255,255,.03), rgba(255,255,255,.01)), rgba(11,14,24,.72); display: grid; gap: 10px; }
  .settings-section { display: grid; gap: 8px; }
  .settings-section + .settings-section { padding-top: 10px; border-top: 1px solid rgba(255,255,255,.05); }
  .settings-inline-row, .settings-title-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
  .settings-inline-group { display: flex; align-items: center; gap: 8px; min-width: 0; }
  .settings-inline-group strong, .settings-title-row strong { font-size: 12px; color: #f8fafc; white-space: nowrap; }
  .diagnostics-inline-group { margin-left: auto; }
  .settings-title-row span { font-size: 11px; color: #94a3b8; }
  .lang-switcher { display: flex; align-items: center; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.06); border-radius: 8px; padding: 2px; gap: 1px; }
  .lang-switcher button { border: 0; background: transparent; color: rgba(255,255,255,.45); font-size: 9px; font-weight: 800; height: 18px; padding: 0 6px; border-radius: 6px; cursor: pointer; }
  .lang-switcher button.active { background: rgba(0,229,255,.16); color: #7cf1ff; border: 1px solid rgba(0,229,255,.2); }
  .diag-toggle-btn { border: 1px solid rgba(255,255,255,.08); background: rgba(255,255,255,.04); color: rgb(255 255 255 / .65); font-size: 11px; height: 24px; padding: 0 10px; cursor: pointer; border-radius: 999px; }
  .scale-slider { display: grid; gap: 8px; }
  .scale-slider input { width: 100%; margin: 0; accent-color: #00e5ff; }
  .scale-slider-labels { display: flex; justify-content: space-between; gap: 10px; color: #94a3b8; font-size: 11px; font-weight: 700; }
  .settings-link-btn { color: #7cf1ff; font-size: 12px; font-weight: 700; text-decoration: none; }
</style>

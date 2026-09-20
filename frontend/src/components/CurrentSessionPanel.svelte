<script lang="ts">
  import type { LaunchStage } from "../lib/frontendExperience";

  let {
    language,
    primaryLabel,
    secondaryLabel,
    launchStage,
    onChooseApp,
    onRecent,
    onAddSecondary,
    onRetry,
  } = $props<{
    language: "ko" | "en";
    primaryLabel: string;
    secondaryLabel: string;
    launchStage: LaunchStage;
    onChooseApp: () => void;
    onRecent: () => void;
    onAddSecondary: () => void;
    onRetry: () => void;
  }>();

  const label = (ko: string, en: string) => language === "ko" ? ko : en;
  let stageText = $derived(
    launchStage === "opening"
      ? label("앱 여는 중", "Opening app")
      : launchStage === "connecting"
        ? label("화면 연결 중", "Connecting screen")
        : launchStage === "failed"
          ? label("실행하지 못했습니다", "Could not launch")
          : launchStage === "ready"
            ? label("실행 완료", "Running")
            : label("실행 대기", "Ready"),
  );
</script>

<section class="session-panel">
  <div class:failed={launchStage === "failed"} class:busy={launchStage === "opening" || launchStage === "connecting"} class="session-state">
    <span></span><strong>{stageText}</strong>
  </div>

  {#if primaryLabel}
    <div class="active-apps">
      <div class="active-app primary"><small>{label("현재 앱", "CURRENT APP")}</small><strong>{primaryLabel}</strong></div>
      {#if secondaryLabel}
        <div class="active-app"><small>{label("보조 앱", "SECOND APP")}</small><strong>{secondaryLabel}</strong></div>
      {/if}
    </div>
    <div class="session-actions">
      <button class="primary" onclick={onChooseApp}>{label("다른 앱으로 전환", "Switch app")}</button>
      {#if !secondaryLabel}<button onclick={onAddSecondary}>{label("분할 화면에 앱 추가", "Add app to split view")}</button>{/if}
      <button onclick={onRecent}>{label("최근 앱", "Recent apps")}</button>
      {#if launchStage === "failed"}<button class="retry" onclick={onRetry}>{label("다시 시도", "Try again")}</button>{/if}
    </div>
  {:else}
    <div class="empty-session">
      <strong>{label("실행 중인 앱이 없습니다", "No app is running")}</strong>
      <p>{label("앱을 선택하면 이곳에서 현재 세션과 실행 상태를 관리할 수 있습니다.", "Choose an app to manage the current session and launch status here.")}</p>
      <button onclick={onChooseApp}>{label("앱 선택", "Choose app")}</button>
    </div>
  {/if}
</section>

<style>
  .session-panel { margin: 0 12px 14px; display: grid; gap: 12px; }
  .session-state { min-height: 38px; display: flex; align-items: center; gap: 9px; padding: 0 13px; border: 1px solid rgba(105,240,174,.2); border-radius: 12px; background: rgba(105,240,174,.07); color: #8bf5b9; }
  .session-state span { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
  .session-state.busy { color: #7cf1ff; border-color: rgba(0,229,255,.24); background: rgba(0,229,255,.08); }
  .session-state.busy span { animation: pulse 1s ease-in-out infinite; }
  .session-state.failed { color: #ff9a9a; border-color: rgba(255,90,90,.3); background: rgba(255,70,70,.08); }
  .session-state strong { font-size: 12px; }
  .active-apps { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 8px; }
  .active-app { min-height: 74px; display: grid; align-content: center; gap: 5px; padding: 12px 14px; border: 1px solid rgba(255,255,255,.08); border-radius: 14px; background: rgba(255,255,255,.035); }
  .active-app.primary { border-color: rgba(0,229,255,.28); background: rgba(0,229,255,.07); }
  .active-app small { color: #7d899d; font-size: 9px; font-weight: 900; letter-spacing: .08em; }
  .active-app strong { color: #f8fafc; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .session-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
  .session-actions button, .empty-session button { min-height: 40px; padding: 0 10px; border: 1px solid rgba(255,255,255,.09); border-radius: 11px; background: rgba(255,255,255,.045); color: #d8deea; font-size: 12px; font-weight: 800; cursor: pointer; }
  .session-actions button.primary, .empty-session button { border-color: rgba(0,229,255,.32); background: rgba(0,229,255,.12); color: #dffbff; }
  .session-actions button.retry { border-color: rgba(255,150,80,.35); color: #ffc49f; }
  .empty-session { min-height: 210px; padding: 26px; display: grid; place-items: center; align-content: center; gap: 12px; text-align: center; border: 1px solid rgba(255,255,255,.08); border-radius: 18px; background: rgba(255,255,255,.025); color: #f8fafc; }
  .empty-session p { max-width: 330px; margin: 0; color: #8f9bad; font-size: 12px; line-height: 1.5; }
  .empty-session button { min-width: 128px; }
  @keyframes pulse { 50% { opacity: .25; transform: scale(.75); } }
</style>

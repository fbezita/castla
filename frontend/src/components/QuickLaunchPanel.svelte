<script lang="ts">
  interface AppInfo {
    packageName: string;
    label: string;
    isPair?: boolean;
    apps?: [string, string];
  }

  let {
    language,
    apps,
    notificationCounts,
    onLaunch,
    onBrowse,
    onRecent,
  } = $props<{
    language: "ko" | "en";
    apps: AppInfo[];
    notificationCounts: Record<string, number>;
    onLaunch: (app: AppInfo) => void;
    onBrowse: () => void;
    onRecent: () => void;
  }>();

  const label = (ko: string, en: string) => language === "ko" ? ko : en;
  const iconPackage = (app: AppInfo) => app.isPair ? app.apps?.[0] ?? app.packageName : app.packageName;
  const badgeCount = (app: AppInfo) => app.isPair
    ? (app.apps ?? []).reduce((sum, pkg) => sum + (notificationCounts[pkg] ?? 0), 0)
    : notificationCounts[app.packageName] ?? 0;
</script>

<section class="quick-home">
  <div class="quick-heading">
    <div>
      <strong>{label("빠른 실행", "Quick launch")}</strong>
      <p>{label("즐겨찾기와 최근 사용 앱", "Favorites and recently used apps")}</p>
    </div>
    <span>{apps.length}</span>
  </div>

  {#if apps.length > 0}
    <div class="quick-grid">
      {#each apps as app (app.packageName)}
        <button class="quick-app" onclick={() => onLaunch(app)} title={app.label}>
          <span class="quick-icon-wrap">
            <img src={`/api/icon?pkg=${encodeURIComponent(iconPackage(app))}`} alt="" draggable="false" />
            {#if badgeCount(app) > 0}
              <span class="quick-badge">{badgeCount(app) > 9 ? "9+" : badgeCount(app)}</span>
            {/if}
          </span>
          <span class="quick-label">{app.label}</span>
        </button>
      {/each}
    </div>
  {:else}
    <div class="quick-empty">
      <strong>{label("아직 바로가기가 없습니다", "No shortcuts yet")}</strong>
      <p>{label("앱 보관함에서 별을 눌러 자주 쓰는 앱을 추가하세요.", "Star frequently used apps in Browse to add them here.")}</p>
    </div>
  {/if}

  <div class="quick-actions">
    <button class="primary" onclick={onBrowse}>{label("모든 앱 보기", "Browse all apps")}</button>
    <button onclick={onRecent}>{label("최근 사용", "Recent")}</button>
  </div>
  <p class="safety-note">{label("영상과 메시지 조작은 정차 중에만 사용하세요.", "Use video and messaging controls only while parked.")}</p>
</section>

<style>
  .quick-home { margin: 0 2px 14px; padding: 14px; display: grid; gap: 14px; border: 1px solid rgba(139,196,255,.16); border-radius: 18px; background: radial-gradient(circle at top left, rgba(0,229,255,.09), transparent 50%), rgba(255,255,255,.025); }
  .quick-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
  .quick-heading strong { color: #f8fafc; font-size: 16px; }
  .quick-heading p { margin: 3px 0 0; color: #8591a3; font-size: 10px; }
  .quick-heading > span { min-width: 26px; height: 22px; display: grid; place-items: center; border-radius: 999px; background: rgba(0,229,255,.12); color: #a8f7ff; font-size: 10px; font-weight: 900; }
  .quick-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
  .quick-app { min-width: 0; min-height: 88px; padding: 10px 5px 8px; display: grid; justify-items: center; align-content: center; gap: 7px; border: 1px solid rgba(255,255,255,.08); border-radius: 14px; background: rgba(255,255,255,.04); color: #eef4ff; cursor: pointer; }
  .quick-app:hover, .quick-app:focus-visible { border-color: rgba(0,229,255,.38); background: rgba(0,229,255,.09); outline: none; }
  .quick-icon-wrap { position: relative; width: 42px; height: 42px; }
  .quick-icon-wrap img { width: 42px; height: 42px; object-fit: contain; border-radius: 10px; }
  .quick-badge { position: absolute; top: -6px; right: -8px; min-width: 18px; height: 18px; padding: 0 4px; display: grid; place-items: center; border: 2px solid #141927; border-radius: 999px; background: #ff4d63; color: #fff; font-size: 9px; font-weight: 900; box-sizing: border-box; }
  .quick-label { width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; font-weight: 800; }
  .quick-empty { min-height: 122px; display: grid; place-content: center; gap: 8px; text-align: center; color: #dce6f5; }
  .quick-empty p { margin: 0; color: #7d899d; font-size: 11px; line-height: 1.45; }
  .quick-actions { display: grid; grid-template-columns: 1.35fr 1fr; gap: 8px; }
  .quick-actions button { min-height: 40px; border: 1px solid rgba(255,255,255,.1); border-radius: 11px; background: rgba(255,255,255,.05); color: #d8deea; font-size: 11px; font-weight: 850; cursor: pointer; }
  .quick-actions button.primary { border-color: rgba(0,229,255,.3); background: rgba(0,229,255,.11); color: #dffbff; }
  .safety-note { margin: -4px 0 0; color: #9aa4b3; font-size: 9px; text-align: center; }
</style>

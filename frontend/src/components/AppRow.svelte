<script lang="ts">
  import { getAppPairPreviewPackages, type LayoutMode, type AppPair } from "../lib/appPair";

  // Type definition for application details
  interface AppInfo extends Partial<AppPair> {
    packageName: string;
    label: string;
    componentName?: string;
    category?: string;
    isWeb?: boolean;
    isPair?: boolean;
    layoutMode?: LayoutMode;
  }

  // Strict Svelte 5 Props using $props Rune
  let {
    app,
    activeTab,
    isActive,
    isDragActive,
    recentMeta,
    notificationCount = 0,
    onLaunch,
    onOpenEdit,
    onStartPress,
    onPointerMove,
    onPointerUp,
    onPointerCancel
  } = $props<{
    app: AppInfo;
    activeTab: "autorun" | "starred" | "recent" | "notifications" | "browse";
    isActive: boolean;
    isDragActive: boolean;
    recentMeta: string;
    notificationCount?: number;
    onLaunch: (app: AppInfo) => void;
    onOpenEdit: (app: AppInfo) => void;
    onStartPress?: (event: PointerEvent, app: AppInfo, element: HTMLElement) => void;
    onPointerMove?: (event: PointerEvent) => void;
    onPointerUp?: (event: PointerEvent) => void;
    onPointerCancel?: (event: PointerEvent) => void;
  }>();

  // Handle keydown keyboard accessibility
  function handleKeyDown(event: KeyboardEvent) {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onLaunch(app);
    }
  }

  // Handle pointer down to register gesture tracker initiation
  function handlePointerDown(event: PointerEvent) {
    if (onStartPress) {
      const target = event.currentTarget as HTMLElement;
      onStartPress(event, app, target);
    }
  }

  function previewPackages(target: AppInfo): string[] {
    if (!target.isPair) return [];
    return getAppPairPreviewPackages(target as any);
  }
</script>

<div
  class="launcher-row"
  class:priority={activeTab === "autorun"}
  class:active-app={isActive}
  class:drag-active={isDragActive}
  title={app.label}
  onkeydown={handleKeyDown}
  onpointerdown={handlePointerDown}
  onpointermove={onPointerMove}
  onpointerup={onPointerUp}
  onpointercancel={onPointerCancel}
  oncontextmenu={(event) => event.preventDefault()}
  role="button"
  tabindex="0"
>
  {#if isActive}
    <span class="active-indicator" title="Currently running" aria-label="Currently running"></span>
  {/if}
  <div class="launcher-icon-wrap">
    {#if app.isPair && previewPackages(app).length > 1}
      <div class="pair-icons row-pair-icon">
      <img
        class="app-pair-icon-left"
        src={`/api/icon?pkg=${encodeURIComponent(previewPackages(app)[0])}`}
        alt=""
        loading="lazy"
        draggable="false"
      />
      <img
        class="app-pair-icon-right"
        src={`/api/icon?pkg=${encodeURIComponent(previewPackages(app)[1])}`}
        alt=""
        loading="lazy"
        draggable="false"
      />
      </div>
    {:else if app.isPair && previewPackages(app).length === 1}
      <img
        class="launcher-row-icon"
        src={`/api/icon?pkg=${encodeURIComponent(previewPackages(app)[0])}`}
        alt=""
        loading="lazy"
        draggable="false"
      />
    {:else}
      <img
        class="launcher-row-icon"
        src={`/api/icon?pkg=${encodeURIComponent(app.packageName)}`}
        alt=""
        loading="lazy"
        draggable="false"
      />
    {/if}
    {#if notificationCount > 0}
      <span class="app-notification-badge">{notificationCount > 9 ? "9+" : notificationCount}</span>
    {/if}
  </div>

  <div class="launcher-row-text">
    <span class="launcher-row-title">{app.label}</span>
    {#if activeTab === "recent"}
      <span class="launcher-row-subtitle">{recentMeta}</span>
    {:else if activeTab === "autorun"}
      <span class="launcher-row-subtitle">Ready on startup</span>
    {/if}
  </div>

  {#if app.isPair}
    <div class="row-actions">
      <button
        class="pair-settings"
        title="Pair settings"
        onclick={(event) => {
          event.stopPropagation();
          onOpenEdit(app);
        }}
      >
        ⚙️
      </button>
    </div>
  {/if}
</div>

<style>
  .launcher-row {
    position: relative;
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    justify-items: center;
    align-content: center;
    gap: 6px;
    min-width: 0;
    aspect-ratio: 1 / 1;
    min-height: 0;
    padding: 8px 4px;
    box-sizing: border-box;
    border: 1px solid rgb(255 255 255 / 0.09);
    border-radius: 14px;
    background: linear-gradient(
        180deg,
        rgb(255 255 255 / 0.05),
        rgb(255 255 255 / 0.02)
      ),
      rgb(18 22 34 / 0.88);
    color: white;
    text-align: left;
    transition:
      transform 0.18s cubic-bezier(0.4, 0, 0.2, 1),
      border-color 0.18s cubic-bezier(0.4, 0, 0.2, 1),
      background 0.18s cubic-bezier(0.4, 0, 0.2, 1),
      box-shadow 0.18s cubic-bezier(0.4, 0, 0.2, 1);
    user-select: none;
    touch-action: pan-y;
    -webkit-user-drag: none;
  }

  .launcher-row.priority {
    min-height: 0;
    padding: 8px 4px;
    background: linear-gradient(
        180deg,
        rgb(255 255 255 / 0.08),
        rgb(255 255 255 / 0.03)
      ),
      rgb(22 27 40 / 0.95);
  }

  .launcher-row.active-app {
    border-color: rgb(74 222 128 / 0.38);
    background: linear-gradient(180deg, rgb(255 255 255 / 0.055), rgb(255 255 255 / 0.02)), rgb(18 22 34 / 0.92);
    box-shadow: inset 0 0 0 1px rgb(74 222 128 / 0.08);
  }

  .launcher-row.drag-active {
    touch-action: none !important;
  }

  .launcher-row:hover,
  .launcher-row:focus-visible {
    border-color: rgb(139 196 255 / 0.42);
    background: linear-gradient(
        180deg,
        rgb(139 196 255 / 0.12),
        rgb(255 255 255 / 0.05)
      ),
      rgb(20 24 36 / 0.98);
    box-shadow: 0 8px 24px rgb(0 0 0 / 0.25);
    transform: translateY(-1px);
    outline: none;
  }

  .launcher-row-text {
    width: 100%;
    min-width: 0;
    display: grid;
    gap: 2px;
    padding: 0;
    text-align: center;
  }

  .row-actions {
    position: absolute;
    top: 5px;
    right: 5px;
    display: flex;
    align-items: center;
    justify-self: auto;
    gap: 3px;
    margin: 0;
  }

  .launcher-row-title {
    display: block;
    overflow: hidden;
    width: 100%;
    font-size: 11px;
    font-weight: 800;
    line-height: 1.2;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .launcher-row-subtitle {
    color: #8f96a4;
    overflow: hidden;
    font-size: 8px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .active-indicator { position: absolute; top: 8px; left: 8px; width: 7px; height: 7px; border: 2px solid rgb(18 22 34 / .95); border-radius: 999px; background: #4ade80; box-shadow: 0 0 7px rgb(74 222 128 / .45); box-sizing: content-box; }

  .launcher-row-icon {
    width: 42px;
    height: 42px;
    object-fit: contain;
    border-radius: 8px;
  }

  .launcher-icon-wrap { position: relative; width: 46px; height: 44px; display: grid; place-items: center; }
  .app-notification-badge { position: absolute; top: -3px; right: -4px; min-width: 17px; height: 17px; padding: 0 3px; display: grid; place-items: center; box-sizing: border-box; border: 2px solid #171b27; border-radius: 999px; background: #ff4d63; color: white; font-size: 8px; font-weight: 900; }

  .row-pair-icon {
    position: relative;
    width: 42px;
    height: 42px;
  }

  .app-pair-icon-left,
  .app-pair-icon-right {
    position: absolute;
    width: 29px;
    height: 29px;
    object-fit: contain;
    border-radius: 6px;
    background: rgb(18 22 34 / 0.92);
    padding: 2px;
    box-shadow: 0 4px 8px rgb(0 0 0 / 0.2);
  }

  .app-pair-icon-left {
    left: 0;
    top: 7px;
    z-index: 1;
  }

  .app-pair-icon-right {
    left: 17px;
    top: 7px;
    z-index: 2;
  }

  .pair-settings {
    border: 0;
    color: white;
    background: transparent;
    cursor: pointer;
  }

  .pair-settings:hover {
    background: rgb(255 255 255 / 0.08);
    transform: scale(1.1);
  }

  .pair-settings {
    width: 18px;
    height: 18px;
    border-radius: 50%;
    color: rgb(255 255 255 / 0.72);
    font-size: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    line-height: 1;
  }

</style>

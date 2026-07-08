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
    isStarred,
    isAutorun,
    isNotification,
    isDragActive,
    recentMeta,
    onLaunch,
    onToggleStar,
    onToggleAutorun,
    onToggleNotification,
    onOpenEdit,
    onStartPress,
    onPointerMove,
    onPointerUp,
    onPointerCancel
  } = $props<{
    app: AppInfo;
    activeTab: "autorun" | "starred" | "recent" | "notifications" | "browse";
    isStarred: boolean;
    isAutorun: boolean;
    isNotification: boolean;
    isDragActive: boolean;
    recentMeta: string;
    onLaunch: (app: AppInfo) => void;
    onToggleStar: (pkg: string) => void;
    onToggleAutorun: (app: AppInfo) => void;
    onToggleNotification: (pkg: string) => void;
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

  <div class="launcher-row-text">
    <span class="launcher-row-title">{app.label}</span>
    {#if activeTab === "recent"}
      <span class="launcher-row-subtitle">{recentMeta}</span>
    {:else if activeTab === "autorun"}
      <span class="launcher-row-subtitle">Ready on startup</span>
    {/if}
  </div>

  <div class="row-actions">
    <button
      class="star"
      class:active={isStarred}
      title="Toggle star"
      onclick={(event) => {
        event.stopPropagation();
        onToggleStar(app.packageName);
      }}
    >
      ★
    </button>

    <button
      class="auto-pill"
      class:active={isAutorun}
      title="Toggle auto-run"
      onclick={(event) => {
        event.stopPropagation();
        onToggleAutorun(app);
      }}
    >
      AUTO
    </button>

    {#if !app.isPair}
      <button
        class="noti-btn"
        class:active={isNotification}
        title="Toggle notifications"
        onclick={(event) => {
          event.stopPropagation();
          onToggleNotification(app.packageName);
        }}
      >
        🔔
      </button>
    {/if}

    {#if app.isPair}
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
    {/if}
  </div>
</div>

<style>
  .launcher-row {
    display: grid;
    grid-template-columns: 42px minmax(0, 1fr) auto;
    align-items: center;
    gap: 8px;
    min-height: 50px;
    padding: 8px 10px;
    border: 1px solid rgb(255 255 255 / 0.06);
    border-radius: 12px;
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
    min-height: 54px;
    padding: 9px 10px;
    background: linear-gradient(
        180deg,
        rgb(255 255 255 / 0.08),
        rgb(255 255 255 / 0.03)
      ),
      rgb(22 27 40 / 0.95);
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
    min-width: 0;
    display: grid;
    gap: 2px;
    padding-left: 4px;
  }

  .row-actions {
    display: flex;
    align-items: center;
    justify-self: end;
    gap: 6px;
    margin-left: 10px;
  }

  .launcher-row-title {
    display: -webkit-box;
    overflow: hidden;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    font-size: 14px;
    font-weight: 700;
    line-height: 1.25;
    word-break: keep-all;
    overflow-wrap: normal;
    text-overflow: ellipsis;
  }

  .launcher-row-subtitle {
    color: #8f96a4;
    font-size: 10px;
    font-weight: 600;
  }

  .launcher-row-icon {
    width: 36px;
    height: 36px;
    object-fit: contain;
    border-radius: 8px;
  }

  .row-pair-icon {
    position: relative;
    width: 42px;
    height: 34px;
  }

  .app-pair-icon-left,
  .app-pair-icon-right {
    position: absolute;
    width: 24px;
    height: 24px;
    object-fit: contain;
    border-radius: 6px;
    background: rgb(18 22 34 / 0.92);
    padding: 2px;
    box-shadow: 0 4px 8px rgb(0 0 0 / 0.2);
  }

  .app-pair-icon-left {
    left: 0;
    top: 5px;
    z-index: 1;
  }

  .app-pair-icon-right {
    left: 16px;
    top: 5px;
    z-index: 2;
  }

  .star,
  .auto-pill,
  .pair-settings {
    border: 0;
    color: white;
    background: transparent;
    cursor: pointer;
  }

  .star,
  .auto-pill {
    width: 22px;
    height: 22px;
    border-radius: 50%;
    color: rgb(255 255 255 / 0.88);
    font-size: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    transition:
      background 0.16s ease,
      color 0.16s ease,
      transform 0.16s ease;
  }

  .star:hover,
  .auto-pill:hover,
  .pair-settings:hover {
    background: rgb(255 255 255 / 0.08);
    transform: scale(1.1);
  }

  .star.active {
    color: #ffd56a;
    text-shadow: 0 0 8px rgba(255, 213, 106, 0.4);
  }

  .auto-pill {
    width: auto;
    min-width: 0;
    padding: 0 6px;
    height: 18px;
    border: 1px solid rgb(255 112 67 / 0.16);
    border-radius: 999px;
    background: rgb(255 112 67 / 0.08);
    color: rgb(255 189 145 / 0.62);
    font-size: 9px;
    font-weight: 800;
    letter-spacing: 0.04em;
    justify-self: auto;
  }

  .auto-pill.active {
    color: #ffd0b7;
    background: rgb(255 112 67 / 0.18);
    border-color: rgb(255 112 67 / 0.32);
    box-shadow: 0 0 8px rgba(255, 112, 67, 0.2);
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

  .noti-btn {
    width: 22px;
    height: 22px;
    border-radius: 50%;
    color: rgb(255 255 255 / 0.4);
    font-size: 13px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 0;
    background: transparent;
    cursor: pointer;
    transition:
      background 0.16s ease,
      color 0.16s ease,
      transform 0.16s ease;
  }

  .noti-btn:hover {
    background: rgb(255 255 255 / 0.08);
    transform: scale(1.1);
  }

  .noti-btn.active {
    color: #00e5ff;
    text-shadow: 0 0 8px rgba(0, 229, 255, 0.4);
  }

</style>

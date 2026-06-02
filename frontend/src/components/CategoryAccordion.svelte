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

  // Type definition for category group
  interface CategoryGroup {
    title: string;
    key: string;
    color: string;
    items: AppInfo[];
  }

  // Strict Svelte 5 Props using $props Rune
  let {
    group,
    isExpanded,
    draggingApp,
    pairTarget,
    favorites,
    isAutorun,
    onToggle,
    onLaunch,
    onToggleStar,
    onToggleAutorun,
    onOpenEdit,
    onStartPress,
    onPointerMove,
    onPointerUp,
    onPointerCancel
  } = $props<{
    group: CategoryGroup;
    isExpanded: boolean;
    draggingApp: AppInfo | null;
    pairTarget: AppInfo | null;
    favorites: string[];
    isAutorun: (app: AppInfo) => boolean;
    onToggle: (key: string) => void;
    onLaunch: (app: AppInfo) => void;
    onToggleStar: (pkg: string) => void;
    onToggleAutorun: (app: AppInfo) => void;
    onOpenEdit: (app: AppInfo) => void;
    onStartPress: (event: PointerEvent, app: AppInfo, element: HTMLElement) => void;
    onPointerMove: (event: PointerEvent) => void;
    onPointerUp: (event: PointerEvent) => void;
    onPointerCancel: (event: PointerEvent) => void;
  }>();

  // Handle pointer down to notify the parent orchestrator of drag-and-drop initiation
  function handlePointerDown(event: PointerEvent, app: AppInfo) {
    const target = event.currentTarget as HTMLElement;
    onStartPress(event, app, target);
  }

  // Handle keyboard activation for in-vehicle keyboard accessibility
  function handleKeyDown(event: KeyboardEvent, app: AppInfo) {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onLaunch(app);
    }
  }

  function previewPackages(app: AppInfo): string[] {
    if (!app.isPair || !app.layoutMode) return [];
    return getAppPairPreviewPackages(app as any);
  }
</script>

<section class="browse-group" style={`--category-color: ${group.color}`}>
  <button
    class="browse-group-header"
    class:expanded={isExpanded}
    onclick={() => onToggle(group.key)}
  >
    <div class="browse-group-label">
      <span class="browse-chevron">▶</span>
      <span>{group.title}</span>
    </div>
    <span class="browse-count">{group.items.length}</span>
  </button>

  <div class="browse-list-wrapper" class:expanded={isExpanded}>
    <div class="browse-list">
      {#each group.items as app (app.packageName)}
        <div
          data-package-name={app.isPair ? undefined : app.packageName}
          class="split-app-item compact"
          class:pair-target={pairTarget?.packageName === app.packageName}
          class:merge-target={pairTarget?.packageName === app.packageName && draggingApp !== null}
          class:drag-source={draggingApp?.packageName === app.packageName}
          class:drag-active={draggingApp !== null}
          title={app.label}
          onpointerdown={(event) => handlePointerDown(event, app)}
          onpointermove={onPointerMove}
          onpointerup={onPointerUp}
          onpointercancel={onPointerCancel}
          onkeydown={(event) => handleKeyDown(event, app)}
          oncontextmenu={(event) => event.preventDefault()}
          role="button"
          tabindex="0"
        >
          {#if app.isPair && app.layoutMode && previewPackages(app).length > 1}
            <div class="pair-icons split-pair-icon">
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
          {:else if app.isPair && app.layoutMode && previewPackages(app).length === 1}
            <img
              class="split-app-icon"
              src={`/api/icon?pkg=${encodeURIComponent(previewPackages(app)[0])}`}
              alt=""
              loading="lazy"
              draggable="false"
            />
          {:else}
            <img
              class="split-app-icon"
              src={`/api/icon?pkg=${encodeURIComponent(app.packageName)}`}
              alt=""
              loading="lazy"
              draggable="false"
            />
          {/if}

          <div class="launch-main">
            <span>{app.label}</span>
          </div>

          <div class="row-actions">
            <button
              class="star"
              class:active={favorites.includes(app.packageName)}
              title="Star"
              onclick={(event) => {
                event.stopPropagation();
                onToggleStar(app.packageName);
              }}
            >
              ★
            </button>

            <button
              class="auto-pill"
              class:active={isAutorun(app)}
              title="Auto-run"
              onclick={(event) => {
                event.stopPropagation();
                onToggleAutorun(app);
              }}
            >
              AUTO
            </button>

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

          <!-- Merge preview overlays when dragging another app onto this app -->
          {#if pairTarget?.packageName === app.packageName && draggingApp}
            <div class="merge-preview" aria-hidden="true">
              <div class="merge-icon incoming">
                <img
                  src={`/api/icon?pkg=${encodeURIComponent(draggingApp.packageName)}`}
                  alt=""
                  draggable="false"
                />
              </div>
              <div class="merge-plus">+</div>
              <div class="merge-icon target">
                <img
                  src={`/api/icon?pkg=${encodeURIComponent(app.packageName)}`}
                  alt=""
                  draggable="false"
                />
              </div>
              <div class="merge-result">
                <img
                  class="merge-half left"
                  src={`/api/icon?pkg=${encodeURIComponent(draggingApp.packageName)}`}
                  alt=""
                  draggable="false"
                />
                <img
                  class="merge-half right"
                  src={`/api/icon?pkg=${encodeURIComponent(app.packageName)}`}
                  alt=""
                  draggable="false"
                />
              </div>
            </div>
          {/if}
        </div>
      {/each}
    </div>
  </div>
</section>

<style>
  .browse-group {
    border: 1px solid rgb(255 255 255 / 0.08);
    border-radius: 16px;
    background: rgb(255 255 255 / 0.04);
    overflow: hidden;
    transition: border-color 0.2s ease;
  }

  .browse-group:focus-within {
    border-color: rgba(255, 255, 255, 0.16);
  }

  .browse-group-header {
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 13px 14px;
    border: 0;
    background: transparent;
    color: #eef2f8;
    font-size: 15px;
    font-weight: 700;
    cursor: pointer;
    text-align: left;
    transition: background 0.2s ease;
  }

  .browse-group-header:hover {
    background: rgba(255, 255, 255, 0.02);
  }

  .browse-group-header.expanded {
    background: rgb(255 255 255 / 0.04);
    border-bottom: 1px solid rgb(255 255 255 / 0.06);
  }

  .browse-group-label {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .browse-chevron {
    color: var(--category-color, #8f96a4);
    font-size: 11px;
    display: inline-block;
    transition: transform 0.28s cubic-bezier(0.16, 1, 0.3, 1);
  }

  .browse-group-header.expanded .browse-chevron {
    transform: rotate(90deg);
  }

  .browse-count {
    color: #8f96a4;
    font-size: 13px;
    font-weight: 700;
  }

  .browse-list-wrapper {
    display: grid;
    grid-template-rows: 0fr;
    opacity: 0;
    visibility: hidden;
    overflow: hidden;
    transition:
      grid-template-rows 0.28s cubic-bezier(0.16, 1, 0.3, 1),
      opacity 0.22s ease,
      visibility 0.22s ease;
    will-change: grid-template-rows, opacity;
  }

  .browse-list-wrapper.expanded {
    grid-template-rows: 1fr;
    opacity: 1;
    visibility: visible;
  }

  .browse-list {
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 6px 10px 8px;
    background: rgba(0, 0, 0, 0.08);
  }

  .split-app-item {
    position: relative;
    display: grid;
    align-items: center;
    user-select: none;
    touch-action: pan-y;
    -webkit-user-drag: none;
    transition:
      transform 0.2s cubic-bezier(0.4, 0, 0.2, 1),
      box-shadow 0.2s cubic-bezier(0.4, 0, 0.2, 1),
      background 0.2s cubic-bezier(0.4, 0, 0.2, 1),
      border-color 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  }

  .split-app-item.compact {
    grid-template-columns: 40px minmax(0, 1fr) auto;
    min-height: 36px;
    padding: 4px 6px;
    border: 1px solid transparent;
    border-radius: 10px;
    background: transparent;
    color: white;
  }

  .split-app-item.compact:hover,
  .split-app-item.compact:focus-visible {
    background: rgba(255, 255, 255, 0.05);
    border-color: rgba(255, 255, 255, 0.08);
    box-shadow: none;
    outline: none;
  }

  .split-app-item.drag-source {
    opacity: 0.35;
    transform: scale(0.97);
  }

  .split-app-item.drag-active {
    touch-action: none !important;
  }

  .split-app-item.pair-target {
    border-color: #00e5ff;
    box-shadow:
      0 0 12px rgba(0, 229, 255, 0.18),
      inset 0 0 12px rgba(0, 229, 255, 0.1);
    background: rgba(0, 229, 255, 0.08) !important;
  }

  .split-app-item.merge-target {
    transform: scale(1.015);
  }

  .split-app-icon {
    width: 28px;
    height: 28px;
    object-fit: contain;
    border-radius: 6px;
    -webkit-user-drag: none;
    user-select: none;
  }

  .pair-icons {
    position: relative;
    width: 34px;
    height: 28px;
  }

  .app-pair-icon-left,
  .app-pair-icon-right {
    position: absolute;
    width: 18px;
    height: 18px;
    object-fit: contain;
    border-radius: 5px;
    background: rgb(18 22 34 / 0.92);
    padding: 1px;
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.2);
    -webkit-user-drag: none;
    user-select: none;
  }

  .app-pair-icon-left {
    left: 0;
    top: 5px;
    z-index: 1;
  }

  .app-pair-icon-right {
    left: 12px;
    top: 5px;
    z-index: 2;
  }

  .launch-main {
    min-width: 0;
    text-align: left;
    font-size: 13px;
    font-weight: 700;
    cursor: pointer;
    color: #e2e8f0;
    padding-left: 2px;
  }

  .row-actions {
    display: flex;
    align-items: center;
    justify-self: end;
    gap: 4px;
    margin-left: 8px;
  }

  .launch-main span {
    display: block;
    word-break: keep-all;
    overflow-wrap: normal;
    text-overflow: ellipsis;
    line-height: 1.15;
    white-space: nowrap;
    overflow: hidden;
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
    width: 20px;
    height: 20px;
    border-radius: 50%;
    color: rgb(255 255 255 / 0.72);
    font-size: 13px;
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
  }

  .auto-pill {
    width: auto;
    min-width: 0;
    padding: 0 5px;
    height: 16px;
    border: 1px solid rgb(255 112 67 / 0.16);
    border-radius: 999px;
    background: rgb(255 112 67 / 0.08);
    color: rgb(255 189 145 / 0.62);
    font-size: 8px;
    font-weight: 800;
    letter-spacing: 0.04em;
    justify-self: auto;
  }

  .auto-pill.active {
    color: #ffd0b7;
    background: rgb(255 112 67 / 0.18);
    border-color: rgb(255 112 67 / 0.32);
  }

  .pair-settings {
    width: 16px;
    height: 16px;
    border-radius: 50%;
    color: rgb(255 255 255 / 0.72);
    font-size: 11px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  /* Merge Preview Overlay styles */
  .merge-preview {
    position: absolute;
    inset: -1px;
    z-index: 10;
    border-radius: 12px;
    background: rgba(10, 15, 28, 0.96);
    border: 2px solid #00e5ff;
    box-shadow: 0 0 20px rgba(0, 229, 255, 0.35);
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    padding: 0 16px;
    animation: fadeIn 0.18s ease-out forwards;
  }

  .merge-icon {
    width: 32px;
    height: 32px;
    background: rgba(255, 255, 255, 0.05);
    border: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 2px;
  }

  .merge-icon img {
    width: 100%;
    height: 100%;
    object-fit: contain;
  }

  .merge-plus {
    color: #00e5ff;
    font-size: 16px;
    font-weight: 800;
  }

  .merge-result {
    position: relative;
    width: 40px;
    height: 32px;
    margin-left: auto;
  }

  .merge-half {
    position: absolute;
    width: 22px;
    height: 22px;
    object-fit: contain;
    border-radius: 6px;
    background: rgb(18 22 34 / 0.95);
    padding: 2px;
    border: 1px solid rgba(255, 255, 255, 0.15);
  }

  .merge-half.left {
    left: 0;
    top: 5px;
    z-index: 1;
  }

  .merge-half.right {
    left: 14px;
    top: 5px;
    z-index: 2;
    border-color: #00e5ff;
  }

  @keyframes fadeIn {
    from {
      opacity: 0;
      transform: scale(0.98);
    }
    to {
      opacity: 1;
      transform: scale(1);
    }
  }
</style>

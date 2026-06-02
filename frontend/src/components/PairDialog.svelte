<script lang="ts">
  import {
    getAppPairModeLabel,
    isValidAppPair,
    swapAppPairApps,
    type LayoutMode,
    type AppPair,
  } from "../lib/appPair";

  interface AppInfo {
    packageName: string;
    label: string;
    componentName?: string;
    category?: string;
    isWeb?: boolean;
    isPair?: boolean;
    layoutMode?: LayoutMode;
    apps?: [string, string];
    primaryApp?: string;
    secondaryApp?: string;
  }

  let {
    editingPair,
    apps,
    onCancel,
    onRemove,
    onSave
  } = $props<{
    editingPair: AppInfo;
    apps: AppInfo[];
    onCancel: () => void;
    onRemove: (pair: AppInfo) => void;
    onSave: (pair: AppPair) => void;
  }>();

  let availableApps = $derived(apps.filter((app: AppInfo) => !app.isPair));

  function createDraft(source: AppInfo, candidates: AppInfo[]): AppPair {
    if (source.apps && source.apps.length === 2) {
      return {
        apps: [source.apps[0], source.apps[1]],
        layoutMode: source.layoutMode ?? "split",
      };
    }
    // Handle migration from legacy primary/secondary states
    const appA = source.primaryApp ?? candidates[0]?.packageName ?? "";
    const appB = source.secondaryApp ?? candidates[1]?.packageName ?? "";
    return {
      apps: [appA, appB],
      layoutMode: source.layoutMode === "popup" ? "popup" : "split",
    };
  }

  let draft = $state<AppPair>({
    apps: ["", ""],
    layoutMode: "split",
  });

  const layoutModes: LayoutMode[] = ["split", "popup"];

  $effect(() => {
    draft = createDraft(editingPair, availableApps);
  });

  $effect(() => {
    // Fill in default app A and app B packages if empty
    if (!draft.apps[0] && availableApps[0]) {
      draft = { ...draft, apps: [availableApps[0].packageName, draft.apps[1]] };
    }
    if (!draft.apps[1]) {
      const fallback = availableApps.find((app: AppInfo) => app.packageName !== draft.apps[0]);
      if (fallback) {
        draft = { ...draft, apps: [draft.apps[0], fallback.packageName] };
      }
    }
  });

  function getAppLabel(packageName?: string) {
    if (!packageName) return "Not assigned";
    return availableApps.find((app: AppInfo) => app.packageName === packageName)?.label ?? "Unknown";
  }

  function handleKeyDown(event: KeyboardEvent) {
    if (event.key === "Escape") {
      event.preventDefault();
      onCancel();
    }
  }

  function setLayoutMode(mode: LayoutMode) {
    draft = {
      ...draft,
      layoutMode: mode,
    };
  }

  function updateAppA(packageName: string) {
    const appB = draft.apps[1] === packageName
      ? availableApps.find((app: AppInfo) => app.packageName !== packageName)?.packageName ?? ""
      : draft.apps[1];
    draft = {
      ...draft,
      apps: [packageName, appB],
    };
  }

  function updateAppB(packageName: string) {
    const appA = draft.apps[0] === packageName
      ? availableApps.find((app: AppInfo) => app.packageName !== packageName)?.packageName ?? ""
      : draft.apps[0];
    draft = {
      ...draft,
      apps: [appA, packageName],
    };
  }

  function swapAssignments() {
    draft = swapAppPairApps(draft);
  }

  function save() {
    if (!isValidAppPair(draft)) return;
    onSave(draft);
  }
</script>

<div
  class="pair-dialog-overlay"
  role="button"
  tabindex="0"
  aria-label="Close app pair editor"
  onclick={(event) => {
    if (event.target === event.currentTarget) onCancel();
  }}
  onkeydown={handleKeyDown}
>
  <div
    class="pair-dialog"
    role="dialog"
    aria-modal="true"
    aria-label="App pair editor"
    tabindex="-1"
  >
    <header class="pair-dialog-header">
      <strong>App Pair Editor</strong>
      <small>Group two apps together to launch them simultaneously.</small>
    </header>

    <section class="mode-picker">
      {#each layoutModes as mode}
        <button
          class:active={draft.layoutMode === mode}
          class="mode-chip"
          onclick={() => setLayoutMode(mode)}
        >
          {getAppPairModeLabel(mode)}
        </button>
      {/each}
    </section>

    <div class="pair-dialog-body">
      <div class="workspace-slot">
        <div class="slot-label-row">
          <span class="slot-label">App 1</span>
        </div>
        <div class="slot-preview">
          <div class="icon-wrap">
            {#if draft.apps[0]}
              <img
                src={`/api/icon?pkg=${encodeURIComponent(draft.apps[0])}`}
                alt=""
                draggable="false"
              />
            {/if}
          </div>
          <div class="slot-meta">
            <strong>{getAppLabel(draft.apps[0])}</strong>
            <small>Left in Split. Background in Popup.</small>
          </div>
        </div>
        <select value={draft.apps[0]} onchange={(event) => updateAppA((event.currentTarget as HTMLSelectElement).value)}>
          {#each availableApps as app (app.packageName)}
            <option value={app.packageName}>{app.label}</option>
          {/each}
        </select>
      </div>

      <button
        class="pair-dialog-swap"
        onclick={swapAssignments}
        title="Swap apps"
      >
        ⇄
      </button>

      <div class="workspace-slot">
        <div class="slot-label-row">
          <span class="slot-label">App 2</span>
        </div>
        <div class="slot-preview">
          <div class="icon-wrap">
            {#if draft.apps[1]}
              <img
                src={`/api/icon?pkg=${encodeURIComponent(draft.apps[1])}`}
                alt=""
                draggable="false"
              />
            {/if}
          </div>
          <div class="slot-meta">
            <strong>{getAppLabel(draft.apps[1])}</strong>
            <small>Right in Split. Floating in Popup.</small>
          </div>
        </div>
        <select
          value={draft.apps[1]}
          onchange={(event) => updateAppB((event.currentTarget as HTMLSelectElement).value)}
        >
          {#each availableApps as app (app.packageName)}
            <option value={app.packageName} disabled={app.packageName === draft.apps[0]}>{app.label}</option>
          {/each}
        </select>
      </div>
    </div>

    <div class="layout-explainer">
      <div>
        <span class="explainer-title">Display Layout</span>
        <p>
          {#if draft.layoutMode === "split"}
            App 1 renders on the left side and App 2 renders on the right side.
          {:else}
            App 1 fills the full background screen and App 2 floats as a popup window.
          {/if}
        </p>
      </div>
    </div>

    <div class="pair-dialog-actions">
      <button class="btn-secondary" onclick={onCancel}>Cancel</button>
      <button class="btn-danger" onclick={() => onRemove(editingPair)}>Remove</button>
      <button class="btn-primary" onclick={save} disabled={!isValidAppPair(draft)}>
        Save App Pair
      </button>
    </div>
  </div>
</div>

<style>
  .pair-dialog-overlay {
    position: fixed;
    inset: 0;
    z-index: 90;
    display: grid;
    place-items: center;
    background: rgba(6, 8, 14, 0.72);
    backdrop-filter: blur(8px);
    padding: 20px;
  }

  .pair-dialog {
    width: min(760px, 96vw);
    background: linear-gradient(180deg, rgb(22 28 42 / 0.98), rgb(14 18 28 / 0.98));
    border: 1px solid rgba(255, 255, 255, 0.08);
    box-shadow:
      0 24px 48px rgba(0, 0, 0, 0.5),
      inset 0 0 24px rgba(255, 255, 255, 0.02);
    border-radius: 20px;
    overflow: hidden;
  }

  .pair-dialog-header {
    display: grid;
    gap: 4px;
    padding: 18px 24px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.06);
    background: rgba(255, 255, 255, 0.01);
  }

  .pair-dialog-header strong {
    font-size: 17px;
    font-weight: 800;
    color: #f1f5f9;
  }

  .pair-dialog-header small {
    color: #94a3b8;
    font-size: 12px;
  }

  .mode-picker {
    display: flex;
    gap: 10px;
    padding: 18px 24px 0;
  }

  .mode-chip {
    height: 34px;
    padding: 0 14px;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.04);
    color: #dbeafe;
    font-size: 12px;
    font-weight: 800;
    cursor: pointer;
  }

  .mode-chip.active {
    border-color: rgba(0, 229, 255, 0.32);
    background: rgba(0, 229, 255, 0.12);
    color: #9cefff;
  }

  .pair-dialog-body {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
    gap: 18px;
    align-items: center;
    padding: 22px 24px 20px;
  }

  .workspace-slot {
    display: grid;
    gap: 10px;
    padding: 16px;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 18px;
    background: rgba(255, 255, 255, 0.03);
  }

  .slot-label-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
  }

  .slot-label {
    color: #eef7ff;
    font-size: 12px;
    font-weight: 800;
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }

  .slot-preview {
    display: grid;
    grid-template-columns: 64px minmax(0, 1fr);
    gap: 12px;
    align-items: center;
  }

  .slot-meta {
    display: grid;
    gap: 4px;
    min-width: 0;
  }

  .slot-meta strong {
    color: #e2e8f0;
    font-size: 14px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .slot-meta small {
    color: #94a3b8;
    font-size: 11px;
    line-height: 1.35;
  }

  .icon-wrap {
    width: 64px;
    height: 64px;
    background: rgba(255, 255, 255, 0.03);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 16px;
    padding: 8px;
    box-shadow: 0 8px 20px rgba(0, 0, 0, 0.2);
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .icon-wrap img {
    width: 100%;
    height: 100%;
    object-fit: contain;
    border-radius: 8px;
  }

  select {
    width: 100%;
    height: 38px;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 12px;
    background: rgba(6, 10, 18, 0.88);
    color: #e2e8f0;
    padding: 0 12px;
  }

  .pair-dialog-swap {
    width: 42px;
    height: 42px;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.04);
    color: #e2e8f0;
    font-size: 18px;
    cursor: pointer;
  }

  .layout-explainer {
    padding: 0 24px 20px;
  }

  .layout-explainer > div {
    padding: 14px 16px;
    border-radius: 14px;
    background: rgba(255, 255, 255, 0.03);
    border: 1px solid rgba(255, 255, 255, 0.06);
  }

  .explainer-title {
    display: inline-block;
    margin-bottom: 6px;
    color: #9cefff;
    font-size: 11px;
    font-weight: 800;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }

  .layout-explainer p {
    margin: 0;
    color: #cbd5e1;
    font-size: 13px;
    line-height: 1.45;
  }

  .pair-dialog-actions {
    display: grid;
    grid-template-columns: 1fr 1fr 1.2fr;
    gap: 12px;
    padding: 18px 24px;
    border-top: 1px solid rgba(255, 255, 255, 0.06);
    background: rgba(0, 0, 0, 0.14);
  }

  .pair-dialog-actions button {
    height: 38px;
    border-radius: 10px;
    font-size: 13px;
    font-weight: 700;
    cursor: pointer;
    border: 0;
  }

  .pair-dialog-actions button:disabled {
    opacity: 0.45;
    cursor: default;
  }

  .btn-secondary {
    background: rgba(255, 255, 255, 0.06);
    color: #cbd5e1;
    border: 1px solid rgba(255, 255, 255, 0.08) !important;
  }

  .btn-danger {
    background: rgba(239, 68, 68, 0.1);
    color: #f87171;
    border: 1px solid rgba(239, 68, 68, 0.15) !important;
  }

  .btn-primary {
    background: #0088cc;
    color: #ffffff;
    box-shadow: 0 4px 12px rgba(0, 136, 204, 0.24);
  }
</style>

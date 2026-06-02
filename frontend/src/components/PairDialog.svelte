<script lang="ts">
  // Type definition for application details
  interface AppInfo {
    packageName: string;
    label: string;
    componentName?: string;
    category?: string;
    isWeb?: boolean;
    left?: string;
    right?: string;
    isPair?: boolean;
  }

  // Strict Svelte 5 Props using $props Rune
  let {
    editingPair,
    apps,
    onSwap,
    onCancel,
    onRemove,
    onSave
  } = $props<{
    editingPair: AppInfo;
    apps: AppInfo[];
    onSwap: () => void;
    onCancel: () => void;
    onRemove: (pair: AppInfo) => void;
    onSave: () => void;
  }>();

  // Helper utility to find an app name cleanly
  function getAppLabel(packageName?: string) {
    if (!packageName) return "Unknown";
    return apps.find((app: AppInfo) => app.packageName === packageName)?.label ?? "Unknown";
  }

  // Handle escape keyboard close trigger
  function handleKeyDown(event: KeyboardEvent) {
    if (event.key === "Escape") {
      event.preventDefault();
      onCancel();
    }
  }
</script>

<div
  class="pair-dialog-overlay"
  role="button"
  tabindex="0"
  aria-label="Close App Pair editor"
  onclick={(event) => {
    if (event.target === event.currentTarget) onCancel();
  }}
  onkeydown={handleKeyDown}
>
  <div
    class="pair-dialog"
    role="dialog"
    aria-modal="true"
    aria-label="App Pair editor"
    tabindex="-1"
  >
    <header class="pair-dialog-header">
      <strong>App Pair Editor</strong>
    </header>

    <div class="pair-dialog-body">
      <!-- Left side App item details -->
      <div class="pair-dialog-app">
        <div class="icon-wrap">
          <img
            src={`/api/icon?pkg=${encodeURIComponent(editingPair.left ?? "")}`}
            alt=""
            draggable="false"
          />
        </div>
        <span>{getAppLabel(editingPair.left)}</span>
        <small class="pane-indicator">Primary (Left)</small>
      </div>

      <!-- Center Swap action button -->
      <button
        class="pair-dialog-swap"
        onclick={onSwap}
        title="Swap positions"
      >
        ⇄
      </button>

      <!-- Right side App item details -->
      <div class="pair-dialog-app">
        <div class="icon-wrap">
          <img
            src={`/api/icon?pkg=${encodeURIComponent(editingPair.right ?? "")}`}
            alt=""
            draggable="false"
          />
        </div>
        <span>{getAppLabel(editingPair.right)}</span>
        <small class="pane-indicator">Secondary (Right)</small>
      </div>
    </div>

    <!-- Actions buttons footer bar -->
    <div class="pair-dialog-actions">
      <button class="btn-secondary" onclick={onCancel}>Cancel</button>
      <button class="btn-danger" onclick={() => onRemove(editingPair)}>Dissolve</button>
      <button class="btn-primary" onclick={onSave}>Save Pair</button>
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
    animation: dialogFadeIn 0.22s cubic-bezier(0.4, 0, 0.2, 1) forwards;
  }

  @keyframes dialogFadeIn {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  .pair-dialog {
    width: min(440px, 95vw);
    background: linear-gradient(
        180deg,
        rgb(22 28 42 / 0.98),
        rgb(14 18 28 / 0.98)
      );
    border: 1px solid rgba(255, 255, 255, 0.08);
    box-shadow:
      0 24px 48px rgba(0, 0, 0, 0.5),
      inset 0 0 24px rgba(255, 255, 255, 0.02);
    border-radius: 20px;
    overflow: hidden;
    transform: translateY(0);
    animation: dialogPopUp 0.24s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
  }

  @keyframes dialogPopUp {
    from {
      transform: translateY(20px) scale(0.96);
    }
    to {
      transform: translateY(0) scale(1);
    }
  }

  .pair-dialog-header {
    padding: 18px 24px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.06);
    background: rgba(255, 255, 255, 0.01);
    text-align: center;
  }

  .pair-dialog-header strong {
    font-size: 17px;
    font-weight: 800;
    letter-spacing: -0.01em;
    color: #f1f5f9;
  }

  .pair-dialog-body {
    display: flex;
    align-items: center;
    justify-content: space-evenly;
    padding: 34px 20px;
    gap: 10px;
  }

  .pair-dialog-app {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    width: 130px;
    text-align: center;
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

  .pair-dialog-app span {
    font-size: 13px;
    font-weight: 700;
    color: #e2e8f0;
    width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .pane-indicator {
    font-size: 9px;
    font-weight: 800;
    color: #64748b;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }

  .pair-dialog-swap {
    width: 40px;
    height: 40px;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.04);
    color: #e2e8f0;
    font-size: 18px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition:
      background 0.2s ease,
      border-color 0.2s ease,
      transform 0.2s cubic-bezier(0.175, 0.885, 0.32, 1.275);
  }

  .pair-dialog-swap:hover {
    background: rgba(255, 255, 255, 0.08);
    border-color: rgba(255, 255, 255, 0.16);
    transform: scale(1.1) rotate(180deg);
  }

  .pair-dialog-swap:active {
    transform: scale(0.9) rotate(180deg);
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
    transition:
      background-color 0.2s ease,
      transform 0.1s ease;
  }

  .pair-dialog-actions button:active {
    transform: scale(0.97);
  }

  .btn-secondary {
    background: rgba(255, 255, 255, 0.06);
    color: #cbd5e1;
    border: 1px solid rgba(255, 255, 255, 0.08) !important;
  }

  .btn-secondary:hover {
    background: rgba(255, 255, 255, 0.1);
  }

  .btn-danger {
    background: rgba(239, 68, 68, 0.1);
    color: #f87171;
    border: 1px solid rgba(239, 68, 68, 0.15) !important;
  }

  .btn-danger:hover {
    background: rgba(239, 68, 68, 0.16);
  }

  .btn-primary {
    background: #0088cc;
    color: #ffffff;
    box-shadow: 0 4px 12px rgba(0, 136, 204, 0.24);
  }

  .btn-primary:hover {
    background: #0099e0;
  }
</style>

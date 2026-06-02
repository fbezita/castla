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
    draggingApp,
    dragX,
    dragY,
    dropZone,
    pairTarget,
    drawerLeft
  } = $props<{
    draggingApp: AppInfo;
    dragX: number;
    dragY: number;
    dropZone: string;
    pairTarget: AppInfo | null;
    drawerLeft: number;
  }>();
</script>

<!-- Renders the dropzone overlays only when dragging -->
<div class="drop-overlay" class:hide-zones={Boolean(pairTarget)} style={`--drawer-left: ${drawerLeft}px`}>
  <!-- Left execution target (Primary Pane) -->
  <div
    class="drop-zone primary-zone"
    class:active={dropZone === "primary"}
  >
    <div class="zone-card">
      <span class="zone-icon">▰</span>
      <strong>Primary (Left Screen)</strong>
      <small>Launch in VD_1</small>
    </div>
  </div>

  <!-- Right execution target (Secondary Pane) -->
  <div
    class="drop-zone secondary-zone"
    class:active={dropZone === "secondary"}
  >
    <div class="zone-card">
      <span class="zone-icon">▰</span>
      <strong>Secondary (Right Screen)</strong>
      <small>Launch in VD_2</small>
    </div>
  </div>

  <!-- Dissolve / Garbage removal target (Remove Pane) -->
  <div
    class="drop-zone remove-zone"
    class:active={dropZone === "remove"}
  >
    <div class="zone-card danger-card">
      <span class="zone-icon">⌫</span>
      <strong>Trash / Dissolve</strong>
      <small>Remove shortcut or pair</small>
    </div>
  </div>
</div>

<!-- Hovering draggable app ghost tracking the pointer coordinates - Rendered independently from pairTarget to prevent unmounting -->
<div class="drag-ghost" style={`left: ${dragX}px; top: ${dragY}px`}>
  {#if draggingApp.isPair && draggingApp.left && draggingApp.right}
    <div class="ghost-pair-icons">
      <img
        class="ghost-pair-left"
        src={`/api/icon?pkg=${encodeURIComponent(draggingApp.left)}`}
        alt=""
        draggable="false"
      />
      <img
        class="ghost-pair-right"
        src={`/api/icon?pkg=${encodeURIComponent(draggingApp.right)}`}
        alt=""
        draggable="false"
      />
    </div>
  {:else}
    <img
      src={`/api/icon?pkg=${encodeURIComponent(draggingApp.packageName)}`}
      alt=""
      draggable="false"
    />
  {/if}
</div>

<style>
  .drop-overlay {
    position: fixed;
    inset: 0;
    z-index: 80;
    pointer-events: none !important;
    animation: fadeOverlay 0.25s cubic-bezier(0.4, 0, 0.2, 1) forwards;
    transition: opacity 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  }

  /* Smoothly fade out zones when hovering over a merge target */
  .drop-overlay.hide-zones {
    opacity: 0 !important;
    pointer-events: none !important;
  }

  /* Force pointer-events none on all descendent overlays to preempt event hijacking */
  .drop-overlay *,
  .drop-zone,
  .drag-ghost {
    pointer-events: none !important;
  }

  @keyframes fadeOverlay {
    from {
      background-color: rgba(0, 0, 0, 0);
      backdrop-filter: blur(0px);
    }
    to {
      background-color: rgba(6, 8, 14, 0.45);
      backdrop-filter: blur(4px);
    }
  }

  .drop-zone {
    position: absolute;
    border: 2px dashed rgba(255, 255, 255, 0.12);
    border-radius: 20px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgba(255, 255, 255, 0.01);
    transition:
      border-color 0.25s cubic-bezier(0.4, 0, 0.2, 1),
      background-color 0.25s cubic-bezier(0.4, 0, 0.2, 1),
      box-shadow 0.25s cubic-bezier(0.4, 0, 0.2, 1),
      transform 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  }

  .primary-zone,
  .secondary-zone {
    top: 80px;
    bottom: 140px;
  }

  .primary-zone {
    left: 20px;
    width: calc((var(--drawer-left) - 60px) / 2);
  }

  .secondary-zone {
    left: calc(40px + ((var(--drawer-left) - 60px) / 2));
    width: calc((var(--drawer-left) - 60px) / 2);
  }

  .zone-card {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    color: #94a3b8;
    text-align: center;
    transform: translateY(0);
    transition: transform 0.2s ease;
  }

  .zone-icon {
    font-size: 32px;
    color: #64748b;
    transition: color 0.2s ease;
  }

  .drop-zone strong {
    font-size: 15px;
    font-weight: 700;
  }

  .drop-zone small {
    font-size: 11px;
    color: #64748b;
  }

  /* Active hover states for zones */
  .drop-zone.active {
    border-style: solid;
    background-color: rgba(139, 196, 255, 0.08);
    border-color: #8bc4ff;
    box-shadow: 0 0 24px rgba(139, 196, 255, 0.15);
    transform: scale(1.01);
  }

  .drop-zone.active .zone-card {
    transform: translateY(-2px);
    color: #ffffff;
  }

  .drop-zone.active .zone-icon {
    color: #8bc4ff;
  }

  .remove-zone {
    left: 20px;
    right: calc(100vw - var(--drawer-left) + 20px);
    bottom: 20px;
    height: 120px;
    border-color: rgba(239, 68, 68, 0.15);
    background: rgba(239, 68, 68, 0.01);
  }

  .remove-zone.active {
    background-color: rgba(239, 68, 68, 0.08);
    border-color: #ef4444;
    box-shadow: 0 0 24px rgba(239, 68, 68, 0.15);
  }

  .remove-zone.active .zone-icon {
    color: #ef4444;
  }

  .remove-zone.active .zone-card {
    color: #ffffff;
  }

  /* Drag ghost icon mapping */
  .drag-ghost {
    position: fixed;
    width: 58px;
    height: 58px;
    margin-left: -29px; /* Centered offsets */
    margin-top: -29px;
    pointer-events: none;
    z-index: 99;
    background: rgb(22 27 42 / 0.95);
    border: 2px solid #00e5ff;
    border-radius: 14px;
    padding: 6px;
    box-shadow: 0 12px 32px rgba(0, 229, 255, 0.32);
    transform: scale(1.1);
    transition: transform 0.08s ease;
  }

  .drag-ghost img {
    width: 100%;
    height: 100%;
    object-fit: contain;
    border-radius: 8px;
  }

  .ghost-pair-icons {
    position: relative;
    width: 100%;
    height: 100%;
  }

  .ghost-pair-left,
  .ghost-pair-right {
    position: absolute;
    width: 28px !important;
    height: 28px !important;
    object-fit: contain;
    border-radius: 8px !important;
    background: rgb(18 22 34 / 0.96);
    padding: 2px;
    box-shadow: 0 6px 14px rgba(0, 0, 0, 0.22);
  }

  .ghost-pair-left {
    left: 4px;
    top: 12px;
    z-index: 1;
  }

  .ghost-pair-right {
    right: 4px;
    top: 12px;
    z-index: 2;
  }
</style>

<script lang="ts">
  import { getAppPairPreviewPackages, type LayoutMode, type AppPair } from "../lib/appPair";

  interface AppInfo extends Partial<AppPair> {
    packageName: string;
    label: string;
    componentName?: string;
    category?: string;
    isWeb?: boolean;
    isPair?: boolean;
    layoutMode?: LayoutMode;
  }

  type DropZone = "favorite" | "autorun" | "primary" | "secondary" | "remove" | "";

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
    dropZone: DropZone;
    pairTarget: AppInfo | null;
    drawerLeft: number;
  }>();

  type HighlightRect = {
    left: number;
    top: number;
    width: number;
    height: number;
  };

  let targetRect = $state<HighlightRect | null>(null);

  $effect(() => {
    void dragX;
    void dragY;
    void dropZone;
    void drawerLeft;
    targetRect = resolveHighlightRect(dropZone);
  });

  function resolveHighlightRect(zone: DropZone): HighlightRect | null {
    if (zone !== "primary" && zone !== "secondary" && zone !== "remove") {
      return null;
    }

    if (zone === "remove") {
      const sideInset = 20;
      const bottomInset = 20;
      const bottomZoneHeight = 120;
      return {
        left: sideInset,
        top: window.innerHeight - bottomZoneHeight - bottomInset,
        width: Math.max(0, drawerLeft - sideInset * 2),
        height: bottomZoneHeight,
      };
    }

    const paneElement = document.querySelector(`.viewport-pane[data-pane="${zone}"]`) as HTMLElement | null;
    if (paneElement) {
      const rect = paneElement.getBoundingClientRect();
      return {
        left: rect.left,
        top: rect.top,
        width: rect.width,
        height: rect.height,
      };
    }

    return null;
  }

  function zoneLabel(zone: DropZone): string {
    if (zone === "primary") return "Selected Window";
    if (zone === "secondary") return "Selected Window";
    if (zone === "remove") return "Release to remove";
    return "";
  }

  function previewPackages(app: AppInfo): string[] {
    if (!app.isPair) return [];
    return getAppPairPreviewPackages(app as any);
  }
</script>

<div class="drop-overlay" class:hide-zones={Boolean(pairTarget)}>
  {#if targetRect}
    <div
      class:remove-highlight={dropZone === "remove"}
      class="window-highlight"
      style={`left:${targetRect.left}px;top:${targetRect.top}px;width:${targetRect.width}px;height:${targetRect.height}px;`}
    >
      <div class="highlight-frame"></div>
      <div class="highlight-glow"></div>
      <div class="highlight-label">{zoneLabel(dropZone)}</div>
    </div>
  {/if}
</div>

<div class="drag-ghost" style={`left: ${dragX}px; top: ${dragY}px`}>
  {#if draggingApp.isPair && previewPackages(draggingApp).length > 1}
    <div class="ghost-pair-icons">
      <img
        class="ghost-pair-left"
        src={`/api/icon?pkg=${encodeURIComponent(previewPackages(draggingApp)[0])}`}
        alt=""
        draggable="false"
      />
      <img
        class="ghost-pair-right"
        src={`/api/icon?pkg=${encodeURIComponent(previewPackages(draggingApp)[1])}`}
        alt=""
        draggable="false"
      />
    </div>
  {:else if draggingApp.isPair && previewPackages(draggingApp).length === 1}
    <img
      src={`/api/icon?pkg=${encodeURIComponent(previewPackages(draggingApp)[0])}`}
      alt=""
      draggable="false"
    />
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
    animation: fadeOverlay 0.18s ease forwards;
    transition: opacity 0.18s ease;
  }

  .drop-overlay.hide-zones {
    opacity: 0 !important;
  }

  .drop-overlay *,
  .drag-ghost {
    pointer-events: none !important;
  }

  @keyframes fadeOverlay {
    from {
      background-color: rgba(0, 0, 0, 0);
    }
    to {
      background-color: rgba(6, 8, 14, 0.18);
    }
  }

  .window-highlight {
    position: absolute;
    border-radius: 22px;
    overflow: hidden;
  }

  .highlight-frame,
  .highlight-glow {
    position: absolute;
    inset: 0;
    border-radius: inherit;
  }

  .highlight-frame {
    border: 2px solid rgba(139, 196, 255, 0.88);
    background:
      linear-gradient(180deg, rgba(139, 196, 255, 0.16), rgba(139, 196, 255, 0.05)),
      rgba(255, 255, 255, 0.03);
    box-shadow:
      inset 0 0 0 1px rgba(255, 255, 255, 0.08),
      0 0 0 1px rgba(139, 196, 255, 0.12);
  }

  .highlight-glow {
    box-shadow:
      0 0 0 9999px rgba(1, 4, 10, 0.08),
      0 0 36px rgba(139, 196, 255, 0.35),
      inset 0 0 24px rgba(139, 196, 255, 0.16);
  }

  .window-highlight.remove-highlight .highlight-frame {
    border-color: rgba(239, 68, 68, 0.9);
    background:
      linear-gradient(180deg, rgba(239, 68, 68, 0.12), rgba(239, 68, 68, 0.04)),
      rgba(255, 255, 255, 0.02);
  }

  .window-highlight.remove-highlight .highlight-glow {
    box-shadow:
      0 0 0 9999px rgba(1, 4, 10, 0.08),
      0 0 36px rgba(239, 68, 68, 0.28),
      inset 0 0 24px rgba(239, 68, 68, 0.14);
  }

  .highlight-label {
    position: absolute;
    top: 16px;
    left: 16px;
    padding: 7px 10px;
    border-radius: 999px;
    background: rgba(8, 16, 28, 0.84);
    color: #eef7ff;
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.02em;
    box-shadow: 0 6px 18px rgba(0, 0, 0, 0.24);
  }

  .drag-ghost {
    position: fixed;
    width: 58px;
    height: 58px;
    margin-left: -29px;
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

<script lang="ts">
  import { getAppPairPreviewPackages, type LayoutMode, type AppPair } from "../lib/appPair";
  import { toOverlayPoint, toOverlayRect } from "../lib/overlayCoordinates";
  import {
    getDropTargetRect,
    getPlacementPreviewRect,
    type ExternalAppDropZone,
  } from "../lib/secondaryPlacement";

  interface AppInfo extends Partial<AppPair> {
    packageName: string;
    label: string;
    componentName?: string;
    category?: string;
    isWeb?: boolean;
    isPair?: boolean;
    layoutMode?: LayoutMode;
  }

  type DropZone =
    | "favorite"
    | "autorun"
    | "primary"
    | "secondary"
    | ExternalAppDropZone;

  let {
    draggingApp,
    dragX,
    dragY,
    dropZone,
    pairTarget,
    overlayUiScale,
    drawerLeft
  } = $props<{
    draggingApp: AppInfo;
    dragX: number;
    dragY: number;
    dropZone: DropZone;
    pairTarget: AppInfo | null;
    overlayUiScale: number;
    drawerLeft: number;
  }>();

  type HighlightRect = {
    left: number;
    top: number;
    width: number;
    height: number;
  };

  let targetRect = $state<HighlightRect | null>(null);
  let previewRect = $state<HighlightRect | null>(null);
  const placementZones = ["left", "right", "top", "bottom", "popup"] as const;

  $effect(() => {
    void dragX;
    void dragY;
    void dropZone;
    void drawerLeft;
    void overlayUiScale;
    targetRect = resolveHighlightRect(dropZone);
    previewRect = resolvePreviewRect(dropZone);
  });

  function resolveHighlightRect(zone: DropZone): HighlightRect | null {
    if (
      zone === "left" ||
      zone === "right" ||
      zone === "top" ||
      zone === "bottom" ||
      zone === "popup"
    ) {
      return getDropTargetRect(zone, {
        width: window.innerWidth,
        height: window.innerHeight,
        drawerLeft,
      });
    }

    if (zone === "remove") {
      return getPlacementPreviewRect(
        zone,
        {
          width: window.innerWidth,
          height: window.innerHeight,
          drawerLeft,
        },
        0.5,
      );
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

  function resolvePreviewRect(zone: DropZone): HighlightRect | null {
    if (
      zone === "left" ||
      zone === "right" ||
      zone === "top" ||
      zone === "bottom" ||
      zone === "popup"
    ) {
      return getPlacementPreviewRect(
        zone,
        {
          width: window.innerWidth,
          height: window.innerHeight,
          drawerLeft,
        },
        0.5,
      );
    }

    return null;
  }

  function zoneLabel(zone: DropZone): string {
    if (zone === "primary") return "Selected Window";
    if (zone === "secondary") return "Selected Window";
    if (zone === "left") return "Dock Left";
    if (zone === "right") return "Dock Right";
    if (zone === "top") return "Dock Top";
    if (zone === "bottom") return "Dock Bottom";
    if (zone === "popup") return "Open Popup";
    if (zone === "remove") return "Release to remove";
    return "";
  }

  function markerRect(zone: typeof placementZones[number]) {
    return getDropTargetRect(zone, {
      width: window.innerWidth,
      height: window.innerHeight,
      drawerLeft,
    });
  }

  function rectStyle(rect: HighlightRect): string {
    const overlayRect = toOverlayRect(
      { x: rect.left, y: rect.top, width: rect.width, height: rect.height },
      overlayUiScale,
    );
    return `left:${overlayRect.x}px;top:${overlayRect.y}px;width:${overlayRect.width}px;height:${overlayRect.height}px;`;
  }

  function ghostStyle(): string {
    const overlayPoint = toOverlayPoint({ x: dragX, y: dragY }, overlayUiScale);
    return `left: ${overlayPoint.x}px; top: ${overlayPoint.y}px`;
  }

  function previewPackages(app: AppInfo): string[] {
    if (!app.isPair) return [];
    return getAppPairPreviewPackages(app as any);
  }
</script>

<div class="drop-overlay" class:hide-zones={Boolean(pairTarget)}>
  {#if previewRect}
    <div
      class="placement-preview"
      style={rectStyle(previewRect)}
    ></div>
  {/if}
  <div class="placement-targets">
    {#each placementZones as zone}
      {@const rect = markerRect(zone)}
      <div
        class="placement-target"
        class:active={dropZone === zone}
        style={rectStyle(rect)}
      >
        <span>{zone === "popup" ? "Popup" : zone[0].toUpperCase()}</span>
      </div>
    {/each}
  </div>
  {#if targetRect}
    <div
      class:remove-highlight={dropZone === "remove"}
      class="window-highlight"
      style={rectStyle(targetRect)}
    >
      <div class="highlight-frame"></div>
      <div class="highlight-glow"></div>
      <div class="highlight-label">{zoneLabel(dropZone)}</div>
    </div>
  {/if}
</div>

<div class="drag-ghost" style={ghostStyle()}>
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

  .placement-targets {
    position: absolute;
    inset: 0;
  }

  .placement-preview {
    position: absolute;
    border-radius: 24px;
    border: 1px solid rgba(125, 242, 255, 0.22);
    background:
      linear-gradient(180deg, rgba(125, 242, 255, 0.1), rgba(125, 242, 255, 0.04)),
      rgba(255, 255, 255, 0.02);
    box-shadow:
      inset 0 0 0 1px rgba(255, 255, 255, 0.04),
      0 0 26px rgba(79, 209, 255, 0.12);
  }

  .placement-target {
    position: absolute;
    border-radius: 28px;
    display: grid;
    place-items: center;
    background:
      radial-gradient(circle at 30% 30%, rgba(255, 255, 255, 0.12), transparent 55%),
      linear-gradient(180deg, rgba(10, 18, 32, 0.9), rgba(6, 12, 24, 0.82));
    border: 1px solid rgba(148, 163, 184, 0.22);
    color: rgba(226, 232, 240, 0.86);
    box-shadow:
      0 12px 32px rgba(0, 0, 0, 0.22),
      inset 0 1px 0 rgba(255, 255, 255, 0.05);
    backdrop-filter: blur(16px) saturate(120%);
    transition:
      transform 0.16s ease,
      border-color 0.16s ease,
      background 0.16s ease,
      box-shadow 0.16s ease,
      color 0.16s ease;
  }

  .placement-target span {
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.1em;
    text-transform: uppercase;
  }

  .placement-target::before {
    content: "";
    width: 28px;
    height: 28px;
    border-radius: 999px;
    border: 1px solid rgba(255, 255, 255, 0.14);
    background: rgba(255, 255, 255, 0.04);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
    margin-bottom: 8px;
  }

  .placement-target:nth-child(1)::before {
    border-radius: 12px;
    width: 24px;
    height: 24px;
  }

  .placement-target.active {
    transform: scale(1.04);
    border-color: rgba(125, 242, 255, 0.72);
    background:
      radial-gradient(circle at 30% 30%, rgba(182, 244, 255, 0.28), transparent 55%),
      linear-gradient(180deg, rgba(18, 72, 92, 0.9), rgba(8, 28, 40, 0.86));
    box-shadow:
      0 18px 36px rgba(0, 0, 0, 0.28),
      0 0 0 1px rgba(125, 242, 255, 0.14),
      0 0 24px rgba(79, 209, 255, 0.22);
    color: #f4fdff;
  }

  .placement-target.active::before {
    border-color: rgba(216, 248, 255, 0.52);
    background: rgba(216, 248, 255, 0.14);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.12),
      0 0 18px rgba(79, 209, 255, 0.18);
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

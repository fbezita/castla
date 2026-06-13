<script lang="ts">
  import type { SecondaryPlacement } from "../stores/compositorStore";
  import {
    getDropTargetRect,
    getPlacementPreviewRect,
  } from "../lib/secondaryPlacement";
  import { t, type Language } from "../lib/i18n";

  let {
    activePlacement,
    drawerLeft,
    language,
    onClose,
    onSelect,
  } = $props<{
    activePlacement: SecondaryPlacement;
    drawerLeft: number;
    language: Language;
    onClose: () => void;
    onSelect: (placement: SecondaryPlacement) => void;
  }>();

  const placementZones: SecondaryPlacement[] = ["left", "right", "top", "bottom", "popup"];

  function markerRect(zone: SecondaryPlacement) {
    return getDropTargetRect(zone, {
      width: window.innerWidth,
      height: window.innerHeight,
      drawerLeft,
    });
  }

  function previewRect(zone: SecondaryPlacement) {
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

  function zoneLabel(zone: SecondaryPlacement) {
    if (zone === "left") return t(language, "left");
    if (zone === "right") return t(language, "right");
    if (zone === "top") return t(language, "top");
    if (zone === "bottom") return t(language, "bottom");
    return t(language, "popup");
  }
</script>

<div
  class="picker-overlay"
  role="button"
  tabindex="0"
  aria-label="Close placement picker"
  onclick={(event) => {
    if (event.target === event.currentTarget) onClose();
  }}
  onkeydown={(event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      onClose();
    }
  }}
>
  {#each placementZones as zone}
    {@const preview = previewRect(zone)}
    {@const marker = markerRect(zone)}
    {#if preview}
      <div
        class="placement-preview"
        class:active={activePlacement === zone}
        style={`left:${preview.left}px;top:${preview.top}px;width:${preview.width}px;height:${preview.height}px;`}
      ></div>
    {/if}
    <button
      class="placement-target"
      class:active={activePlacement === zone}
      style={`left:${marker.left}px;top:${marker.top}px;width:${marker.width}px;height:${marker.height}px;`}
      onclick={() => onSelect(zone)}
    >
      <span>{zoneLabel(zone)}</span>
    </button>
  {/each}

  <div class="picker-caption">
    <strong>{t(language, "moveSecondaryTitle")}</strong>
    <span>{t(language, "moveSecondaryDesc")}</span>
  </div>
</div>

<style>
  .picker-overlay {
    position: fixed;
    inset: 0;
    z-index: 88;
    background: rgba(6, 8, 14, 0.32);
    backdrop-filter: blur(8px);
  }

  .placement-preview {
    position: absolute;
    border-radius: 24px;
    border: 1px solid transparent;
    background: transparent;
    box-shadow: none;
    opacity: 0;
    transition: opacity 0.22s ease, border-color 0.22s ease, box-shadow 0.22s ease;
    pointer-events: none;
  }

  .placement-preview.active {
    opacity: 1;
    border-color: rgba(125, 242, 255, 0.34);
    background:
      linear-gradient(180deg, rgba(125, 242, 255, 0.08), rgba(125, 242, 255, 0.03)),
      rgba(255, 255, 255, 0.02);
    box-shadow:
      inset 0 0 0 1px rgba(255, 255, 255, 0.05),
      0 0 28px rgba(79, 209, 255, 0.16);
  }

  .placement-target {
    position: absolute;
    border-radius: 28px;
    display: grid;
    place-items: center;
    border: 1px solid rgba(148, 163, 184, 0.22);
    background:
      radial-gradient(circle at 30% 30%, rgba(255, 255, 255, 0.12), transparent 55%),
      linear-gradient(180deg, rgba(10, 18, 32, 0.94), rgba(6, 12, 24, 0.86));
    color: rgba(226, 232, 240, 0.88);
    box-shadow:
      0 12px 32px rgba(0, 0, 0, 0.22),
      inset 0 1px 0 rgba(255, 255, 255, 0.05);
    backdrop-filter: blur(16px) saturate(120%);
    cursor: pointer;
    transition:
      transform 0.16s ease,
      border-color 0.16s ease,
      background 0.16s ease,
      box-shadow 0.16s ease;
  }

  .placement-target:hover,
  .placement-target.active {
    transform: scale(1.04);
    border-color: rgba(125, 242, 255, 0.72);
    background:
      radial-gradient(circle at 30% 30%, rgba(182, 244, 255, 0.28), transparent 55%),
      linear-gradient(180deg, rgba(18, 72, 92, 0.92), rgba(8, 28, 40, 0.9));
    box-shadow:
      0 18px 36px rgba(0, 0, 0, 0.28),
      0 0 0 1px rgba(125, 242, 255, 0.14),
      0 0 24px rgba(79, 209, 255, 0.22);
  }

  .placement-target span {
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  .picker-caption {
    position: absolute;
    left: 24px;
    bottom: 24px;
    display: grid;
    gap: 4px;
    padding: 14px 16px;
    border-radius: 16px;
    background: rgba(8, 16, 28, 0.88);
    color: #eef7ff;
    box-shadow: 0 12px 28px rgba(0, 0, 0, 0.28);
  }

  .picker-caption strong {
    font-size: 13px;
    font-weight: 800;
  }

  .picker-caption span {
    font-size: 12px;
    color: rgba(226, 232, 240, 0.76);
  }
</style>

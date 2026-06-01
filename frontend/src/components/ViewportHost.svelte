<script lang="ts">
  import { onDestroy, onMount, tick } from "svelte";
  import {
    compositorStore,
    type ViewportModel,
  } from "../stores/compositorStore";
  import ViewportPane from "./ViewportPane.svelte";
  import type { TouchRouter } from "../touch/TouchRouter";
  import type { StreamRuntime } from "../runtime/StreamRuntime";
  import type { PaneId } from "../protocol";

  export let touchRouter: TouchRouter;
  export let runtime: StreamRuntime;

  let host: HTMLDivElement;
  let resizer: HTMLButtonElement;
  let controls: HTMLDivElement;
  let resizeObserver: ResizeObserver;
  let resizing = false;
  let hostRect = new DOMRect();
  let layoutTrigger = "";
  let layoutFlushScheduled = false;
  let provisionalLayoutTimer = 0;
  const activeTouchPanes = new Map<number, PaneId>();
  const PROVISIONAL_LAYOUT_SETTLE_MS = 220;

  onMount(() => {
    resizeObserver = new ResizeObserver(() => {
      hostRect = host.getBoundingClientRect();
      touchRouter.updateHost(hostRect);
      if (!resizing) {
        dispatchLayout();
      }
    });
    resizeObserver.observe(host);
    hostRect = host.getBoundingClientRect();
    touchRouter.updateHost(hostRect);
    dispatchLayout();
  });

  onDestroy(() => {
    resizeObserver?.disconnect();
    resizing = false;
    activeTouchPanes.clear();
    window.clearTimeout(provisionalLayoutTimer);
    window.removeEventListener("pointermove", resizeMove);
    window.removeEventListener("pointerup", endResize);
    window.removeEventListener("pointercancel", endResize);
  });

  $: visibleViewports = Array.from($compositorStore.viewports.values()).filter(
    (viewport) => viewport.visible,
  );
  $: splitActive =
    $compositorStore.layoutMode === "split" && visibleViewports.length >= 2;
  $: leftPane = $compositorStore.splitReversed ? "secondary" : "primary";
  $: rightPane = $compositorStore.splitReversed ? "primary" : "secondary";
  $: layoutMetrics = computeLayoutMetrics(
    hostRect.width,
    hostRect.height,
    $compositorStore.splitRatio,
    $compositorStore.splitReversed,
  );
  $: boundary = layoutMetrics.boundaryPercent;
  $: layoutTrigger = [
    Math.round(hostRect.width),
    Math.round(hostRect.height),
    $compositorStore.layoutMode,
    $compositorStore.splitRatio.toFixed(4),
    $compositorStore.splitReversed ? "reversed" : "normal",
    visibleViewports
      .map(
        (viewport) =>
          `${viewport.pane}:${viewport.visible ? 1 : 0}:${viewport.generation}:${viewport.width}x${viewport.height}`,
      )
      .join("|"),
  ].join(";");
  $: if (host && layoutTrigger) {
    updateSplitChrome();
    if (!resizing) {
      dispatchLayout();
    }
  }

  function paneStyle(pane: string): string {
    if (!splitActive) return "left:0;right:0;width:100%;";
    const { leftPercent, rightPercent } = layoutMetrics;
    if (!$compositorStore.splitReversed) {
      if (pane === "secondary")
        return `left:${leftPercent}%;right:0;width:${rightPercent}%;`;
      return `left:0;width:${leftPercent}%;right:auto;`;
    }
    if (pane === "secondary") return `left:0;width:${leftPercent}%;right:auto;`;
    return `left:${leftPercent}%;right:0;width:${rightPercent}%;`;
  }

  function beginResize(event: PointerEvent) {
    event.preventDefault();
    activeTouchPanes.clear();
    touchRouter.reset();
    resizing = true;
    updateSplitChrome();
    window.addEventListener("pointermove", resizeMove);
    window.addEventListener("pointerup", endResize, { once: true });
    window.addEventListener("pointercancel", endResize, { once: true });
  }

  function resizeMove(event: PointerEvent) {
    if (!resizing || !host) return;
    const rect = host.getBoundingClientRect();
    const pos = clamp((event.clientX - rect.left) / rect.width, 0.22, 0.78);
    const nextRatio = $compositorStore.splitReversed ? 1 - pos : pos;
    localStorage.setItem("castla_split_ratio", String(nextRatio));
    compositorStore.update((state) => ({ ...state, splitRatio: nextRatio }));
    updateSplitChrome(nextRatio);
  }

  function endResize() {
    resizing = false;
    window.removeEventListener("pointermove", resizeMove);
    window.removeEventListener("pointerup", endResize);
    window.removeEventListener("pointercancel", endResize);
    scheduleLayoutFlush();
    visibleViewports.forEach((viewport) =>
      runtime.requestKeyframe(viewport.pane),
    );
    touchRouter.reset();
  }

  function expand(pane: PaneId) {
    activeTouchPanes.clear();
    touchRouter.reset();
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      viewports.forEach((viewport, key) =>
        viewports.set(key, { ...viewport, visible: key === pane }),
      );
      return { ...state, viewports, layoutMode: "single" };
    });
    scheduleLayoutFlush();
    runtime.requestKeyframe(pane);
  }

  function expandLeft() {
    expand(leftPane as PaneId);
  }

  function expandRight() {
    expand(rightPane as PaneId);
  }

  function swap() {
    activeTouchPanes.clear();
    touchRouter.reset();
    compositorStore.update((state) => ({
      ...state,
      splitReversed: !state.splitReversed,
    }));
    updateSplitChrome();
    scheduleLayoutFlush();
  }

  function clamp(value: number, min: number, max: number) {
    return Math.min(max, Math.max(min, value));
  }

  function shouldDelayProvisionalLayout(): boolean {
    if (resizing) return false;
    const hasCommittedStream = visibleViewports.some((viewport) => viewport.committed);
    if (hasCommittedStream) return false;
    return runtime.currentAppLaunchSequence() === 0;
  }

  function dispatchLayout(forceImmediate = false) {
    window.clearTimeout(provisionalLayoutTimer);
    if (!forceImmediate && shouldDelayProvisionalLayout()) {
      provisionalLayoutTimer = window.setTimeout(() => {
        provisionalLayoutTimer = 0;
        sendCurrentLayout();
      }, PROVISIONAL_LAYOUT_SETTLE_MS);
      return;
    }
    sendCurrentLayout();
  }

  function sendCurrentLayout() {
    if (!host) return;
    const rect =
      hostRect.width > 0 && hostRect.height > 0
        ? hostRect
        : host.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    const alignedHeight = align16(rect.height);
    if (!splitActive) {
      const activePane = visibleViewports[0]?.pane ?? "primary";
      const hiddenPane: PaneId =
        activePane === "primary" ? "secondary" : "primary";
      const alignedWidth = align16(rect.width);
      runtime.sendLayout([
        {
          id: activePane,
          width: alignedWidth,
          height: alignedHeight,
          visible: true,
        },
        {
          id: hiddenPane,
          width: alignedWidth,
          height: alignedHeight,
          visible: false,
        },
      ]);
      return;
    }

    const { primaryWidth, secondaryWidth } = layoutMetrics;
    runtime.sendLayout([
      {
        id: leftPane,
        width: leftPane === "primary" ? primaryWidth : secondaryWidth,
        height: alignedHeight,
        visible: true,
      },
      {
        id: rightPane,
        width: rightPane === "primary" ? primaryWidth : secondaryWidth,
        height: alignedHeight,
        visible: true,
      },
    ]);
  }

  function handlePointer(event: PointerEvent) {
    if (resizing) return;
    const target = event.target as HTMLElement | null;
    if (target?.closest(".split-resizer") || target?.closest(".split-controls"))
      return;

    // Map pointerdown to 'down', pointermove to 'move', and pointerup/pointercancel/lostpointercapture to 'up'
    // to guarantee all active states are gracefully finalized if the browser strips pointer control.
    const action =
      event.type === "pointerdown"
        ? "down"
        : event.type === "pointermove"
          ? "move"
          : "up";

    const paneElement = target?.closest<HTMLElement>(".viewport-pane");
    const pointerKey = event.pointerId & 0xff;
    const pane =
      (paneElement?.dataset.pane as PaneId | undefined) ??
      activeTouchPanes.get(pointerKey);
    if (!pane) return;
    const viewport = visibleViewports.find((entry) => entry.pane === pane);
    if (!viewport) return;
    if (action === "down") {
      activeTouchPanes.set(pointerKey, pane);
    }
    touchRouter.pointer(
      event,
      viewport,
      splitActive ? "fill" : "contain",
      paneElement ?? undefined,
    );
    if (action === "up") {
      activeTouchPanes.delete(pointerKey);
    }
  }

  async function scheduleLayoutFlush() {
    if (layoutFlushScheduled) return;
    layoutFlushScheduled = true;
    await tick();
    await tick();
    layoutFlushScheduled = false;
    if (!host) return;
    hostRect = host.getBoundingClientRect();
    touchRouter.updateHost(hostRect);
    dispatchLayout(true);
  }

  function updateSplitChrome(ratio = $compositorStore.splitRatio) {
    if (!splitActive) return;
    const boundaryValue = computeLayoutMetrics(
      hostRect.width,
      hostRect.height,
      ratio,
      $compositorStore.splitReversed,
    ).boundaryPercent;
    if (resizer) {
      resizer.style.left = `${boundaryValue}%`;
    }
    if (controls) {
      controls.style.left = `${boundaryValue}%`;
    }
  }

  function computeLayoutMetrics(
    width: number,
    height: number,
    ratio: number,
    reversed: boolean,
  ) {
    const safeWidth = Math.max(0, Math.round(width));
    const safeHeight = Math.max(0, Math.round(height));
    if (safeWidth <= 0 || safeHeight <= 0) {
      return {
        primaryWidth: 0,
        secondaryWidth: 0,
        leftPercent: reversed ? 50 : 50,
        rightPercent: 50,
        boundaryPercent: 50,
      };
    }

    const rawPrimaryWidth = Math.max(320, Math.round(safeWidth * ratio));
    const rawSecondaryWidth = Math.max(320, safeWidth - rawPrimaryWidth);
    const alignedPrimaryWidth = align16(rawPrimaryWidth);
    const alignedSecondaryWidth = align16(rawSecondaryWidth);
    const totalAlignedWidth = alignedPrimaryWidth + alignedSecondaryWidth;
    const primaryPercent = (alignedPrimaryWidth / totalAlignedWidth) * 100;
    const secondaryPercent = (alignedSecondaryWidth / totalAlignedWidth) * 100;

    return {
      primaryWidth: alignedPrimaryWidth,
      secondaryWidth: alignedSecondaryWidth,
      leftPercent: reversed ? secondaryPercent : primaryPercent,
      rightPercent: reversed ? primaryPercent : secondaryPercent,
      boundaryPercent: reversed ? secondaryPercent : primaryPercent,
    };
  }

  function align16(value: number): number {
    return Math.max(320, (Math.round(value) + 15) & ~15);
  }
</script>

<div
  bind:this={host}
  class="viewport-host"
  role="presentation"
  on:pointerdown={handlePointer}
  on:pointermove={handlePointer}
  on:pointerup={handlePointer}
  on:pointercancel={handlePointer}
  on:lostpointercapture={handlePointer}
>
  {#each visibleViewports as viewport (viewport.pane)}
    <ViewportPane
      {viewport}
      {runtime}
      paneStyle={paneStyle(viewport.pane)}
      fitMode={splitActive ? "fill" : "contain"}
    />
  {/each}

  {#if splitActive}
    <button
      bind:this={resizer}
      class="split-resizer"
      style={`left:${boundary}%`}
      aria-label="Resize split"
      on:pointerdown={beginResize}
    ></button>
    <div
      bind:this={controls}
      class="split-controls"
      style={`left:${boundary}%`}
    >
      <button title="왼쪽 전체 확대" on:click={expandLeft}>↖</button>
      <button title="좌우 변경" on:click={swap}>⟳</button>
      <button title="오른쪽 전체 확대" on:click={expandRight}>↗</button>
    </div>
  {/if}
</div>

<style>
  .viewport-host {
    position: relative;
    width: 100%;
    height: 100%;
    background: #05070a;
    overflow: hidden;
  }

  .split-resizer {
    position: absolute;
    top: 0;
    bottom: 0;
    z-index: 35;
    width: 22px;
    transform: translateX(-50%);
    border: 0;
    background: transparent;
    cursor: ew-resize;
    touch-action: none;
  }

  .split-resizer::before {
    content: "";
    position: absolute;
    top: 0;
    bottom: 0;
    left: 10px;
    width: 2px;
    background: rgb(0 229 255 / 0.65);
    box-shadow: 0 0 12px rgb(0 229 255 / 0.65);
  }

  .split-resizer::after {
    content: "";
    position: absolute;
    left: 6px;
    top: 50%;
    width: 10px;
    height: 72px;
    transform: translateY(-50%);
    border-radius: 6px;
    background: #13dff5;
  }

  .split-controls {
    position: absolute;
    top: 28px;
    z-index: 36;
    display: flex;
    align-items: center;
    gap: 5px;
    padding: 7px 9px;
    border: 1px solid rgb(255 255 255 / 0.14);
    border-radius: 18px;
    background: rgb(12 22 34 / 0.94);
    box-shadow: 0 10px 26px rgb(0 0 0 / 0.35);
    transform: translateX(-50%);
  }

  .split-controls button {
    width: 30px;
    height: 30px;
    border: 0;
    border-radius: 50%;
    background: transparent;
    color: #39dfff;
    font-size: 20px;
    font-weight: 800;
  }

  .split-controls button:nth-child(2) {
    background: rgb(255 255 255 / 0.12);
    color: white;
  }
</style>

<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { compositorStore, type ViewportModel } from '../stores/compositorStore';
  import ViewportPane from './ViewportPane.svelte';
  import type { TouchRouter } from '../touch/TouchRouter';
  import type { StreamRuntime } from '../runtime/StreamRuntime';
  import type { PaneId } from '../protocol';

  export let touchRouter: TouchRouter;
  export let runtime: StreamRuntime;

  let host: HTMLDivElement;
  let resizer: HTMLButtonElement;
  let controls: HTMLDivElement;
  let resizeObserver: ResizeObserver;
  let resizing = false;

  onMount(() => {
    resizeObserver = new ResizeObserver(() => {
      touchRouter.updateHost(host.getBoundingClientRect());
      sendCurrentLayout();
    });
    resizeObserver.observe(host);
    touchRouter.updateHost(host.getBoundingClientRect());
    sendCurrentLayout();
  });

  onDestroy(() => resizeObserver?.disconnect());

  $: visibleViewports = Array.from($compositorStore.viewports.values()).filter((viewport) => viewport.visible);
  $: splitActive = $compositorStore.layoutMode === 'split' && visibleViewports.length >= 2;
  $: leftPane = $compositorStore.splitReversed ? 'secondary' : 'primary';
  $: rightPane = $compositorStore.splitReversed ? 'primary' : 'secondary';
  $: boundary = boundaryPercent();
  $: if (host) {
    updateSplitChrome();
    sendCurrentLayout();
  }

  function paneStyle(pane: string): string {
    if (!splitActive) return 'left:0;right:0;width:100%;';
    const primaryWidth = Math.round($compositorStore.splitRatio * 1000) / 10;
    if (!$compositorStore.splitReversed) {
      if (pane === 'secondary') return `left:${primaryWidth}%;right:0;width:${100 - primaryWidth}%;`;
      return `left:0;width:${primaryWidth}%;right:auto;`;
    }
    if (pane === 'secondary') return `left:0;width:${100 - primaryWidth}%;right:auto;`;
    return `left:${100 - primaryWidth}%;right:0;width:${primaryWidth}%;`;
  }

  function boundaryPercent(): number {
    const primary = Math.round($compositorStore.splitRatio * 1000) / 10;
    return $compositorStore.splitReversed ? 100 - primary : primary;
  }

  function beginResize(event: PointerEvent) {
    event.preventDefault();
    resizing = true;
    updateSplitChrome();
    window.addEventListener('pointermove', resizeMove);
    window.addEventListener('pointerup', endResize, { once: true });
  }

  function resizeMove(event: PointerEvent) {
    if (!resizing || !host) return;
    const rect = host.getBoundingClientRect();
    const pos = clamp((event.clientX - rect.left) / rect.width, 0.22, 0.78);
    const nextRatio = $compositorStore.splitReversed ? 1 - pos : pos;
    localStorage.setItem('castla_split_ratio', String(nextRatio));
    compositorStore.update((state) => ({ ...state, splitRatio: nextRatio }));
    updateSplitChrome(nextRatio);
  }

  function endResize() {
    resizing = false;
    window.removeEventListener('pointermove', resizeMove);
  }

  function expand(pane: PaneId) {
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      viewports.forEach((viewport, key) => viewports.set(key, { ...viewport, visible: key === pane }));
      return { ...state, viewports, layoutMode: 'single' };
    });
  }

  function expandLeft() {
    expand(leftPane as PaneId);
  }

  function expandRight() {
    expand(rightPane as PaneId);
  }

  function swap() {
    compositorStore.update((state) => ({ ...state, splitReversed: !state.splitReversed }));
    updateSplitChrome();
  }

  function clamp(value: number, min: number, max: number) {
    return Math.min(max, Math.max(min, value));
  }

  function sendCurrentLayout() {
    if (!host) return;
    const rect = host.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    if (!splitActive) {
      const activePane = visibleViewports[0]?.pane ?? 'primary';
      const hiddenPane: PaneId = activePane === 'primary' ? 'secondary' : 'primary';
      runtime.sendLayout([
        {
          id: activePane,
          width: rect.width,
          height: rect.height,
          visible: true
        },
        {
          id: hiddenPane,
          width: rect.width,
          height: rect.height,
          visible: false
        }
      ]);
      return;
    }

    const ratio = $compositorStore.splitRatio;
    const primaryWidth = Math.max(320, Math.round(rect.width * ratio));
    const secondaryWidth = Math.max(320, Math.round(rect.width - primaryWidth));
    runtime.sendLayout([
      {
        id: leftPane,
        width: leftPane === 'primary' ? primaryWidth : secondaryWidth,
        height: rect.height,
        visible: true
      },
      {
        id: rightPane,
        width: rightPane === 'primary' ? primaryWidth : secondaryWidth,
        height: rect.height,
        visible: true
      }
    ]);
  }

  function updateSplitChrome(ratio = $compositorStore.splitRatio) {
    if (!splitActive) return;
    const boundaryValue = $compositorStore.splitReversed ? 100 - Math.round(ratio * 1000) / 10 : Math.round(ratio * 1000) / 10;
    if (resizer) {
      resizer.style.left = `${boundaryValue}%`;
    }
    if (controls) {
      controls.style.left = `${boundaryValue}%`;
    }
  }
</script>

<div bind:this={host} class="viewport-host">
  {#each visibleViewports as viewport (viewport.pane)}
    <ViewportPane {viewport} {touchRouter} {runtime} paneStyle={paneStyle(viewport.pane)} />
  {/each}

  {#if splitActive}
    <button bind:this={resizer} class="split-resizer" style={`left:${boundary}%`} aria-label="Resize split" on:pointerdown={beginResize}></button>
    <div bind:this={controls} class="split-controls" style={`left:${boundary}%`}>
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
    content: '';
    position: absolute;
    top: 0;
    bottom: 0;
    left: 10px;
    width: 2px;
    background: rgb(0 229 255 / 0.65);
    box-shadow: 0 0 12px rgb(0 229 255 / 0.65);
  }

  .split-resizer::after {
    content: '';
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

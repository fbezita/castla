<script lang="ts">
  import { onDestroy, onMount, tick } from 'svelte';
  import type { ViewportModel } from '../stores/compositorStore';
  import type { TouchRouter } from '../touch/TouchRouter';
  import type { StreamRuntime } from '../runtime/StreamRuntime';
  import type { DecoderBackend } from '../decoder/DecoderBackend';
  import { WebCodecsBackend } from '../decoder/WebCodecsBackend';
  import { JMuxerBackend } from '../decoder/JMuxerBackend';

  export let viewport: ViewportModel;
  export let touchRouter: TouchRouter;
  export let runtime: StreamRuntime;
  export let paneStyle = '';

  let canvas: HTMLCanvasElement;
  let video: HTMLVideoElement;
  let host: HTMLElement;
  let decoder: DecoderBackend | undefined;
  let detachVideo: (() => void) | undefined;
  let detachConnection: (() => void) | undefined;
  let decoderError = '';
  let backend: 'jmuxer' | 'webcodecs' = 'jmuxer';
  let currentGeneration = -1;

  onMount(async () => {
    await tick();
    detachConnection = runtime.onConnectionChange(async (connected) => {
      if (!connected) {
        hideSurface();
        return;
      }
      if (decoder) {
        await refreshDecoder();
      }
    });
    await initializeDecoder();
  });

  onDestroy(() => {
    detachConnection?.();
    detachVideo?.();
    decoder?.destroy();
  });

  $: if (viewport.generation > 0 && viewport.generation !== currentGeneration) {
    currentGeneration = viewport.generation;
    if (decoder) {
      refreshDecoder();
    }
  }

  $: if (!viewport.committed) {
    hideSurface();
  }

  function markReady() {
    canvas.style.opacity = '1';
    video.style.opacity = '1';
  }

  function hideSurface() {
    if (canvas) canvas.style.opacity = '0';
    if (video) video.style.opacity = '0';
  }

  async function initializeDecoder() {
    try {
      decoderError = '';
      const wantsWebCodecs = new URLSearchParams(location.search).get('decoder') === 'webcodecs';
      const canUseWebCodecs = wantsWebCodecs && window.isSecureContext && 'VideoDecoder' in window;
      if (canUseWebCodecs) {
        backend = 'webcodecs';
        decoder = new WebCodecsBackend(() => markReady(), () => runtime.requestKeyframe(viewport.pane));
        await decoder.initialize(canvas);
        runtime.setCodec(viewport.pane, 'h264', 'High');
      } else {
        backend = 'jmuxer';
        decoder = new JMuxerBackend(
          () => markReady(),
          (event, detail) => runtime.reportDecoderStatus(viewport.pane, event, detail)
        );
        await decoder.initialize(video);
        runtime.setCodec(viewport.pane, 'h264', 'High');
      }
      detachVideo ??= runtime.attachVideo(viewport.pane, (frame) => decoder?.decode(frame));
      runtime.requestKeyframe(viewport.pane);
    } catch (error) {
      decoderError = error instanceof Error ? error.message : String(error);
    }
  }

  async function refreshDecoder() {
    detachVideo?.();
    detachVideo = undefined;
    decoder?.destroy();
    decoder = undefined;
    hideSurface();
    video.pause();
    video.removeAttribute('src');
    video.load();
    await tick();
    await initializeDecoder();
  }
</script>

<section
  bind:this={host}
  class:pending={!viewport.committed}
  class="viewport-pane"
  style={paneStyle}
  data-pane={viewport.pane}
  on:pointerdown={(event) => touchRouter.pointer(event, viewport)}
  on:pointermove={(event) => touchRouter.pointer(event, viewport)}
  on:pointerup={(event) => touchRouter.pointer(event, viewport)}
  on:pointercancel={(event) => touchRouter.pointer(event, viewport)}
>
  <video class:hidden={backend !== 'jmuxer'} bind:this={video} id={`video-${viewport.pane}`} playsinline muted autoplay></video>
  <canvas class:hidden={backend !== 'webcodecs'} bind:this={canvas} id={`canvas-${viewport.pane}`}></canvas>
  {#if decoderError}
    <div class="decoder-error">{decoderError}</div>
  {/if}
</section>

<style>
  .viewport-pane {
    position: absolute;
    inset: 0;
    overflow: hidden;
    background: #05070a;
    touch-action: none;
  }

  .viewport-pane.pending {
    opacity: 1;
  }

  video,
  canvas {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: fill;
  }

  .hidden {
    display: none;
  }

  .decoder-error {
    position: absolute;
    left: 12px;
    bottom: 12px;
    max-width: 420px;
    padding: 8px 10px;
    border-radius: 8px;
    background: rgb(140 20 30 / 0.82);
    color: white;
    font-size: 12px;
    z-index: 20;
  }
</style>

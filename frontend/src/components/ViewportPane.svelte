<script lang="ts">
  import { onDestroy, onMount, tick } from "svelte";
  import type { ViewportModel } from "../stores/compositorStore";
  import type { StreamRuntime } from "../runtime/StreamRuntime";
  import type { DecoderBackend } from "../decoder/DecoderBackend";
  import { WebCodecsBackend } from "../decoder/WebCodecsBackend";
  import { JMuxerBackend } from "../decoder/JMuxerBackend";

  export let viewport: ViewportModel;
  export let runtime: StreamRuntime;
  export let paneStyle = "";
  export let fitMode: "contain" | "fill" = "contain";

  let canvas: HTMLCanvasElement;
  let video: HTMLVideoElement;
  let host: HTMLElement;
  let decoder: DecoderBackend | undefined;
  let detachVideo: (() => void) | undefined;
  let detachConnection: (() => void) | undefined;
  let detachSession: (() => void) | undefined;
  let decoderError = "";
  let backend: "jmuxer" | "webcodecs" = "jmuxer";
  let currentGeneration = -1;
  let decoderSession = 0;
  let stallTimer = 0;
  let lastRecoveryAt = 0;
  let recoveryAttempt = 0;
  let lastDecoderRecoveryAt = 0;

  onMount(async () => {
    await tick();
    detachConnection = runtime.onConnectionChange(async (connected) => {
      if (!connected) {
        return;
      }
      runtime.requestKeyframe(viewport.pane);
    });
    detachSession = runtime.onSessionChange(async () => {
      currentGeneration = -1;
      await refreshDecoder();
    });
    stallTimer = window.setInterval(() => {
      void maybeRecoverStalledPane();
    }, 1200);
    await refreshDecoder();
  });

  onDestroy(() => {
    window.clearInterval(stallTimer);
    detachConnection?.();
    detachSession?.();
    detachVideo?.();
    decoder?.destroy();
  });

  $: if (viewport.generation > 0 && viewport.generation !== currentGeneration) {
    currentGeneration = viewport.generation;
    recoveryAttempt = 0;
    if (decoder) {
      refreshDecoder();
    }
  }

  $: if (!viewport.committed) {
    hideSurface();
  }

  function markReady() {
    canvas.style.opacity = "1";
    video.style.opacity = "1";
  }

  function hideSurface() {
    if (canvas) canvas.style.opacity = "0";
    if (video) video.style.opacity = "0";
  }

  async function initializeDecoder(session: number) {
    try {
      decoderError = "";
      const wantsWebCodecs =
        new URLSearchParams(location.search).get("decoder") === "webcodecs";
      const canUseWebCodecs =
        wantsWebCodecs && window.isSecureContext && "VideoDecoder" in window;
      let nextDecoder: DecoderBackend | undefined;
      if (canUseWebCodecs) {
        backend = "webcodecs";
        nextDecoder = new WebCodecsBackend(
          () => markReady(),
          () => runtime.requestKeyframe(viewport.pane),
        );
        await nextDecoder.initialize(canvas);
        runtime.setCodec(viewport.pane, "h264", "High");
      } else {
        backend = "jmuxer";
        nextDecoder = new JMuxerBackend(
          () => markReady(),
          (event, detail) =>
            runtime.reportDecoderStatus(viewport.pane, event, detail),
        );
        await nextDecoder.initialize(video);
        runtime.setCodec(viewport.pane, "h264", "High");
      }

      if (session !== decoderSession) {
        nextDecoder.destroy();
        return;
      }

      decoder = nextDecoder;
      detachVideo = runtime.attachVideo(viewport.pane, (frame) =>
        decoder?.decode(frame),
      );
      runtime.requestKeyframe(viewport.pane);
    } catch (error) {
      if (session === decoderSession) {
        decoderError = error instanceof Error ? error.message : String(error);
      }
    }
  }

  async function refreshDecoder() {
    const session = ++decoderSession;
    detachVideo?.();
    detachVideo = undefined;
    decoder?.destroy();
    decoder = undefined;
    hideSurface();
    decoderError = "";
    if (video) {
      video.pause();
      video.removeAttribute("src");
      video.load();
    }
    await tick();
    await initializeDecoder(session);
  }

  async function maybeRecoverStalledPane() {
    if (!viewport.visible || !decoder) return;
    if (currentGeneration <= 0) return;
    const sample = runtime.health.sample(viewport.pane);
    if (!sample.decoderStalled) {
      recoveryAttempt = 0;
      return;
    }
    const now = performance.now();
    if (now - lastRecoveryAt < 5000) return;
    lastRecoveryAt = now;
    recoveryAttempt += 1;
    runtime.health.beginRecovery(viewport.pane, 5000);
    runtime.reportDecoderStatus(
      viewport.pane,
      "stallRecover",
      `generation=${currentGeneration} attempt=${recoveryAttempt}`,
    );
    if (recoveryAttempt >= 3 && now - lastDecoderRecoveryAt > 20000) {
      lastDecoderRecoveryAt = now;
      await refreshDecoder();
    }
    runtime.recoverPaneStream(viewport.pane);
  }
</script>

<section
  bind:this={host}
  class:pending={!viewport.committed}
  class="viewport-pane"
  style={paneStyle}
  data-pane={viewport.pane}
>
  <video
    class:hidden={backend !== "jmuxer"}
    class:fill-mode={fitMode === "fill"}
    bind:this={video}
    id={`video-${viewport.pane}`}
    playsinline
    muted
    autoplay
  ></video>
  <canvas
    class:hidden={backend !== "webcodecs"}
    class:fill-mode={fitMode === "fill"}
    bind:this={canvas}
    id={`canvas-${viewport.pane}`}
  ></canvas>
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
    object-fit: contain;
    object-position: center;
  }

  video.fill-mode,
  canvas.fill-mode {
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

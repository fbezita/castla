<script lang="ts">
  import { onDestroy, onMount, tick } from "svelte";
  import { compositorStore, type ViewportModel } from "../stores/compositorStore";
  import { t } from "../lib/i18n";
  import { connectionOverlayDelayMs } from "../lib/connectionUi";
  import type { StreamRuntime } from "../runtime/StreamRuntime";
  import type { DecoderBackend } from "../decoder/DecoderBackend";
  import { WebCodecsBackend } from "../decoder/WebCodecsBackend";
  import { JMuxerBackend } from "../decoder/JMuxerBackend";

  export let viewport: ViewportModel;
  export let runtime: StreamRuntime;
  export let paneStyle = "";
  export let fitMode: "contain" | "fill" = "contain";
  export let resizingSplit = false;
  export let activeTouchPanesSize = 0;

  let canvas: HTMLCanvasElement;
  let video: HTMLVideoElement;
  let host: HTMLElement;
  let decoder: DecoderBackend | undefined;
  let detachVideo: (() => void) | undefined;
  let detachConnection: (() => void) | undefined;
  let detachSession: (() => void) | undefined;
  let decoderError = "";
  let backend: "jmuxer" | "webcodecs" = "webcodecs";
  let currentGeneration = -1;
  let decoderSession = 0;
  let stallTimer = 0;
  let lastRecoveryAt = 0;
  let recoveryAttempt = 0;
  let lastDecoderRecoveryAt = 0;

  // Reconnection tracking state
  let isConnected = false;
  let hasConnected = false;
  let showConnectionOverlay = false;
  let connectionOverlayTimer = 0;

  onMount(async () => {
    await tick();
    detachConnection = runtime.onConnectionChange(async (connected) => {
      window.clearTimeout(connectionOverlayTimer);
      isConnected = connected;
      if (connected) {
        hasConnected = true;
        showConnectionOverlay = false;
      }
      if (!connected) {
        connectionOverlayTimer = window.setTimeout(() => {
          if (!isConnected) showConnectionOverlay = true;
        }, connectionOverlayDelayMs(hasConnected));
        return;
      }
      runtime.requestKeyframe(viewport.pane);
    });
    detachSession = runtime.onSessionChange(async () => {
      currentGeneration = -1;
      hasCommittedOnce = false;
      runtime.requestKeyframe(viewport.pane);
    });
    stallTimer = window.setInterval(() => {
      void maybeRecoverStalledPane();
    }, 1200);
    await refreshDecoder();
  });

  onDestroy(() => {
    window.clearInterval(stallTimer);
    window.clearTimeout(connectionOverlayTimer);
    detachConnection?.();
    detachSession?.();
    detachVideo?.();
    decoder?.destroy();
  });

  $: if (viewport.generation > 0 && viewport.generation !== currentGeneration) {
    currentGeneration = viewport.generation;
    recoveryAttempt = 0;
    if (decoder) {
      runtime.requestKeyframe(viewport.pane);
    }
  }

  $: decoder?.setVideoLatencyMs(viewport.videoLatencyMs ?? 0);

  $: if (!viewport.committed) {
    hasCommittedOnce = false;
    hideSurface();
  }

  let hasCommittedOnce = false;

  function markReady() {
    canvas.style.opacity = "1";
    video.style.opacity = "1";
    hasCommittedOnce = true;
  }

  function hideSurface() {
    // Keep last frame visible during transitions to achieve seamless switches without black blinks
    if (hasCommittedOnce) {
      return;
    }
    if (canvas) canvas.style.opacity = "0";
    if (video) video.style.opacity = "0";
  }

  async function initializeDecoder(session: number) {
    try {
      decoderError = "";
      const decoderParam = new URLSearchParams(location.search).get("decoder");
      const forcesJmuxer = decoderParam === "jmuxer" || decoderParam === "mse";
      const canUseWebCodecs =
        !forcesJmuxer && window.isSecureContext && "VideoDecoder" in window;
      let nextDecoder: DecoderBackend | undefined;
      if (canUseWebCodecs) {
        backend = "webcodecs";
        nextDecoder = new WebCodecsBackend(
          () => markReady(),
          () => runtime.requestKeyframe(viewport.pane),
          (event, detail) => runtime.reportDecoderStatus(viewport.pane, event, detail),
          () => runtime.isScreenOff,
          () => runtime.isVideoFrozen,
        );
        nextDecoder.setVideoLatencyMs(viewport.videoLatencyMs ?? 0);
        await nextDecoder.initialize(canvas);
        runtime.setCodec(viewport.pane, "h264", "High");
      } else {
        backend = "jmuxer";
        nextDecoder = new JMuxerBackend(
          () => markReady(),
          (event, detail) =>
            runtime.reportDecoderStatus(viewport.pane, event, detail),
        );
        nextDecoder.setVideoLatencyMs(viewport.videoLatencyMs ?? 0);
        await nextDecoder.initialize(video);
        runtime.setCodec(viewport.pane, "h264", "Baseline");
      }

      console.warn(
        `[CastlaDecoder:${viewport.pane}] backend=${backend} secure=${window.isSecureContext} videoDecoder=${typeof VideoDecoder} decoderParam=${new URLSearchParams(location.search).get("decoder")}`,
      );

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
    if (resizingSplit || activeTouchPanesSize > 0) return;
    if (!viewport.visible || !decoder) return;
    if (currentGeneration <= 0) return;
    // Samsung may pause VirtualDisplay frames during physical screen-off.
    // Keep the last canvas frame and defer decoder recovery until SCREEN_ON.
    if (runtime.isScreenOff) return;
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

  <!-- Premium Glassmorphism Reconnection Overlay UI -->
  {#if showConnectionOverlay && !isConnected && viewport.visible}
    <div class="reconnect-overlay">
      <div class="spinner"></div>
      <p class="reconnect-text">
        {t($compositorStore.language, hasConnected ? "reconnecting" : "serverUnavailable")}
      </p>
    </div>
  {/if}

</section>

<style>
  .viewport-pane {
    position: absolute;
    inset: 0;
    overflow: hidden;
    background: #05070a;
    touch-action: none;
    border: 1px solid rgba(255, 255, 255, 0.16);
    border-radius: 0;
    box-sizing: border-box;
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


  .reconnect-overlay {
    position: absolute;
    inset: 0;
    z-index: 25; /* Higher than decoderError but below app global modals */
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    background: radial-gradient(
      circle,
      rgba(12, 22, 34, 0.75) 0%,
      rgba(5, 7, 10, 0.9) 100%
    );
    backdrop-filter: blur(8px) saturate(140%);
    -webkit-backdrop-filter: blur(8px) saturate(140%);
    gap: 20px;
    pointer-events: none; /* Crucial: do not block touch/input pipeline */
    animation: fadeIn 0.4s cubic-bezier(0.16, 1, 0.3, 1) forwards;
  }

  .spinner {
    position: relative;
    width: 54px;
    height: 54px;
    border-radius: 50%;
    background: conic-gradient(transparent 10%, #00e5ff);
    -webkit-mask: radial-gradient(
      farthest-side,
      transparent calc(100% - 4px),
      #000 0
    );
    mask: radial-gradient(farthest-side, transparent calc(100% - 4px), #000 0);
    animation: spin 1s linear infinite;
  }

  .spinner::after {
    content: "";
    position: absolute;
    inset: 0;
    border-radius: 50%;
    box-shadow: 0 0 15px rgba(0, 229, 255, 0.45);
    filter: blur(1px);
  }

  .reconnect-text {
    color: #e2e8f0;
    font-family:
      "Outfit",
      "Inter",
      -apple-system,
      sans-serif;
    font-size: 14px;
    font-weight: 500;
    letter-spacing: 0.5px;
    text-align: center;
    text-shadow: 0 2px 10px rgba(0, 0, 0, 0.5);
    animation: pulseText 2s ease-in-out infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  @keyframes fadeIn {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  @keyframes pulseText {
    0%,
    100% {
      opacity: 0.85;
    }
    50% {
      opacity: 0.55;
    }
  }
</style>

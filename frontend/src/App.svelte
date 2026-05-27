<script lang="ts">
  import ViewportHost from "./components/ViewportHost.svelte";
  import DiagnosticsOverlay from "./components/DiagnosticsOverlay.svelte";
  import AppLauncher from "./components/AppLauncher.svelte";
  import {
    compositorStore,
    resetCompositorStore,
  } from "./stores/compositorStore";
  import { BrowserCompositor } from "./compositor/BrowserCompositor";
  import { StreamRuntime } from "./runtime/StreamRuntime";
  import { TouchRouter, mapViewportPoint } from "./touch/TouchRouter";
  import { ImeBridge } from "./ime/ImeBridge";

  let runtime: StreamRuntime;
  let compositor: BrowserCompositor;
  let touchRouter: TouchRouter;
  let imeBridge: ImeBridge;
  let frontendResetEpoch = 0;
  let runtimeEpoch = 0;
  let showDiagnostics = false;
  let frontendResetCleanup: (() => void) | undefined;
  const JMUXER_SCRIPT_SRC = "/js/jmuxer.min.js";

  let imeActiveCleanup: (() => void) | undefined;
  let remoteTextMode = false;

  const REMOTE_IME_REFOCUS_DELAY_MS = 120;
  let intentionalBlur = false;
  let layoutTransitioning = false;
  let androidFocusStillEditable = false;
  let canvasTapped = false;
  let canvasTappedTimeout: number | null = null;

  let lastCanvasTap: { pane: string; x: number; y: number } | null = null;
  let suppressImeFocusUntil = 0;

  function createRuntimeGraph(): void {
    runtime = new StreamRuntime(location.host);
    compositor = new BrowserCompositor(runtime, compositorStore);
    touchRouter = new TouchRouter(runtime);
    imeBridge = new ImeBridge(runtime.control);

    // Maintain and restore input focus securely under user gesture contexts.
    // Important: viewport taps are already sent through TouchRouter as normal
    // touch events. The IME tapOutside message must not inject another tap;
    // it is only a focus/IME cleanup signal.
    const gestureFocusListener = (event: PointerEvent): void => {
      const target = event.target as HTMLElement | null;
      const paneElement = target?.closest<HTMLElement>(".viewport-pane");
      const imeProxy = document.querySelector(
        ".ime-proxy",
      ) as HTMLTextAreaElement | null;

      if (paneElement) {
        const paneId = paneElement.dataset.pane ?? "primary";
        const viewport = Array.from($compositorStore.viewports.values()).find(
          (v) => v.pane === paneId,
        );

        const mapped = viewport
          ? mapViewportPoint(
              event.clientX,
              event.clientY,
              paneElement.getBoundingClientRect(),
              viewport.width,
              viewport.height,
              "contain",
              false,
            )
          : null;

        if (!mapped) {
          console.warn("[ImeBridge] viewport tap could not be mapped", {
            paneId,
          });
          return;
        }

        canvasTapped = true;
        lastCanvasTap = { pane: paneId, x: mapped.x, y: mapped.y };
        if (canvasTappedTimeout) {
          window.clearTimeout(canvasTappedTimeout);
        }
        canvasTappedTimeout = window.setTimeout(() => {
          canvasTapped = false;
        }, 350);

        // console.log("[ImeBridge] viewport tapped", lastCanvasTap);

        // If the remote editor is not active, this is just a normal map tap.
        // Do not send tapOutside because the normal TouchRouter event is already
        // going to the Android app. Sending both causes duplicate taps/double-tap zoom.
        if (!remoteTextMode && !androidFocusStillEditable) {
          return;
        }

        // console.log(
        //   "[ImeBridge] Sending tapOutside cleanup from viewport pointerdown.",
        // );

        intentionalBlur = true;
        suppressImeFocusUntil = performance.now() + 700;

        runtime.control.send({
          type: "ime",
          op: "tapOutside",
        });

        imeProxy?.blur();

        window.setTimeout(() => {
          intentionalBlur = false;
          lastCanvasTap = null;
        }, 300);
        return;
      }

      if (remoteTextMode) {
        if (imeProxy && document.activeElement !== imeProxy) {
          // console.log(
          //   "[ImeBridge] Restoring focus inside user gesture context",
          // );
          imeProxy.focus();
        }
      }
    };
    window.addEventListener("pointerdown", gestureFocusListener);

    // Focus or blur the hidden ime-proxy element based on Android IME focus session status
    let lastInstanceId: string | null = null;
    const msgCleanup = runtime.control.onMessage((msg) => {
      if (msg.type === "serverInit") {
        const nextId = String((msg as any).instanceId ?? "unknown");
        if (lastInstanceId && lastInstanceId !== nextId) {
          console.warn("[CastlaSession] Server reboot detected! Forcing session hardReset.");
          lastInstanceId = nextId;
          hardReset("server_reboot");
          return;
        }
        lastInstanceId = nextId;
      }
      if (msg.type === "ime_active") {
        const active = (msg as any).focused === true;

        if (active && performance.now() < suppressImeFocusUntil) {
          // console.log(
          //   "[ImeBridge] Suppressing ime_active=true during tapOutside cooldown",
          // );
          return;
        }

        remoteTextMode = active;
        androidFocusStillEditable = active;
        const imeProxy = document.querySelector(
          ".ime-proxy",
        ) as HTMLTextAreaElement | null;
        if (imeProxy) {
          if (active) {
            // console.log(
            //   "[ImeBridge] Focusing hidden ime-proxy input due to remote focus event",
            // );
            imeProxy.focus();
          } else {
            // console.log(
            //   "[ImeBridge] Blurring hidden ime-proxy input due to remote blur event",
            // );
            imeProxy.blur();
          }
        }
      }
    });

    imeActiveCleanup = () => {
      msgCleanup();
      window.removeEventListener("pointerdown", gestureFocusListener);
    };

    frontendResetCleanup = runtime.onFrontendReset(() => {
      (document.activeElement as HTMLElement | null)?.blur?.();
      touchRouter.dispose();
      touchRouter = new TouchRouter(runtime);
      imeBridge = new ImeBridge(runtime.control);
      frontendResetEpoch += 1;
    });
    compositor.start();
  }

  async function hardReset(reason: string): Promise<void> {
    console.info("[CastlaSession] hard_reset_begin", {
      reason,
      appLaunchSequence: runtime.currentAppLaunchSequence(),
      sessionEpoch: runtime.currentSessionEpoch(),
      runtimeEpoch,
    });
    (document.activeElement as HTMLElement | null)?.blur?.();
    frontendResetCleanup?.();
    frontendResetCleanup = undefined;
    imeActiveCleanup?.();
    imeActiveCleanup = undefined;
    touchRouter.dispose();
    compositor.dispose();
    runtime.dispose();
    resetCompositorStore();
    await reloadJmuxerScript();
    // Introduce a hardware recovery cooldown delay to allow the native decoder
    // to cleanly garbage collect and let the Android WMS finish active window transitions.
    await new Promise<void>((resolve) => setTimeout(resolve, 1200));
    createRuntimeGraph();

    frontendResetEpoch = 0;
    runtimeEpoch += 1;
  }

  createRuntimeGraph();

  console.info("[CastlaSession] page_boot", {
    href: location.href,
    ts: Date.now(),
  });

  (window as Window & { castlaDebug?: Record<string, unknown> }).castlaDebug = {
    touchReset: (reason = "manual_debug") => runtime.resetTouchState(reason),
    controlReconnect: () => runtime.control.reconnectNow(),
    serverRearm: () => runtime.control.send({ type: "debugBrowserRearm" }),
    serverTeardown: () =>
      runtime.control.send({ type: "debugBrowserTeardown" }),
    socketCycle: () => runtime.control.send({ type: "debugSocketCycle" }),
    hardReset: (reason = "manual_debug") => {
      hardReset(reason);
      runtime.updateLayout();
    },
    reloadJmuxer: () => reloadJmuxerScript(),
    fullReload: () => {
      console.info("[CastlaSession] full_reload", {
        appLaunchSequence: runtime.currentAppLaunchSequence(),
        sessionEpoch: runtime.currentSessionEpoch(),
        ts: Date.now(),
      });
      location.reload();
    },
    paneRecover: (pane = "primary") => runtime.recoverPaneStream(pane),
    softReconnect: (pane = "primary") => runtime.softReconnect(pane),
    frontendReset: (reason = "manual_debug") =>
      runtime.resetFrontendInteraction(reason),
    sessionSnapshot: () => ({
      appLaunchSequence: runtime.currentAppLaunchSequence(),
      sessionEpoch: runtime.currentSessionEpoch(),
      runtimeEpoch,
      liveRouterCount: TouchRouter.getLiveRouterCount(),
    }),
    routerSnapshot: () => touchRouter.debugSnapshot(),
    toggleDiagnostics: () => {
      showDiagnostics = !showDiagnostics;
    },
  };

  async function reloadJmuxerScript(): Promise<void> {
    console.info("[CastlaSession] reload_jmuxer_begin", {
      ts: Date.now(),
      hasJMuxer: Boolean((window as Window & { JMuxer?: unknown }).JMuxer),
    });
    const existing = Array.from(document.querySelectorAll("script")).find(
      (script) => {
        const src = script.getAttribute("src") ?? "";
        return src.includes("jmuxer.min.js");
      },
    );
    if (existing) {
      existing.remove();
    }
    try {
      delete (window as Window & { JMuxer?: unknown }).JMuxer;
    } catch {}
    await new Promise<void>((resolve, reject) => {
      const script = document.createElement("script");
      script.src = `${JMUXER_SCRIPT_SRC}?ts=${Date.now()}`;
      script.async = false;
      script.onload = () => resolve();
      script.onerror = () =>
        reject(new Error("Failed to reload JMuxer script"));
      document.head.appendChild(script);
    });
    console.info("[CastlaSession] reload_jmuxer_done", {
      ts: Date.now(),
      hasJMuxer: Boolean((window as Window & { JMuxer?: unknown }).JMuxer),
    });
  }
</script>

<main class="app-shell">
  {#key `${runtimeEpoch}:${frontendResetEpoch}`}
    <ViewportHost {touchRouter} {runtime} />
  {/key}
  {#key runtimeEpoch}
    <AppLauncher {runtime} />
  {/key}
  {#if showDiagnostics}
    <DiagnosticsOverlay />
  {/if}
  <textarea
    class="ime-proxy"
    on:compositionstart={(event) => imeBridge.compositionStart(event)}
    on:compositionupdate={(event) => imeBridge.compositionUpdate(event)}
    on:compositionend={(event) => imeBridge.compositionEnd(event)}
    on:input={(event) => imeBridge.input(event)}
    on:keydown={(event) => imeBridge.keydown(event)}
    on:blur={(event) => {
      if (remoteTextMode) {
        // Prevent keyboard collapse by restoring focus asynchronously inside the microtask queue.
        // We use a latency-aware buffer to wait and see if Android notifies us that the remote editor
        // actually lost focus (ime_active -> focused=false). If so, we safely yield instead of refocusing.
        setTimeout(() => {
          if (
            remoteTextMode &&
            !intentionalBlur &&
            !layoutTransitioning &&
            androidFocusStillEditable &&
            !canvasTapped
          ) {
            const target = event.target;
            if (target && typeof target.focus === "function") {
              // console.log(
              //   "[ImeBridge] Restoring focus after blur buffer timeout",
              // );
              target.focus();
            }
          }
        }, REMOTE_IME_REFOCUS_DELAY_MS);
      }
    }}
  ></textarea>
</main>

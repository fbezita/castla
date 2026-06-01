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
  import { TouchRouter } from "./touch/TouchRouter";
  import { ImeBridge } from "./ime/ImeBridge";
  import { triggerDump, isLoggingEnabled, setLoggingEnabled } from "./utils/debugLogger";

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

  type ImeFsmState = 'IDLE' | 'ANDROID_FOCUSING' | 'READY' | 'BLUR_PENDING' | 'RECOVERING';
  let imeState: ImeFsmState = 'IDLE';
  let currentSessionId = 0;

  let blurDebounceTimer: number | undefined;
  let focusRetryTimer: number | undefined;

  function setImeState(newState: ImeFsmState) {
    console.warn(`[FSM] state change: ${imeState} -> ${newState}`);
    imeState = newState;
    remoteTextMode = newState !== 'IDLE';
  }

  function attemptProxyFocus(retryCount = 0) {
    if (focusRetryTimer) {
      window.clearTimeout(focusRetryTimer);
      focusRetryTimer = undefined;
    }

    const imeProxy = document.querySelector(".ime-proxy") as HTMLTextAreaElement | null;
    if (!imeProxy) {
      console.error("[PROXY_FOCUS] Proxy textarea not found in DOM");
      return;
    }

    console.warn(`[PROXY_FOCUS] attempt #${retryCount + 1}`, {
      exists: !!imeProxy,
      disabled: imeProxy.disabled,
      readOnly: imeProxy.readOnly,
      display: getComputedStyle(imeProxy).display,
      visibility: getComputedStyle(imeProxy).visibility,
      width: imeProxy.offsetWidth,
      height: imeProxy.offsetHeight,
      activeElement: document.activeElement?.tagName,
    });

    try {
      imeProxy.focus({ preventScroll: true });
    } catch (e) {
      console.error("[PROXY_FOCUS] Error calling focus()", e);
    }

    // Verify focus
    if (document.activeElement === imeProxy) {
      console.warn("[PROXY_FOCUS] verified successfully!");
      setImeState('READY');
    } else {
      console.warn(`[PROXY_FOCUS] verification failed. Active element is ${document.activeElement?.tagName}`);
      if (retryCount < 3) {
        focusRetryTimer = window.setTimeout(() => {
          attemptProxyFocus(retryCount + 1);
        }, 50);
      } else {
        console.error("[PROXY_FOCUS] Failed to acquire proxy focus after 3 retries");
      }
    }
  }

  const REMOTE_IME_REFOCUS_DELAY_MS = 120;
  let audioStarted = false;

  function isLocalEditableTarget(target: EventTarget | null): boolean {
    const el = target as HTMLElement | null;
    if (!el) return false;
    if (el.classList?.contains("ime-proxy")) return false;

    return (
      el instanceof HTMLInputElement ||
      el instanceof HTMLTextAreaElement ||
      el.isContentEditable ||
      Boolean(el.closest("[contenteditable='true']"))
    );
  }

  function createRuntimeGraph(): void {
    runtime = new StreamRuntime(location.host);
    (window as any).castlaRuntime = runtime;
    compositor = new BrowserCompositor(runtime, compositorStore);
    touchRouter = new TouchRouter(runtime);
    imeBridge = new ImeBridge(runtime.control);

    // Maintain and restore input focus securely under user gesture contexts.
    // Viewport taps should only help focus acquisition and never infer dismiss intent.
    const gestureFocusListener = (event: PointerEvent): void => {
      if (!audioStarted) {
        audioStarted = true;
        console.log("[Audio] First user gesture detected. Unmuting high-fidelity audio stream...");
        runtime.startAudio();
      }
      const target = event.target as HTMLElement | null;
      if (isLocalEditableTarget(target)) {
        console.log("[ImeBridge] User clicked local input, skipping proactive remote focus");
        return;
      }
      const paneElement = target?.closest<HTMLElement>(".viewport-pane");
      const imeProxy = document.querySelector(
        ".ime-proxy",
      ) as HTMLTextAreaElement | null;

      if (paneElement) {
        // Proactively grab text focus under direct User Gesture viewport touch context
        // to bypass modern browser asynchronous programmatic focus restrictions.
        if (imeProxy && document.activeElement !== imeProxy) {
          console.warn("[ImeBridge] Proactively focusing ime-proxy under User Gesture viewport click context");
          imeProxy.focus();
        }
        return;
      }

      if (imeState !== 'IDLE') {
        if (imeProxy && document.activeElement !== imeProxy) {
          imeProxy.focus();
        }
      }
    };

    const globalKeydownListener = (event: KeyboardEvent): void => {
      console.warn("[IME_MODE]", {
        imeState,
        activeElement: document.activeElement?.tagName,
        key: event.key
      });

      const activeEl = document.activeElement;
      if (isLocalEditableTarget(activeEl)) {
        console.warn("[ImeBridge] skipping global keydown intercept for local input");
        return;
      }

      const imeProxyElement = document.querySelector(
        ".ime-proxy",
      ) as HTMLTextAreaElement | null;

      if (
        remoteTextMode === true &&
        imeState !== "READY" &&
        document.activeElement !== imeProxyElement &&
        !event.defaultPrevented
      ) {
        const key = event.key;
        // Exclude system/modifier keys that should not be forwarded as input keys
        const isExcluded = ['Shift', 'Control', 'Alt', 'Meta', 'CapsLock', 'Tab', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F8', 'F9', 'F10', 'F11', 'F12'].includes(key);
        if (!isExcluded) {
          console.warn("[ImeBridge] global keydown forwarding fallback", {
            key: event.key,
            active: document.activeElement?.tagName,
          });
          imeBridge.keydown(event);
          event.preventDefault();
        }
      }
    };

    window.addEventListener("pointerdown", gestureFocusListener);
    window.addEventListener("keydown", globalKeydownListener);

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
      if (msg.type === "ime" && msg.op === "androidFocusChanged") {
        const incomingSessionId = Number((msg as any).sessionId ?? 0);
        const active = (msg as any).focused === true;
        const targetPkg = (msg as any).packageName;

        console.warn("[ANDROID_FOCUS] received event", {
          focused: active,
          packageName: targetPkg,
          sessionId: incomingSessionId,
          currentSessionId
        });

        // Ignore self package focus events to prevent loops/keyboard collapse
        if (targetPkg === "com.castla.mirror" || targetPkg === "com.castla.mirror.debug") {
          console.warn("[ANDROID_FOCUS] ignore self package focus event", targetPkg);
          return;
        }

        // Stale event prevention (sessionId reverse prevention)
        if (incomingSessionId < currentSessionId) {
          console.warn("[ANDROID_FOCUS] stale event ignored due to older sessionId", {
            incomingSessionId,
            currentSessionId
          });
          return;
        }
        currentSessionId = incomingSessionId;

        if (active) {
          if (blurDebounceTimer) {
            window.clearTimeout(blurDebounceTimer);
            blurDebounceTimer = undefined;
          }
          setImeState('ANDROID_FOCUSING');
          requestAnimationFrame(() => {
            attemptProxyFocus(0);
          });
        } else {
          if (blurDebounceTimer) {
            window.clearTimeout(blurDebounceTimer);
          }
          setImeState('BLUR_PENDING');
          blurDebounceTimer = window.setTimeout(() => {
            if (imeState === 'BLUR_PENDING') {
              const imeProxy = document.querySelector(
                ".ime-proxy",
              ) as HTMLTextAreaElement | null;
              if (imeProxy) {
                try {
                  imeProxy.blur();
                } catch (e) {
                  console.error("[ANDROID_FOCUS] Failed to blur imeProxy", e);
                }
              }
              setImeState('IDLE');
            }
            blurDebounceTimer = undefined;
          }, 200); // 200ms debounce
        }
      }
    });

    imeActiveCleanup = () => {
      msgCleanup();
      window.removeEventListener("pointerdown", gestureFocusListener);
      window.removeEventListener("keydown", globalKeydownListener);
      if (blurDebounceTimer) {
        window.clearTimeout(blurDebounceTimer);
        blurDebounceTimer = undefined;
      }
      if (focusRetryTimer) {
        window.clearTimeout(focusRetryTimer);
        focusRetryTimer = undefined;
      }
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
    isLoggingEnabled,
    setLoggingEnabled,
    triggerDump: (reason = "manual_debug") => triggerDump(runtime, reason),
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

  function handleProxyBlur(event: FocusEvent): void {
    const related = event.relatedTarget as HTMLElement | null;

    if (isLocalEditableTarget(related)) {
      console.warn("[ImeBridge] Refocus recovery bypassed immediately via relatedTarget");
      return;
    }

    window.setTimeout(() => {
      if (isLocalEditableTarget(document.activeElement)) {
        console.warn("[ImeBridge] Refocus recovery bypassed after delay via activeElement");
        return;
      }
      if (imeState === 'READY' || imeState === 'ANDROID_FOCUSING' || imeState === 'RECOVERING') {
        setImeState('RECOVERING');
        window.setTimeout(() => {
          if (imeState === 'RECOVERING') {
            attemptProxyFocus(0);
          }
        }, REMOTE_IME_REFOCUS_DELAY_MS);
      }
    }, 60);
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
    on:compositionstart={(event) => {
      const imeProxy = event.currentTarget;
      if (imeState === 'READY' && document.activeElement === imeProxy) {
        imeBridge.compositionStart(event);
      } else {
        event.preventDefault();
      }
    }}
    on:compositionupdate={(event) => {
      const imeProxy = event.currentTarget;
      if (imeState === 'READY' && document.activeElement === imeProxy) {
        imeBridge.compositionUpdate(event);
      } else {
        event.preventDefault();
      }
    }}
    on:compositionend={(event) => {
      const imeProxy = event.currentTarget;
      if (imeState === 'READY' && document.activeElement === imeProxy) {
        imeBridge.compositionEnd(event);
      } else {
        event.preventDefault();
      }
    }}
    on:input={(event) => {
      const imeProxy = event.currentTarget;
      console.warn("[INPUT_FORWARD] input event", {
        active: document.activeElement?.tagName,
        imeState,
        value: imeProxy.value,
      });
      if (imeState === 'READY' && document.activeElement === imeProxy) {
        imeBridge.input(event);
      } else {
        event.preventDefault();
      }
    }}
    on:keydown={(event) => {
      const imeProxy = event.currentTarget;
      console.warn("[INPUT_FORWARD] keydown event", {
        active: document.activeElement?.tagName,
        imeState,
        value: imeProxy.value,
      });
      if (imeState === 'READY' && document.activeElement === imeProxy) {
        imeBridge.keydown(event);
      } else {
        event.preventDefault();
      }
    }}
    on:blur={handleProxyBlur}
  ></textarea>
</main>

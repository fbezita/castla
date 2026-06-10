<script context="module" lang="ts">
  declare const __CASTLA_BUILD_TIMESTAMP__: string;
</script>

<script lang="ts">
  import { onDestroy } from "svelte";

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
  import {
    readOverlayUiScalePreference,
    writeOverlayUiScalePreference,
    type OverlayUiScalePreference,
  } from "./utils/overlayUiScalePreference";

  // References to tie components together for launch sequence state machine
  let viewportHostRef: any = undefined;
  let appLauncherRef: any = undefined;
  let runtime: StreamRuntime;
  let compositor: BrowserCompositor;
  let touchRouter: TouchRouter;
  let imeBridge: ImeBridge;
  let frontendResetEpoch = 0;
  let runtimeEpoch = 0;
  let showDiagnostics = false;
  let overlayUiScalePreference: OverlayUiScalePreference = readOverlayUiScalePreference();
  let overlayUiScale = 1;
  let frontendResetCleanup: (() => void) | undefined;
  const JMUXER_SCRIPT_SRC = "/js/jmuxer.min.js";
  const FRONTEND_BUILD_MARKER = "frontend_ime_guard_v4_20260601";

  let imeActiveCleanup: (() => void) | undefined;
  let remoteTextMode = false;

  type ImeFsmState = 'IDLE' | 'ANDROID_FOCUSING' | 'READY' | 'BLUR_PENDING' | 'RECOVERING';
  let imeState: ImeFsmState = 'IDLE';
  let currentSessionId = 0;
  let remoteEditableActive = false;
  let lastRemoteFocusPackage: string | null = null;
  let lastRemoteFocusSessionId = 0;

  let blurDebounceTimer: number | undefined;
  let focusRetryTimer: number | undefined;
  let lifecycleCleanup: (() => void) | undefined;
  (window as any).__CASTLA_VERBOSE_DIAGNOSTICS__ ??= false;

  function isVerboseFrontendDiagnostics(): boolean {
    return (window as any).__CASTLA_VERBOSE_DIAGNOSTICS__ === true;
  }

  function refreshOverlayUiScale(): void {
    overlayUiScale = overlayUiScalePreference;
  }

  function updateOverlayUiScalePreference(nextPreference: OverlayUiScalePreference): void {
    overlayUiScalePreference = nextPreference;
    writeOverlayUiScalePreference(nextPreference);
    refreshOverlayUiScale();
  }

  function verboseWarn(message: string, payload?: unknown) {
    if (!isVerboseFrontendDiagnostics()) return;
    console.warn(message, payload);
  }

  function sendVerboseFrontendDiag(tag: string, message: string, payload: Record<string, unknown>) {
    if (!isVerboseFrontendDiagnostics()) return;
    runtime?.control?.sendFrontendDiag(tag, message, payload);
  }

  function setImeState(newState: ImeFsmState) {
    verboseWarn(`[FSM] state change: ${imeState} -> ${newState}`);
    imeState = newState;
    remoteTextMode = newState !== 'IDLE';
  }

  function sendFrontendRuntimeDiag(
    tag: string,
    message: string,
    extra: Record<string, unknown> = {},
  ) {
    const payload = {
      href: location.href,
      visibilityState: document.visibilityState,
      readyState: document.readyState,
      activeElement: describeActiveElement(),
      ts: Date.now(),
      ...extra,
    };
    if (tag === "FRONTEND_ERROR" || isVerboseFrontendDiagnostics()) {
      console.warn(`[${tag}] ${message}`, payload);
      runtime?.control?.sendFrontendDiag(tag, message, payload);
    }
  }

  function describeActiveElement(): string {
    const active = document.activeElement as HTMLElement | null;
    if (!active) return "null";
    const tag = active.tagName || "unknown";
    const className = active.className ? String(active.className) : "";
    return className ? `${tag}.${className}` : tag;
  }

  function setRemoteEditableActive(next: boolean, reason: string) {
    remoteEditableActive = next;
    const payload = {
      reason,
      imeState,
      currentSessionId,
      lastRemoteFocusPackage,
      lastRemoteFocusSessionId,
      activeElement: describeActiveElement(),
    };
    verboseWarn(`[IME_DEBUG] remoteEditableActive=${next}`, payload);
    sendVerboseFrontendDiag("IME_DEBUG", `remoteEditableActive=${next}`, payload);
  }

  function attemptProxyFocus(reason: string, retryCount = 0) {
    if (focusRetryTimer) {
      window.clearTimeout(focusRetryTimer);
      focusRetryTimer = undefined;
    }

    const imeProxy = document.querySelector(".ime-proxy") as HTMLTextAreaElement | null;
    if (!imeProxy) {
      console.error("[PROXY_FOCUS] Proxy textarea not found in DOM");
      return;
    }

    const focusPayload = {
      retryCount,
      remoteEditableActive,
      imeState,
      currentSessionId,
      lastRemoteFocusPackage,
      lastRemoteFocusSessionId,
      activeElement: describeActiveElement(),
    };
    verboseWarn(`[IME_DEBUG] attemptProxyFocus reason=${reason}`, focusPayload);
    sendVerboseFrontendDiag("IME_DEBUG", `attemptProxyFocus reason=${reason}`, focusPayload);

    if (!remoteEditableActive) {
      verboseWarn(`[IME_DEBUG] attemptProxyFocus blocked reason=${reason}`, {
        retryCount,
        imeState,
        activeElement: describeActiveElement(),
      });
      return;
    }

    verboseWarn(`[PROXY_FOCUS] attempt #${retryCount + 1}`, {
      exists: !!imeProxy,
      disabled: imeProxy.disabled,
      readOnly: imeProxy.readOnly,
      display: getComputedStyle(imeProxy).display,
      visibility: getComputedStyle(imeProxy).visibility,
      width: imeProxy.offsetWidth,
      height: imeProxy.offsetHeight,
      activeElement: describeActiveElement(),
    });

    try {
      imeProxy.focus({ preventScroll: true });
    } catch (e) {
      console.error("[PROXY_FOCUS] Error calling focus()", e);
    }

    // Verify focus
    if (document.activeElement === imeProxy) {
      verboseWarn("[PROXY_FOCUS] verified successfully!");
      setImeState('READY');
    } else {
      verboseWarn(`[PROXY_FOCUS] verification failed. Active element is ${describeActiveElement()}`);
      if (retryCount < 3) {
        focusRetryTimer = window.setTimeout(() => {
          attemptProxyFocus(reason, retryCount + 1);
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

  function isEditableInputType(inputType: number | null | undefined): boolean {
    if (!inputType) return false;
    const TYPE_MASK_CLASS = 0x0000000f;
    const TYPE_CLASS_TEXT = 0x00000001;
    const TYPE_CLASS_NUMBER = 0x00000002;
    const TYPE_CLASS_PHONE = 0x00000003;
    const TYPE_CLASS_DATETIME = 0x00000004;
    const inputClass = inputType & TYPE_MASK_CLASS;
    return (
      inputClass === TYPE_CLASS_TEXT ||
      inputClass === TYPE_CLASS_NUMBER ||
      inputClass === TYPE_CLASS_PHONE ||
      inputClass === TYPE_CLASS_DATETIME
    );
  }

  function createRuntimeGraph(): void {
    runtime = new StreamRuntime(location.host);
    (window as any).castlaRuntime = runtime;
    const frontendBuildPayload = {
      marker: FRONTEND_BUILD_MARKER,
      buildTimestamp: __CASTLA_BUILD_TIMESTAMP__,
      href: location.href,
      userAgent: navigator.userAgent,
      ts: Date.now(),
    };
    console.warn("[BUILD_MARKER] frontend boot", frontendBuildPayload);
    runtime.control.sendFrontendDiag("BUILD_MARKER", "frontend boot", frontendBuildPayload);
    runtime.control.onConnectionChange((connected) => {
      if (!connected) return;
      runtime.control.sendFrontendDiag("BUILD_MARKER", "frontend control connected", {
        ...frontendBuildPayload,
        control: runtime.control.debugSnapshot(),
      });
    });
    const errorListener = (event: ErrorEvent) => {
      sendFrontendRuntimeDiag("FRONTEND_ERROR", "window.error", {
        message: event.message,
        filename: event.filename,
        lineno: event.lineno,
        colno: event.colno,
        error: event.error instanceof Error
          ? {
              name: event.error.name,
              message: event.error.message,
              stack: event.error.stack,
            }
          : String(event.error),
      });
    };
    const rejectionListener = (event: PromiseRejectionEvent) => {
      const reason = event.reason;
      sendFrontendRuntimeDiag("FRONTEND_ERROR", "window.unhandledrejection", {
        reason: reason instanceof Error
          ? {
              name: reason.name,
              message: reason.message,
              stack: reason.stack,
            }
          : String(reason),
      });
    };
    const beforeUnloadListener = () => {
      sendFrontendRuntimeDiag("FRONTEND_LIFECYCLE", "beforeunload");
    };
    const pageHideListener = (event: PageTransitionEvent) => {
      sendFrontendRuntimeDiag("FRONTEND_LIFECYCLE", "pagehide", {
        persisted: event.persisted,
      });
    };
    const pageShowListener = (event: PageTransitionEvent) => {
      sendFrontendRuntimeDiag("FRONTEND_LIFECYCLE", "pageshow", {
        persisted: event.persisted,
      });
    };
    const visibilityListener = () => {
      sendFrontendRuntimeDiag("FRONTEND_LIFECYCLE", "visibilitychange", {
        hidden: document.hidden,
      });
    };
    const onlineListener = () => {
      sendFrontendRuntimeDiag("FRONTEND_LIFECYCLE", "online");
    };
    const offlineListener = () => {
      sendFrontendRuntimeDiag("FRONTEND_LIFECYCLE", "offline");
    };
    const heartbeatId = isVerboseFrontendDiagnostics()
      ? window.setInterval(() => {
          sendFrontendRuntimeDiag("FRONTEND_LIFECYCLE", "heartbeat", {
            control: runtime.control.debugSnapshot(),
          });
        }, 5000)
      : 0;
    window.addEventListener("error", errorListener);
    window.addEventListener("unhandledrejection", rejectionListener);
    if (isVerboseFrontendDiagnostics()) {
      window.addEventListener("beforeunload", beforeUnloadListener);
      window.addEventListener("pagehide", pageHideListener);
      window.addEventListener("pageshow", pageShowListener);
      document.addEventListener("visibilitychange", visibilityListener);
      window.addEventListener("online", onlineListener);
      window.addEventListener("offline", offlineListener);
    }
    lifecycleCleanup = () => {
      window.clearInterval(heartbeatId);
      window.removeEventListener("error", errorListener);
      window.removeEventListener("unhandledrejection", rejectionListener);
      if (isVerboseFrontendDiagnostics()) {
        window.removeEventListener("beforeunload", beforeUnloadListener);
        window.removeEventListener("pagehide", pageHideListener);
        window.removeEventListener("pageshow", pageShowListener);
        document.removeEventListener("visibilitychange", visibilityListener);
        window.removeEventListener("online", onlineListener);
        window.removeEventListener("offline", offlineListener);
      }
    };
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
        if (remoteEditableActive && imeProxy && document.activeElement !== imeProxy) {
        const payload = {
          remoteEditableActive,
          imeState,
          activeElement: describeActiveElement(),
          };
          verboseWarn("[IME_DEBUG] viewport tap eligible for proxy focus", payload);
          sendVerboseFrontendDiag("IME_DEBUG", "viewport tap eligible for proxy focus", payload);
          attemptProxyFocus("viewport_pointerdown_remote_editable");
        } else {
          const payload = {
            remoteEditableActive,
            imeState,
            activeElement: describeActiveElement(),
          };
          verboseWarn("[IME_DEBUG] viewport tap skipped proxy focus", payload);
          sendVerboseFrontendDiag("IME_DEBUG", "viewport tap skipped proxy focus", payload);
        }
        return;
      }

      if (remoteEditableActive && imeState !== 'IDLE') {
        if (imeProxy && document.activeElement !== imeProxy) {
          attemptProxyFocus("global_pointerdown_remote_editable");
        }
      }
    };

    const globalKeydownListener = (event: KeyboardEvent): void => {
      verboseWarn("[IME_MODE]", {
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
        (window as any).__CASTLA_VERBOSE_DIAGNOSTICS__ = (msg as any).verboseDiagnosticsEnabled === true;
        if (lastInstanceId && lastInstanceId !== nextId) {
          console.warn("[CastlaSession] Server reboot detected! Forcing session hardReset.");
          lastInstanceId = nextId;
          hardReset("server_reboot");
          return;
        }
        lastInstanceId = nextId;
      }
      if (msg.type === "requestFrontendDebugDump") {
        triggerDump(runtime, String((msg as any).reason ?? "native_share_logs"));
      }
      if (msg.type === "ime" && msg.op === "androidFocusChanged") {
        const incomingSessionId = Number((msg as any).sessionId ?? 0);
        const active = (msg as any).focused === true;
        const targetPkg = (msg as any).packageName;
        const inputType = Number((msg as any).inputType ?? 0);
        const editableConfirmed = (msg as any).editableConfirmed === true;
        const focusSource = String((msg as any).focusSource ?? "unknown");

        const androidFocusPayload = {
          focused: active,
          packageName: targetPkg,
          sessionId: incomingSessionId,
          inputType,
          editableConfirmed,
          focusSource,
          currentSessionId,
          activeElement: describeActiveElement(),
          remoteEditableActive,
        };
        verboseWarn(`[IME_DEBUG] androidFocusChanged focused=${active}`, androidFocusPayload);
        if (active) {
          sendVerboseFrontendDiag("IME_DEBUG", `androidFocusChanged focused=${active}`, androidFocusPayload);
        }

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
        const editable =
          isEditableInputType(inputType) || editableConfirmed === true;
        if (active && !editable) {
          verboseWarn("[IME_DEBUG] skip focused=true on frontend due to non-editable inputType", {
            packageName: targetPkg,
            sessionId: incomingSessionId,
            inputType,
            editable,
            editableConfirmed,
            focusSource,
          });
          sendVerboseFrontendDiag("IME_DEBUG", "skip focused=true on frontend due to non-editable inputType", {
            packageName: targetPkg,
            sessionId: incomingSessionId,
            inputType,
            editable,
            editableConfirmed,
            focusSource,
          });
          return;
        }
        currentSessionId = incomingSessionId;
        lastRemoteFocusPackage = targetPkg ?? null;
        lastRemoteFocusSessionId = incomingSessionId;

        if (active) {
          if (blurDebounceTimer) {
            window.clearTimeout(blurDebounceTimer);
            blurDebounceTimer = undefined;
          }
          setRemoteEditableActive(true, "androidFocusChanged_true");
          setImeState('ANDROID_FOCUSING');
          requestAnimationFrame(() => {
            attemptProxyFocus("androidFocusChanged_true", 0);
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
              setRemoteEditableActive(false, "androidFocusChanged_false_debounced");
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
    lifecycleCleanup?.();
    lifecycleCleanup = undefined;
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
  refreshOverlayUiScale();
  window.addEventListener("resize", refreshOverlayUiScale);
  onDestroy(() => {
    window.removeEventListener("resize", refreshOverlayUiScale);
  });

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

  onDestroy(() => {
    window.removeEventListener("resize", refreshOverlayUiScale);
  });

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
      if (!remoteEditableActive) {
        verboseWarn("[IME_DEBUG] proxy blur recovery skipped because remoteEditableActive=false", {
          imeState,
          activeElement: describeActiveElement(),
        });
        return;
      }
      if (imeState === 'READY' || imeState === 'ANDROID_FOCUSING' || imeState === 'RECOVERING') {
        setImeState('RECOVERING');
        window.setTimeout(() => {
          if (imeState === 'RECOVERING') {
            attemptProxyFocus("proxy_blur_recovery", 0);
          }
        }, REMOTE_IME_REFOCUS_DELAY_MS);
      }
    }, 60);
  }
</script>

<main class="app-shell">
  {#key `${runtimeEpoch}:${frontendResetEpoch}`}
    <ViewportHost bind:this={viewportHostRef} {touchRouter} {runtime} appLauncher={appLauncherRef} />
  {/key}
  <div
    class="overlay-ui-layer"
    style={`--overlay-ui-scale: ${overlayUiScale}; --overlay-ui-inverse-scale: ${1 / overlayUiScale};`}
  >
    {#key runtimeEpoch}
      <AppLauncher
        bind:this={appLauncherRef}
        {runtime}
        viewportHost={viewportHostRef}
        overlayUiScale={overlayUiScale}
        overlayUiScalePreference={overlayUiScalePreference}
        onOverlayUiScalePreferenceChange={updateOverlayUiScalePreference}
      />
    {/key}
    {#if showDiagnostics}
      <DiagnosticsOverlay />
    {/if}
  </div>
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
      verboseWarn("[INPUT_FORWARD] input event", {
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
      verboseWarn("[INPUT_FORWARD] keydown event", {
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

<style>
  .overlay-ui-layer {
    position: fixed;
    inset: 0;
    width: calc(100% * var(--overlay-ui-inverse-scale, 1));
    height: calc(100% * var(--overlay-ui-inverse-scale, 1));
    transform: scale(var(--overlay-ui-scale, 1));
    transform-origin: top left;
    pointer-events: none;
  }

  :global(.overlay-ui-layer > *) {
    pointer-events: auto;
  }
</style>

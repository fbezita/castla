<script lang="ts">
  import ViewportHost from './components/ViewportHost.svelte';
  import DiagnosticsOverlay from './components/DiagnosticsOverlay.svelte';
  import AppLauncher from './components/AppLauncher.svelte';
  import { compositorStore, resetCompositorStore } from './stores/compositorStore';
  import { BrowserCompositor } from './compositor/BrowserCompositor';
  import { StreamRuntime } from './runtime/StreamRuntime';
  import { TouchRouter } from './touch/TouchRouter';
  import { ImeBridge } from './ime/ImeBridge';

  let runtime: StreamRuntime;
  let compositor: BrowserCompositor;
  let touchRouter: TouchRouter;
  let imeBridge: ImeBridge;
  let frontendResetEpoch = 0;
  let runtimeEpoch = 0;
  let frontendResetCleanup: (() => void) | undefined;
  const JMUXER_SCRIPT_SRC = '/js/jmuxer.min.js';

  function createRuntimeGraph(): void {
    runtime = new StreamRuntime(location.host);
    compositor = new BrowserCompositor(runtime, compositorStore);
    touchRouter = new TouchRouter(runtime);
    imeBridge = new ImeBridge(runtime.control);
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
    console.info('[CastlaSession] hard_reset_begin', {
      reason,
      appLaunchSequence: runtime.currentAppLaunchSequence(),
      sessionEpoch: runtime.currentSessionEpoch(),
      runtimeEpoch
    });
    (document.activeElement as HTMLElement | null)?.blur?.();
    frontendResetCleanup?.();
    frontendResetCleanup = undefined;
    touchRouter.dispose();
    compositor.dispose();
    runtime.dispose();
    resetCompositorStore();
    await reloadJmuxerScript();
    createRuntimeGraph();
    frontendResetEpoch = 0;
    runtimeEpoch += 1;
  }

  createRuntimeGraph();

  console.info('[CastlaSession] page_boot', {
    href: location.href,
    ts: Date.now()
  });

  (window as Window & { castlaDebug?: Record<string, unknown> }).castlaDebug = {
    touchReset: (reason = 'manual_debug') => runtime.resetTouchState(reason),
    controlReconnect: () => runtime.control.reconnectNow(),
    serverRearm: () => runtime.control.send({ type: 'debugBrowserRearm' }),
    serverTeardown: () => runtime.control.send({ type: 'debugBrowserTeardown' }),
    socketCycle: () => runtime.control.send({ type: 'debugSocketCycle' }),
    hardReset: (reason = 'manual_debug') => hardReset(reason),
    reloadJmuxer: () => reloadJmuxerScript(),
    fullReload: () => {
      console.info('[CastlaSession] full_reload', {
        appLaunchSequence: runtime.currentAppLaunchSequence(),
        sessionEpoch: runtime.currentSessionEpoch(),
        ts: Date.now()
      });
      location.reload();
    },
    paneRecover: (pane = 'primary') => runtime.recoverPaneStream(pane),
    softReconnect: (pane = 'primary') => runtime.softReconnect(pane),
    frontendReset: (reason = 'manual_debug') => runtime.resetFrontendInteraction(reason),
    sessionSnapshot: () => ({
      appLaunchSequence: runtime.currentAppLaunchSequence(),
      sessionEpoch: runtime.currentSessionEpoch(),
      runtimeEpoch
    })
  };

  async function reloadJmuxerScript(): Promise<void> {
    console.info('[CastlaSession] reload_jmuxer_begin', {
      ts: Date.now(),
      hasJMuxer: Boolean((window as Window & { JMuxer?: unknown }).JMuxer)
    });
    const existing = Array.from(document.querySelectorAll('script')).find((script) => {
      const src = script.getAttribute('src') ?? '';
      return src.includes('jmuxer.min.js');
    });
    if (existing) {
      existing.remove();
    }
    try {
      delete (window as Window & { JMuxer?: unknown }).JMuxer;
    } catch {}
    await new Promise<void>((resolve, reject) => {
      const script = document.createElement('script');
      script.src = `${JMUXER_SCRIPT_SRC}?ts=${Date.now()}`;
      script.async = false;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error('Failed to reload JMuxer script'));
      document.head.appendChild(script);
    });
    console.info('[CastlaSession] reload_jmuxer_done', {
      ts: Date.now(),
      hasJMuxer: Boolean((window as Window & { JMuxer?: unknown }).JMuxer)
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
  <DiagnosticsOverlay />
  <input class="ime-proxy" aria-hidden="true" on:compositionstart={(event) => imeBridge.compositionStart(event)}
    on:compositionupdate={(event) => imeBridge.compositionUpdate(event)}
    on:compositionend={(event) => imeBridge.compositionEnd(event)}
    on:input={(event) => imeBridge.input(event)}
    on:keydown={(event) => imeBridge.keydown(event)} />
</main>

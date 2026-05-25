<script lang="ts">
  import ViewportHost from './components/ViewportHost.svelte';
  import DiagnosticsOverlay from './components/DiagnosticsOverlay.svelte';
  import AppLauncher from './components/AppLauncher.svelte';
  import { compositorStore } from './stores/compositorStore';
  import { BrowserCompositor } from './compositor/BrowserCompositor';
  import { StreamRuntime } from './runtime/StreamRuntime';
  import { TouchRouter } from './touch/TouchRouter';
  import { ImeBridge } from './ime/ImeBridge';

  const runtime = new StreamRuntime(location.host);
  const compositor = new BrowserCompositor(runtime, compositorStore);
  let touchRouter = new TouchRouter(runtime);
  let imeBridge = new ImeBridge(runtime.control);
  let frontendResetEpoch = 0;

  runtime.onFrontendReset((reason) => {
    console.info('[CastlaFrontend] reset interaction shell', { reason });
    (document.activeElement as HTMLElement | null)?.blur?.();
    touchRouter.dispose();
    touchRouter = new TouchRouter(runtime);
    imeBridge = new ImeBridge(runtime.control);
    frontendResetEpoch += 1;
  });

  compositor.start();
  console.info('[CastlaFrontend] app bootstrap', { host: location.host });
</script>

<main class="app-shell">
  {#key frontendResetEpoch}
    <ViewportHost {touchRouter} {runtime} />
  {/key}
  <AppLauncher {runtime} />
  <DiagnosticsOverlay />
  <input class="ime-proxy" aria-hidden="true" on:compositionstart={(event) => imeBridge.compositionStart(event)}
    on:compositionupdate={(event) => imeBridge.compositionUpdate(event)}
    on:compositionend={(event) => imeBridge.compositionEnd(event)}
    on:input={(event) => imeBridge.input(event)}
    on:keydown={(event) => imeBridge.keydown(event)} />
</main>

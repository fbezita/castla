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
  const touchRouter = new TouchRouter(runtime.control);
  const imeBridge = new ImeBridge(runtime.control);

  compositor.start();
</script>

<main class="app-shell">
  <ViewportHost {touchRouter} {runtime} />
  <AppLauncher {runtime} />
  <DiagnosticsOverlay />
  <input class="ime-proxy" aria-hidden="true" on:compositionstart={(event) => imeBridge.compositionStart(event)}
    on:compositionupdate={(event) => imeBridge.compositionUpdate(event)}
    on:compositionend={(event) => imeBridge.compositionEnd(event)}
    on:input={(event) => imeBridge.input(event)}
    on:keydown={(event) => imeBridge.keydown(event)} />
</main>

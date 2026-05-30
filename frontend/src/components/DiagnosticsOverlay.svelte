<script lang="ts">
  import { onMount } from 'svelte';
  import { compositorStore } from '../stores/compositorStore';
  import { triggerDump, isLoggingEnabled, setLoggingEnabled } from '../utils/debugLogger';

  let enabled = true;

  onMount(() => {
    enabled = isLoggingEnabled();
  });

  function handleManualDump() {
    const runtime = (window as any).castlaRuntime;
    if (runtime) {
      triggerDump(runtime, 'manual_debug_button');
    }
  }

  function toggleLogging() {
    enabled = !enabled;
    setLoggingEnabled(enabled);
  }
</script>

<aside class="diagnostics" aria-live="polite">
  <div class="control-row">
    <button class="dump-btn" on:click={handleManualDump}>TRIGGER DUMP</button>
    <button class="toggle-btn" class:active={enabled} on:click={toggleLogging}>
      LOG: {enabled ? 'ON' : 'OFF'}
    </button>
  </div>

  {#if $compositorStore.serverDiagnostics}
    <div class="server-block">
      <strong>server</strong>
      <div>{$compositorStore.serverDiagnostics.reason}</div>
      <div>
        browser={$compositorStore.serverDiagnostics.browserConnected ? 'up' : 'down'}
        server={$compositorStore.serverDiagnostics.serverBrowserConnected ? 'up' : 'down'}
        pending={$compositorStore.serverDiagnostics.pendingDisconnect ? 'yes' : 'no'}
      </div>
      <div>
        grace={$compositorStore.serverDiagnostics.disconnectGraceMs}ms
        screenOff={$compositorStore.serverDiagnostics.screenOff ? 'yes' : 'no'}
        teardown={$compositorStore.serverDiagnostics.teardownPhase}
      </div>
      <div>launch={$compositorStore.serverDiagnostics.launchSeq} pane={$compositorStore.serverDiagnostics.lastTouchPane}</div>
      <div class="detail">{$compositorStore.serverDiagnostics.socketSummary}</div>
      <div class="detail">{$compositorStore.serverDiagnostics.pipelineSnapshot}</div>
      {#if $compositorStore.serverDiagnostics.injectorSnapshot}
        <div class="detail block">injector {$compositorStore.serverDiagnostics.injectorSnapshot}</div>
      {/if}
      {#if $compositorStore.serverDiagnostics.rejectProbe}
        <div class="detail block">probe {$compositorStore.serverDiagnostics.rejectProbe}</div>
      {/if}
      {#if $compositorStore.serverDiagnostics.touchTrace?.length}
        <div class="detail block">
          {#each $compositorStore.serverDiagnostics.touchTrace as line}
            <div>{line}</div>
          {/each}
        </div>
      {/if}
    </div>
  {/if}

  {#each $compositorStore.diagnostics as display (display.sessionId)}
    <div>
      <strong>{display.sessionId}</strong>
      {display.tier} gen {display.generation}
      {display.width}x{display.height}
    </div>
  {/each}
</aside>

<style>
  .diagnostics {
    position: absolute;
    top: 8px;
    right: 8px;
    max-width: 360px;
    padding: 8px;
    border-radius: 8px;
    background: rgb(0 0 0 / 0.55);
    color: #dce7f2;
    font-size: 12px;
    line-height: 1.35;
    pointer-events: none;
    z-index: 99;
  }

  .control-row {
    margin-bottom: 8px;
    display: flex;
    gap: 8px;
    justify-content: center;
    align-items: center;
  }

  .dump-btn {
    pointer-events: auto;
    background: #00e5ff;
    color: #05070a;
    border: none;
    padding: 6px 12px;
    border-radius: 4px;
    font-size: 11px;
    font-weight: bold;
    cursor: pointer;
    box-shadow: 0 0 10px rgba(0, 229, 255, 0.4);
    transition: all 0.2s ease;
  }

  .dump-btn:hover {
    background: #00b8cc;
  }

  .toggle-btn {
    pointer-events: auto;
    background: rgb(255 255 255 / 0.08);
    color: #a9adba;
    border: 1px solid rgb(255 255 255 / 0.12);
    padding: 5px 10px;
    border-radius: 4px;
    font-size: 11px;
    font-weight: bold;
    cursor: pointer;
    transition: all 0.2s ease;
  }

  .toggle-btn:hover {
    background: rgb(255 255 255 / 0.14);
  }

  .toggle-btn.active {
    background: #ff7043;
    color: #ffffff;
    border-color: #ff7043;
    box-shadow: 0 0 10px rgba(255, 112, 67, 0.45);
  }

  .server-block {
    margin-bottom: 8px;
    padding-bottom: 8px;
    border-bottom: 1px solid rgb(255 255 255 / 0.12);
  }

  .detail {
    opacity: 0.82;
    word-break: break-word;
  }

  .block {
    margin-top: 6px;
    max-height: 180px;
    overflow: hidden;
  }
</style>

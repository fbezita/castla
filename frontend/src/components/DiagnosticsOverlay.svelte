<script lang="ts">
  import { compositorStore } from '../stores/compositorStore';
</script>

<aside class="diagnostics" aria-live="polite">
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

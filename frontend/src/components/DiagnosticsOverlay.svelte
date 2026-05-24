<script lang="ts">
  import { compositorStore } from '../stores/compositorStore';
</script>

<aside class="diagnostics" aria-live="polite">
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
</style>

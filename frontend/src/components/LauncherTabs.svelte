<script lang="ts">
  // Strict Svelte 5 Props using $props Rune
  let {
    activeTab,
    selectTab,
    draggingApp,
    dropZone
  } = $props<{
    activeTab: "autorun" | "starred" | "recent" | "browse";
    selectTab: (tab: "autorun" | "starred" | "recent" | "browse") => void;
    draggingApp: any | null;
    dropZone: string;
  }>();
</script>

<nav class="hub-tabs" aria-label="Launch hub views" class:dragging-mode={draggingApp !== null}>
  <button
    data-launcher-tab="autorun"
    class:active={activeTab === "autorun"}
    class:drop-target={draggingApp && dropZone === "autorun"}
    onclick={() => selectTab("autorun")}
  >
    <span class="tab-label">Auto Run</span>
    {#if draggingApp && dropZone === "autorun"}
      <span class="drop-badge">DROP</span>
    {/if}
  </button>

  <button
    data-launcher-tab="starred"
    class:active={activeTab === "starred"}
    class:drop-target={draggingApp && dropZone === "favorite"}
    onclick={() => selectTab("starred")}
  >
    <span class="tab-label">Starred</span>
    {#if draggingApp && dropZone === "favorite"}
      <span class="drop-badge">DROP</span>
    {/if}
  </button>

  <button
    data-launcher-tab="recent"
    class:active={activeTab === "recent"}
    onclick={() => selectTab("recent")}
  >
    Recent
  </button>

  <button
    data-launcher-tab="browse"
    class:active={activeTab === "browse"}
    onclick={() => selectTab("browse")}
  >
    Browse
  </button>
</nav>

{#if draggingApp && (dropZone === "autorun" || dropZone === "favorite")}
  <div class="drop-hint" aria-live="polite">
    {dropZone === "autorun" ? "Release to add to Auto Run" : "Release to add to Starred"}
  </div>
{/if}

<style>
  .hub-tabs {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 6px;
    padding: 0 12px 12px;
    margin-bottom: 8px;
    position: relative;
  }

  .hub-tabs button {
    min-width: 0;
    height: 38px;
    padding: 0 4px;
    border: 1px solid rgb(255 255 255 / 0.08);
    border-radius: 12px;
    background: rgb(255 255 255 / 0.04);
    color: #b5bdcb;
    font-size: 12px;
    font-weight: 700;
    white-space: nowrap;
    letter-spacing: -0.01em;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    transition:
      background 0.2s cubic-bezier(0.4, 0, 0.2, 1),
      border-color 0.2s cubic-bezier(0.4, 0, 0.2, 1),
      box-shadow 0.2s cubic-bezier(0.4, 0, 0.2, 1),
      color 0.2s ease,
      transform 0.15s ease;
  }

  .hub-tabs button:hover {
    background: rgb(255 255 255 / 0.08);
    color: #f6f8fc;
  }

  .hub-tabs button.active {
    background: linear-gradient(
        180deg,
        rgb(139 196 255 / 0.18),
        rgb(139 196 255 / 0.04)
      ),
      rgb(20 28 48 / 0.6);
    border-color: rgb(139 196 255 / 0.38);
    color: #f6f8fc;
    box-shadow: 0 4px 12px rgb(139 196 255 / 0.08);
  }

  /* Active glow target mode when dragging an app */
  .hub-tabs.dragging-mode button {
    border-style: dashed;
    border-color: rgb(255 255 255 / 0.15);
    background: rgb(255 255 255 / 0.02);
  }

  .hub-tabs.dragging-mode button.drop-target {
    border-style: solid;
    background: linear-gradient(
      180deg,
      rgba(0, 229, 255, 0.16),
      rgba(0, 229, 255, 0.04)
    );
    border-color: #00e5ff;
    color: #ffffff;
    box-shadow: 0 0 16px rgba(0, 229, 255, 0.24);
    transform: scale(1.02);
  }

  .tab-label {
    min-width: 0;
  }

  .drop-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 0;
    padding: 0 5px;
    height: 16px;
    border-radius: 999px;
    background: rgba(0, 229, 255, 0.18);
    color: #dffbff;
    font-size: 9px;
    font-weight: 900;
    letter-spacing: 0.06em;
    animation: pulse 1s infinite alternate;
  }

  .drop-hint {
    margin: -2px 0 10px;
    padding: 8px 10px;
    border: 1px solid rgba(0, 229, 255, 0.24);
    border-radius: 12px;
    background: linear-gradient(180deg, rgba(0, 229, 255, 0.12), rgba(0, 229, 255, 0.04));
    color: #dffbff;
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.01em;
    text-align: center;
    box-shadow: inset 0 0 18px rgba(0, 229, 255, 0.08);
  }

  @keyframes pulse {
    0% {
      opacity: 0.6;
    }
    100% {
      opacity: 1;
    }
  }
</style>

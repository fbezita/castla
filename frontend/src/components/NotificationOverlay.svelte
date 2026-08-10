<script lang="ts">
  import { onDestroy } from "svelte";
  import { formatNotificationText, groupNotificationHistory, type OverlayNotification } from "../lib/notificationOverlay";
  import { t } from "../lib/i18n";
  import { compositorStore } from "../stores/compositorStore";

  let {
    items = [],
    history = [],
    openRequest = 0,
    autoReveal = true,
  } = $props<{
    items: OverlayNotification[];
    history?: OverlayNotification[];
    openRequest?: number;
    autoReveal?: boolean;
  }>();

  type HistoryButtonVisibility = "visible" | "faded" | "hidden";

  let expanded = $state(new Set<string>());
  let historyOpen = $state(false);
  let historyButtonVisibility = $state<HistoryButtonVisibility>("hidden");
  let latestHistoryItem = $state<OverlayNotification | undefined>(undefined);
  let handledOpenRequest = $state(0);
  let historyFadeTimer: number | undefined;
  let historyHideTimer: number | undefined;

  const AUTO_COLLAPSE_MS = 10_000;
  const HISTORY_FADE_DELAY_MS = 5_000;
  const HISTORY_HIDE_DELAY_MS = 8_000;

  function clearHistoryButtonTimers() {
    if (historyFadeTimer !== undefined) window.clearTimeout(historyFadeTimer);
    if (historyHideTimer !== undefined) window.clearTimeout(historyHideTimer);
    historyFadeTimer = undefined;
    historyHideTimer = undefined;
  }

  function scheduleHistoryButtonFade() {
    clearHistoryButtonTimers();
    historyFadeTimer = window.setTimeout(() => {
      if (!historyOpen) historyButtonVisibility = "faded";
    }, HISTORY_FADE_DELAY_MS);
    historyHideTimer = window.setTimeout(() => {
      if (!historyOpen) historyButtonVisibility = "hidden";
    }, HISTORY_HIDE_DELAY_MS);
  }

  function revealHistoryButton() {
    historyButtonVisibility = "visible";
    scheduleHistoryButtonFade();
  }

  function openHistory() {
    if (history.length === 0) return;
    clearHistoryButtonTimers();
    historyButtonVisibility = "visible";
    historyOpen = true;
  }

  function closeHistory() {
    historyOpen = false;
    revealHistoryButton();
  }

  function toggleHistory() {
    if (historyOpen) closeHistory();
    else openHistory();
  }

  $effect(() => {
    const newest = history[0];
    if (!newest) {
      latestHistoryItem = undefined;
      historyOpen = false;
      historyButtonVisibility = "hidden";
      clearHistoryButtonTimers();
    } else if (newest !== latestHistoryItem) {
      latestHistoryItem = newest;
      if (autoReveal) revealHistoryButton();
    }
  });

  $effect(() => {
    if (openRequest === handledOpenRequest) return;
    handledOpenRequest = openRequest;
    openHistory();
  });

  $effect(() => {
    if (!autoReveal && !historyOpen) {
      historyButtonVisibility = "hidden";
      clearHistoryButtonTimers();
    }
  });

  function toggle(id: string) {
    const next = new Set(expanded);

    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);

      setTimeout(() => {
        if (!expanded.has(id)) return;

        const current = new Set(expanded);
        current.delete(id);
        expanded = current;
      }, AUTO_COLLAPSE_MS);
    }

    expanded = next;
  }

  onDestroy(clearHistoryButtonTimers);
</script>

{#if items.length > 0 || historyOpen || historyButtonVisibility !== "hidden"}
  <section class="notification-overlay" aria-live="polite">
    {#if history.length > 0 && historyButtonVisibility !== "hidden"}
      <div class:faded={historyButtonVisibility === "faded"} class="history-actions">
        <button class="history-toggle" onclick={toggleHistory}>
          {historyOpen ? t($compositorStore.language, "notificationHistoryClose") : `${t($compositorStore.language, "notificationHistory")} (${history.length})`}
        </button>
      </div>
    {/if}
    {#if historyOpen}
      <div class="notification-history">
        {#each groupNotificationHistory(history) as group (group.packageName)}
          <section class="history-group">
            <header class="history-group-header">
              <span>{group.appLabel}</span>
              <span>{group.count}</span>
            </header>
            {#each group.conversations as conversation}
              <section class="history-conversation">
                <header class="history-conversation-header">
                  <span>{conversation.title}</span>
                  <span>{conversation.items.length}</span>
                </header>
                {#each conversation.items as item}
                  <article class="history-item">
                    {#if item.sender}<div class="notification-sender">{item.sender}</div>{/if}
                    <div class="notification-text">{formatNotificationText(item, $compositorStore.language)}</div>
                  </article>
                {/each}
              </section>
            {/each}
          </section>
        {/each}
      </div>
    {/if}
    {#each items as item (item.id)}
      <article class="notification-card">
        <div class="notification-header">
          <div class="notification-info">
            <div class="notification-app">{item.appLabel}</div>
            <div class="notification-title">{item.title}</div>
            {#if item.sender}<div class="notification-sender">{item.sender}</div>{/if}
          </div>

          <button
            class="notification-toggle"
            onclick={() => toggle(item.id)}
            aria-expanded={expanded.has(item.id)}
          >
            {t($compositorStore.language, expanded.has(item.id) ? "notificationCollapse" : "notificationExpand")}
          </button>
        </div>

        {#if expanded.has(item.id)}
          <div class="notification-text">
            {formatNotificationText(item, $compositorStore.language)}
          </div>
        {:else}
          <div class="notification-placeholder">{t($compositorStore.language, "notificationContentHidden")}</div>
        {/if}
      </article>
    {/each}
  </section>
{/if}

<style>
  .notification-overlay {
    position: absolute;
    top: 20px;
    left: 20px;
    display: grid;
    gap: 10px;
    width: min(360px, calc(100vw - 40px));
    z-index: 120;
    pointer-events: none;
  }

  .history-actions {
    display: flex;
    justify-self: start;
    opacity: 0.9;
    transition: opacity 300ms ease;
  }

  .history-actions.faded {
    opacity: 0.2;
  }

  .history-toggle {
    pointer-events: auto;
    justify-self: start;
    border: 1px solid rgba(139, 233, 255, 0.35);
    border-radius: 999px;
    padding: 7px 12px;
    background: rgba(8, 12, 20, 0.9);
    color: #8be9ff;
    font-size: 12px;
    font-weight: 700;
    cursor: pointer;
  }

  .notification-history {
    display: grid;
    gap: 8px;
    max-height: min(60vh, 520px);
    overflow: auto;
    pointer-events: auto;
  }

  .history-group {
    display: grid;
    gap: 8px;
  }

  .history-group-header {
    position: sticky;
    top: 0;
    display: flex;
    justify-content: space-between;
    padding: 8px 10px;
    border-radius: 10px;
    background: rgba(21, 31, 46, 0.98);
    color: #8be9ff;
    font-size: 12px;
    font-weight: 800;
  }

  .history-conversation {
    display: grid;
    gap: 6px;
    padding: 8px;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 14px;
    background: rgba(8, 12, 20, 0.92);
  }

  .history-conversation-header {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    padding: 2px 4px 6px;
    color: #f8fbff;
    font-size: 15px;
    font-weight: 800;
  }

  .history-item {
    padding: 10px;
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.05);
  }

  .notification-card {
    padding: 14px 16px;
    border: 1px solid rgba(255, 255, 255, 0.14);
    border-radius: 18px;
    background: linear-gradient(
        180deg,
        rgba(255, 255, 255, 0.1),
        rgba(255, 255, 255, 0.04)
      ),
      rgba(8, 12, 20, 0.86);
    box-shadow: 0 14px 30px rgba(0, 0, 0, 0.34);
    backdrop-filter: blur(16px);
    color: #f8fbff;
  }

  .notification-header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 12px;
  }

  .notification-info {
    flex: 1;
    min-width: 0;
  }

  .notification-app {
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: #8be9ff;
    margin-bottom: 6px;
  }

  .notification-title {
    font-size: 16px;
    font-weight: 800;
    word-break: break-word;
  }

  .notification-sender {
    margin-top: 4px;
    color: rgba(248, 251, 255, 0.68);
    font-size: 13px;
    font-weight: 700;
    word-break: break-word;
  }

  .notification-placeholder,
  .notification-text {
    margin-top: 10px;
    font-size: 13px;
    line-height: 1.4;
  }

  .notification-placeholder {
    color: rgba(248, 251, 255, 0.55);
    font-style: italic;
    user-select: none;
  }

  .notification-text {
    color: rgba(248, 251, 255, 0.82);
    white-space: pre-wrap;
    word-break: break-word;
  }

  .notification-toggle {
    pointer-events: auto;
    flex-shrink: 0;
    border: none;
    border-radius: 999px;
    padding: 6px 12px;
    background: rgba(255, 255, 255, 0.12);
    color: #fff;
    font-size: 12px;
    font-weight: 700;
    cursor: pointer;
    transition: background 0.2s ease;
  }

  .notification-toggle:hover {
    background: rgba(255, 255, 255, 0.2);
  }

  .notification-toggle:active {
    background: rgba(255, 255, 255, 0.28);
  }
</style>

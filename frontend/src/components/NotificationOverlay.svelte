<script lang="ts">
  import { formatNotificationText, groupNotificationHistory, type OverlayNotification } from "../lib/notificationOverlay";
  import { t } from "../lib/i18n";
  import { compositorStore } from "../stores/compositorStore";

  let { items = [], history = [] } = $props<{ items: OverlayNotification[]; history?: OverlayNotification[] }>();

  let expanded = $state(new Set<string>());
  let historyOpen = $state(false);
  const AUTO_COLLAPSE_MS = 10_000;

  function toggle(id: string) {
    const next = new Set(expanded);

    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);

      // 10초 후 자동 접기
      setTimeout(() => {
        if (!expanded.has(id)) return;

        const current = new Set(expanded);
        current.delete(id);
        expanded = current;
      }, AUTO_COLLAPSE_MS);
    }

    expanded = next;
  }
</script>

{#if items.length > 0 || history.length > 0}
  <section class="notification-overlay" aria-live="polite">
    {#if history.length > 0}
      <button class="history-toggle" onclick={() => (historyOpen = !historyOpen)}>
        {historyOpen ? t($compositorStore.language, "notificationHistoryClose") : `${t($compositorStore.language, "notificationHistory")} (${history.length})`}
      </button>
    {/if}
    {#if historyOpen}
      <div class="notification-history">
        {#each groupNotificationHistory(history) as group (group.packageName)}
          <section class="history-group">
            <header class="history-group-header">
              <span>{group.appLabel}</span>
              <span>{group.items.length}</span>
            </header>
            {#each group.items as item}
              <article class="history-item">
                <div class="notification-title">{item.title}</div>
                {#if item.sender}<div class="notification-sender">{item.sender}</div>{/if}
                <div class="notification-text">{formatNotificationText(item, $compositorStore.language)}</div>
              </article>
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

  .history-item {
    padding: 12px 14px;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 14px;
    background: rgba(8, 12, 20, 0.92);
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

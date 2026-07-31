<script lang="ts">
  import type { OverlayNotification } from "../lib/notificationOverlay";

  let { items = [] } = $props<{ items: OverlayNotification[] }>();

  let expanded = $state(new Set<string>());
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

{#if items.length > 0}
  <section class="notification-overlay" aria-live="polite">
    {#each items as item (item.id)}
      <article class="notification-card">
        <div class="notification-header">
          <div class="notification-info">
            <div class="notification-app">{item.appLabel}</div>
            <div class="notification-title">{item.title}</div>
          </div>

          <button
            class="notification-toggle"
            onclick={() => toggle(item.id)}
            aria-expanded={expanded.has(item.id)}
          >
            {expanded.has(item.id) ? "접기 ▲" : "펴기 ▼"}
          </button>
        </div>

        {#if expanded.has(item.id)}
          <div class="notification-text">
            {item.text}
          </div>
        {:else}
          <div class="notification-placeholder">🔒 내용을 숨겼습니다.</div>
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

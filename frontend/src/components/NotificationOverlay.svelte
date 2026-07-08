<script lang="ts">
  import type { OverlayNotification } from "../lib/notificationOverlay";

  let { items = [] } = $props<{ items: OverlayNotification[] }>();
</script>

{#if items.length > 0}
  <section class="notification-overlay" aria-live="polite">
    {#each items as item (item.id)}
      <article class="notification-card">
        <div class="notification-app">{item.appLabel}</div>
        <div class="notification-title">{item.title}</div>
        <div class="notification-text">{item.text}</div>
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
    background:
      linear-gradient(180deg, rgba(255, 255, 255, 0.1), rgba(255, 255, 255, 0.04)),
      rgba(8, 12, 20, 0.86);
    box-shadow: 0 14px 30px rgba(0, 0, 0, 0.34);
    backdrop-filter: blur(16px);
    color: #f8fbff;
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
    margin-bottom: 4px;
  }

  .notification-text {
    font-size: 13px;
    line-height: 1.4;
    color: rgba(248, 251, 255, 0.82);
  }
</style>

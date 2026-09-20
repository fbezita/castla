<script lang="ts">
  import type { ConnectionView } from "../lib/frontendExperience";

  let { view, language, onRetry } = $props<{
    view: Exclude<ConnectionView, "connected">;
    language: "ko" | "en";
    onRetry: () => void;
  }>();

  const label = (ko: string, en: string) => language === "ko" ? ko : en;
  let title = $derived(
    view === "busy"
      ? label("다른 브라우저가 제어 중", "Another browser is controlling Castla")
      : view === "connecting"
      ? label("휴대폰에 연결하는 중", "Connecting to your phone")
      : view === "reconnecting"
        ? label("연결을 복구하는 중", "Restoring connection")
        : label("휴대폰과 연결되지 않음", "Phone is not connected"),
  );
  let description = $derived(
    view === "busy"
      ? label("이 브라우저에서 계속하려면 아래 버튼으로 제어권을 가져오세요.", "To continue here, use the button below to take control.")
      : view === "unavailable"
      ? label("휴대폰에서 Castla를 실행하고 미러링이 시작됐는지 확인해 주세요.", "Open Castla on your phone and make sure mirroring is running.")
      : label("잠시만 기다려 주세요. 연결이 완료되면 앱 목록이 자동으로 열립니다.", "Please wait. Apps will appear automatically when the connection is ready."),
  );
</script>

<section class="connection-panel" aria-live="polite">
  <div class:spinning={view !== "unavailable" && view !== "busy"} class="connection-icon">
    {view === "unavailable" || view === "busy" ? "!" : "↻"}
  </div>
  <strong>{title}</strong>
  <p>{description}</p>
  <button onclick={onRetry}>
    {view === "busy"
      ? label("이 브라우저로 전환", "Switch to this browser")
      : label("이 브라우저로 연결", "Connect this browser")}
  </button>
</section>

<style>
  .connection-panel { margin: 12px; min-height: 240px; padding: 28px 22px; display: grid; place-items: center; align-content: center; gap: 13px; text-align: center; border: 1px solid rgba(255,255,255,.08); border-radius: 18px; background: rgba(13,18,31,.9); color: #f8fafc; }
  .connection-icon { width: 54px; height: 54px; display: grid; place-items: center; border-radius: 50%; border: 2px solid rgba(0,229,255,.45); color: #7cf1ff; font-size: 26px; font-weight: 900; }
  .connection-icon.spinning { animation: spin 1.2s linear infinite; }
  strong { font-size: 17px; }
  p { max-width: 360px; margin: 0; color: #9aa7ba; font-size: 13px; line-height: 1.55; }
  button { min-width: 132px; height: 40px; margin-top: 6px; border: 1px solid rgba(0,229,255,.35); border-radius: 12px; background: rgba(0,229,255,.12); color: #dffbff; font-weight: 800; cursor: pointer; }
  @keyframes spin { to { transform: rotate(360deg); } }
</style>

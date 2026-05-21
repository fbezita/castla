const host = window.location.host;

let videoSocket = null;
let controlSocket = null;
let audioPlayer = null;
let touchHandler = null;
let secondaryVideoSocket = null;
let secondaryTouchHandler = null;
let secondaryDecoder = null;

// ### 수정 시작 ###
// Removed SPLIT_STRATEGY constant as the system has converged to dual_stream architecture.
// ### 수정 끝 ###

let decoder = null;
let framePacer = null;
let secondaryFramePacer = null;
let currentPrimaryApp = null;
let isLauncherMode = false; // Homeless mode starts directly in streaming mode!
let currentServerInstanceId = null; // Track current server instance to detect restarts
let lastLaunchedInstanceId = null; // Prevent duplicate auto-run launches in the same session
let launchGuardUntil = 0; // block accidental launches after splash dismiss
let lastLaunchTime = 0; // track last app launch time to prevent transient stream-stop redirection
let codecMode = "h264"; // Default to h264, switch to mjpeg if needed
let isPromotingSecondary = false; // Flag to trace seamless secondary-to-primary promotion transition

// Playback profile system:
//   userPreferredProfile — what the user manually chose (persisted in localStorage)
//   currentThermalLevel  — last thermal status from service ("none"/"light"/"moderate"/"severe")
//   playbackProfile      — effective profile after thermal capping (applied to pacer/decoder)
//
// Thermal cap is always enforced: even if the user picks "smooth", if thermal
// level is "moderate" the effective profile is capped at "balanced".
let userPreferredProfile = (() => {
  try {
    // Migrate from old key if present
    const saved = localStorage.getItem("userPreferredProfile");
    if (saved) return saved;
    const legacy = localStorage.getItem("playbackProfile");
    if (legacy) {
      localStorage.setItem("userPreferredProfile", legacy);
      localStorage.removeItem("playbackProfile");
      return legacy;
    }
    return "balanced";
  } catch (_) {
    return "balanced";
  }
})();
let currentThermalLevel = "none";
let ottProfileActive = false; // server-driven OTT profile hint
let playbackProfile = userPreferredProfile;
const DENSITY_STORAGE_KEY = "castla_display_density";
let currentDensity = (() => {
  try {
    const saved = parseFloat(localStorage.getItem(DENSITY_STORAGE_KEY));
    return Number.isFinite(saved) ? saved : 0.7;
  } catch (_) {
    return 0.7;
  }
})();
let streamPolicy = {
  fitMode: "contain",
  autoFit: false,
  layoutMode: "single",
};

// Auto tier toast — shown briefly when auto-scale changes tier
let _autoTierToastTimer = null;
function showAutoTierToast(message) {
  const el = document.getElementById("auto-tier-toast");
  if (!el) return;
  el.textContent = message;
  el.style.opacity = "1";
  clearTimeout(_autoTierToastTimer);
  _autoTierToastTimer = setTimeout(() => {
    el.style.opacity = "0";
  }, 4500);
}

document.addEventListener("DOMContentLoaded", async () => {
  console.log("[Main] DOM Loaded, initializing components...");

  const webLauncher = document.getElementById("web-launcher");
  const homeBtn = document.getElementById("home-btn");
  const overlayMenu = document.getElementById("overlay-menu");
  const overlayMenuToggle = document.getElementById("overlay-menu-toggle");
  const overlayMenuPanel = document.getElementById("overlay-menu-panel");
  const densityControl = document.getElementById("density-control");
  const densityBtn = document.getElementById("density-btn");
  const densityLabel = document.getElementById("density-label");
  const densityPopup = document.getElementById("density-popup");
  const overlay = document.getElementById("overlay");
  const statusText = document.getElementById("status");
  const launcherLoading = document.getElementById("launcher-loading");
  const launcherContent = document.getElementById("launcher-content");
  const canvas = document.getElementById("display");
  const playerShell = document.getElementById("player-shell");
  const streamPane = document.getElementById("stream-pane");
  const browserSplitPane = document.getElementById("browser-split-pane");
  const secondaryCanvas = document.getElementById("display-secondary");
  const splitDivider = document.getElementById("split-divider");
  const splitResetBtn = document.getElementById("split-reset-btn");
  const splitCloseBtn = document.getElementById("split-close-btn");
  const splitSwapBtn = document.getElementById("split-swap-btn");

  // Split drawer
  const splitDrawer = document.getElementById("split-drawer");
  const splitHandle = document.getElementById("split-handle");
  const splitAppList = document.getElementById("split-app-list");

  const DEFAULT_SPLIT_RATIO = 0.5;
  let splitRatio = DEFAULT_SPLIT_RATIO;
  let isResizing = false;
  let leftLockedViewport = null;
  let rightLockedViewport = null;

  // 🔴 대칭적인 2가지 가상 디스플레이 독립 실행 정보 (SSOT 리액티브 상태 엔진)
  let _leftApp = null;
  let _rightApp = null;

  const state = {
    get left() {
      return _leftApp;
    },
    set left(app) {
      if (_leftApp === app) return;
      console.log(
        `[State] Left display app changed: ${app ? app.label : "null"}`,
      );
      _leftApp = app;
      requestAnimationFrame(() => updateLayoutUI());
    },
    get right() {
      return _rightApp;
    },
    set right(app) {
      if (_rightApp === app) return;
      console.log(
        `[State] Right display app changed: ${app ? app.label : "null"}`,
      );
      _rightApp = app;
      requestAnimationFrame(() => updateLayoutUI());
    },
  };

  let browserSplitState = { url: null, preset: null, swapped: false };
  const BROWSER_PRESETS = [
    { label: "YouTube", url: "https://m.youtube.com" },
    { label: "Netflix", url: "https://www.netflix.com" },
    { label: "Disney+", url: "https://www.disneyplus.com" },
    { label: "Wavve", url: "https://m.wavve.com" },
    { label: "TVING", url: "https://www.tving.com" },
    { label: "Coupang Play", url: "https://www.coupangplay.com" },
    { label: "Google", url: "https://www.google.com" },
  ];

  function isControlSocketOpen() {
    return !!controlSocket && controlSocket.readyState === WebSocket.OPEN;
  }

  /**
   * 제어 소켓이 열려있을 때만 데이터를 JSON 문자열로 안전하게 변환하여 송신합니다.
   * 에러 가드가 내장되어 전송 누수로 인한 스레드 다운을 방지합니다.
   */
  function sendControlMessage(obj) {
    if (isControlSocketOpen()) {
      try {
        controlSocket.send(JSON.stringify(obj));
      } catch (err) {
        console.error(
          "[Socket] Failed to serialize or send message:",
          err,
          obj,
        );
      }
    }
  }

  function setStatus(message, type = "") {
    if (!statusText) return;
    statusText.textContent = message;
    statusText.className = type;
  }

  function showOverlay() {
    if (!overlay) return;
    overlay.classList.remove("hidden");
  }

  function hideOverlay() {
    if (!overlay) return;
    overlay.classList.add("hidden");
  }

  function getActiveRenderer() {
    return decoder && decoder.renderer ? decoder.renderer : null;
  }

  function getActiveSecondaryRenderer() {
    return secondaryDecoder && secondaryDecoder.renderer
      ? secondaryDecoder.renderer
      : null;
  }

  function getEffectivePrimaryFitMode() {
    return !!state.right ? "fill" : streamPolicy.fitMode;
  }

  function getEffectiveSecondaryFitMode() {
    return !!state.right ? "fill" : streamPolicy.fitMode;
  }

  function alignDimension(value) {
    return Math.max(320, (Math.round(value) + 15) & ~15);
  }

  function buildLockedViewport(width, height, aspectRatio = null) {
    let nextWidth = Math.max(1, Math.round(width));
    let nextHeight = Math.max(1, Math.round(height));
    if (aspectRatio && Number.isFinite(aspectRatio) && aspectRatio > 0) {
      nextWidth = Math.max(nextWidth, Math.round(nextHeight * aspectRatio));
    }
    return {
      width: alignDimension(nextWidth),
      height: alignDimension(nextHeight),
    };
  }

  function getAppLayoutHints(app) {
    const packageName = app?.packageName || "";
    const category = app?.category || "";
    const label = (app?.label || "").toLowerCase();
    const isMapApp =
      category === "NAVIGATION" ||
      packageName.includes("map") ||
      packageName.includes("nmap") ||
      packageName.includes("waze") ||
      label.includes("지도") ||
      label.includes("map");
    const isVideoApp =
      category === "VIDEO" ||
      packageName.includes("youtube") ||
      packageName.includes("netflix") ||
      packageName.includes("tving") ||
      packageName.includes("wavve") ||
      packageName.includes("disney") ||
      label.includes("youtube") ||
      label.includes("netflix");
    const isMailOrFeed =
      packageName.includes("gmail") ||
      packageName.includes("mail") ||
      packageName.includes("outlook") ||
      packageName.includes("news") ||
      packageName.includes("reddit") ||
      packageName.includes("x.com") ||
      label.includes("gmail") ||
      label.includes("mail");

    return { isMapApp, isVideoApp, isMailOrFeed };
  }

  function getAppPreferredAspectRatio(app, role = "secondary") {
    const { isMapApp, isVideoApp, isMailOrFeed } = getAppLayoutHints(app);

    if (isMapApp) return 9 / 16;
    if (isVideoApp) return role === "secondary" ? 0.62 : 0.58;
    if (isMailOrFeed) return 0.56;
    return role === "primary" ? 9 / 16 : 0.58;
  }

  function computePrimaryPaneRatio(primaryAspectRatio) {
    const shellWidth = Math.round(
      playerShell?.clientWidth || window.innerWidth || 0,
    );
    const shellHeight = Math.round(
      playerShell?.clientHeight || window.innerHeight || 0,
    );
    if (
      shellWidth <= 0 ||
      shellHeight <= 0 ||
      !Number.isFinite(primaryAspectRatio) ||
      primaryAspectRatio <= 0
    ) {
      return DEFAULT_SPLIT_RATIO;
    }

    const desiredPrimaryWidth = Math.round(shellHeight * primaryAspectRatio);
    const minPrimaryWidth = Math.round(shellWidth * 0.25);
    const maxPrimaryWidth = Math.max(minPrimaryWidth, shellWidth - 320);
    const clampedPrimaryWidth = Math.max(
      minPrimaryWidth,
      Math.min(maxPrimaryWidth, desiredPrimaryWidth),
    );
    return clampedPrimaryWidth / shellWidth;
  }

  function resolveSplitPreset(primaryApp, secondaryApp) {
    const primaryAspectRatio = getAppPreferredAspectRatio(
      primaryApp,
      "primary",
    );
    const secondaryAspectRatio = getAppPreferredAspectRatio(
      secondaryApp,
      "secondary",
    );

    let ratio = 0.5;

    return {
      ratio: Math.max(0.25, Math.min(0.75, ratio)),
      secondaryAspectRatio,
      primaryAspectRatio,
    };
  }

  function lockBrowserSplitViewports(app = state.right) {
    if (!!!state.right) return;
    const preset = resolveSplitPreset(state.left, app);

    // Use current ratio (may have been changed by divider drag), not preset ratio
    const activeRatio = splitRatio;
    const { primaryWidth, secondaryWidth, shellHeight } =
      getDesiredSplitWidths(activeRatio);
    const primaryHeight = Math.round(
      streamPane?.clientHeight ||
        canvas?.clientHeight ||
        shellHeight ||
        window.innerHeight ||
        0,
    );

    console.log(
      `[ViewportLockDebug] activeRatio=${activeRatio} primaryWidth=${primaryWidth} secondaryWidth=${secondaryWidth} shellHeight=${shellHeight} isFullscreen=${playerShell?.classList.contains("secondary-fullscreen")}`,
    );

    if (primaryWidth > 0 && primaryHeight > 0) {
      leftLockedViewport = buildLockedViewport(primaryWidth, primaryHeight);
    } else {
      leftLockedViewport = null;
    }

    // ### 수정 시작 ###
    // Automatically build secondary locked viewport since system is dual stream only.
    const secondaryHeight = shellHeight;
    if (secondaryWidth > 0 && secondaryHeight > 0) {
      rightLockedViewport = buildLockedViewport(
        secondaryWidth,
        secondaryHeight,
        preset.secondaryAspectRatio,
      );
      console.log(
        `[ViewportLockDebug] lockedSecondaryViewport locked! width=${rightLockedViewport.width} height=${rightLockedViewport.height}`,
      );
    } else {
      console.warn(
        `[ViewportLockDebug] Secondary lock skipped because secondaryWidth=${secondaryWidth} or secondaryHeight=${secondaryHeight}`,
      );
    }
    // ### 수정 끝 ###
  }

  function updateSplitFitButton() {
    // No-op: fit button removed in browser-only split
  }

  function applyActiveFitModes() {
    const primaryFitMode = getEffectivePrimaryFitMode();
    const secondaryFitMode = getEffectiveSecondaryFitMode();
    document.body.dataset.fitMode = !!state.right
      ? secondaryFitMode
      : primaryFitMode;
    getActiveRenderer()?.setFitMode?.(primaryFitMode);
    getActiveSecondaryRenderer()?.setFitMode?.(secondaryFitMode);
    updateSplitFitButton();
  }

  function getSplitShellSize() {
    const shellWidth = Math.round(
      playerShell?.clientWidth || window.innerWidth || 0,
    );
    const shellHeight = Math.round(
      playerShell?.clientHeight || window.innerHeight || 0,
    );
    return { shellWidth, shellHeight };
  }

  function getDesiredSplitWidths(ratio = splitRatio) {
    const { shellWidth, shellHeight } = getSplitShellSize();
    if (shellWidth <= 0 || shellHeight <= 0) {
      return { primaryWidth: 0, secondaryWidth: 0, shellWidth, shellHeight };
    }

    // Strict Secondary Fullscreen Safeguard: Dedicate 100% shellWidth to VD_2 viewport
    if (playerShell?.classList.contains("secondary-fullscreen")) {
      return {
        primaryWidth: 0,
        secondaryWidth: shellWidth,
        shellWidth,
        shellHeight,
      };
    }

    // ### 수정 시작 ###
    // Relax minimum width constraints from 320px to 160px for extremely flexible resizing
    const minPrimaryWidth = 160;
    const minSecondaryWidth = 160;
    // ### 수정 끝 ###
    const desiredPrimaryWidth = Math.round(shellWidth * ratio);
    const maxPrimaryWidth = Math.max(
      minPrimaryWidth,
      shellWidth - minSecondaryWidth,
    );
    const primaryWidth = Math.max(
      minPrimaryWidth,
      Math.min(maxPrimaryWidth, desiredPrimaryWidth),
    );
    const secondaryWidth = Math.max(
      minSecondaryWidth,
      shellWidth - primaryWidth,
    );
    return { primaryWidth, secondaryWidth, shellWidth, shellHeight };
  }

  function updateSplitToolbarVisibility() {
    if (!splitToolbar) return;
    splitToolbar.style.display = !!state.right ? "flex" : "none";
  }

  function setBrowserSplitRatio(nextRatio) {
    const ratio = Math.max(0.1, Math.min(0.9, nextRatio));
    splitRatio = ratio;
    const { primaryWidth, shellWidth } = getDesiredSplitWidths(ratio);
    if (primaryWidth > 0 && shellWidth > 0) {
      playerShell?.style.setProperty("--split-left-width", `${primaryWidth}px`);
    } else {
      playerShell?.style.setProperty(
        "--split-left-width",
        `${Math.round(ratio * 1000) / 10}%`,
      );
    }
  }

  function isDualStreamCapable(app) {
    return !!app;
  }

  function destroySecondaryTransport() {
    if (secondaryTouchHandler) {
      secondaryTouchHandler.destroy();
      secondaryTouchHandler = null;
    }
    if (secondaryVideoSocket) {
      try {
        secondaryVideoSocket.close();
      } catch (_) {}
      secondaryVideoSocket = null;
    }
    if (secondaryFramePacer) {
      secondaryFramePacer.destroy();
      secondaryFramePacer = null;
    }
    if (secondaryDecoder) {
      secondaryDecoder.destroy?.();
      secondaryDecoder = null;
    }
    if (secondaryCanvas) {
      const ctx = secondaryCanvas.getContext("2d");
      ctx?.clearRect(
        0,
        0,
        secondaryCanvas.width || secondaryCanvas.clientWidth || 0,
        secondaryCanvas.height || secondaryCanvas.clientHeight || 0,
      );
    }
  }

  async function initSecondaryDecoder(preserveCache = false) {
    if (!secondaryCanvas) return null;

    // [SPS/PPS MIGRATION SAFEGUARD] Backup SPS/PPS cache only when preserveCache is true (not a resolution change)
    const prevSpsPps =
      preserveCache && secondaryDecoder ? secondaryDecoder._cachedSpsPps : null;

    if (secondaryDecoder) {
      secondaryDecoder.destroy?.();
      secondaryDecoder = null;
    }
    if (secondaryFramePacer) {
      secondaryFramePacer.destroy();
      secondaryFramePacer = null;
    }

    if (typeof WebCodecs !== "undefined" || window.VideoDecoder) {
      const renderer = new CanvasRenderer(secondaryCanvas);
      renderer.setFitMode(getEffectiveSecondaryFitMode());

      secondaryFramePacer = new FramePacer((frame) => renderer.render(frame));
      secondaryFramePacer.setProfile(playbackProfile);

      secondaryDecoder = new H264Decoder(
        (frame) => secondaryFramePacer.push(frame),
        (error) => console.error("[Main] Secondary decoder error:", error),
      );
      secondaryDecoder.onFrameGap = () => {
        sendControlMessage({ type: "requestKeyframe", pane: "secondary" });
      };
      secondaryDecoder.setBacklogProfile(playbackProfile);
      secondaryFramePacer.setDecoder(secondaryDecoder);
      secondaryDecoder.renderer = renderer;

      // Restore/migrate previous SPS/PPS cache only if appropriate
      if (prevSpsPps) {
        secondaryDecoder._cachedSpsPps = prevSpsPps;
        console.log(
          "[Decoder] Successfully migrated cached SPS/PPS to new secondary decoder",
        );
      } else {
        console.log(
          "[Decoder] Secondary SPS/PPS cache cleared/reset for new resolution stream",
        );
      }

      await secondaryDecoder.init(secondaryCanvas);
    } else if (typeof createImageBitmap !== "undefined") {
      secondaryDecoder = new FallbackDecoder(
        () => {},
        (error) => console.error("[Main] Secondary fallback error:", error),
      );
      await secondaryDecoder.init(secondaryCanvas);
      secondaryDecoder.renderer?.setFitMode?.(getEffectiveSecondaryFitMode());
    }
    return secondaryDecoder;
  }

  function connectSecondaryVideo(isHotRefresh = false) {
    if (!!!state.right) return;
    if (secondaryVideoSocket) {
      try {
        secondaryVideoSocket.close();
      } catch (_) {}
    }

    // Reset sequence tracking but preserve the precious SPS/PPS cache if in hot-refresh mode
    if (secondaryDecoder) {
      secondaryDecoder._lastSeqNum = undefined;
      if (!isHotRefresh) {
        secondaryDecoder._cachedSpsPps = null;
      }
    }

    const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${wsProtocol}//${host}/ws/video?channel=secondary`;
    secondaryVideoSocket = new WebSocket(wsUrl);
    secondaryVideoSocket.binaryType = "arraybuffer";
    secondaryVideoSocket.onopen = () => {
      console.log("[Main] Secondary video socket connected successfully");

      if (codecMode === "h264") {
        sendControlMessage({ type: "requestKeyframe", pane: "secondary" });
      }
    };
    secondaryVideoSocket.onmessage = async (event) => {
      if (event.data instanceof ArrayBuffer && secondaryDecoder) {
        secondaryDecoder.decode(event.data);
      }
    };
    secondaryVideoSocket.onclose = () => {
      if (!!state.right) scheduleReconnect();
    };
    secondaryVideoSocket.onerror = (error) =>
      console.error("[Main] Secondary video WebSocket error:", error);
  }

  function sendSecondaryLaunchRequest() {
    if (!!!state.right || !state.right) return;
    if (isControlSocketOpen()) return;

    const primaryHints = getAppLayoutHints(primaryApp);

    const app = state.right;
    const message = {
      type: "launchApp",
      pkg: app.packageName,
      pane: "secondary",
    };
    if (app.componentName) message.componentName = app.componentName;

    sendControlMessage(message);

    // Request keyframes for secondary display to prevent black screen when restarting standard launch
    if (codecMode === "h264") {
      sendControlMessage({ type: "requestKeyframe", pane: "secondary" });

      setTimeout(() => {
        sendControlMessage({ type: "requestKeyframe", pane: "secondary" });
      }, 800);
    }
  }

  async function updateLayoutUI() {
    // [PROMOTION SAFEGUARD] Avoid layout reflow shifts during active secondary-to-primary promotion
    if (isPromotingSecondary) {
      console.log(
        "[Layout] Layout update bypassed due to active secondary-to-primary promotion.",
      );
      return;
    }

    const hasLeft = !!state.left;
    const hasRight = !!state.right;

    console.log(
      `[Launcher] updateLayoutUI: hasLeft=${hasLeft}, hasRight=${hasRight}`,
    );

    if (hasLeft && hasRight) {
      // ==========================================
      // 🔴 상황 1: 둘 다 실행된 상태 -> 2개 보이는 듀얼 스플릿(left & right) UI 구성!
      // ==========================================
      destroySecondaryTransport();

      streamPolicy.layoutMode = "browser_split";
      document.body.dataset.layoutMode = streamPolicy.layoutMode;

      const initialRatio = splitRatio || DEFAULT_SPLIT_RATIO;
      setBrowserSplitRatio(initialRatio);

      // Highlight the closest ratio button
      document.querySelectorAll(".split-ratio-btn").forEach((b) => {
        const btnRatio = parseFloat(b.dataset.ratio);
        b.classList.toggle("active", Math.abs(btnRatio - initialRatio) < 0.05);
      });

      playerShell?.classList.remove("secondary-fullscreen");
      playerShell?.classList.add("browser-split");
      updateSplitToolbarVisibility();
      applyActiveFitModes();

      // Synchronously lock viewports BEFORE any await to prevent intermediate fullscreen sends
      lockBrowserSplitViewports(state.right);

      await new Promise((resolve) => requestAnimationFrame(() => resolve()));
      lockBrowserSplitViewports(state.right);
      await initSecondaryDecoder();
      if (secondaryTouchHandler) {
        secondaryTouchHandler.destroy();
      }
      secondaryTouchHandler = new TouchHandler(
        secondaryCanvas,
        getActiveSecondaryRenderer(),
        controlSocket,
        "secondary",
      );
      applyActiveFitModes();
      connectSecondaryVideo();
      requestAnimationFrame(() => sendViewportSize(true));
    } else if (hasRight) {
      // ==========================================
      // 🔴 상황 2: state.right(오른쪽)만 단독 실행된 상태 -> 1개용 right 단독 풀 UI 구성!
      // ==========================================
      destroySecondaryTransport();

      streamPolicy.layoutMode = "browser_split";
      document.body.dataset.layoutMode = streamPolicy.layoutMode;

      const initialRatio = 0.5; // 단독 기동 시 중앙 밸런싱 배치
      setBrowserSplitRatio(initialRatio);

      // Highlight the closest ratio button
      document.querySelectorAll(".split-ratio-btn").forEach((b) => {
        const btnRatio = parseFloat(b.dataset.ratio);
        b.classList.toggle("active", Math.abs(btnRatio - initialRatio) < 0.05);
      });

      playerShell?.classList.add("secondary-fullscreen");
      playerShell?.classList.add("browser-split");
      updateSplitToolbarVisibility();
      applyActiveFitModes();

      // Synchronously lock viewports BEFORE any await to prevent intermediate fullscreen sends
      lockBrowserSplitViewports(state.right);

      await new Promise((resolve) => requestAnimationFrame(() => resolve()));
      lockBrowserSplitViewports(state.right);
      await initSecondaryDecoder();
      if (secondaryTouchHandler) {
        secondaryTouchHandler.destroy();
      }
      secondaryTouchHandler = new TouchHandler(
        secondaryCanvas,
        getActiveSecondaryRenderer(),
        controlSocket,
        "secondary",
      );
      applyActiveFitModes();
      connectSecondaryVideo();
      requestAnimationFrame(() => sendViewportSize(true));
    } else if (hasLeft || !isLauncherMode) {
      // Keep single display stream view active even when there is no user app active (i.e. system home launcher) as long as mirroring is active
      // ==========================================
      // 🔴 상황 3: state.left(왼쪽)만 단독 실행된 상태 또는 스트리밍 중인 홈 화면 상태 -> 1개용 left 단독 풀 UI 구성!
      // ==========================================
      destroySecondaryTransport();
      updateSplitToolbarVisibility();

      playerShell?.classList.remove("secondary-fullscreen");
      playerShell?.classList.remove("browser-split");

      isLauncherMode = false;
      webLauncher.classList.add("hidden");
      splitDrawer.style.display = "flex";
      homeBtn.style.display = "block";

      // Clear locked viewports since we are in single display mode
      leftLockedViewport = null;
      rightLockedViewport = null;

      // Keep primary canvas visible during seamless hot-refresh resize
      canvas.style.opacity = "1";
      const mseVideo = document.getElementById("mse-video");
      if (mseVideo) mseVideo.style.opacity = "0";

      applyActiveFitModes();
      sendViewportSize();
    } else {
      // ==========================================
      // 🔴 상황 4: 둘 다 실행이 안 된 상태 -> 깨끗하게 홈 런처로 환원!
      // ==========================================
      destroySecondaryTransport();
      updateSplitToolbarVisibility();

      playerShell?.classList.remove("secondary-fullscreen");
      playerShell?.classList.remove("browser-split");

      state.left = null;
      state.right = null;
      isLauncherMode = true;
      webLauncher.classList.remove("hidden");
      splitDrawer.style.display = "none";
      homeBtn.style.display = "none";

      clearCanvas();
    }
  }

  // ### 수정 시작 ###
  let pendingLayoutSwitch = null;

  function handleRendererResolutionChange(width, height) {
    if (pendingLayoutSwitch === "single") {
      const aspect = width / height;
      // Fullscreen expects a landscape layout (typically >= 1.0)
      if (aspect >= 1.0) {
        console.log(
          `[LayoutSmoothing] Target fullscreen frame received: ${width}x${height} (aspect: ${aspect.toFixed(2)}). Smoothly expanding layout.`,
        );
        executeVisualFullscreenLayout();
      }
    }
  }

  function executeVisualFullscreenLayout() {
    pendingLayoutSwitch = null;
    playerShell?.classList.remove("browser-split");
    playerShell?.classList.remove("secondary-fullscreen");
    playerShell?.style.removeProperty("--split-left-width");

    // Force synchronous browser layout reflow immediately after removing layout classes
    if (playerShell) {
      const _reflow = playerShell.offsetWidth;
    }

    applyActiveFitModes();

    // Instantly fit and redraw the primary canvas layout to cover 100% fullscreen
    getActiveRenderer()?.updateLayout?.();
  }

  function disableBrowserSplit(options = {}) {
    const { notifyServer = true, delayVisual = false } = options;
    const wasActive = !!state.right || isPromotingSecondary;

    state.right = null;
    updateSplitToolbarVisibility();
    isResizing = false;
    browserSplitState.url = null;

    leftLockedViewport = null;
    rightLockedViewport = null;
    browserSplitState.preset = null;
    browserSplitState.swapped = false;
    playerShell?.classList.remove("swapped");
    streamPolicy.layoutMode = "single";
    document.body.dataset.layoutMode = streamPolicy.layoutMode;

    destroySecondaryTransport();
    if (notifyServer && wasActive) {
      sendControlMessage({ type: "closeSecondary" });
    }

    // Send full settled viewport size synchronously and instantly to the server
    if (
      wasActive &&
      controlSocket &&
      controlSocket.readyState === WebSocket.OPEN
    ) {
      console.log(
        "[Main] Split closed — sending full viewport size immediately",
      );
      sendViewportSize(true);
    }

    if (delayVisual && wasActive) {
      console.log(
        "[Main] Delaying visual layout smoothing until fullscreen frame arrives.",
      );
      pendingLayoutSwitch = "single";
    } else {
      executeVisualFullscreenLayout();
    }

    // Light backup trigger to guard against edge cases or late socket arrivals
    setTimeout(() => {
      sendViewportSize(true);
    }, 100);
  }
  // ### 수정 끝 ###

  function promoteSecondaryToPrimary(secondaryApp) {
    if (!secondaryApp) return;

    console.log(
      "[Promotion] Seamlessly promoting Secondary to Primary display.",
    );

    // 1. Mark promotion state globally
    isPromotingSecondary = true;

    // 2. Instantly stretch the secondary pane/renderer to 100% fullscreen via CSS
    playerShell?.classList.add("secondary-fullscreen");

    // 4. Update core state variables to reflect the transition
    state.left = secondaryApp;
    state.right = null;
    browserSplitState.url = null;
    browserSplitState.preset = null;
    browserSplitState.swapped = false;

    leftLockedViewport = null;
    rightLockedViewport = null;

    // 5. Send backend request to launch this app on the primary display pane
    if (isControlSocketOpen()) {
      // Eagerly flag firstFrameReceived as false to await the new stream frame
      firstFrameReceived = false;

      const message = {
        type: "launchApp",
        pkg: secondaryApp.packageName,
        pane: "primary",
      };
      if (secondaryApp.componentName)
        message.componentName = secondaryApp.componentName;

      sendControlMessage(message);

      // Eagerly notify the server that the primary pane is now covering 100% fullscreen
      const shellWidth = Math.round(
        playerShell?.clientWidth || window.innerWidth || 1920,
      );
      const shellHeight = Math.round(
        playerShell?.clientHeight || window.innerHeight || 1080,
      );

      console.log(
        `[Promotion] Sending immediate primary fullscreen viewport: ${shellWidth}x${shellHeight}`,
      );

      lastSentPrimary = {
        width: shellWidth,
        height: shellHeight,
        fitMode: streamPolicy.fitMode,
        layoutMode: "single",
      };

      sendControlMessage({
        type: "viewport",
        pane: "primary",
        width: shellWidth,
        height: shellHeight,
        fitMode: streamPolicy.fitMode,
        layoutMode: "single",
      });

      sendControlMessage({ type: "requestKeyframe", pane: "primary" });
      // Force immediate keyframe request to trigger fast stream startup

      // Set backup keyframe request
      setTimeout(() => {
        sendControlMessage({ type: "requestKeyframe", pane: "primary" });
      }, 800);
    }
  }

  function applyStreamPolicy(config = {}) {
    streamPolicy = {
      ...streamPolicy,
      ...config,
    };

    document.body.dataset.layoutMode = streamPolicy.layoutMode;

    applyActiveFitModes();
    requestAnimationFrame(() => sendViewportSize());
  }

  function focusKeyboardProxy() {
    const kbInput = document.getElementById("keyboard-input");
    if (!kbInput) return;

    kbInput.style.pointerEvents = "auto";
    kbInput.focus({ preventScroll: true });
    if (typeof kbInput.setSelectionRange === "function") {
      const len = kbInput.value.length;
      kbInput.setSelectionRange(len, len);
    }
  }

  function blurKeyboardProxy() {
    const kbInput = document.getElementById("keyboard-input");
    if (!kbInput) return;

    kbInput.blur();
    kbInput.style.pointerEvents = "none";
    kbInput.value = "";
  }

  let firstFrameReceived = false;
  let launchTimeout = null;
  let composing = false;
  let skipNextInput = false;

  // ── Bubble Composer state ──
  let useBubbleInput = localStorage.getItem("castla_use_bubble") === "true";
  let bubbleVisible = false;
  const inputBubble = document.getElementById("input-bubble");
  const bubbleText = document.getElementById("bubble-text");
  const bubbleSubmit = document.getElementById("bubble-submit");
  const bubbleBackspace = document.getElementById("bubble-backspace");
  const bubbleCancel = document.getElementById("bubble-cancel");

  // Prevent bubble touch/pointer events from propagating to canvas
  if (inputBubble) {
    for (const evt of [
      "pointerdown",
      "pointerup",
      "pointermove",
      "touchstart",
      "touchend",
      "touchmove",
      "mousedown",
      "mouseup",
    ]) {
      inputBubble.addEventListener(evt, (e) => e.stopPropagation());
    }
  }

  function clearLaunchTimeout() {
    if (launchTimeout) {
      clearTimeout(launchTimeout);
      launchTimeout = null;
    }
  }

  let launcherNoticeTimer = null;

  function showLauncherNotice(message) {
    if (!launcherLoading) return;
    const isAppLoading =
      message.toLowerCase().includes("loading") ||
      message.toLowerCase().includes("launching");
    launcherLoading.innerHTML = `
            ${isAppLoading ? '<div class="loading-spinner"></div>' : ""}
            <div class="loading-text">${message}</div>
        `;
    launcherLoading.style.display = "flex";

    if (launcherNoticeTimer) {
      clearTimeout(launcherNoticeTimer);
      launcherNoticeTimer = null;
    }
    if (!isAppLoading) {
      launcherNoticeTimer = setTimeout(() => {
        hideLauncherNotice();
      }, 3000);
    }
  }

  function hideLauncherNotice() {
    if (!launcherLoading) return;
    launcherLoading.style.display = "none";
    if (launcherNoticeTimer) {
      clearTimeout(launcherNoticeTimer);
      launcherNoticeTimer = null;
    }
  }

  // ── Bubble Composer functions ──
  function positionInputBubble(anchor) {
    if (!inputBubble) return;
    const bh = 56;
    const margin = 12;
    const bw = inputBubble.offsetWidth || 360;

    let cx, top;
    if (anchor) {
      cx = anchor.clientX;
      top = anchor.clientY - bh - margin;
      if (top < margin) top = anchor.clientY + margin;
    } else {
      cx = window.innerWidth / 2;
      top = window.innerHeight - bh - 60;
    }

    let left = cx - bw / 2;
    if (left < margin) left = margin;
    if (left + bw > window.innerWidth - margin)
      left = window.innerWidth - margin - bw;
    if (top < margin) top = margin;
    if (top + bh > window.innerHeight - margin)
      top = window.innerHeight - margin - bh;

    inputBubble.style.left = `${left}px`;
    inputBubble.style.top = `${top}px`;
  }

  function openInputBubble(anchor) {
    if (!inputBubble || bubbleVisible) return;
    bubbleVisible = true;
    positionInputBubble(anchor);
    inputBubble.classList.add("visible");
    bubbleText.value = "";
    setTimeout(() => bubbleText.focus({ preventScroll: true }), 80);
  }

  function closeInputBubble(clear = true) {
    if (!inputBubble) return;
    bubbleVisible = false;
    inputBubble.classList.remove("visible");
    if (clear && bubbleText) bubbleText.value = "";
    bubbleText?.blur();
  }

  function submitBubbleInput() {
    if (!bubbleText) return;
    // Read value BEFORE blur — blur() may discard uncommitted Korean
    // IME composition on some WebView implementations instead of
    // committing it, which would leave the value empty.
    const text = bubbleText.value;
    bubbleText.blur();

    if (text) {
      sendControlMessage({ type: "textInput", text });
    }
    sendControlMessage({ type: "keyEvent", keyCode: 66 });
    bubbleText.value = "";
  }

  if (bubbleSubmit) {
    bubbleSubmit.addEventListener("click", (e) => {
      e.preventDefault();
      submitBubbleInput();
    });
  }
  if (bubbleBackspace) {
    bubbleBackspace.addEventListener("click", (e) => {
      e.preventDefault();
      sendControlMessage({ type: "keyEvent", keyCode: 67 });
      bubbleText?.focus({ preventScroll: true });
    });
  }
  if (bubbleCancel) {
    bubbleCancel.addEventListener("click", (e) => {
      e.preventDefault();
      closeInputBubble(true);
      // Tell the server so it can suppress the hasTarget fallback until the
      // user re-engages (touch-down on mirror). Otherwise on platforms where
      // the phone IME never actually shows, the bubble would re-open on the
      // next poll because imeInputTarget persists.
      sendControlMessage({ type: "bubbleClosed" });
    });
  }
  if (bubbleText) {
    bubbleText.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        submitBubbleInput();
      }
    });
  }

  // ── Browser-Only Split Pane Functions ──
  const browserPaneUi = document.getElementById("browser-pane-ui");
  const browserUrlInput = document.getElementById("browser-url-input");
  const browserGoBtn = document.getElementById("browser-go-btn");
  const browserRefreshBtn = document.getElementById("browser-refresh-btn");
  const browserPresetsContainer = document.getElementById("browser-presets");
  const browserIframe = document.getElementById("browser-iframe");
  const browserLoading = document.getElementById("browser-loading");
  const browserError = document.getElementById("browser-error");
  const browserErrorClose = document.getElementById("browser-error-close");
  const browserHomeState = document.getElementById("browser-home-state");

  // Map from package name to browser URL (mirrors server-side OTT_WEB_URLS)
  const OTT_WEB_URLS = {
    "com.google.android.youtube": "https://m.youtube.com",
    "com.netflix.mediaclient": "https://www.netflix.com",
    "com.disney.disneyplus": "https://www.disneyplus.com",
    "com.disney.disneyplus.kr": "https://www.disneyplus.com",
    "com.wavve.player": "https://m.wavve.com",
    "net.cj.cjhv.gs.tving": "https://www.tving.com",
    "com.coupang.play": "https://www.coupangplay.com",
    "com.frograms.watcha": "https://watcha.com",
  };

  function getPresetUrlForApp(app) {
    if (!app) return null;
    // 1. Server already provides webUrl for OTT apps
    if (app.webUrl) return app.webUrl;
    // 2. Fallback: check client-side OTT map
    const url = OTT_WEB_URLS[app.packageName];
    if (url) return url;
    return null;
  }

  function loadBrowserUrl(url) {
    if (!url) return;
    browserSplitState.url = url;
    if (browserHomeState) browserHomeState.classList.add("hidden");
    if (browserError) browserError.classList.remove("visible");
    if (browserLoading) browserLoading.classList.remove("hidden");
    if (browserIframe) {
      browserIframe.src = url;
      browserIframe.style.display = "block";
    }
    if (browserUrlInput) browserUrlInput.value = url;
    updatePresetButtons(url);
    console.log(`[Main] Browser pane loading: ${url}`);

    // Detect load or timeout
    let loaded = false;
    const onLoad = () => {
      loaded = true;
      if (browserLoading) browserLoading.classList.add("hidden");
    };
    if (browserIframe) {
      browserIframe.onload = onLoad;
    }
    setTimeout(() => {
      if (!loaded && browserLoading) browserLoading.classList.add("hidden");
    }, 8000);
  }

  function showBrowserHome() {
    browserSplitState.url = null;
    if (browserIframe) {
      browserIframe.src = "about:blank";
      browserIframe.style.display = "none";
    }
    if (browserHomeState) browserHomeState.classList.remove("hidden");
    if (browserError) browserError.classList.remove("visible");
    if (browserLoading) browserLoading.classList.add("hidden");
    if (browserUrlInput) browserUrlInput.value = "";
    updatePresetButtons(null);
  }

  function clearBrowserPane() {
    if (browserIframe) {
      browserIframe.src = "about:blank";
      browserIframe.style.display = "none";
    }
    if (browserLoading) browserLoading.classList.add("hidden");
    if (browserError) browserError.classList.remove("visible");
    if (browserHomeState) browserHomeState.classList.remove("hidden");
    if (browserUrlInput) browserUrlInput.value = "";
  }

  function updatePresetButtons(activeUrl) {
    if (!browserPresetsContainer) return;
    const buttons = browserPresetsContainer.querySelectorAll(
      ".browser-preset-btn",
    );
    buttons.forEach((btn) => {
      btn.classList.toggle(
        "active",
        activeUrl && btn.dataset.url === activeUrl,
      );
    });
  }

  // Render preset buttons
  if (browserPresetsContainer) {
    BROWSER_PRESETS.forEach((preset) => {
      const btn = document.createElement("button");
      btn.className = "browser-preset-btn";
      btn.textContent = preset.label;
      btn.dataset.url = preset.url;
      btn.addEventListener("click", () => loadBrowserUrl(preset.url));
      browserPresetsContainer.appendChild(btn);
    });
  }

  if (browserGoBtn && browserUrlInput) {
    const navigateBrowserUrl = () => {
      let url = (browserUrlInput.value || "").trim();
      if (!url) return;
      if (!/^https?:\/\//i.test(url)) url = "https://" + url;
      loadBrowserUrl(url);
    };
    browserGoBtn.addEventListener("click", navigateBrowserUrl);
    browserUrlInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        navigateBrowserUrl();
      }
    });
  }

  if (browserRefreshBtn && browserIframe) {
    browserRefreshBtn.addEventListener("click", () => {
      if (browserSplitState.url) loadBrowserUrl(browserSplitState.url);
    });
  }

  if (browserErrorClose) {
    browserErrorClose.addEventListener("click", () => showBrowserHome());
  }

  setBrowserSplitRatio(DEFAULT_SPLIT_RATIO);
  updateSplitFitButton();
  hideOverlay();

  async function initDecoder(preserveCache = false) {
    console.log(
      "[Main] Initializing decoders... preserveCache=",
      preserveCache,
    );

    if (typeof WebCodecs !== "undefined" || window.VideoDecoder) {
      console.log("[Main] Using WebCodecs Decoder");
      const renderer = new CanvasRenderer(canvas);
      renderer.setFitMode(getEffectivePrimaryFitMode());
      // ### 수정 시작 ###
      // Hook the smooth layout resolution change callback
      renderer.onFrameResolutionChange = handleRendererResolutionChange;
      // ### 수정 끝 ###

      // Create frame pacer between decoder and renderer
      if (framePacer) framePacer.destroy();
      framePacer = new FramePacer((frame) => renderer.render(frame));
      framePacer.setProfile(playbackProfile);

      // [SPS/PPS MIGRATION SAFEGUARD] Backup SPS/PPS cache only when preserveCache is true (not a resolution change)
      const prevSpsPps =
        preserveCache && decoder ? decoder._cachedSpsPps : null;

      // ### 수정 시작 ###
      // Destroy existing primary decoder cleanly to prevent GPU resource conflict & screen freezes
      if (decoder) {
        decoder.destroy?.();
        decoder = null;
      }
      // ### 수정 끝 ###

      decoder = new H264Decoder(
        (frame) => framePacer.push(frame),
        (error) => console.error("[Main] Decoder error:", error),
      );
      decoder.onFrameGap = () => {
        sendControlMessage({ type: "requestKeyframe", pane: "primary" });
      };
      decoder.setBacklogProfile(playbackProfile);
      framePacer.setDecoder(decoder);
      decoder.renderer = renderer;

      // Restore/migrate previous SPS/PPS cache only if appropriate
      if (prevSpsPps) {
        decoder._cachedSpsPps = prevSpsPps;
        console.log(
          "[Decoder] Successfully migrated cached SPS/PPS to new primary decoder",
        );
      } else {
        console.log(
          "[Decoder] SPS/PPS cache cleared/reset for new resolution stream",
        );
      }

      await decoder.init(canvas);
      codecMode = "h264";
      applyActiveFitModes();

      canvas.style.display = "block";
      const mseVideo = document.getElementById("mse-video");
      if (mseVideo) mseVideo.style.display = "none";
    } else if (typeof createImageBitmap !== "undefined") {
      console.log("[Main] Using MJPEG fallback");
      decoder = new FallbackDecoder(
        () => {
          if (!firstFrameReceived) {
            firstFrameReceived = true;
            checkReady();
          }
        },
        (error) => console.error("[Main] Fallback error:", error),
      );
      await decoder.init(canvas);
      // ### 수정 시작 ###
      // Hook the smooth layout resolution change callback on fallback decoder renderer
      if (decoder.renderer) {
        decoder.renderer.onFrameResolutionChange =
          handleRendererResolutionChange;
      }
      // ### 수정 끝 ###
      decoder.renderer?.setFitMode?.(getEffectivePrimaryFitMode());
      codecMode = "mjpeg";
      applyActiveFitModes();

      canvas.style.display = "block";
      const mseVideo = document.getElementById("mse-video");
      if (mseVideo) mseVideo.style.display = "none";

      sendControlMessage({ type: "codec", mode: "mjpeg" });
    } else {
      throw new Error("No supported decoder available.");
    }
  }

  function connectVideo(isHotRefresh = false) {
    if (videoSocket) {
      try {
        videoSocket.onopen = null;
        videoSocket.onmessage = null;
        videoSocket.onerror = null;
        videoSocket.onclose = null;
        videoSocket.close();
      } catch (_) {}
    }
    const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${wsProtocol}//${host}/ws/video`;
    if (!isLauncherMode) setStatus("Connecting...", "");

    clearFrameWatchdog();

    // Reset firstFrameReceived so that the reconnected stream's first frame triggers checkReady() and hides the overlay!
    firstFrameReceived = false;

    // Reset decoder sequence tracking and SPS/PPS cache only when NOT in a hot-refresh transition
    if (decoder) {
      decoder._lastSeqNum = undefined;
      if (!isHotRefresh) {
        decoder._cachedSpsPps = null;
      }
      if (decoder.resetStats) decoder.resetStats();
    }
    if (secondaryDecoder) {
      secondaryDecoder._lastSeqNum = undefined;
      if (!isHotRefresh) {
        secondaryDecoder._cachedSpsPps = null;
      }
      if (secondaryDecoder.resetStats) secondaryDecoder.resetStats();
    }

    console.log(`[Main] Connecting video socket to: ${wsUrl}`);
    videoSocket = new WebSocket(wsUrl);
    videoSocket.binaryType = "arraybuffer";

    videoSocket.onopen = () => {
      console.log(`[Main] Video socket connected!`);
      if (!isLauncherMode) setStatus("Loading...", "");
      if (codecMode === "mjpeg") {
        console.log(
          `[Main] Sending codec preference: mjpeg via control socket`,
        );
        sendControlMessage({ type: "codec", mode: "mjpeg" });
      } else if (codecMode === "h264") {
        console.log(
          `[Main] Requesting H264 keyframe via control socket on video open`,
        );
        sendControlMessage({ type: "requestKeyframe", pane: "primary" });
      }
    };

    videoSocket.onmessage = async (event) => {
      if (event.data instanceof ArrayBuffer) {
        armFrameWatchdog(videoSocket);
        if (!decoder) return;
        if (codecMode === "h264") {
          const v = new Uint8Array(event.data);
          if (v.length > 0 && v[0] === 0x01 && !firstFrameReceived) {
            firstFrameReceived = true;
            checkReady();
          }
        }
        decoder.decode(event.data);
      }
    };

    videoSocket.onclose = () => {
      clearFrameWatchdog();
      if (!isLauncherMode) {
        setStatus("Disconnected", "error");
        showOverlay();
      }
      scheduleReconnect();
    };

    videoSocket.onerror = (error) =>
      console.error("[Main] Video WebSocket error:", error);
  }

  function checkReady() {
    if (firstFrameReceived) {
      clearLaunchTimeout();
      const mseVideo = document.getElementById("mse-video");
      if (isLauncherMode) {
        canvas.style.opacity = "0";
        if (mseVideo) {
          mseVideo.style.opacity = "0";
          mseVideo.style.display = "none";
        }
      } else {
        canvas.style.opacity = "1";
        if (mseVideo) {
          mseVideo.style.opacity = "0";
          mseVideo.style.display = "none";
        }
      }
      hideOverlay();
      if (decoder && decoder.play) {
        decoder.play();
      }

      // Seamless Promotion Transition Cleanup:
      // If we were waiting for the promoted secondary app to land on primary stream, complete the transition now!
      if (isPromotingSecondary) {
        console.log(
          "[Promotion] First primary frame of promoted app received! Completing transition.",
        );

        // First call disableBrowserSplit while isPromotingSecondary is still true
        // to trigger proper full-screen transition and primary temporary fill shield!
        disableBrowserSplit({ notifyServer: false });

        isPromotingSecondary = false;
        playerShell?.classList.remove("secondary-fullscreen");
      }
    }
  }

  let reconnectTimer = null;
  let isReconnecting = false;
  let qualityReportInterval = null;

  // Frame-arrival watchdog: first try to recover an open but quiet stream by
  // asking the server/encoder for a fresh frame. Only reconnect if the stream
  // stays quiet for the hard timeout.
  const FRAME_SOFT_TIMEOUT_MS = 4000;
  const FRAME_HARD_TIMEOUT_MS = 10000;
  let frameWatchdogTimer = null;

  function armFrameWatchdog(socket) {
    if (isLauncherMode || !socket) return;
    if (socket !== videoSocket) return;
    if (frameWatchdogTimer !== null) clearTimeout(frameWatchdogTimer);
    frameWatchdogTimer = setTimeout(() => {
      // [핵심 해결 포인트]
      // 프레임은 안 들어왔지만, 웹소켓 파이프라인(TCP)이 여전히 정상 연결(OPEN) 상태라면?
      // 이는 에러가 아니라 안드로이드 화면에 아무런 움직임이 없는 '정상 정지 상태'입니다.
      if (socket && socket.readyState === WebSocket.OPEN) {
        // 소프트 스톨 리커버리를 호출하지 않고, 조용히 타이머를 다음 주기로 갱신합니다.
        armFrameWatchdog(socket);
        return;
      }

      // 소켓이 CONNECTING, CLOSING, CLOSED 상태이거나 아예 먹통인 진짜 정체 상황일 때만 실행
      onFrameSoftStalled(socket);
    }, FRAME_SOFT_TIMEOUT_MS);
  }

  function clearFrameWatchdog() {
    if (frameWatchdogTimer !== null) clearTimeout(frameWatchdogTimer);
    frameWatchdogTimer = null;
  }

  function requestStreamRecovery() {
    if (!controlSocket || controlSocket.readyState !== WebSocket.OPEN) return;
    try {
      controlSocket.send(JSON.stringify({ type: "requestKeyframe" }));
      if (codecMode === "mjpeg") {
        controlSocket.send(JSON.stringify({ type: "codec", mode: "mjpeg" }));
      }
    } catch (err) {
      console.warn("[Main] Stream recovery request failed:", err);
    }
  }

  function onFrameSoftStalled(socket) {
    if (socket !== videoSocket) return;
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    if (isLauncherMode) return;
    console.warn(
      "[Main] Video stream quiet for",
      FRAME_SOFT_TIMEOUT_MS,
      "ms. Requesting recovery frame.",
    );
    requestStreamRecovery();
    clearTimeout(frameWatchdogTimer);
    frameWatchdogTimer = setTimeout(
      () => onFrameHardStalled(socket),
      FRAME_HARD_TIMEOUT_MS - FRAME_SOFT_TIMEOUT_MS,
    );
  }

  function onFrameHardStalled(socket) {
    if (socket !== videoSocket) return;
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    if (isLauncherMode) return;
    console.warn(
      "[Main] Video stream stalled — no frame for",
      FRAME_HARD_TIMEOUT_MS,
      "ms. Triggering reconnect.",
    );
    setStatus("Disconnected", "error");
    showOverlay();
    try {
      socket.close();
    } catch (_) {}
  }

  function scheduleReconnect() {
    if (isReconnecting) return;
    isReconnecting = true;
    clearTimeout(reconnectTimer);
    console.log(
      `[Main] Reconnect scheduled in 3000ms. Status: videoSocket=${videoSocket ? videoSocket.readyState : "null"}, controlSocket=${controlSocket ? controlSocket.readyState : "null"}`,
    );
    reconnectTimer = setTimeout(() => {
      isReconnecting = false;

      const videoNeedsReconnect =
        !videoSocket ||
        videoSocket.readyState === WebSocket.CLOSED ||
        videoSocket.readyState === WebSocket.CLOSING;
      const controlNeedsReconnect =
        !controlSocket ||
        controlSocket.readyState === WebSocket.CLOSED ||
        controlSocket.readyState === WebSocket.CLOSING;

      if (videoNeedsReconnect) {
        console.log("[Main] Reconnecting video socket...");
        connectVideo();
      }
      // ### 수정 시작 ###
      // Reconnect secondary video socket when right app is active in dual mode
      if (
        !!state.right &&
        (!secondaryVideoSocket ||
          secondaryVideoSocket.readyState === WebSocket.CLOSED ||
          secondaryVideoSocket.readyState === WebSocket.CLOSING)
      ) {
        console.log("[Main] Reconnecting secondary video socket...");
        connectSecondaryVideo();
      }
      // ### 수정 끝 ###
      // Reconnect control socket only when the control socket itself is disconnected
      if (controlNeedsReconnect) {
        console.log("[Main] Reconnecting control socket...");
        connectControl();
      }
      if (
        audioPlayer &&
        (!audioPlayer.socket ||
          audioPlayer.socket.readyState === WebSocket.CLOSED ||
          audioPlayer.socket.readyState === WebSocket.CLOSING)
      ) {
        console.log("[Main] Reconnecting audio player socket...");
        const wsProtocol =
          window.location.protocol === "https:" ? "wss:" : "ws:";
        audioPlayer.startFromUserGesture(`${wsProtocol}//${host}/ws/audio`);
      }
    }, 3000);
  }

  let resizeTimer = null;
  let lastSentPrimary = {
    width: 0,
    height: 0,
    fitMode: null,
    layoutMode: null,
  };
  let lastSentSecondary = {
    width: 0,
    height: 0,
    fitMode: null,
    layoutMode: null,
  };

  function describeViewport(viewport) {
    return viewport && viewport.width > 0 && viewport.height > 0
      ? `${viewport.width}x${viewport.height}`
      : "none";
  }

  function sendViewportSize(immediate = false) {
    if (!controlSocket || controlSocket.readyState !== WebSocket.OPEN) return;
    if (codecMode === "mjpeg" && !streamPolicy.autoFit) return;

    const livePrimaryWidth = Math.round(
      !!!state.right
        ? playerShell?.clientWidth ||
            streamPane?.clientWidth ||
            canvas.clientWidth ||
            window.innerWidth
        : streamPane?.clientWidth || canvas.clientWidth || window.innerWidth,
    );
    const livePrimaryHeight = Math.round(
      !!!state.right
        ? playerShell?.clientHeight ||
            streamPane?.clientHeight ||
            canvas.clientHeight ||
            window.innerHeight
        : streamPane?.clientHeight || canvas.clientHeight || window.innerHeight,
    );
    if (livePrimaryWidth <= 0 || livePrimaryHeight <= 0) return;

    clearTimeout(resizeTimer);
    const doSend = () => {
      const primaryViewport =
        !!state.right && leftLockedViewport
          ? leftLockedViewport
          : { width: livePrimaryWidth, height: livePrimaryHeight };

      // ### 수정 시작 ###
      // Send secondary viewport if dual display is active, otherwise reset secondary cache
      if (!!state.right && browserSplitPane) {
        const secondaryViewport = rightLockedViewport;
        if (
          secondaryViewport &&
          secondaryViewport.width > 0 &&
          secondaryViewport.height > 0
        ) {
          const secFitMode = getEffectiveSecondaryFitMode();
          const secLayoutMode = streamPolicy.layoutMode;

          if (
            lastSentSecondary.width !== secondaryViewport.width ||
            lastSentSecondary.height !== secondaryViewport.height ||
            lastSentSecondary.fitMode !== secFitMode ||
            lastSentSecondary.layoutMode !== secLayoutMode
          ) {
            lastSentSecondary = {
              width: secondaryViewport.width,
              height: secondaryViewport.height,
              fitMode: secFitMode,
              layoutMode: secLayoutMode,
            };

            console.log(
              `[Main] Sending viewport pane=secondary requested=${secondaryViewport.width}x${secondaryViewport.height} fitMode=${secFitMode} locked=${describeViewport(secondaryViewport)} split=${!!state.right}`,
            );
            controlSocket.send(
              JSON.stringify({
                type: "viewport",
                pane: "secondary",
                width: secondaryViewport.width,
                height: secondaryViewport.height,
                fitMode: secFitMode,
                layoutMode: secLayoutMode,
              }),
            );
          }
        }
      } else {
        // Secondary is not active (single screen mode). Reset the cache to guarantee the next dual transition triggers viewport packet.
        lastSentSecondary = {
          width: 0,
          height: 0,
          fitMode: null,
          layoutMode: null,
        };
      }
      // ### 수정 끝 ###

      const primFitMode = getEffectivePrimaryFitMode();
      const primLayoutMode = streamPolicy.layoutMode;

      if (
        lastSentPrimary.width !== primaryViewport.width ||
        lastSentPrimary.height !== primaryViewport.height ||
        lastSentPrimary.fitMode !== primFitMode ||
        lastSentPrimary.layoutMode !== primLayoutMode
      ) {
        lastSentPrimary = {
          width: primaryViewport.width,
          height: primaryViewport.height,
          fitMode: primFitMode,
          layoutMode: primLayoutMode,
        };

        console.log(
          `[Main] Sending viewport pane=primary requested=${primaryViewport.width}x${primaryViewport.height} fitMode=${primFitMode} locked=${describeViewport(leftLockedViewport)} split=${!!state.right}`,
        );

        controlSocket.send(
          JSON.stringify({
            type: "viewport",
            pane: "primary",
            width: primaryViewport.width,
            height: primaryViewport.height,
            fitMode: primFitMode,
            layoutMode: primLayoutMode,
          }),
        );
      }
    };
    if (immediate) doSend();
    else resizeTimer = setTimeout(doSend, 500);
  }

  function waitForControlSocketOpen(timeoutMs) {
    return new Promise((resolve) => {
      const deadline = Date.now() + timeoutMs;
      const check = () => {
        if (isControlSocketOpen()) return resolve();
        if (Date.now() >= deadline) return resolve(); // best-effort — fall through on timeout
        setTimeout(check, 20);
      };
      check();
    });
  }

  function connectControl() {
    if (controlSocket) {
      try {
        controlSocket.onopen = null;
        controlSocket.onmessage = null;
        controlSocket.onerror = null;
        controlSocket.onclose = null;
        controlSocket.close();
      } catch (_) {}
    }
    const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${wsProtocol}//${host}/ws/control`;
    console.log(`[Main] Connecting control socket to: ${wsUrl}`);
    controlSocket = new WebSocket(wsUrl);

    controlSocket.onopen = () => {
      console.log(`[Main] Control socket connected!`);

      // Reset last sent viewports so the initial size is always sent
      lastSentPrimary = {
        width: 0,
        height: 0,
        fitMode: null,
        layoutMode: null,
      };
      lastSentSecondary = {
        width: 0,
        height: 0,
        fitMode: null,
        layoutMode: null,
      };

      // Reset active apps state to prevent launch evaluation mismatches on reconnect
      state.left = null;
      state.right = null;

      closeInputBubble(true);
      if (touchHandler) touchHandler.destroy();
      const renderer = decoder && decoder.renderer ? decoder.renderer : null;
      touchHandler = new TouchHandler(
        canvas,
        renderer,
        controlSocket,
        "primary",
      );
      // ### 수정 시작 ###
      // Initialize touch handler for secondary display since system is dual stream only
      if (!!state.right && secondaryCanvas) {
        if (secondaryTouchHandler) secondaryTouchHandler.destroy();
        secondaryTouchHandler = new TouchHandler(
          secondaryCanvas,
          getActiveSecondaryRenderer(),
          controlSocket,
          "secondary",
        );
      }
      // ### 수정 끝 ###

      // Send viewport IMMEDIATELY and BEFORE displayDensity so the
      // server knows the correct dimensions before density triggers a
      // force rebuild (which otherwise uses stale full-screen size).
      sendViewportSize(true);

      // ### 수정 시작 ###
      // Initialize secondary video and trigger launch since system is dual stream only
      if (!!state.right) {
        if (
          !secondaryVideoSocket ||
          secondaryVideoSocket.readyState === WebSocket.CLOSED
        ) {
          connectSecondaryVideo();
        }
        setTimeout(() => sendSecondaryLaunchRequest(), 150);
      }
      // ### 수정 끝 ###

      if (codecMode === "mjpeg") {
        console.log(
          `[Main] Sending codec preference: mjpeg via control socket on open`,
        );
        controlSocket.send(JSON.stringify({ type: "codec", mode: "mjpeg" }));
        console.log(
          `[Main] Requesting MJPEG keyframe immediately on open to force server wakeUp`,
        );
        controlSocket.send(JSON.stringify({ type: "requestKeyframe" }));
      } else if (codecMode === "h264") {
        if (videoSocket && videoSocket.readyState === WebSocket.OPEN) {
          console.log(
            `[Main] Requesting H264 keyframe via control socket on control open (video is already open)`,
          );
          controlSocket.send(
            JSON.stringify({ type: "requestKeyframe", pane: "primary" }),
          );
        }
        if (
          secondaryVideoSocket &&
          secondaryVideoSocket.readyState === WebSocket.OPEN
        ) {
          console.log(
            "[Main] Requesting H264 secondary keyframe via control socket on control open (secondary video is already open)",
          );
          controlSocket.send(
            JSON.stringify({ type: "requestKeyframe", pane: "secondary" }),
          );
        }
      }

      if (controlSocket.readyState === WebSocket.OPEN) {
        controlSocket.send(
          JSON.stringify({ type: "displayDensity", scale: currentDensity }),
        );
      }

      loadLauncherApps();

      // Periodic quality report for auto-scale decisions.
      // Sends per-interval deltas (not cumulative totals) so the service
      // can evaluate each 10s window independently.
      // Works with both WebCodecs (FramePacer + H264Decoder) and MJPEG (FallbackDecoder) paths.
      clearInterval(qualityReportInterval);
      // Seed prev counters from current cumulative values so the first
      // delta after reconnect reflects only the new interval, not the
      // entire lifetime of the decoder/pacer instance.
      let _prevDropped = 0,
        _prevBacklog = 0;
      let _prevTotalLatency = 0,
        _prevRendered = 0;
      const _seedMetrics = () => {
        const src =
          framePacer || (decoder && decoder.getMetrics ? decoder : null);
        if (src) {
          const m = src.getMetrics();
          _prevDropped = m.droppedFrames;
          _prevTotalLatency = m.totalLatency || 0;
          _prevRendered = m.renderedFrames || 0;
        }
        if (decoder && decoder.getBacklogMetrics) {
          _prevBacklog = decoder.getBacklogMetrics().backlogDrops;
        }
      };
      _seedMetrics();
      qualityReportInterval = setInterval(() => {
        if (!controlSocket || controlSocket.readyState !== WebSocket.OPEN)
          return;
        const report = { type: "qualityReport" };
        // Prefer FramePacer metrics (WebCodecs path), fall back to decoder metrics (MJPEG)
        const src =
          framePacer || (decoder && decoder.getMetrics ? decoder : null);
        if (src) {
          const m = src.getMetrics();
          report.droppedFrames = m.droppedFrames - _prevDropped;
          _prevDropped = m.droppedFrames;
          // Per-interval average delay (not lifetime cumulative)
          const intervalLatency = (m.totalLatency || 0) - _prevTotalLatency;
          const intervalRendered = (m.renderedFrames || 0) - _prevRendered;
          _prevTotalLatency = m.totalLatency || 0;
          _prevRendered = m.renderedFrames || 0;
          report.avgDelayMs =
            intervalRendered > 0
              ? parseFloat((intervalLatency / intervalRendered).toFixed(1))
              : 0;
        }
        if (decoder && decoder.getBacklogMetrics) {
          const d = decoder.getBacklogMetrics();
          report.backlogDrops = d.backlogDrops - _prevBacklog;
          _prevBacklog = d.backlogDrops;
        }
        try {
          controlSocket.send(JSON.stringify(report));
        } catch (_) {}
      }, 10_000);
    };

    controlSocket.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === "serverInit") {
          const newInstanceId = msg.instanceId;
          console.log(
            `[Main] Server init received: ${newInstanceId} (current: ${currentServerInstanceId})`,
          );
          if (
            newInstanceId &&
            currentServerInstanceId &&
            currentServerInstanceId !== newInstanceId
          ) {
            console.log(
              "[Main] Server instance changed! Resetting to launcher.",
            );
            goHome();
          }
          currentServerInstanceId = newInstanceId;
        } else if (msg.type === "APP_STREAM_STOPPED") {
          const elapsed = Date.now() - lastLaunchTime;
          if (elapsed < 5000) {
            console.log(
              `[Main] APP_STREAM_STOPPED received during launch transition (${elapsed}ms). Ignoring transient signal to ensure stable dual-app boot.`,
            );
          } else {
            console.log(
              "[Main] APP_STREAM_STOPPED received. Redirecting to home...",
            );
            goHome();
          }
        } else if (msg.type === "resolutionChanged") {
          const pane = msg.pane || "primary";
          const lockedViewport =
            pane === "secondary" ? rightLockedViewport : leftLockedViewport;
          const fitMode =
            pane === "secondary"
              ? getEffectiveSecondaryFitMode()
              : getEffectivePrimaryFitMode();
          console.log(
            `[Main] Server resolution changed pane=${pane} server=${msg.width}x${msg.height} fitMode=${fitMode} locked=${describeViewport(lockedViewport)} split=${!!state.right}`,
          );

          // Safely hot-refresh the specific active decoder and request a keyframe immediately to maintain zero-restart clean feed
          if (pane === "secondary") {
            if (!!state.right) {
              console.log(
                "[Main] Performing hot-refresh on secondary decoder to prevent rainbow artifacts",
              );
              initSecondaryDecoder(false).then(() => {
                // Clear old resolution cache
                // Zero-restart hot-refresh: Reuse existing active websocket connection and immediately request new keyframe
                setTimeout(() => {
                  if (
                    controlSocket &&
                    controlSocket.readyState === WebSocket.OPEN
                  ) {
                    controlSocket.send(
                      JSON.stringify({
                        type: "requestKeyframe",
                        pane: "secondary",
                      }),
                    );
                  }
                }, 80);
              });
            }
          } else {
            console.log(
              "[Main] Performing hot-refresh on primary decoder to prevent rainbow artifacts",
            );
            initDecoder(false).then(() => {
              // Clear old resolution cache
              // Zero-restart hot-refresh: Reuse existing active websocket connection and immediately request new keyframe
              setTimeout(() => {
                if (
                  controlSocket &&
                  controlSocket.readyState === WebSocket.OPEN
                ) {
                  controlSocket.send(
                    JSON.stringify({
                      type: "requestKeyframe",
                      pane: "primary",
                    }),
                  );
                }
              }, 80);
            });
          }
        } else if (msg.type === "showKeyboard") {
          console.log(
            "[IME] showKeyboard received pane=",
            msg.pane,
            "useBubble=",
            useBubbleInput,
            "bubbleEl=",
            !!inputBubble,
            "bubbleVisible=",
            bubbleVisible,
          );
          if (useBubbleInput) {
            const pane = msg.pane || "primary";
            const anchor =
              pane === "secondary"
                ? secondaryTouchHandler?.lastTap || null
                : touchHandler?.lastTap || null;
            console.log("[IME] anchor=", anchor);
            // Reposition if already visible (user switched pane).
            if (bubbleVisible) {
              positionInputBubble(anchor);
            } else {
              openInputBubble(anchor);
            }
          } else focusKeyboardProxy();
        } else if (msg.type === "hideKeyboard") {
          console.log("[IME] hideKeyboard received");
          if (useBubbleInput) closeInputBubble(true);
          else blurKeyboardProxy();
        } else if (msg.type === "thermalStatus") {
          handleThermalProfileSwitch(msg.level);
        } else if (msg.type === "ottProfileHint") {
          ottProfileActive = !!msg.active;
          refreshEffectiveProfile();
          console.log(`[Profile] OTT hint: active=${ottProfileActive}`);
        } else if (msg.type === "autoTierChange") {
          const tier = msg.tier;
          const reason = msg.reason;
          let text;
          if (reason === "thermal")
            text = `Auto reduced to ${tier} due to temperature`;
          else if (reason === "congestion")
            text = `Auto reduced to ${tier} due to network`;
          else if (reason === "quality")
            text = `Auto reduced to ${tier} due to playback`;
          else text = `Auto optimized to ${tier}`;
          showAutoTierToast(text);
        }
      } catch (e) {}
    };

    controlSocket.onclose = (e) => {
      console.log(
        `[Main] Control socket closed: code=${e.code}, reason=${e.reason}`,
      );
      clearInterval(qualityReportInterval);
      scheduleReconnect();
    };

    controlSocket.onerror = (err) => {
      console.error("[Main] Control socket error:", err);
    };
  }

  // --- Web Launcher & Split Launcher Code ---

  // 🌟 Favorites data helper functions
  function getFavorites() {
    try {
      return JSON.parse(localStorage.getItem("castla_favorites") || "[]");
    } catch (e) {
      return [];
    }
  }

  function toggleFavorite(packageName) {
    let favorites = getFavorites();
    if (favorites.includes(packageName)) {
      favorites = favorites.filter((pkg) => pkg !== packageName);
    } else {
      favorites.push(packageName);
    }
    localStorage.setItem("castla_favorites", JSON.stringify(favorites));
    // Refresh the launcher lists in real-time instantly without network lag
    refreshLauncherUI();
  }

  // ⚡ Auto-run data helper function
  function toggleAutoRun(packageName) {
    const primaryPkg = localStorage.getItem("castla_autorun_primary");
    const secondaryPkg = localStorage.getItem("castla_autorun_secondary");

    if (primaryPkg === packageName) {
      // Primary -> Secondary
      localStorage.removeItem("castla_autorun_primary");
      localStorage.setItem("castla_autorun_secondary", packageName);
    } else if (secondaryPkg === packageName) {
      // Secondary -> Off
      localStorage.removeItem("castla_autorun_secondary");
    } else {
      // Off -> Primary (Clear existing primary and set this)
      localStorage.setItem("castla_autorun_primary", packageName);
      if (secondaryPkg === packageName) {
        localStorage.removeItem("castla_autorun_secondary");
      }
    }
    // Refresh the launcher lists in real-time instantly without network lag
    refreshLauncherUI();
  }

  // ── App Pairs & Drag-and-Drop States & Helper Functions ──
  let allApps = []; // Globally tracked apps
  let appPairs = (() => {
    try {
      const saved = localStorage.getItem("castla_app_pairs");
      return saved ? JSON.parse(saved) : [];
    } catch (_) {
      return [];
    }
  })();

  let activeDragApp = null;
  let activeDragIsExisting = false;
  let longPressTimer = null;
  let dragGhost = null;
  let currentHoveredDropZone = null;
  let shouldDeferDragOverlay = false;
  let isFromSidebarDrag = false;

  // Cache elements for Drag-and-Drop (Spatial Launching)
  let dragOverlay,
    dropZoneTop,
    dropZoneAutorun,
    dropZoneLaunchLeft,
    dropZoneLaunchRight,
    dropZoneBottom;
  let dropZoneAutorunPreview;
  let appPairOverlay,
    pairAppLeft,
    pairAppRight,
    pairSwapBtn,
    pairCancelBtn,
    pairDissolveBtn,
    pairSaveBtn;
  let currentEditingPair = null;

  function initDragAndDropElements() {
    dragOverlay = document.getElementById("drag-overlay");
    dropZoneTop = document.getElementById("drop-zone-top");
    dropZoneAutorun = document.getElementById("drop-zone-autorun");
    dropZoneLaunchLeft = document.getElementById("drop-zone-launch-left");
    dropZoneLaunchRight = document.getElementById("drop-zone-launch-right");
    dropZoneBottom = document.getElementById("drop-zone-bottom");
    dropZoneAutorunPreview = document.getElementById(
      "drop-zone-autorun-preview",
    );

    appPairOverlay = document.getElementById("app-pair-dialog-overlay");
    pairAppLeft = document.getElementById("pair-app-left");
    pairAppRight = document.getElementById("pair-app-right");
    pairSwapBtn = document.getElementById("pair-swap-btn");
    pairCancelBtn = document.getElementById("pair-dialog-cancel");
    pairDissolveBtn = document.getElementById("pair-dialog-dissolve");
    pairSaveBtn = document.getElementById("pair-dialog-save");

    if (pairSwapBtn && !pairSwapBtn.hasAttribute("data-bound")) {
      pairSwapBtn.setAttribute("data-bound", "true");
      pairSwapBtn.addEventListener("click", () => {
        if (!currentEditingPair) return;
        const temp = currentEditingPair.left;
        currentEditingPair.left = currentEditingPair.right;
        currentEditingPair.right = temp;
        updatePairDialogUI();
      });

      pairCancelBtn.addEventListener("click", () => {
        appPairOverlay.classList.remove("active");
        currentEditingPair = null;
      });

      pairDissolveBtn.addEventListener("click", () => {
        if (!currentEditingPair) return;
        appPairs = appPairs.filter(
          (p) =>
            !(
              p.left === currentEditingPair.left &&
              p.right === currentEditingPair.right
            ) &&
            !(
              p.left === currentEditingPair.right &&
              p.right === currentEditingPair.left
            ),
        );
        localStorage.setItem("castla_app_pairs", JSON.stringify(appPairs));
        appPairOverlay.classList.remove("active");
        currentEditingPair = null;
        refreshLauncherUI();
      });

      pairSaveBtn.addEventListener("click", () => {
        if (!currentEditingPair) return;
        const index = appPairs.findIndex(
          (p) =>
            (p.left === currentEditingPair.left &&
              p.right === currentEditingPair.right) ||
            (p.left === currentEditingPair.right &&
              p.right === currentEditingPair.left),
        );
        if (index !== -1) {
          appPairs[index] = currentEditingPair;
        } else {
          appPairs.push(currentEditingPair);
        }
        localStorage.setItem("castla_app_pairs", JSON.stringify(appPairs));
        appPairOverlay.classList.remove("active");
        currentEditingPair = null;
        refreshLauncherUI();
      });
    }
  }

  function openAppPairEdit(pair) {
    initDragAndDropElements();
    currentEditingPair = { ...pair };
    updatePairDialogUI();
    appPairOverlay.classList.add("active");
  }

  function updatePairDialogUI() {
    if (!currentEditingPair) return;
    const leftApp = allApps.find(
      (a) => a.packageName === currentEditingPair.left,
    );
    const rightApp = allApps.find(
      (a) => a.packageName === currentEditingPair.right,
    );

    const leftImg = pairAppLeft.querySelector("img");
    const leftSpan = pairAppLeft.querySelector("span");
    leftImg.src = leftApp ? `/api/icon?pkg=${leftApp.packageName}` : "";
    leftSpan.textContent = leftApp ? leftApp.label : "Unknown";

    const rightImg = pairAppRight.querySelector("img");
    const rightSpan = pairAppRight.querySelector("span");
    rightImg.src = rightApp ? `/api/icon?pkg=${rightApp.packageName}` : "";
    rightSpan.textContent = rightApp ? rightApp.label : "Unknown";
  }

  function getPairPseudoApps(apps) {
    return appPairs.map((pair) => {
      const leftApp = apps.find((a) => a.packageName === pair.left);
      const rightApp = apps.find((a) => a.packageName === pair.right);
      return {
        packageName: `pair:${pair.left}:${pair.right}`,
        isPair: true,
        left: pair.left,
        right: pair.right,
        label: `${leftApp?.label || "Left"} + ${rightApp?.label || "Right"}`,
        category: "PAIR",
      };
    });
  }

  function startDragging(app, cell, event) {
    initDragAndDropElements();
    activeDragApp = app;
    cell.classList.add("dragging");

    // Lock body, launcher, and split drawer scrolling dynamically to prevent scroll gesture cancelling pointer capture
    const launcherEl = document.getElementById("web-launcher");
    if (launcherEl) {
      launcherEl.style.overflowY = "hidden";
      launcherEl.style.touchAction = "none";
    }
    const drawerListEl = document.getElementById("split-app-list");
    if (drawerListEl) {
      drawerListEl.style.overflowY = "hidden";
      drawerListEl.style.touchAction = "none";
    }
    document.body.style.overflow = "hidden";
    document.body.style.touchAction = "none";

    if (navigator.vibrate) {
      navigator.vibrate(50);
    }

    const sourceCat = cell.getAttribute("data-source-category");
    activeDragIsExisting =
      sourceCat === "FAVORITES" ||
      sourceCat === "AUTORUN" ||
      sourceCat === "PAIR";

    const isFromSidebar = !!cell.closest("#split-drawer");
    isFromSidebarDrag = isFromSidebar;
    shouldDeferDragOverlay = isFromSidebar;

    if (!shouldDeferDragOverlay) {
      dragOverlay.classList.add("active");

      // Toggle the bottom trash drop zone wrap dynamically
      const bottomWrap = document.getElementById("drag-row-bottom-wrap");
      if (bottomWrap) {
        bottomWrap.style.display = activeDragIsExisting ? "flex" : "none";
      }
    }

    // Update live viewport task guides inside spatial drop zones
    const leftGuide = dropZoneLaunchLeft
      ? dropZoneLaunchLeft.querySelector("span:last-of-type")
      : null;
    if (leftGuide) {
      if (state.left) {
        leftGuide.innerHTML = `<img src="/api/icon?pkg=${state.left.packageName}" style="width:16px;height:16px;object-fit:contain;vertical-align:middle;margin-right:4px;border-radius:4px;"/> <strong style="color:#00E5FF;">${state.left.label}</strong> 실행 중`;
      } else {
        leftGuide.innerHTML = `<span style="color:rgba(255,255,255,0.35);">빈 화면 (VD_1)</span>`;
      }
    }

    const rightGuide = dropZoneLaunchRight
      ? dropZoneLaunchRight.querySelector("span:last-of-type")
      : null;
    if (rightGuide) {
      if (!!state.right && state.right) {
        rightGuide.innerHTML = `<img src="/api/icon?pkg=${state.right.packageName}" style="width:16px;height:16px;object-fit:contain;vertical-align:middle;margin-right:4px;border-radius:4px;"/> <strong style="color:#E040FB;">${state.right.label}</strong> 실행 중`;
      } else {
        rightGuide.innerHTML = `<span style="color:rgba(255,255,255,0.35);">빈 화면 (VD_2)</span>`;
      }
    }

    updateDropZonePreviews();

    dragGhost = document.createElement("div");
    dragGhost.className = "drag-ghost";

    const ghostImg = document.createElement("img");
    if (app.isPair) {
      ghostImg.src = `/api/icon?pkg=${app.left}`;
    } else {
      ghostImg.src = `/api/icon?pkg=${app.packageName}`;
    }
    dragGhost.appendChild(ghostImg);
    document.body.appendChild(dragGhost);

    updateGhostPosition(event.clientX, event.clientY);
  }

  function updateGhostPosition(x, y) {
    if (!dragGhost) return;
    dragGhost.style.left = `${x}px`;
    dragGhost.style.top = `${y}px`;
  }

  function updateDropZonePreviews() {
    const primaryPkg = localStorage.getItem("castla_autorun_primary");
    const secondaryPkg = localStorage.getItem("castla_autorun_secondary");

    if (!dropZoneAutorunPreview) return;

    if (primaryPkg && secondaryPkg) {
      const leftApp = allApps.find((a) => a.packageName === primaryPkg);
      const rightApp = allApps.find((a) => a.packageName === secondaryPkg);
      dropZoneAutorunPreview.innerHTML = `
                <div style="display:flex; gap:10px; align-items:center; margin-bottom: 6px;">
                    <img src="/api/icon?pkg=${primaryPkg}" style="width:36px; height:36px; object-fit:contain;" />
                    <span style="font-size:18px; color:#00E5FF; font-weight:bold;">+</span>
                    <img src="/api/icon?pkg=${secondaryPkg}" style="width:36px; height:36px; object-fit:contain;" />
                </div>
                <span>${leftApp ? leftApp.label : "Left"} + ${rightApp ? rightApp.label : "Right"}</span>
            `;
      dropZoneAutorunPreview.style.display = "flex";
    } else if (primaryPkg) {
      const leftApp = allApps.find((a) => a.packageName === primaryPkg);
      dropZoneAutorunPreview.innerHTML = `
                <img src="/api/icon?pkg=${primaryPkg}" />
                <span>${leftApp ? leftApp.label : "Auto-run"}</span>
            `;
      dropZoneAutorunPreview.style.display = "flex";
    } else {
      dropZoneAutorunPreview.style.display = "none";
    }
  }

  function checkHoveredZone(x, y) {
    if (!dropZoneTop) return null;

    const topRect = dropZoneTop.getBoundingClientRect();
    if (
      x >= topRect.left &&
      x <= topRect.right &&
      y >= topRect.top &&
      y <= topRect.bottom
    ) {
      return "top";
    }

    if (dropZoneAutorun) {
      const autorunRect = dropZoneAutorun.getBoundingClientRect();
      if (
        x >= autorunRect.left &&
        x <= autorunRect.right &&
        y >= autorunRect.top &&
        y <= autorunRect.bottom
      ) {
        return "autorun";
      }
    }

    if (dropZoneLaunchLeft) {
      const leftRect = dropZoneLaunchLeft.getBoundingClientRect();
      if (
        x >= leftRect.left &&
        x <= leftRect.right &&
        y >= leftRect.top &&
        y <= leftRect.bottom
      ) {
        return "launch_left";
      }
    }

    if (dropZoneLaunchRight) {
      const rightRect = dropZoneLaunchRight.getBoundingClientRect();
      if (
        x >= rightRect.left &&
        x <= rightRect.right &&
        y >= rightRect.top &&
        y <= rightRect.bottom
      ) {
        return "launch_right";
      }
    }

    if (dropZoneBottom && activeDragIsExisting) {
      const bottomRect = dropZoneBottom.getBoundingClientRect();
      if (
        x >= bottomRect.left &&
        x <= bottomRect.right &&
        y >= bottomRect.top &&
        y <= bottomRect.bottom
      ) {
        return "bottom";
      }
    }

    return null;
  }

  function checkHoveredCell(x, y) {
    const cells = document.querySelectorAll(".app-cell, .split-app-item");
    for (const cell of cells) {
      if (cell.classList.contains("dragging")) continue;
      const rect = cell.getBoundingClientRect();
      if (
        x >= rect.left &&
        x <= rect.right &&
        y >= rect.top &&
        y <= rect.bottom
      ) {
        return cell;
      }
    }
    return null;
  }

  function handleDragMove(x, y) {
    if (!activeDragApp) return;
    updateGhostPosition(x, y);

    if (isFromSidebarDrag) {
      const drawerEl = document.getElementById("split-drawer");
      const drawerRect = drawerEl?.getBoundingClientRect();
      if (drawerRect) {
        if (x < drawerRect.left) {
          // 드로어 밖으로 이탈
          if (!dragOverlay.classList.contains("active")) {
            dragOverlay.classList.add("active");
            // ### 수정 시작 ###
            // Apply Ghosting UI by adding dragging-active class to the drawer
            drawerEl?.classList.add("dragging-active");
            // ### 수정 끝 ###
            const bottomWrap = document.getElementById("drag-row-bottom-wrap");
            if (bottomWrap) {
              bottomWrap.style.display = activeDragIsExisting ? "flex" : "none";
            }
            if (navigator.vibrate) {
              navigator.vibrate(30);
            }
            console.log(
              "[DragAndDrop] Left split drawer. Activated drag overlay.",
            );
          }
        } else {
          // 드로어 안으로 진입/복귀
          if (dragOverlay.classList.contains("active")) {
            dragOverlay.classList.remove("active");
            // ### 수정 시작 ###
            // Remove Ghosting UI when cursor goes back into the drawer
            drawerEl?.classList.remove("dragging-active");
            // ### 수정 끝 ###
            console.log(
              "[DragAndDrop] Entered split drawer. Deactivated drag overlay.",
            );
          }
        }
      }
    }

    // 드로어에서 시작한 드래그이고, 마우스가 현재 드로어 내부(x >= drawerRect.left)에 있는 경우
    const drawerEl = document.getElementById("split-drawer");
    const drawerRect = drawerEl?.getBoundingClientRect();
    if (isFromSidebarDrag && drawerRect && x >= drawerRect.left) {
      // 바깥 분할 드롭 영역 hover 효과 전부 리셋
      if (dropZoneTop) dropZoneTop.classList.remove("hovered");
      if (dropZoneAutorun) dropZoneAutorun.classList.remove("hovered");
      if (dropZoneLaunchLeft) dropZoneLaunchLeft.classList.remove("hovered");
      if (dropZoneLaunchRight) dropZoneLaunchRight.classList.remove("hovered");
      if (dropZoneBottom) dropZoneBottom.classList.remove("hovered");

      // 드로어 내부 앱 리스트들 중에서 호버된 아이템이 있는지 스캔하여 병합(App Pair) 비주얼 피드백 제공!
      const hoveredCell = checkHoveredCell(x, y);
      if (hoveredCell) {
        currentHoveredDropZone = null;
        hoveredCell.style.transform = "scale(1.15)";
        hoveredCell.style.boxShadow = "0 0 15px rgba(0, 229, 255, 0.4)";
        hoveredCell.style.border = "1px solid #00E5FF";
      } else {
        document
          .querySelectorAll(".app-cell, .split-app-item")
          .forEach((cell) => {
            if (cell.classList.contains("dragging")) return;
            cell.style.transform = "";
            cell.style.boxShadow = "";
            cell.style.border = "";
          });
      }
      return;
    }

    // Auto-scroll launcher grid during drag when hovering near top/bottom grid boundaries
    const launcherEl = document.getElementById("web-launcher");
    if (launcherEl) {
      const scrollSpeed = 12;
      if (y > window.innerHeight - 140) {
        launcherEl.scrollTop += scrollSpeed;
      } else if (y > 140 && y < 260) {
        launcherEl.scrollTop -= scrollSpeed;
      }
    }

    if (dropZoneTop) dropZoneTop.classList.remove("hovered");
    if (dropZoneAutorun) dropZoneAutorun.classList.remove("hovered");
    if (dropZoneLaunchLeft) dropZoneLaunchLeft.classList.remove("hovered");
    if (dropZoneLaunchRight) dropZoneLaunchRight.classList.remove("hovered");
    if (dropZoneBottom) dropZoneBottom.classList.remove("hovered");

    const hoveredCell = checkHoveredCell(x, y);
    if (hoveredCell) {
      currentHoveredDropZone = null;
      hoveredCell.style.transform = "scale(1.15)";
      hoveredCell.style.boxShadow = "0 0 15px rgba(0, 229, 255, 0.4)";
      hoveredCell.style.border = "1px solid #00E5FF";
    } else {
      document
        .querySelectorAll(".app-cell, .split-app-item")
        .forEach((cell) => {
          if (cell.classList.contains("dragging")) return;
          cell.style.transform = "";
          cell.style.boxShadow = "";
          cell.style.border = "";
        });

      const hoveredZone = checkHoveredZone(x, y);
      currentHoveredDropZone = hoveredZone;

      if (hoveredZone === "top" && dropZoneTop)
        dropZoneTop.classList.add("hovered");
      else if (hoveredZone === "autorun" && dropZoneAutorun)
        dropZoneAutorun.classList.add("hovered");
      else if (hoveredZone === "launch_left" && dropZoneLaunchLeft)
        dropZoneLaunchLeft.classList.add("hovered");
      else if (hoveredZone === "launch_right" && dropZoneLaunchRight)
        dropZoneLaunchRight.classList.add("hovered");
      else if (hoveredZone === "bottom" && dropZoneBottom)
        dropZoneBottom.classList.add("hovered");
    }
  }

  function handleDragEnd(x, y) {
    if (!activeDragApp) return;

    const cell = document.querySelector(
      ".app-cell.dragging, .split-app-item.dragging",
    );
    if (cell) cell.classList.remove("dragging");

    document.querySelectorAll(".app-cell, .split-app-item").forEach((cell) => {
      cell.style.transform = "";
      cell.style.boxShadow = "";
      cell.style.border = "";
    });

    isFromSidebarDrag = false;
    shouldDeferDragOverlay = false;
    dragOverlay.classList.remove("active");
    // ### 수정 시작 ###
    // Clean up Ghosting UI class on drag end to restore opacity
    if (splitDrawer) {
      splitDrawer.classList.remove("dragging-active");
    }
    // ### 수정 끝 ###
    if (dragGhost) {
      dragGhost.remove();
      dragGhost = null;
    }

    const hoveredZone = checkHoveredZone(x, y);
    const hoveredCell = checkHoveredCell(x, y);

    if (hoveredCell) {
      const targetPkg = hoveredCell.getAttribute("data-package");
      if (
        targetPkg &&
        targetPkg !== activeDragApp.packageName &&
        !activeDragApp.isPair
      ) {
        const targetApp = allApps.find((a) => a.packageName === targetPkg);
        if (targetApp && !targetApp.isPair) {
          createAppPair(activeDragApp.packageName, targetApp.packageName);
        }
      }
    } else if (hoveredZone) {
      triggerDropZoneAction(hoveredZone, activeDragApp, cell);
    }

    // Restore body, launcher, and split drawer scrolling dynamically
    const launcherEl = document.getElementById("web-launcher");
    if (launcherEl) {
      launcherEl.style.overflowY = "";
      launcherEl.style.touchAction = "";
    }
    const drawerListEl = document.getElementById("split-app-list");
    if (drawerListEl) {
      drawerListEl.style.overflowY = "";
      drawerListEl.style.touchAction = "";
    }
    document.body.style.overflow = "";
    document.body.style.touchAction = "";

    // Close the sidebar quick launcher automatically only if dropped outside the drawer
    if (splitDrawer) {
      const drawerRect = splitDrawer.getBoundingClientRect();
      if (x < drawerRect.left) {
        splitDrawer.classList.remove("open");
      } else {
        console.log(
          "[DragAndDrop] Dropped inside split drawer. Kept drawer open.",
        );
      }
    }

    activeDragApp = null;
    currentHoveredDropZone = null;
  }

  function cancelDrag() {
    if (!activeDragApp) return;

    const cell = document.querySelector(
      ".app-cell.dragging, .split-app-item.dragging",
    );
    if (cell) cell.classList.remove("dragging");

    document.querySelectorAll(".app-cell, .split-app-item").forEach((cell) => {
      cell.style.transform = "";
      cell.style.boxShadow = "";
      cell.style.border = "";
    });

    isFromSidebarDrag = false;
    shouldDeferDragOverlay = false;
    dragOverlay.classList.remove("active");
    // ### 수정 시작 ###
    // Clean up Ghosting UI class on drag cancel to restore opacity
    if (splitDrawer) {
      splitDrawer.classList.remove("dragging-active");
    }
    // ### 수정 끝 ###
    if (dragGhost) {
      dragGhost.remove();
      dragGhost = null;
    }

    // Restore scrolling dynamically
    const launcherEl = document.getElementById("web-launcher");
    if (launcherEl) {
      launcherEl.style.overflowY = "";
      launcherEl.style.touchAction = "";
    }
    const drawerListEl = document.getElementById("split-app-list");
    if (drawerListEl) {
      drawerListEl.style.overflowY = "";
      drawerListEl.style.touchAction = "";
    }
    document.body.style.overflow = "";
    document.body.style.touchAction = "";

    activeDragApp = null;
    currentHoveredDropZone = null;
    console.log(
      "[DragAndDrop] Drag and drop cancelled safely via Escape key or backout.",
    );
  }

  // Bind Escape key cancellation globally
  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && activeDragApp) {
      e.preventDefault();
      cancelDrag();
    }
  });

  function triggerDropZoneAction(zone, app, cell) {
    if (app.isPair) {
      if (zone === "autorun") {
        localStorage.setItem("castla_autorun_primary", app.left);
        localStorage.setItem("castla_autorun_secondary", app.right);
        showLauncherNotice(`${app.label} set to Auto-run (Split Screen).`);
        refreshLauncherUI();
        return;
      }
    }

    const pkg = app.packageName;
    const sourceCat = cell ? cell.getAttribute("data-source-category") : "";

    if (zone === "top") {
      let favorites = getFavorites();
      if (!favorites.includes(pkg)) {
        favorites.push(pkg);
        localStorage.setItem("castla_favorites", JSON.stringify(favorites));
        showLauncherNotice(`${app.label} added to Favorites.`);
      }
    } else if (zone === "autorun") {
      localStorage.setItem("castla_autorun_primary", pkg);
      localStorage.removeItem("castla_autorun_secondary");
      showLauncherNotice(`${app.label} set to Auto-run.`);
    } else if (zone === "launch_left") {
      console.log(`[Launcher] Drag-launching Primary (VD_1): ${app.label}`);
      // 1. 이미 동일한 왼쪽 화면에 켜져 있으면 에러 공지 없이 조용히 무음 리턴!
      if (state.left && state.left.packageName === pkg) {
        return;
      }
      // 2. 반대쪽인 오른쪽 화면에 이미 실행 중인 경우에만 경고 알림 후 리턴 차단!
      if (!!state.right && state.right && state.right.packageName === pkg) {
        showLauncherNotice(
          "이미 오른쪽 화면(Secondary)에서 실행 중인 앱입니다.",
        );
        return;
      }
      launchApp(app, false); // 반대쪽 실행 정보가 이미 채워져 있으므로 그대로 런칭!
    } else if (zone === "launch_right") {
      console.log(`[Launcher] Drag-launching Secondary (VD_2): ${app.label}`);
      // 3. 이미 동일한 오른쪽 화면에 켜져 있으면 에러 공지 없이 조용히 무음 리턴!
      if (!!state.right && state.right && state.right.packageName === pkg) {
        return;
      }
      // 4. 반대쪽인 왼쪽 화면에 이미 실행 중인 경우에만 경고 알림 후 리턴 차단!
      if (state.left && state.left.packageName === pkg) {
        showLauncherNotice("이미 왼쪽 화면(Primary)에서 실행 중인 앱입니다.");
        return;
      }
      // 5. 공통 기동 파이프라인 호출로 전후 상황 파악 및 UI 업데이트 자동 위임!
      launchApp(app, true); // 반대쪽 실행 정보가 이미 채워져 있으므로 그대로 런칭!
    } else if (zone === "bottom") {
      if (sourceCat === "FAVORITES") {
        let favorites = getFavorites().filter((p) => p !== pkg);
        localStorage.setItem("castla_favorites", JSON.stringify(favorites));
        showLauncherNotice(`${app.label} removed from Favorites.`);
      } else if (sourceCat === "PAIR") {
        appPairs = appPairs.filter(
          (p) => !(p.left === app.left && p.right === app.right),
        );
        localStorage.setItem("castla_app_pairs", JSON.stringify(appPairs));
        let favorites = getFavorites().filter((p) => p !== pkg);
        localStorage.setItem("castla_favorites", JSON.stringify(favorites));
        showLauncherNotice("App Pair dissolved.");
      } else if (sourceCat === "AUTORUN") {
        const leftPkg = localStorage.getItem("castla_autorun_primary");
        const rightPkg = localStorage.getItem("castla_autorun_secondary");
        if (leftPkg === pkg) localStorage.removeItem("castla_autorun_primary");
        if (rightPkg === pkg)
          localStorage.removeItem("castla_autorun_secondary");
        showLauncherNotice(`${app.label} removed from Auto-run.`);
      }
    }

    refreshLauncherUI();
  }

  function createAppPair(leftPkg, rightPkg) {
    const exists = appPairs.some(
      (p) =>
        (p.left === leftPkg && p.right === rightPkg) ||
        (p.left === rightPkg && p.right === leftPkg),
    );
    if (exists) {
      showLauncherNotice("This App Pair already exists.");
      return;
    }

    appPairs.push({
      left: leftPkg,
      right: rightPkg,
    });
    localStorage.setItem("castla_app_pairs", JSON.stringify(appPairs));
    showLauncherNotice("New App Pair created!");
    refreshLauncherUI();
  }

  async function loadLauncherApps() {
    try {
      const response = await fetch("/api/apps");
      if (!response.ok) throw new Error("Network error");
      const data = await response.json();

      const apps = data.apps || [];
      allApps = apps;

      applyStreamPolicy({
        fitMode: data.fitMode || "contain",
        autoFit: data.autoFit === true,
        layoutMode: data.layoutMode || "single",
      });

      const pairApps = getPairPseudoApps(apps);
      const combinedApps = [...apps, ...pairApps];

      renderLauncherApps(combinedApps);
      renderSplitLauncherApps(combinedApps);

      // ⚡ Auto-run logic (Execute auto-run once per initial server instance connection)
      const primaryPkg = localStorage.getItem("castla_autorun_primary");
      const secondaryPkg = localStorage.getItem("castla_autorun_secondary");

      if (!lastLaunchedInstanceId && currentServerInstanceId) {
        lastLaunchedInstanceId = currentServerInstanceId;
        if (primaryPkg) {
          const primaryApp = apps.find((a) => a.packageName === primaryPkg);
          if (primaryApp) {
            const secondaryApp = secondaryPkg
              ? apps.find((a) => a.packageName === secondaryPkg)
              : null;
            if (secondaryApp) {
              console.log(
                `[AutoRun] Automatically launching dual apps directly: ${primaryApp.packageName} + ${secondaryApp.packageName}`,
              );
              launchDualAppsDirectly(primaryApp, secondaryApp);
            } else {
              console.log(
                `[AutoRun] Automatically launching primary app at startup: ${primaryApp.packageName}`,
              );
              launchApp(primaryApp, false);
            }
          }
        }
      }
    } catch (err) {
      console.error("[Launcher]", err);
      showLauncherNotice("Failed to load apps. Try refreshing.");
    }
  }

  function refreshLauncherUI() {
    const pairApps = getPairPseudoApps(allApps);
    const combinedApps = [...allApps, ...pairApps];
    renderLauncherApps(combinedApps);
    renderSplitLauncherApps(combinedApps);
  }

  function renderSplitLauncherApps(apps) {
    if (!splitAppList) return;
    splitAppList.innerHTML = "";

    const singleApps = apps.filter((app) => !app.isPair);
    const pairPseudoApps = getPairPseudoApps(apps);
    const allDisplayApps = [...singleApps, ...pairPseudoApps];

    const primaryPkg = localStorage.getItem("castla_autorun_primary");
    const secondaryPkg = localStorage.getItem("castla_autorun_secondary");

    const favoritesList = getFavorites();
    const grouped = {};

    // Prepend Auto-run category group at the very top if any exist
    if (primaryPkg || secondaryPkg) {
      grouped["AUTORUN"] = { title: "Auto-run", color: "#00E5FF", items: [] };
    }

    // Prepend Favorites category group right below Auto-run if any exist
    if (favoritesList.length > 0) {
      grouped["FAVORITES"] = {
        title: "Favorites",
        color: "#FFD700",
        items: [],
      };
    }

    // Prepend App Pairs category group if any exist
    if (pairPseudoApps.length > 0) {
      grouped["PAIR"] = { title: "App Pairs", color: "#00E5FF", items: [] };
    }

    grouped["NAVIGATION"] = {
      title: "Navigation",
      color: "#4CAF50",
      items: [],
    };
    grouped["VIDEO"] = { title: "Video", color: "#FF5722", items: [] };
    grouped["MUSIC"] = { title: "Music", color: "#9C27B0", items: [] };
    grouped["OTHER"] = { title: "Apps", color: "#9E9E9E", items: [] };

    allDisplayApps.forEach((app) => {
      if (app.packageName === primaryPkg || app.packageName === secondaryPkg) {
        grouped["AUTORUN"]?.items.push(app);
      }
      if (favoritesList.includes(app.packageName)) {
        grouped["FAVORITES"]?.items.push(app);
      }
      if (app.isPair) {
        grouped["PAIR"]?.items.push(app);
      } else {
        if (grouped[app.category]) grouped[app.category].items.push(app);
        else grouped["OTHER"].items.push(app);
      }
    });

    Object.keys(grouped).forEach((key) => {
      const group = grouped[key];
      if (group.items.length === 0) return;

      const section = document.createElement("div");
      section.className = "split-category-section";

      const header = document.createElement("div");
      header.className = "split-category-header";
      const bar = document.createElement("div");
      bar.className = "split-category-bar";
      bar.style.backgroundColor = group.color;
      const title = document.createElement("div");
      title.className = "split-category-title";
      title.textContent = group.title;
      header.appendChild(bar);
      header.appendChild(title);
      section.appendChild(header);

      const items = document.createElement("div");
      items.className = "split-category-items";

      group.items.forEach((app) => {
        const cell = document.createElement("div");
        cell.className = "split-app-item";
        cell.setAttribute("data-package", app.packageName);
        cell.setAttribute("data-source-category", key);

        if (app.isPair) {
          const iconWrapper = document.createElement("div");
          iconWrapper.className = "app-pair-icon-wrapper";

          const leftIcon = document.createElement("img");
          leftIcon.className = "app-pair-icon-left";
          leftIcon.src = `/api/icon?pkg=${app.left}`;
          leftIcon.loading = "lazy";
          iconWrapper.appendChild(leftIcon);

          const rightIcon = document.createElement("img");
          rightIcon.className = "app-pair-icon-right";
          rightIcon.src = `/api/icon?pkg=${app.right}`;
          rightIcon.loading = "lazy";
          iconWrapper.appendChild(rightIcon);

          cell.appendChild(iconWrapper);
        } else {
          const icon = document.createElement("img");
          icon.className = "split-app-icon";
          icon.src = `/api/icon?pkg=${app.packageName}`;
          icon.loading = "lazy";
          cell.appendChild(icon);
        }

        const label = document.createElement("div");
        label.className = "split-app-label";
        label.textContent = app.label;
        if (app.isPair) {
          label.style.color = "#00E5FF";
        }
        cell.appendChild(label);

        if (app.isPair) {
          // Curved swap / pair edit gear button
          const editBtn = document.createElement("div");
          editBtn.className = "split-app-star active";
          editBtn.style.color = "#00E5FF";
          editBtn.style.fontSize = "14px";
          editBtn.innerHTML = "⚙️";

          editBtn.addEventListener("click", (e) => {
            e.stopPropagation(); // Prevent parent launching event
            openAppPairEdit(app);
          });
          editBtn.addEventListener("pointerdown", (e) => e.stopPropagation());
          editBtn.addEventListener("pointerup", (e) => e.stopPropagation());
          cell.appendChild(editBtn);
        } else {
          // Favorite star button
          const star = document.createElement("div");
          const isFav = favoritesList.includes(app.packageName);
          star.className = `split-app-star ${isFav ? "active" : ""}`;
          star.innerHTML = "&#9733;";

          star.addEventListener("click", (e) => {
            e.stopPropagation(); // Prevent parent launching event
            toggleFavorite(app.packageName);
          });
          star.addEventListener("pointerdown", (e) => e.stopPropagation());
          star.addEventListener("pointerup", (e) => e.stopPropagation());
          cell.appendChild(star);

          // Auto-run bolt button
          const bolt = document.createElement("div");
          const pPkg = localStorage.getItem("castla_autorun_primary");
          const sPkg = localStorage.getItem("castla_autorun_secondary");

          let boltClass = "split-app-bolt";
          if (pPkg === app.packageName) {
            boltClass += " active primary";
          } else if (sPkg === app.packageName) {
            boltClass += " active secondary";
          }
          bolt.className = boltClass;
          bolt.innerHTML = "&#9889;";

          bolt.addEventListener("click", (e) => {
            e.stopPropagation(); // Prevent parent launching event
            toggleAutoRun(app.packageName);
          });
          bolt.addEventListener("pointerdown", (e) => e.stopPropagation());
          bolt.addEventListener("pointerup", (e) => e.stopPropagation());
          cell.appendChild(bolt);
        }

        // Long-press Drag and Drop pointer events for split launcher sidebar app items
        let startX = 0,
          startY = 0;
        let isPointerDown = false;
        let wasDragging = false;

        cell.addEventListener("pointerdown", (e) => {
          if (e.button !== 0) return;
          // Ignore clicks on toggle icons or edit gears
          if (
            e.target.classList.contains("split-app-star") ||
            e.target.classList.contains("split-app-bolt") ||
            e.target.innerHTML === "⚙️"
          ) {
            return;
          }
          isPointerDown = true;
          wasDragging = false;
          startX = e.clientX;
          startY = e.clientY;

          try {
            cell.setPointerCapture(e.pointerId);
          } catch (_) {}

          longPressTimer = setTimeout(() => {
            if (isPointerDown) {
              startDragging(app, cell, e);
            }
          }, 1000);
        });

        cell.addEventListener("pointermove", (e) => {
          if (!isPointerDown) return;

          const dist = Math.hypot(e.clientX - startX, e.clientY - startY);
          if (dist > 18 && !activeDragApp) {
            clearTimeout(longPressTimer);
          }

          if (activeDragApp) {
            handleDragMove(e.clientX, e.clientY);
          }
        });

        const endPointerHandler = (e) => {
          if (!isPointerDown) return;
          isPointerDown = false;
          clearTimeout(longPressTimer);
          try {
            cell.releasePointerCapture(e.pointerId);
          } catch (_) {}

          if (activeDragApp) {
            wasDragging = true;
            handleDragEnd(e.clientX, e.clientY);
            setTimeout(() => {
              wasDragging = false;
            }, 100);
          }
        };

        cell.addEventListener("pointerup", endPointerHandler);
        cell.addEventListener("pointercancel", endPointerHandler);

        cell.addEventListener("click", (e) => {
          // Double safeguard: Block event propagation if hitting toggle icons or edit gears
          if (
            e.target.classList.contains("split-app-star") ||
            e.target.classList.contains("split-app-bolt") ||
            e.target.innerHTML === "⚙️"
          ) {
            return;
          }
          if (wasDragging) {
            wasDragging = false;
            return;
          }
          if (app.isPair) {
            launchAppPair(app.left, app.right);
          } else {
            // 🔴 낱개 앱 일반 클릭 시에는 단독 실행이므로 반대쪽 실행 정보를 SSOT에 맞춰 비워줍니다!
            state.right = null;
            launchApp(app, false);
          }
          splitDrawer.classList.remove("open");
        });

        items.appendChild(cell);
      });

      section.appendChild(items);
      splitAppList.appendChild(section);
    });
  }

  function renderLauncherApps(apps) {
    launcherContent.innerHTML = "";
    const primaryPkg = localStorage.getItem("castla_autorun_primary");
    const secondaryPkg = localStorage.getItem("castla_autorun_secondary");

    const favoritesList = getFavorites();
    const grouped = {};

    // Prepend Auto-run category group at the very top if any exist
    if (primaryPkg || secondaryPkg) {
      grouped["AUTORUN"] = { title: "Auto-run", color: "#00E5FF", items: [] };
    }

    // Prepend Favorites category group right below Auto-run if any exist
    if (favoritesList.length > 0) {
      grouped["FAVORITES"] = {
        title: "Favorites",
        color: "#FFD700",
        items: [],
      };
    }

    // Add App Pairs category group if any exist
    if (appPairs.length > 0) {
      grouped["PAIR"] = { title: "App Pairs", color: "#E040FB", items: [] };
    }

    grouped["NAVIGATION"] = {
      title: "Navigation",
      color: "#4CAF50",
      items: [],
    };
    grouped["VIDEO"] = { title: "Video", color: "#FF5722", items: [] };
    grouped["MUSIC"] = { title: "Music", color: "#9C27B0", items: [] };
    grouped["OTHER"] = { title: "Apps", color: "#9E9E9E", items: [] };

    // Check if there is a matching App Pair for the auto-run configuration
    const matchingAutorunPair = apps.find(
      (app) =>
        app.isPair &&
        ((app.left === primaryPkg && app.right === secondaryPkg) ||
          (app.left === secondaryPkg && app.right === primaryPkg)),
    );

    apps.forEach((app) => {
      if (favoritesList.includes(app.packageName)) {
        grouped["FAVORITES"]?.items.push(app);
      }
      if (app.isPair) {
        if (app === matchingAutorunPair) {
          grouped["AUTORUN"]?.items.push(app);
        }
        grouped["PAIR"]?.items.push(app);
        return;
      }
      if (
        !matchingAutorunPair &&
        (app.packageName === primaryPkg || app.packageName === secondaryPkg)
      ) {
        grouped["AUTORUN"]?.items.push(app);
      }
      if (grouped[app.category]) grouped[app.category].items.push(app);
      else grouped["OTHER"].items.push(app);
    });

    Object.keys(grouped).forEach((key) => {
      const group = grouped[key];
      if (group.items.length === 0) return;

      const section = document.createElement("div");
      section.className = "category-section";

      const header = document.createElement("div");
      header.className = "category-header";
      const bar = document.createElement("div");
      bar.className = "category-bar";
      bar.style.backgroundColor = group.color;
      const title = document.createElement("div");
      title.className = "category-title";
      title.textContent = group.title;

      header.appendChild(bar);
      header.appendChild(title);
      section.appendChild(header);

      const grid = document.createElement("div");
      grid.className = "app-grid";

      group.items.forEach((app) => {
        const cell = document.createElement("div");
        cell.className = "app-cell";
        cell.setAttribute("data-package", app.packageName);
        cell.setAttribute("data-source-category", key);

        const iconWrapper = document.createElement("div");

        if (app.isPair) {
          iconWrapper.className = "app-pair-icon-wrapper";

          const leftIcon = document.createElement("img");
          leftIcon.className = "app-pair-icon-left";
          leftIcon.src = `/api/icon?pkg=${app.left}`;
          leftIcon.loading = "lazy";
          iconWrapper.appendChild(leftIcon);

          const rightIcon = document.createElement("img");
          rightIcon.className = "app-pair-icon-right";
          rightIcon.src = `/api/icon?pkg=${app.right}`;
          rightIcon.loading = "lazy";
          iconWrapper.appendChild(rightIcon);

          if (key === "FAVORITES") {
            const delBadge = document.createElement("div");
            delBadge.className = "app-star active";
            delBadge.style.color = "#F44336";
            delBadge.style.right = "-8px";
            delBadge.style.top = "-6px";
            delBadge.style.zIndex = "15";
            delBadge.style.fontSize = "14px";
            delBadge.style.textShadow = "0 1px 3px rgba(0,0,0,0.9)";
            delBadge.innerHTML = "🗑️";
            delBadge.addEventListener("click", (e) => {
              e.stopPropagation();
              let favorites = getFavorites().filter(
                (p) => p !== app.packageName,
              );
              localStorage.setItem(
                "castla_favorites",
                JSON.stringify(favorites),
              );
              showLauncherNotice(`${app.label} removed from Favorites.`);
              refreshLauncherUI();
            });
            iconWrapper.appendChild(delBadge);
          } else if (key === "AUTORUN") {
            const delBadge = document.createElement("div");
            delBadge.className = "app-star active";
            delBadge.style.color = "#F44336";
            delBadge.style.right = "-8px";
            delBadge.style.top = "-6px";
            delBadge.style.zIndex = "15";
            delBadge.style.fontSize = "14px";
            delBadge.style.textShadow = "0 1px 3px rgba(0,0,0,0.9)";
            delBadge.innerHTML = "🗑️";
            delBadge.addEventListener("click", (e) => {
              e.stopPropagation();
              localStorage.removeItem("castla_autorun_primary");
              localStorage.removeItem("castla_autorun_secondary");
              showLauncherNotice("Auto-run apps removed.");
              refreshLauncherUI();
            });
            iconWrapper.appendChild(delBadge);
          } else {
            // Add an elegant edit gear button
            const editBtn = document.createElement("div");
            editBtn.className = "app-star active";
            editBtn.style.color = "#00E5FF";
            editBtn.style.right = "-8px";
            editBtn.style.top = "-6px";
            editBtn.style.zIndex = "15";
            editBtn.style.textShadow =
              "0 1px 3px rgba(0,0,0,0.9), 0 0 2px rgba(0,0,0,0.9)";
            editBtn.innerHTML = "⚙️";
            editBtn.addEventListener("click", (e) => {
              e.stopPropagation();
              openAppPairEdit(app);
            });
            iconWrapper.appendChild(editBtn);
          }
        } else {
          iconWrapper.className = "app-icon-wrapper";

          const icon = document.createElement("img");
          icon.className = "app-icon";
          icon.src = `/api/icon?pkg=${app.packageName}`;
          icon.loading = "lazy";
          iconWrapper.appendChild(icon);

          if (key === "FAVORITES") {
            // Show direct delete badge instead of toggle buttons
            const delBadge = document.createElement("div");
            delBadge.className = "app-star active";
            delBadge.style.color = "#F44336";
            delBadge.style.right = "-8px";
            delBadge.style.top = "-6px";
            delBadge.style.zIndex = "15";
            delBadge.style.fontSize = "14px";
            delBadge.style.textShadow = "0 1px 3px rgba(0,0,0,0.9)";
            delBadge.innerHTML = "🗑️";
            delBadge.addEventListener("click", (e) => {
              e.stopPropagation();
              let favorites = getFavorites().filter(
                (p) => p !== app.packageName,
              );
              localStorage.setItem(
                "castla_favorites",
                JSON.stringify(favorites),
              );
              showLauncherNotice(`${app.label} removed from Favorites.`);
              refreshLauncherUI();
            });
            iconWrapper.appendChild(delBadge);
          } else if (key === "AUTORUN") {
            // Show direct delete badge instead of toggle buttons
            const delBadge = document.createElement("div");
            delBadge.className = "app-star active";
            delBadge.style.color = "#F44336";
            delBadge.style.right = "-8px";
            delBadge.style.top = "-6px";
            delBadge.style.zIndex = "15";
            delBadge.style.fontSize = "14px";
            delBadge.style.textShadow = "0 1px 3px rgba(0,0,0,0.9)";
            delBadge.innerHTML = "🗑️";
            delBadge.addEventListener("click", (e) => {
              e.stopPropagation();
              const leftPkg = localStorage.getItem("castla_autorun_primary");
              const rightPkg = localStorage.getItem("castla_autorun_secondary");
              if (leftPkg === app.packageName)
                localStorage.removeItem("castla_autorun_primary");
              if (rightPkg === app.packageName)
                localStorage.removeItem("castla_autorun_secondary");
              showLauncherNotice(`${app.label} removed from Auto-run.`);
              refreshLauncherUI();
            });
            iconWrapper.appendChild(delBadge);
          } else {
            // Favorite star button
            const star = document.createElement("div");
            const isFav = favoritesList.includes(app.packageName);
            star.className = `app-star ${isFav ? "active" : ""}`;
            star.innerHTML = "&#9733;";

            star.addEventListener("click", (e) => {
              e.stopPropagation(); // Prevent parent launching event
              toggleFavorite(app.packageName);
            });
            iconWrapper.appendChild(star);

            // Auto-run bolt button
            const bolt = document.createElement("div");
            const pPkg = localStorage.getItem("castla_autorun_primary");
            const sPkg = localStorage.getItem("castla_autorun_secondary");

            let boltClass = "app-bolt";
            if (pPkg === app.packageName) {
              boltClass += " active primary";
            } else if (sPkg === app.packageName) {
              boltClass += " active secondary";
            }
            bolt.className = boltClass;
            bolt.innerHTML = "&#9889;";

            bolt.addEventListener("click", (e) => {
              e.stopPropagation(); // Prevent parent launching event
              toggleAutoRun(app.packageName);
            });
            iconWrapper.appendChild(bolt);
          }
        }

        const label = document.createElement("div");
        label.className = "app-label";
        label.textContent = app.label;

        cell.appendChild(iconWrapper);
        cell.appendChild(label);

        // Long-press Drag and Drop pointer events
        let startX = 0,
          startY = 0;
        let isPointerDown = false;
        let wasDragging = false;

        cell.addEventListener("pointerdown", (e) => {
          if (e.button !== 0) return;
          isPointerDown = true;
          wasDragging = false;
          startX = e.clientX;
          startY = e.clientY;

          cell.setPointerCapture(e.pointerId);

          longPressTimer = setTimeout(() => {
            if (isPointerDown) {
              startDragging(app, cell, e);
            }
          }, 1000);
        });

        cell.addEventListener("pointermove", (e) => {
          if (!isPointerDown) return;

          const dist = Math.hypot(e.clientX - startX, e.clientY - startY);
          if (dist > 18 && !activeDragApp) {
            clearTimeout(longPressTimer);
          }

          if (activeDragApp) {
            handleDragMove(e.clientX, e.clientY);
          }
        });

        const endPointerHandler = (e) => {
          if (!isPointerDown) return;
          isPointerDown = false;
          clearTimeout(longPressTimer);
          try {
            cell.releasePointerCapture(e.pointerId);
          } catch (_) {}

          if (activeDragApp) {
            wasDragging = true;
            handleDragEnd(e.clientX, e.clientY);
            // Reset wasDragging after 100ms so that if the pointer was released outside the cell
            // (which triggers no click event on the cell), the cell click is not permanently blocked.
            setTimeout(() => {
              wasDragging = false;
            }, 100);
          }
        };

        cell.addEventListener("pointerup", endPointerHandler);
        cell.addEventListener("pointercancel", endPointerHandler);

        // Launch or Edit Double Click
        cell.addEventListener("dblclick", () => {
          if (app.isPair) {
            openAppPairEdit(app);
          }
        });

        cell.addEventListener("click", () => {
          if (wasDragging) {
            wasDragging = false;
            return;
          }
          launchApp(app, false);
        });

        grid.appendChild(cell);
      });

      section.appendChild(grid);
      launcherContent.appendChild(section);
    });

    hideLauncherNotice();
    launcherContent.style.display = "block";
    if (isLauncherMode) {
      webLauncher.classList.remove("hidden");
    }
    window.dispatchEvent(new Event("launcher-ready"));
  }

  async function launchDualAppsDirectly(primaryApp, secondaryApp) {
    console.log(
      `[Launcher] Launching dual apps directly: ${primaryApp.packageName} + ${secondaryApp.packageName}`,
    );
    lastLaunchTime = Date.now();

    // 1. Prepare browser layout state for split screen
    destroySecondaryTransport();

    state.right = secondaryApp;

    lockBrowserSplitViewports(secondaryApp);
    browserSplitState.preset = resolveSplitPreset(primaryApp, secondaryApp);
    streamPolicy.layoutMode = "browser_split";
    document.body.dataset.layoutMode = streamPolicy.layoutMode;

    const initialRatio = splitRatio || DEFAULT_SPLIT_RATIO;
    setBrowserSplitRatio(initialRatio);

    // Highlight the closest ratio button
    document.querySelectorAll(".split-ratio-btn").forEach((b) => {
      const btnRatio = parseFloat(b.dataset.ratio);
      b.classList.toggle("active", Math.abs(btnRatio - initialRatio) < 0.05);
    });

    playerShell?.classList.add("browser-split");
    updateSplitToolbarVisibility();
    applyActiveFitModes();

    // 2. Clear launcher UI state
    state.left = primaryApp;
    isLauncherMode = false;
    webLauncher.classList.add("hidden");
    splitDrawer.style.display = "flex";
    homeBtn.style.display = "block";
    clearLaunchTimeout();
    clearFrameWatchdog();
    clearCanvas();

    // 3. Force lock the split viewports so we send correct split resolutions immediately
    lockBrowserSplitViewports(secondaryApp);

    // 4. Initialize secondary decoder and video connection
    await initSecondaryDecoder();
    if (secondaryTouchHandler) {
      secondaryTouchHandler.destroy();
    }
    secondaryTouchHandler = new TouchHandler(
      secondaryCanvas,
      getActiveSecondaryRenderer(),
      controlSocket,
      "secondary",
    );
    applyActiveFitModes();
    connectSecondaryVideo();

    // 5. Send viewport resolutions to the server immediately (split viewports!)
    sendViewportSize(true);

    // 6. Send the launch command for the primary app
    setTimeout(() => {
      sendControlMessage({
        type: "launchApp",
        pkg: primaryApp.packageName,
        pane: "primary",
      });
    }, 200);

    // 7. Send the launch command for the secondary app (fast sequential launch)
    setTimeout(() => {
      sendControlMessage({
        type: "launchApp",
        pkg: secondaryApp.packageName,
        pane: "secondary",
      });
    }, 500);
  }

  function launchAppPair(leftPkg, rightPkg) {
    console.log(
      `[Launcher] Smart Launching App Pair: left=${leftPkg}, right=${rightPkg}`,
    );
    if (Date.now() < launchGuardUntil) return;
    lastLaunchTime = Date.now(); // Record launch timestamp

    const targetLeftApp = allApps.find((a) => a.packageName === leftPkg);
    const targetRightApp = allApps.find((a) => a.packageName === rightPkg);

    if (!targetLeftApp || !targetRightApp) {
      console.warn(
        `[Launcher] Failed to launch App Pair: one or both apps are missing.`,
      );
      return;
    }

    // Get current running package names (if any)
    const currentLeftPkg = state.left ? state.left.packageName : null;
    const currentRightPkg = state.right ? state.right.packageName : null;

    // Check if either of the target apps is already running in either slot
    const leftRunningMatch =
      currentLeftPkg === leftPkg
        ? "left"
        : currentRightPkg === leftPkg
          ? "right"
          : null;
    const rightRunningMatch =
      currentLeftPkg === rightPkg
        ? "left"
        : currentRightPkg === rightPkg
          ? "right"
          : null;

    console.log(
      `[Launcher] AppPair Smart Evaluation: X(${leftPkg}) runningSlot=${leftRunningMatch}, Y(${rightPkg}) runningSlot=${rightRunningMatch}`,
    );

    if (leftRunningMatch && rightRunningMatch) {
      // Case 1: Both apps are already running on the screen (regardless of swap)
      console.log(
        `[Launcher] Both apps ${leftPkg} and ${rightPkg} are already running. Skipping.`,
      );
      return;
    }

    if (leftRunningMatch && !rightRunningMatch) {
      // Case 2: Only the requested left app (X) is running.
      // Keep X where it is running, and launch Y (rightPkg) in the other slot!
      if (leftRunningMatch === "left") {
        console.log(
          `[Launcher] ${leftPkg} is running on Left. Launching ${rightPkg} on Right.`,
        );
        _leftApp = targetLeftApp;
        _rightApp = targetRightApp;
        updateLayoutUI();
        launchApp(targetRightApp, true, true);
      } else {
        console.log(
          `[Launcher] ${leftPkg} is running on Right. Keeping it, launching ${rightPkg} on Left.`,
        );
        _leftApp = targetRightApp; // the missing app goes to the Left
        _rightApp = targetLeftApp; // the existing app stays on the Right
        updateLayoutUI();
        launchApp(targetRightApp, false, true);
      }
      return;
    }

    if (!leftRunningMatch && rightRunningMatch) {
      // Case 3: Only the requested right app (Y) is running.
      // Keep Y where it is running, and launch X (leftPkg) in the other slot!
      if (rightRunningMatch === "right") {
        console.log(
          `[Launcher] ${rightPkg} is running on Right. Launching ${leftPkg} on Left.`,
        );
        _leftApp = targetLeftApp;
        _rightApp = targetRightApp;
        updateLayoutUI();
        launchApp(targetLeftApp, false, true);
      } else {
        console.log(
          `[Launcher] ${rightPkg} is running on Left. Keeping it, launching ${leftPkg} on Right.`,
        );
        _leftApp = targetRightApp; // the existing app stays on the Left
        _rightApp = targetLeftApp; // the missing app goes to the Right
        updateLayoutUI();
        launchApp(targetLeftApp, true, true);
      }
      return;
    }

    // Case 4: Neither app is running anywhere. Launch both!
    console.log(
      `[Launcher] Neither app in pair is running. Launching both: X(${leftPkg}) and Y(${rightPkg})`,
    );
    _leftApp = targetLeftApp;
    _rightApp = targetRightApp;
    updateLayoutUI();

    launchApp(targetLeftApp, false, true);
    setTimeout(() => {
      launchApp(targetRightApp, true, true);
    }, 300);
  }

  function launchApp(app, isRight = false, forceLaunch = false) {
    if (app.isPair) {
      launchAppPair(app.left, app.right);
      return;
    }

    // Strict Duplication Safeguard:
    // Prevent running the exact same app on both VD_1 (left) and VD_2 (right) simultaneously.
    const pkgName = app.packageName;
    if (!forceLaunch) {
      if (isRight) {
        // 1. 이미 동일한 오른쪽(right) 화면에 실행 중이라면, 에러 공지 없이 조용히 리턴!
        if (state.right && state.right.packageName === pkgName) {
          console.log(
            `[Launcher] ${pkgName} is already running on Right. Skipping redundant launch without Notice.`,
          );
          return;
        }
        // 2. 반대쪽인 왼쪽(left) 화면에 이미 실행 중인 경우에만 고급 알림을 주고 차단!
        if (state.left && state.left.packageName === pkgName) {
          showLauncherNotice("이미 왼쪽 화면(Primary)에서 실행 중인 앱입니다.");
          return;
        }
      } else {
        // 3. 이미 동일한 왼쪽(left) 화면에 실행 중이라면, 에러 공지 없이 조용히 리턴!
        if (state.left && state.left.packageName === pkgName) {
          console.log(
            `[Launcher] ${pkgName} is already running on Left. Skipping redundant launch without Notice.`,
          );
          return;
        }
        // 4. 반대쪽인 오른쪽(right) 화면에 이미 실행 중인 경우에만 고급 알림을 주고 차단!
        if (state.right && state.right.packageName === pkgName) {
          showLauncherNotice(
            "이미 오른쪽 화면(Secondary)에서 실행 중인 앱입니다.",
          );
          return;
        }
      }
    }

    // Block accidental launches right after splash dismiss
    if (Date.now() < launchGuardUntil) {
      console.log(
        `[Launcher] Blocked accidental launch: ${app.packageName} (guard active)`,
      );
      return;
    }
    lastLaunchTime = Date.now(); // Record launch timestamp
    const componentName = app.componentName || null;
    console.log(
      `[Launcher] Launching app: ${pkgName} (pane=${isRight ? "secondary" : "primary"})`,
    );

    // 🔴 1. 기동 상태 변수들을 현재 디스플레이 타겟 정보(state.left, state.right)에만 대칭 갱신 -> 자율 Reactive 바인딩에 의해 자동으로 updateLayoutUI() 연쇄 기동!
    if (isRight) {
      state.right = app;
    } else {
      state.left = app;
    }

    // 🔴 3. 백엔드로 안정적으로 소켓 런칭 요청 송출!
    setTimeout(() => {
      const message = {
        type: "launchApp",
        pkg: pkgName,
        pane: isRight ? "secondary" : "primary",
      };
      if (componentName) message.componentName = componentName;

      sendControlMessage(message);

      // Force an immediate keyframe request to set firstFrameReceived=true and dismiss loading overlay instantly
      sendControlMessage({
        type: "requestKeyframe",
        pane: isRight ? "secondary" : "primary",
      });

      // Delayed keyframe request to ensure perfect image sync when the launched app renders its UI
      setTimeout(() => {
        sendControlMessage({
          type: "requestKeyframe",
          pane: isRight ? "secondary" : "primary",
        });
      }, 800);

      if (codecMode === "mjpeg") {
        sendControlMessage({ type: "codec", mode: "mjpeg" });
      }

      firstFrameReceived = false;
      setStatus("Loading...", "");
      showOverlay();
      launchTimeout = setTimeout(() => {
        if (firstFrameReceived) return;
        closeInputBubble(true);
        isLauncherMode = false;
        splitDrawer.classList.add("open");
        homeBtn.style.display = "block";
        hideOverlay();
        showLauncherNotice("Launch timed out. Try again.");
      }, 5000);
    }, 150);
  }

  function clearCanvas() {
    try {
      const ctx = canvas.getContext("2d");
      if (ctx) {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
      }
    } catch (e) {
      /* canvas may be using webgl */
    }
    const mseVideo = document.getElementById("mse-video");
    if (mseVideo) mseVideo.style.opacity = "0";
    canvas.style.opacity = "0";
  }

  function goHome() {
    collapseOverlayMenu();
    isLauncherMode = false;
    clearLaunchTimeout();
    clearFrameWatchdog();
    closeInputBubble(true);
    blurKeyboardProxy();
    // Reset active display apps cleanly to match the server's home/launcher state
    state.left = null;
    state.right = null;
    disableBrowserSplit();

    // Toggle the unified side drawer launcher!
    splitDrawer.classList.toggle("open");
    homeBtn.style.display = "block";

    hideOverlay();
    firstFrameReceived = false;
    sendControlMessage({ type: "goHome" });
  }

  homeBtn.addEventListener("click", goHome);

  // ── Edge Swipe Handlers for Split Drawer ──
  if (splitHandle) {
    splitHandle.addEventListener("click", () => {
      splitDrawer.classList.toggle("open");
    });

    // Swipe on handle
    let startX = 0;
    splitHandle.addEventListener(
      "touchstart",
      (e) => {
        startX = e.touches[0].clientX;
      },
      { passive: true },
    );

    splitHandle.addEventListener(
      "touchend",
      (e) => {
        let endX = e.changedTouches[0].clientX;
        if (startX - endX > 15) {
          // Swiped left
          splitDrawer.classList.add("open");
        } else if (endX - startX > 15) {
          // Swiped right
          splitDrawer.classList.remove("open");
        }
      },
      { passive: true },
    );
  }

  if (splitDrawer) {
    // Swipe on the drawer itself to close it
    let drawerStartX = 0;
    splitDrawer.addEventListener(
      "touchstart",
      (e) => {
        drawerStartX = e.touches[0].clientX;
      },
      { passive: true },
    );

    splitDrawer.addEventListener(
      "touchend",
      (e) => {
        let endX = e.changedTouches[0].clientX;
        if (endX - drawerStartX > 30) {
          // Swiped right
          splitDrawer.classList.remove("open");
        }
      },
      { passive: true },
    );
  }

  // Split toolbar lives inside #overlay-menu-panel; visibility is driven by
  // (!!state.right) so it appears only when a split is actually open.
  const splitToolbar = document.getElementById("split-pane-toolbar");

  // Split ratio buttons
  document.querySelectorAll(".split-ratio-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      const ratio = parseFloat(btn.dataset.ratio);
      if (!ratio || !!!state.right) return;
      const rect = playerShell.getBoundingClientRect();
      if (rect.width <= 0) return;

      // Constrain ratio dynamically to guarantee at least 320px width on both panes
      const minRatioMargin = 320 / rect.width;
      const constrainedRatio = Math.max(
        minRatioMargin,
        Math.min(1 - minRatioMargin, ratio),
      );

      document
        .querySelectorAll(".split-ratio-btn")
        .forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      setBrowserSplitRatio(constrainedRatio);
      lockBrowserSplitViewports(state.right);
      requestAnimationFrame(() => sendViewportSize());
    });
  });

  function swapSplitMode() {
    if (!!!state.right) return;
    browserSplitState.swapped = !browserSplitState.swapped;
    playerShell?.classList.toggle("swapped", browserSplitState.swapped);
    if (navigator.vibrate) {
      navigator.vibrate(30);
    }
    console.log(
      "[SplitSwap] Swapped split panes. Swapped status:",
      browserSplitState.swapped,
    );

    // Re-apply ratio to trigger physical updates
    setBrowserSplitRatio(splitRatio);

    // Lock viewports and send to server
    lockBrowserSplitViewports(state.right);
    requestAnimationFrame(() => sendViewportSize());
  }

  // Draggable Split Divider for real-time resizing
  if (splitDivider) {
    let isDraggingDivider = false;

    // Top Control Pill and Buttons inside divider
    const splitControlPill = document.querySelector(".split-control-pill");
    if (splitControlPill) {
      const stopEvents = [
        "pointerdown",
        "pointerup",
        "pointermove",
        "mousedown",
        "mouseup",
        "click",
      ];
      stopEvents.forEach((evt) => {
        splitControlPill.addEventListener(evt, (e) => {
          e.stopPropagation();
        });
      });
    }

    const splitExpandLeftBtn = document.getElementById("split-expand-left-btn");
    if (splitExpandLeftBtn) {
      const stopEvents = [
        "pointerdown",
        "pointerup",
        "pointermove",
        "mousedown",
        "mouseup",
        "click",
      ];
      stopEvents.forEach((evt) => {
        splitExpandLeftBtn.addEventListener(evt, (e) => {
          e.stopPropagation();
        });
      });
      splitExpandLeftBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        if (!!!state.right) return;
        if (navigator.vibrate) navigator.vibrate(30);

        if (!browserSplitState.swapped) {
          // Left is Primary. Maximize Left means maximizing Primary.
          console.log("[ExpandLeft] Maximizing Left (Primary).");
          disableBrowserSplit({ delayVisual: true });
        } else {
          // Left is Secondary. Maximize Left means promoting Secondary to Primary and maximizing.
          console.log(
            "[ExpandLeft] Promoting and maximizing Left (Secondary).",
          );
          const secondaryApp = state.right;
          if (secondaryApp) {
            promoteSecondaryToPrimary(secondaryApp);
          }
        }
      });
    }

    const splitSwapTopBtn = document.getElementById("split-swap-top-btn");
    if (splitSwapTopBtn) {
      const stopEvents = [
        "pointerdown",
        "pointerup",
        "pointermove",
        "mousedown",
        "mouseup",
        "click",
      ];
      stopEvents.forEach((evt) => {
        splitSwapTopBtn.addEventListener(evt, (e) => {
          e.stopPropagation();
        });
      });
      splitSwapTopBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        swapSplitMode();
      });
    }

    const splitExpandRightBtn = document.getElementById(
      "split-expand-right-btn",
    );
    if (splitExpandRightBtn) {
      const stopEvents = [
        "pointerdown",
        "pointerup",
        "pointermove",
        "mousedown",
        "mouseup",
        "click",
      ];
      stopEvents.forEach((evt) => {
        splitExpandRightBtn.addEventListener(evt, (e) => {
          e.stopPropagation();
        });
      });
      splitExpandRightBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        if (!!!state.right) return;
        if (navigator.vibrate) navigator.vibrate(30);

        if (browserSplitState.swapped) {
          // Right is Primary. Maximize Right means maximizing Primary.
          console.log("[ExpandRight] Maximizing Right (Primary).");
          disableBrowserSplit({ delayVisual: true });
        } else {
          // Right is Secondary. Maximize Right means promoting Secondary to Primary and maximizing.
          console.log(
            "[ExpandRight] Promoting and maximizing Right (Secondary).",
          );
          const secondaryApp = state.right;
          if (secondaryApp) {
            promoteSecondaryToPrimary(secondaryApp);
          }
        }
      });
    }

    splitDivider.addEventListener("pointerdown", (e) => {
      if (!!!state.right) return;
      isDraggingDivider = true;
      splitDivider.setPointerCapture(e.pointerId);
      playerShell?.classList.add("resizing-divider");
    });

    splitDivider.addEventListener("pointermove", (e) => {
      if (!isDraggingDivider || !!!state.right) return;

      const rect = playerShell.getBoundingClientRect();
      if (rect.width <= 0) return;
      const relativeX = e.clientX - rect.left;
      let ratio = relativeX / rect.width;

      if (browserSplitState.swapped) {
        ratio = 1 - ratio;
      }

      // Constrain ratio dynamically to guarantee at least 320px width on both panes (H.264 minimum safe resolution boundary)
      const minRatioMargin = 320 / rect.width;
      const constrainedRatio = Math.max(
        minRatioMargin,
        Math.min(1 - minRatioMargin, ratio),
      );
      setBrowserSplitRatio(constrainedRatio);
    });

    const endDrag = (e) => {
      if (!isDraggingDivider) return;
      isDraggingDivider = false;
      try {
        splitDivider.releasePointerCapture(e.pointerId);
      } catch (_) {}
      playerShell?.classList.remove("resizing-divider");

      const currentRatio = splitRatio;
      if (currentRatio >= 0.85) {
        // Extreme drag right: Maximize Left (Primary) app to 100%
        console.log(
          "[SplitDivider] Dragged extreme right. Maximizing Primary app to 100%.",
        );
        disableBrowserSplit({ delayVisual: true });
        return;
      } else if (currentRatio <= 0.15) {
        // Extreme drag left: Maximize Right (Secondary) app to 100% (Promote it to primary)
        console.log(
          "[SplitDivider] Dragged extreme left. Promoting and maximizing Secondary app.",
        );
        const secondaryApp = state.right;
        if (secondaryApp) {
          disableBrowserSplit({ notifyServer: false, delayVisual: true });
          launchApp(secondaryApp, false);
        }
        return;
      }

      // Save and sync with server
      lockBrowserSplitViewports(state.right);
      requestAnimationFrame(() => sendViewportSize());

      // Highlight nearest ratio button if any close match exists
      document.querySelectorAll(".split-ratio-btn").forEach((b) => {
        const btnRatio = parseFloat(b.dataset.ratio);
        b.classList.toggle("active", Math.abs(btnRatio - currentRatio) < 0.05);
      });
    };

    splitDivider.addEventListener("pointerup", endDrag);
    splitDivider.addEventListener("pointercancel", endDrag);
  }

  if (splitCloseBtn) {
    splitCloseBtn.addEventListener("click", () => {
      disableBrowserSplit({ delayVisual: true });
    });
  }

  if (splitSwapBtn) {
    splitSwapBtn.addEventListener("click", () => {
      swapSplitMode();
    });
  }

  // ── Keyboard handling ──
  if (canvas) {
    const maybeFocusKeyboard = () => {
      if (!isLauncherMode && !useBubbleInput) focusKeyboardProxy();
    };
    canvas.addEventListener("pointerup", maybeFocusKeyboard);
    canvas.addEventListener("mouseup", maybeFocusKeyboard);
    canvas.addEventListener("touchend", maybeFocusKeyboard, { passive: true });
  }

  const kbInput = document.getElementById("keyboard-input");
  if (kbInput) {
    kbInput.addEventListener("compositionstart", () => {
      if (useBubbleInput) return;
      composing = true;
      skipNextInput = false;
    });
    kbInput.addEventListener("compositionupdate", () => {});
    kbInput.addEventListener("compositionend", (e) => {
      if (useBubbleInput) return;
      const finalText = e.data || kbInput.value || "";
      if (
        finalText &&
        controlSocket &&
        controlSocket.readyState === WebSocket.OPEN
      ) {
        controlSocket.send(
          JSON.stringify({ type: "textInput", text: finalText }),
        );
      }
      composing = false;
      skipNextInput = true;
      kbInput.value = "";
    });
    kbInput.addEventListener("input", (e) => {
      if (useBubbleInput || composing) return;
      if (skipNextInput) {
        skipNextInput = false;
        kbInput.value = "";
        return;
      }
      const text = e.data || e.target.value;
      if (
        text &&
        controlSocket &&
        controlSocket.readyState === WebSocket.OPEN
      ) {
        controlSocket.send(JSON.stringify({ type: "textInput", text }));
      }
      kbInput.value = "";
    });
    kbInput.addEventListener("keydown", (e) => {
      if (!controlSocket || controlSocket.readyState !== WebSocket.OPEN) return;
      if (e.key === "Backspace" && !composing) {
        controlSocket.send(JSON.stringify({ type: "keyEvent", keyCode: 67 }));
        e.preventDefault();
        return;
      }
      if (useBubbleInput) return;
      if (e.key === "Enter") {
        controlSocket.send(JSON.stringify({ type: "textInput", text: "\n" }));
        e.preventDefault();
      }
    });
    kbInput.addEventListener("blur", () => {
      kbInput.style.pointerEvents = "none";
      composing = false;
      skipNextInput = false;
    });
  }

  window.addEventListener("resize", () => {
    if (!!state.right) {
      requestAnimationFrame(() => {
        setBrowserSplitRatio(splitRatio);
        lockBrowserSplitViewports(state.right);
        sendViewportSize();
      });
      return;
    }
    sendViewportSize();
  });

  // Initialize decoder and stream sockets asynchronously to prevent blocking DOMContentLoaded / launcher-ready listeners
  (async () => {
    try {
      await initDecoder();
      if (codecMode === "mjpeg") {
        // Open the control socket first so the `codec: mjpeg` preference
        // reaches the server before the video socket starts streaming.
        // Otherwise the server ships H.264 until it processes the switch,
        // which an MJPEG decoder can't render.
        connectControl();
        await waitForControlSocketOpen(2000);
        connectVideo();
      } else {
        connectVideo();
        connectControl();
      }
    } catch (e) {
      console.error("[Main] Stream initialization failed:", e);
      setStatus(e.message, "error");
      showOverlay();
    }
  })();

  audioPlayer = new AudioPlayer();
  const splashScreen = document.getElementById("splash-screen");
  const splashUnmute = document.getElementById("splash-unmute");
  let splashReady = false;

  // Auto-dismiss the splash screen instantly once launcher apps are loaded so the user doesn't have to tap!
  const splashLoading = document.getElementById("splash-loading");
  window.addEventListener("launcher-ready", () => {
    splashReady = true;
    if (splashLoading) splashLoading.classList.add("hidden");
    if (splashUnmute) splashUnmute.classList.add("visible");

    // Instant seamless auto-dismiss
    if (splashScreen) {
      splashScreen.classList.add("hidden");
      setTimeout(() => splashScreen.classList.add("removed"), 500);
    }

    // Open the split drawer by default so users can select their first app!
    splitDrawer.classList.add("open");
    homeBtn.style.display = "block";
  });

  // Initialize audio lazily upon the first actual user interaction (click/touch) on the page.
  // This perfectly satisfies the browser's autoplay gesture policy without requiring a dedicated tap screen!
  const initAudioOnFirstGesture = async () => {
    if (
      !audioPlayer.socket ||
      audioPlayer.socket.readyState === WebSocket.CLOSED
    ) {
      try {
        const wsProtocol =
          window.location.protocol === "https:" ? "wss:" : "ws:";
        await audioPlayer.startFromUserGesture(
          `${wsProtocol}//${host}/ws/audio`,
        );
        console.log(
          "[Audio] Successfully initialized audio on first user gesture.",
        );
      } catch (e) {
        console.warn("[Audio] Failed to initialize audio on gesture", e);
      }
    }
    document.removeEventListener("click", initAudioOnFirstGesture);
    document.removeEventListener("touchstart", initAudioOnFirstGesture);
  };
  document.addEventListener("click", initAudioOnFirstGesture);
  document.addEventListener("touchstart", initAudioOnFirstGesture);

  const mseVideo = document.getElementById("mse-video");
  if (mseVideo) mseVideo.style.pointerEvents = "none";
  if (canvas) canvas.style.pointerEvents = "auto";
  if (secondaryCanvas) secondaryCanvas.style.pointerEvents = "auto";

  // ── Display Density UI ──
  const DENSITY_LEVELS = [
    { value: 1.0, label: "Large" },
    { value: 0.85, label: "Default" },
    { value: 0.7, label: "Small" },
    { value: 0.55, label: "Compact" },
  ];

  function normalizeDensity(scale) {
    return DENSITY_LEVELS.some((level) => level.value === scale) ? scale : 0.7;
  }

  function applyDensity(scale) {
    currentDensity = normalizeDensity(scale);
    try {
      localStorage.setItem(DENSITY_STORAGE_KEY, String(currentDensity));
    } catch (_) {}
    const level =
      DENSITY_LEVELS.find((item) => item.value === currentDensity) ||
      DENSITY_LEVELS[2];
    if (densityLabel) densityLabel.textContent = level.label;
    if (densityPopup) buildDensityPopup();
  }

  function sendDensity(scale) {
    applyDensity(scale);
    sendControlMessage({ type: "displayDensity", scale: currentDensity });
  }

  function buildDensityPopup() {
    if (!densityPopup) return;
    densityPopup.innerHTML = "";
    DENSITY_LEVELS.forEach((option) => {
      const btn = document.createElement("button");
      const isActive = option.value === currentDensity;
      btn.textContent = option.label;
      btn.style.cssText = `
                display:block;width:100%;padding:10px 16px;border:none;
                border-radius:8px;background:${isActive ? "rgba(100,181,246,0.25)" : "transparent"};
                color:${isActive ? "#64B5F6" : "#ccc"};
                font-size:14px;font-weight:${isActive ? "600" : "400"};
                cursor:pointer;text-align:left;
                font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
            `;
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        sendDensity(option.value);
        densityPopup.style.display = "none";
      });
      densityPopup.appendChild(btn);
    });
  }

  function collapseOverlayMenu() {
    if (overlayMenuPanel) overlayMenuPanel.style.display = "none";
    if (overlayMenuToggle)
      overlayMenuToggle.setAttribute("aria-expanded", "false");
    if (densityPopup) densityPopup.style.display = "none";
    if (profilePopup) profilePopup.style.display = "none";
  }

  function updateOverlayControlsVisibility() {
    const active = homeBtn && homeBtn.style.display !== "none";
    if (overlayMenu) overlayMenu.style.display = active ? "flex" : "none";
    if (!active) collapseOverlayMenu();
  }

  // ── Playback Profile UI ──
  // Controls client-side frame pacing / decoder backlog.
  // Thermal-aware auto-downgrade: the service broadcasts thermalStatus
  // messages and handleThermalProfileSwitch() auto-downgrades the profile.
  const profileControl = document.getElementById("playback-profile-control");
  const profileBtn = document.getElementById("playback-profile-btn");
  const profileLabel = document.getElementById("playback-profile-label");
  const profilePopup = document.getElementById("playback-profile-popup");

  const PROFILE_OPTIONS = [
    { value: "low_latency", label: "Low Latency" },
    { value: "balanced", label: "Balanced" },
    { value: "smooth", label: "Smooth" },
  ];

  // Profile rank: lower = more conservative (less buffering, less decode work)
  const PROFILE_RANK = { low_latency: 0, balanced: 1, smooth: 2 };

  // Thermal cap: the maximum profile rank allowed at each thermal level.
  // severe -> only low_latency, moderate/light -> up to balanced, none -> uncapped
  const THERMAL_MAX_PROFILE = {
    severe: "low_latency",
    moderate: "balanced",
    light: "balanced",
    none: "smooth", // no cap
  };

  /**
   * Resolve effective profile = min(userPreference, thermalCap).
   * Always returns the most conservative of the two.
   */
  function resolveEffectiveProfile(preferred, thermalLevel) {
    const cap = THERMAL_MAX_PROFILE[thermalLevel] || "smooth";
    // OTT hint: boost to at least smooth for video apps (more buffering = less stutter)
    const base = ottProfileActive ? "smooth" : preferred;
    const baseRank = PROFILE_RANK[base] ?? 1;
    const capRank = PROFILE_RANK[cap] ?? 2;
    return baseRank <= capRank ? base : cap;
  }

  /**
   * Apply the effective profile to pacer, decoder, and UI label.
   * Does NOT modify userPreferredProfile or localStorage.
   */
  function applyEffectiveProfile(profileName) {
    playbackProfile = profileName;
    if (framePacer) framePacer.setProfile(profileName);
    if (secondaryFramePacer) secondaryFramePacer.setProfile(profileName);
    if (decoder && decoder.setBacklogProfile)
      decoder.setBacklogProfile(profileName);
    if (secondaryDecoder && secondaryDecoder.setBacklogProfile)
      secondaryDecoder.setBacklogProfile(profileName);
    const opt =
      PROFILE_OPTIONS.find((p) => p.value === profileName) ||
      PROFILE_OPTIONS[1];
    if (profileLabel) profileLabel.textContent = opt.label;
  }

  /**
   * Recompute and apply the effective profile from current user pref + thermal level.
   */
  function refreshEffectiveProfile() {
    const effective = resolveEffectiveProfile(
      userPreferredProfile,
      currentThermalLevel,
    );
    if (effective !== playbackProfile) {
      console.log(
        `[Profile] effective=${effective} (user=${userPreferredProfile}, thermal=${currentThermalLevel})`,
      );
    }
    applyEffectiveProfile(effective);
  }

  /**
   * Called when user manually selects a profile from the popup.
   * Saves their preference and recomputes effective (may be capped by thermal).
   */
  function setUserPreferredProfile(profileName) {
    userPreferredProfile = profileName;
    try {
      localStorage.setItem("userPreferredProfile", profileName);
    } catch (_) {}
    refreshEffectiveProfile();
  }

  /**
   * Called when service sends thermalStatus message.
   */
  function handleThermalProfileSwitch(level) {
    const prev = currentThermalLevel;
    currentThermalLevel = level;
    if (prev !== level) {
      console.log(`[Thermal] level changed: ${prev} -> ${level}`);
    }
    refreshEffectiveProfile();
    buildProfilePopup(); // update "(limited)" indicators
  }

  function buildProfilePopup() {
    if (!profilePopup) return;
    profilePopup.innerHTML = "";
    PROFILE_OPTIONS.forEach((p) => {
      const btn = document.createElement("button");
      // Show thermal cap indicator if this option would be capped
      const effective = resolveEffectiveProfile(p.value, currentThermalLevel);
      const isCapped = effective !== p.value;
      btn.textContent = p.label + (isCapped ? " (limited)" : "");
      const isActive = p.value === userPreferredProfile;
      btn.style.cssText = `
                display:block;width:100%;padding:10px 16px;border:none;
                border-radius:8px;background:${isActive ? "rgba(100,181,246,0.25)" : "transparent"};
                color:${isActive ? "#64B5F6" : isCapped ? "rgba(204,204,204,0.5)" : "#ccc"};
                font-size:14px;font-weight:${isActive ? "600" : "400"};
                cursor:pointer;text-align:left;
                font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
            `;
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        setUserPreferredProfile(p.value);
        profilePopup.style.display = "none";
        buildProfilePopup();
      });
      profilePopup.appendChild(btn);
    });
  }

  if (profileBtn && profilePopup) {
    refreshEffectiveProfile();
    buildProfilePopup();

    profileBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      const isVisible = profilePopup.style.display === "block";
      profilePopup.style.display = isVisible ? "none" : "block";
    });
    document.addEventListener("click", () => {
      if (profilePopup) profilePopup.style.display = "none";
    });
  }

  if (densityBtn && densityPopup) {
    applyDensity(currentDensity);
    buildDensityPopup();

    densityBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      const isVisible = densityPopup.style.display === "block";
      densityPopup.style.display = isVisible ? "none" : "block";
    });
    document.addEventListener("click", () => {
      if (densityPopup) densityPopup.style.display = "none";
    });
  }

  // ── Keyboard Input Mode Toggle ──
  const kbModeBtn = document.getElementById("keyboard-mode-btn");
  const kbModeLabel = document.getElementById("keyboard-mode-label");

  function updateKeyboardModeUI() {
    if (!kbModeLabel) return;
    if (useBubbleInput) {
      kbModeLabel.textContent = "Keyboard: Bubble";
      if (kbModeBtn) kbModeBtn.style.background = "rgba(0,0,0,0.6)";
    } else {
      kbModeLabel.textContent = "Keyboard: Direct";
      if (kbModeBtn) kbModeBtn.style.background = "rgba(40,110,250,0.85)";
    }
  }

  if (kbModeBtn) {
    updateKeyboardModeUI();
    kbModeBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      useBubbleInput = !useBubbleInput;
      localStorage.setItem("castla_use_bubble", useBubbleInput);
      updateKeyboardModeUI();

      if (!useBubbleInput) {
        closeInputBubble(true);
        focusKeyboardProxy();
      } else {
        blurKeyboardProxy();
      }
    });
  }

  // Hamburger toggle: expand/collapse the overlay menu panel on click;
  // collapse on clicks outside the entire #overlay-menu wrapper.
  if (overlayMenuToggle && overlayMenuPanel && overlayMenu) {
    overlayMenuToggle.addEventListener("click", (e) => {
      e.stopPropagation();
      const expanded = overlayMenuPanel.style.display === "flex";
      if (expanded) {
        collapseOverlayMenu();
      } else {
        overlayMenuPanel.style.display = "flex";
        overlayMenuToggle.setAttribute("aria-expanded", "true");
      }
    });
    document.addEventListener("click", (e) => {
      if (!overlayMenu.contains(e.target)) collapseOverlayMenu();
    });
  }

  // Show/hide floating controls with home button
  const profileObserver = new MutationObserver(() =>
    updateOverlayControlsVisibility(),
  );
  if (homeBtn) {
    profileObserver.observe(homeBtn, {
      attributes: true,
      attributeFilter: ["style"],
    });
  }
  // Block browser native context menu globally to allow premium custom long-press drag & drop
  document.addEventListener("contextmenu", (e) => e.preventDefault());

  // Block HTML5 native dragging globally to prevent interference with pointer drag & drop
  document.addEventListener("dragstart", (e) => {
    e.preventDefault();
  });

  // Prevent default touchmove when dragging an app to block mobile Chrome from triggering native scrolling and firing pointercancel
  document.addEventListener(
    "touchmove",
    (e) => {
      if (activeDragApp) {
        e.preventDefault();
      }
    },
    { passive: false },
  );

  // Global pointerup and pointercancel to safely clean up dragging state even if mouse is released outside the browser window
  window.addEventListener("pointerup", (e) => {
    if (activeDragApp) {
      handleDragEnd(e.clientX, e.clientY);
    }
  });

  window.addEventListener("pointercancel", (e) => {
    if (activeDragApp) {
      handleDragEnd(e.clientX, e.clientY);
    }
  });

  // Reset drag state instantly if window loses focus (e.g. user Alt-Tabs or clicks outside the window)
  window.addEventListener("blur", () => {
    if (activeDragApp) {
      handleDragEnd(0, 0); // Safely cancel drag without action
    }
  });

  updateOverlayControlsVisibility();
  updateSplitToolbarVisibility();
});

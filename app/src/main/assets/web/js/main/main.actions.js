// English comment: Unified application launching, pair smart evaluation, home redirect, and canvas control actions for Castla Web Client.
// Maintains 100% functional integrity within the optimized 1,000-line modular constraint.

async function launchDualAppsDirectly(primaryApp, secondaryApp) {
  const {
    console,
    destroySecondaryTransport,
    state,
    lockBrowserSplitViewports,
    browserSplitState,
    resolveSplitPreset,
    streamPolicy,
    document,
    splitRatio,
    DEFAULT_SPLIT_RATIO,
    setBrowserSplitRatio,
    playerShell,
    updateSplitToolbarVisibility,
    applyActiveFitModes,
    webLauncher,
    splitDrawer,
    homeBtn,
    clearLaunchTimeout,
    clearFrameWatchdog,
    clearCanvas,
    initSecondaryDecoder,
    secondaryCanvas,
    TouchHandler,
    getActiveSecondaryRenderer,
    controlSocket,
    connectSecondaryVideo,
    sendViewportSize,
    sendControlMessage,
    setTimeout,
  } = window;

  console.log(
    `[Launcher] Launching dual apps directly: ${primaryApp.packageName} + ${secondaryApp.packageName}`,
  );
  window.lastLaunchTime = Date.now();

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
  window.isLauncherMode = false;
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
  if (window.secondaryTouchHandler) {
    window.secondaryTouchHandler.destroy();
  }
  window.secondaryTouchHandler = new TouchHandler(
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
  const {
    console,
    launchGuardUntil,
    allApps,
    state,
    updateLayoutUI,
    launchApp,
    setTimeout,
  } = window;

  console.log(
    `[Launcher] Smart Launching App Pair: left=${leftPkg}, right=${rightPkg}`,
  );
  if (Date.now() < launchGuardUntil) return;

  // Debounce guard to prevent double-clicking or rapid touch double-triggers (within 1200ms)
  const now = Date.now();
  if (window.lastLaunchTime && now - window.lastLaunchTime < 1200) {
    console.log(
      `[Launcher] Blocked rapid double launch attempt for App Pair: ${leftPkg} + ${rightPkg}`,
    );
    return;
  }
  window.lastLaunchTime = now;

  const targetLeftApp = allApps.find((a) => a.packageName === leftPkg);
  const targetRightApp = allApps.find((a) => a.packageName === rightPkg);

  if (!targetLeftApp || !targetRightApp) {
    console.warn(
      `[Launcher] Failed to launch App Pair: one or both apps are missing.`,
    );
    return;
  }

  /* ### 수정 시작 ### */
  // Consistently enforce the exact user-specified App Pair layout direction (Left -> Primary, Right -> Secondary).
  // Avoid fragile 'Smart Swap' prediction branches which violate user-defined layouts and trigger task displacement deadlocks.
  window._leftApp = targetLeftApp;
  window._rightApp = targetRightApp;
  updateLayoutUI();

  // Launch the primary (Left) app on the left slot (pane=primary)
  launchApp(targetLeftApp, false, true);

  // Launch the secondary (Right) app on the right slot (pane=secondary) after a 300ms safety delay
  setTimeout(() => {
    launchApp(targetRightApp, true, true);
  }, 300);
  /* ### 수정 끝 ### */
}

function launchApp(app, isRight = false, forceLaunch = false) {
  const {
    console,
    launchAppPair,
    state,
    showLauncherNotice,
    launchGuardUntil,
    sendControlMessage,
    codecMode,
    closeInputBubble,
    splitDrawer,
    homeBtn,
    hideOverlay,
    showOverlay,
    setStatus,
    setTimeout,
  } = window;

  if (app.isPair) {
    launchAppPair(app.left, app.right);
    return;
  }

  // Debounce guard to prevent double-clicking or rapid touch double-triggers (within 1200ms).
  // Cache the timestamp per panel and packageName to only debounce identical launch commands.
  // Also bypass this safeguard entirely if forceLaunch is enabled (e.g. app pair launching sequence).
  const now = Date.now();
  window.lastLaunchCache = window.lastLaunchCache || {};
  const paneKey = isRight ? "right" : "left";
  const cacheKey = `${paneKey}_${app.packageName}`;

  if (
    !forceLaunch &&
    window.lastLaunchCache[cacheKey] &&
    now - window.lastLaunchCache[cacheKey] < 1200
  ) {
    console.log(
      `[Launcher] Debounced duplicate launch attempt for same package: ${app.packageName} on ${paneKey} pane`,
    );
    return;
  }
  window.lastLaunchCache[cacheKey] = now;

  /* ### 수정 시작 ### */
  // Strict Duplication Safeguard:
  // Prevent running the exact same app on both VD_1 (left) and VD_2 (right) simultaneously.
  const pkgName = app.packageName;
  if (!forceLaunch) {
    if (isRight) {
      // 1. If it's already running on the same Right pane, do not return. Send launch request to backend to trigger recovery.
      if (state.right && state.right.packageName === pkgName) {
        console.log(
          `[Launcher] ${pkgName} is already running on Right. Sending redundant launch to trigger recovery.`,
        );
      }
      // 2. Prevent identical app launch if it's running on the opposite Left pane.
      else if (state.left && state.left.packageName === pkgName) {
        showLauncherNotice("이미 왼쪽 화면(Primary)에서 실행 중인 앱입니다.");
        return;
      }
    } else {
      // 3. If it's already running on the same Left pane, do not return. Send launch request to backend to trigger recovery.
      if (state.left && state.left.packageName === pkgName) {
        console.log(
          `[Launcher] ${pkgName} is already running on Left. Sending redundant launch to trigger recovery.`,
        );
      }
      // 4. Prevent identical app launch if it's running on the opposite Right pane.
      else if (state.right && state.right.packageName === pkgName) {
        showLauncherNotice(
          "이미 오른쪽 화면(Secondary)에서 실행 중인 앱입니다.",
        );
        return;
      }
    }
  }
  /* ### 수정 끝 ### */

  // Block accidental launches right after splash dismiss
  if (Date.now() < launchGuardUntil) {
    console.log(
      `[Launcher] Blocked accidental launch: ${app.packageName} (guard active)`,
    );
    return;
  }
  window.lastLaunchTime = Date.now(); // Record launch timestamp
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

    window.firstFrameReceived = false;
    setStatus("Loading...", "");
    showOverlay();
    window.launchTimeout = setTimeout(() => {
      if (window.firstFrameReceived) return;
      closeInputBubble(true);
      window.isLauncherMode = false;
      splitDrawer.classList.add("open");
      homeBtn.style.display = "block";
      hideOverlay();
      showLauncherNotice("Launch timed out. Try again.");
    }, 5000);
  }, 150);
}

function clearCanvas() {
  const { canvas } = window;
  try {
    const ctx = canvas.getContext("2d");
    if (ctx) {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
    }
  } catch (e) {
    /* canvas may be using webgl */
  }
  const mseVideoEl = document.getElementById("mse-video");
  if (mseVideoEl) mseVideoEl.style.opacity = "0";
  canvas.style.opacity = "0";
}

function goHome() {
  const {
    collapseOverlayMenu,
    clearLaunchTimeout,
    clearFrameWatchdog,
    closeInputBubble,
    blurKeyboardProxy,
    state,
    disableBrowserSplit,
    splitDrawer,
    homeBtn,
    hideOverlay,
    sendControlMessage,
  } = window;

  collapseOverlayMenu();
  window.isLauncherMode = false;
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
  window.firstFrameReceived = false;
  sendControlMessage({ type: "goHome" });
}

// Promote secondary app to primary stream and transition to fullscreen primary layout cleanly
async function promoteSecondaryToPrimary(secondaryApp) {
  const { console, playerShell, sendControlMessage, initDecoder, canvas } =
    window;
  if (!secondaryApp) return;
  console.log(
    `[Promotion] Promoting secondary app to primary: ${secondaryApp.packageName}`,
  );

  // Instantly migrate SPS/PPS parameter cache to avoid WAITING_SPS_PPS deadlocks
  if (window.secondaryDecoder && window.secondaryDecoder._cachedSpsPps) {
    window._lastSpsPps = window.secondaryDecoder._cachedSpsPps;
  } else if (window._lastSecondarySpsPps) {
    window._lastSpsPps = window._lastSecondarySpsPps;
  }

  // Set transition expectations, backup target app, and trigger UI Transition Shield
  window.pendingLayoutSwitch = "single";
  window.isPromotingSecondary = true;
  window._promotedApp = secondaryApp;
  window.firstFrameReceived = false;
  if (canvas) canvas.style.opacity = "0";

  // Hot-reboot the primary decoder and frame pacer to wipe out obsolete sequences and timings
  if (typeof initDecoder === "function") {
    await initDecoder(true);
  }

  playerShell?.classList.add("secondary-fullscreen");

  // Send launchApp request on primary pane to trigger server-side transition
  sendControlMessage?.({
    type: "launchApp",
    pkg: secondaryApp.packageName,
    pane: "primary",
  });

  // Instantly request primary keyframe to accelerate playback transition
  sendControlMessage?.({
    type: "requestKeyframe",
    pane: "primary",
  });
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  launchDualAppsDirectly,
  launchAppPair,
  launchApp,
  clearCanvas,
  goHome,

  // Expose promoteSecondaryToPrimary globally
  promoteSecondaryToPrimary,
});

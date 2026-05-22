// English comment: Dual app fast sequential launcher and smart App Pair launcher for Castla Web Client.
// Strictly respects 100% functional integrity and keeps code within the 300-line limit.

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
    secondaryTouchHandler,
    secondaryCanvas,
    TouchHandler,
    getActiveSecondaryRenderer,
    controlSocket,
    connectSecondaryVideo,
    sendViewportSize,
    sendControlMessage,
    setTimeout
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
    setTimeout
  } = window;

  console.log(
    `[Launcher] Smart Launching App Pair: left=${leftPkg}, right=${rightPkg}`,
  );
  if (Date.now() < launchGuardUntil) return;

  // Debounce guard to prevent double-clicking or rapid touch double-triggers (within 1200ms).
  // Cache the timestamp per specific pair signature to only debounce identical pair commands.
  const now = Date.now();
  window.lastLaunchCache = window.lastLaunchCache || {};
  const cacheKey = `pair_${leftPkg}_${rightPkg}`;
  if (window.lastLaunchCache[cacheKey] && (now - window.lastLaunchCache[cacheKey] < 1200)) {
    console.log(`[Launcher] Debounced duplicate launch attempt for same App Pair: ${leftPkg} + ${rightPkg}`);
    return;
  }
  window.lastLaunchCache[cacheKey] = now;

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

function clearLaunchTimeout() {
  if (window.launchTimeout) {
    clearTimeout(window.launchTimeout);
    window.launchTimeout = null;
  }
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  launchDualAppsDirectly,
  launchAppPair,
  clearLaunchTimeout
});

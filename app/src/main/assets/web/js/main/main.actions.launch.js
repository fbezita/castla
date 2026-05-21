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
  window.lastLaunchTime = Date.now(); // Record launch timestamp

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
      window._leftApp = targetLeftApp;
      window._rightApp = targetRightApp;
      updateLayoutUI();
      launchApp(targetRightApp, true, true);
    } else {
      console.log(
        `[Launcher] ${leftPkg} is running on Right. Keeping it, launching ${rightPkg} on Left.`,
      );
      window._leftApp = targetRightApp; // the missing app goes to the Left
      window._rightApp = targetLeftApp; // the existing app stays on the Right
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
      window._leftApp = targetLeftApp;
      window._rightApp = targetRightApp;
      updateLayoutUI();
      launchApp(targetLeftApp, false, true);
    } else {
      console.log(
        `[Launcher] ${rightPkg} is running on Left. Keeping it, launching ${leftPkg} on Right.`,
      );
      window._leftApp = targetRightApp; // the existing app stays on the Left
      window._rightApp = targetLeftApp; // the missing app goes to the Right
      updateLayoutUI();
      launchApp(targetLeftApp, true, true);
    }
    return;
  }

  // Case 4: Neither app is running anywhere. Launch both!
  console.log(
    `[Launcher] Neither app in pair is running. Launching both: X(${leftPkg}) and Y(${rightPkg})`,
  );
  window._leftApp = targetLeftApp;
  window._rightApp = targetRightApp;
  updateLayoutUI();

  launchApp(targetLeftApp, false, true);
  setTimeout(() => {
    launchApp(targetRightApp, true, true);
  }, 300);
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

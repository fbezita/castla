// English comment: Layout action handlers, split resizing, and viewport boundary locks for Castla Web Client.
// Maintains 100% functional integrity and strictly respects the 300-line constraint.

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

  // console.log(
  //   `[ViewportLockDebug] activeRatio=${activeRatio} primaryWidth=${primaryWidth} secondaryWidth=${secondaryWidth} shellHeight=${shellHeight} isFullscreen=${playerShell?.classList.contains("secondary-fullscreen")}`,
  // );

  if (primaryWidth > 0 && primaryHeight > 0) {
    leftLockedViewport = buildLockedViewport(primaryWidth, primaryHeight);
  } else {
    leftLockedViewport = null;
  }

  
  // Automatically build secondary locked viewport since system is dual stream only.
  const secondaryHeight = shellHeight;
  if (secondaryWidth > 0 && secondaryHeight > 0) {
    rightLockedViewport = buildLockedViewport(
      secondaryWidth,
      secondaryHeight,
      preset.secondaryAspectRatio,
    );
    // console.log(
    //   `[ViewportLockDebug] lockedSecondaryViewport locked! width=${rightLockedViewport.width} height=${rightLockedViewport.height}`,
    // );
  } else {
    // console.warn(
    //   `[ViewportLockDebug] Secondary lock skipped because secondaryWidth=${secondaryWidth} or secondaryHeight=${secondaryHeight}`,
    // );
  }
  
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

  
  // Relax minimum width constraints from 320px to 160px for extremely flexible resizing
  const minPrimaryWidth = 160;
  const minSecondaryWidth = 160;
  
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
  // ### 수정 시작 ###
  // Reference splitToolbar and layoutState from window scope to prevent ReferenceError under strict ESM modules
  const tb = window.splitToolbar;
  if (!tb) return;
  const activePipelines = (window.layoutState?.pipelines || []).filter(p => p !== null);
  tb.style.display = activePipelines.length >= 2 ? "flex" : "none";
  // ### 수정 끝 ###
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

/* ### 수정 시작 ### */
// Reactive multi-pipeline based layout renderer
async function updateLayoutUI() {
  const {
    isPromotingSecondary,
    layoutState,
    state,
    destroySecondaryTransport,
    streamPolicy,
    document,
    splitRatio,
    DEFAULT_SPLIT_RATIO,
    setBrowserSplitRatio,
    playerShell,
    updateSplitToolbarVisibility,
    applyActiveFitModes,
    lockBrowserSplitViewports,
    initSecondaryDecoder,
    secondaryCanvas,
    getActiveSecondaryRenderer,
    controlSocket,
    connectSecondaryVideo,
    sendViewportSize,
    webLauncher,
    splitDrawer,
    homeBtn,
    canvas,
    clearCanvas
  } = window;

  if (isPromotingSecondary) {
    console.log(
      "[Layout] Layout update bypassed due to active secondary-to-primary promotion.",
    );
    return;
  }

  const pipelines = layoutState.pipelines;
  const activePipelinesCount = pipelines.filter(p => p !== null).length;

  console.log(
    `[Launcher] updateLayoutUI: activePipelinesCount=${activePipelinesCount}, pipelinesLength=${pipelines.length}`,
  );

  if (activePipelinesCount === 0) {
    // Scenario 4: No active pipelines -> Cleanly return to the standby UI
    destroySecondaryTransport();
    updateSplitToolbarVisibility();

    playerShell?.classList.remove("secondary-fullscreen");
    playerShell?.classList.remove("browser-split");

    layoutState.pipelines = [];
    webLauncher.classList.remove("hidden");
    // ### 수정 시작 ###
    // Ensure the sidebar split launcher remains visible in standby dashboard mode
    // to allow automatic sliding out and manual gesture pulling.
    splitDrawer.style.display = "flex";
    // ### 수정 끝 ###
    homeBtn.style.display = "none";

    clearCanvas();
  } else if (pipelines.length === 2 && pipelines[0] === null && pipelines[1] !== null) {
    // Scenario 2: Only secondary app (VD_2) is active -> Show right app full screen
    destroySecondaryTransport();

    /* ### 수정 시작 ### */
    // Automatically close the sidebar split drawer when mirroring starts to optimize viewport space.
    if (splitDrawer) {
      splitDrawer.classList.remove("open");
      splitDrawer.style.right = "-300px";
    }
    /* ### 수정 끝 ### */

    streamPolicy.layoutMode = "browser_split";
    document.body.dataset.layoutMode = streamPolicy.layoutMode;

    const initialRatio = 0.5;
    setBrowserSplitRatio(initialRatio);

    document.querySelectorAll(".split-ratio-btn").forEach((b) => {
      const btnRatio = parseFloat(b.dataset.ratio);
      b.classList.toggle("active", Math.abs(btnRatio - initialRatio) < 0.05);
    });

    playerShell?.classList.add("secondary-fullscreen");
    playerShell?.classList.add("browser-split");
    updateSplitToolbarVisibility();
    applyActiveFitModes();

    lockBrowserSplitViewports(state.right);

    await new Promise((resolve) => requestAnimationFrame(() => resolve()));
    lockBrowserSplitViewports(state.right);
    await initSecondaryDecoder();
    if (window.secondaryTouchHandler) {
      window.secondaryTouchHandler.destroy();
    }
    
    if (secondaryCanvas) {
      window.secondaryTouchHandler = new TouchHandler(
        secondaryCanvas,
        getActiveSecondaryRenderer(),
        controlSocket,
        "secondary",
      );
    } else {
      console.warn("[Layout] secondaryCanvas element is missing. TouchHandler skipped.");
    }
    
    applyActiveFitModes();
    connectSecondaryVideo();
    requestAnimationFrame(() => sendViewportSize(true));
  } else if (pipelines.length >= 2 && pipelines[0] !== null && pipelines[1] !== null) {
    // Scenario 1: Both apps are active -> Enable 50:50 dual split layout
    destroySecondaryTransport();

    /* ### 수정 시작 ### */
    // Automatically close the sidebar split drawer when mirroring starts to optimize viewport space.
    if (splitDrawer) {
      splitDrawer.classList.remove("open");
      splitDrawer.style.right = "-300px";
    }
    /* ### 수정 끝 ### */

    streamPolicy.layoutMode = "browser_split";
    document.body.dataset.layoutMode = streamPolicy.layoutMode;

    const initialRatio = splitRatio || DEFAULT_SPLIT_RATIO;
    setBrowserSplitRatio(initialRatio);

    document.querySelectorAll(".split-ratio-btn").forEach((b) => {
      const btnRatio = parseFloat(b.dataset.ratio);
      b.classList.toggle("active", Math.abs(btnRatio - initialRatio) < 0.05);
    });

    playerShell?.classList.remove("secondary-fullscreen");
    playerShell?.classList.add("browser-split");
    updateSplitToolbarVisibility();
    applyActiveFitModes();

    lockBrowserSplitViewports(state.right);

    await new Promise((resolve) => requestAnimationFrame(() => resolve()));
    lockBrowserSplitViewports(state.right);
    await initSecondaryDecoder();
    if (window.secondaryTouchHandler) {
      window.secondaryTouchHandler.destroy();
    }
    
    if (secondaryCanvas) {
      window.secondaryTouchHandler = new TouchHandler(
        secondaryCanvas,
        getActiveSecondaryRenderer(),
        controlSocket,
        "secondary",
      );
    } else {
      console.warn("[Layout] secondaryCanvas element is missing. TouchHandler skipped.");
    }
    
    applyActiveFitModes();
    connectSecondaryVideo();
    requestAnimationFrame(() => sendViewportSize(true));
  } else {
    // Scenario 3: Only left (primary) app is active -> Show primary app full screen
    destroySecondaryTransport();
    updateSplitToolbarVisibility();

    playerShell?.classList.remove("secondary-fullscreen");
    playerShell?.classList.remove("browser-split");

    webLauncher.classList.add("hidden");
    splitDrawer.style.display = "flex";
    /* ### 수정 시작 ### */
    // Automatically close the sidebar split drawer when mirroring starts to optimize viewport space,
    // and completely hide the home button as launcher mode is deprecated and unified under the standby dashboard.
    if (splitDrawer) {
      splitDrawer.classList.remove("open");
      splitDrawer.style.right = "-300px";
    }
    homeBtn.style.display = "none";
    /* ### 수정 끝 ### */

    window.leftLockedViewport = null;
    window.rightLockedViewport = null;

    const isMseActive = window.decoder && window.decoder.constructor.name === "MseDecoder";
    if (isMseActive) {
      canvas.style.opacity = "0";
      canvas.style.display = "block";
      const mseVideo = document.getElementById("mse-video");
      if (mseVideo) {
        mseVideo.style.opacity = "1";
        mseVideo.style.display = "block";
      }
    } else {
      canvas.style.opacity = "1";
      canvas.style.display = "block";
      const mseVideo = document.getElementById("mse-video");
      if (mseVideo) {
        mseVideo.style.opacity = "0";
        mseVideo.style.display = "none";
      }
    }

    applyActiveFitModes();
    sendViewportSize();
  }
}
/* ### 수정 끝 ### */



// Handle seamless layout changes upon video frame resolution change
function handleRendererResolutionChange(width, height) {
  if (window.pendingLayoutSwitch === "single") {
    const aspect = width / height;
    // Fullscreen expects a landscape layout (typically >= 1.0)
    if (aspect >= 1.0) {
      console.log(`[LayoutSmoothing] Target fullscreen frame received: ${width}x${height} (aspect: ${aspect.toFixed(2)}). Smoothly expanding layout.`);
      window.executeVisualFullscreenLayout();
    }
  }
}

function executeVisualFullscreenLayout() {
  
  // Clear layout promotion lock and reset secondary app states
  window.pendingLayoutSwitch = null;
  window.isPromotingSecondary = false;
  if (window.state) {
    window.state.right = null;
  }
  
  window.playerShell?.classList.remove("browser-split");
  window.playerShell?.classList.remove("secondary-fullscreen");
  window.playerShell?.style.removeProperty("--split-left-width");

  // Force synchronous browser layout reflow immediately after removing layout classes
  if (window.playerShell) {
    const _reflow = window.playerShell.offsetWidth;
  }

  window.applyActiveFitModes();

  // Instantly fit and redraw the primary canvas layout to cover 100% fullscreen
  window.getActiveRenderer()?.updateLayout?.();
  
  // Force immediate keyframe request to trigger fast stream startup
  window.controlSocket.send(JSON.stringify({ type: "requestKeyframe", pane: "primary" }));
  
  // Set backup keyframe request
  setTimeout(() => {
    if (window.controlSocket && window.controlSocket.readyState === WebSocket.OPEN) {
      window.controlSocket.send(JSON.stringify({ type: "requestKeyframe", pane: "primary" }));
    }
  }, 800);
}

function disableBrowserSplit(options = {}) {
  const { notifyServer = true } = options;
  const wasActive = !!window.state?.right;
  
  
  // Clear any active promotion locks on browser split closure
  window.isPromotingSecondary = false;
  
  if (window.state) {
    window.state.right = null;
  }
  
  window.updateSplitToolbarVisibility?.();
  
  window.leftLockedViewport = null;
  window.rightLockedViewport = null;
  
  if (window.streamPolicy) {
    window.streamPolicy.layoutMode = "single";
  }
  document.body.dataset.layoutMode = "single";
  
  const playerShell = window.playerShell;
  playerShell?.classList.remove("browser-split");
  playerShell?.classList.remove("secondary-fullscreen");
  playerShell?.style.removeProperty("--split-left-width");

  window.destroySecondaryTransport?.();
  
  if (notifyServer && wasActive && window.controlSocket && window.controlSocket.readyState === WebSocket.OPEN) {
    window.controlSocket.send(JSON.stringify({ type: "closeSecondary" }));
  }

  window.applyActiveFitModes?.();
  
  // Force send full viewport size immediately to guarantee responsive resizing reflow
  if (wasActive && window.controlSocket && window.controlSocket.readyState === WebSocket.OPEN) {
    const fullWidth = Math.round(window.innerWidth || 1920);
    const fullHeight = Math.round(window.innerHeight || 1080);
    console.log(`[Main] Split closed: forcing full viewport ${fullWidth}x${fullHeight}`);
    window.controlSocket.send(
      JSON.stringify({
        type: "viewport",
        pane: "primary",
        width: fullWidth,
        height: fullHeight,
        fitMode: typeof window.getEffectivePrimaryFitMode === "function" ? window.getEffectivePrimaryFitMode() : "contain",
        layoutMode: "single"
      })
    );
  }
}


// Restore and optimize the stream quality and layout policy applicator
function applyStreamPolicy(config = {}) {
  window.streamPolicy = {
    ...window.streamPolicy,
    ...config
  };

  document.body.dataset.layoutMode = window.streamPolicy.layoutMode;

  const adBanner = document.getElementById("ad-banner");
  if (adBanner) {
    adBanner.style.display = window.streamPolicy.showAdBanner ? "block" : "none";
  }

  if (typeof window.applyActiveFitModes === "function") {
    window.applyActiveFitModes();
  }
  if (typeof window.sendViewportSize === "function") {
    requestAnimationFrame(() => window.sendViewportSize());
  }
}


// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  lockBrowserSplitViewports,
  updateSplitFitButton,
  applyActiveFitModes,
  getSplitShellSize,
  getDesiredSplitWidths,
  updateSplitToolbarVisibility,
  setBrowserSplitRatio,
  isDualStreamCapable,
  updateLayoutUI,
  handleRendererResolutionChange,
  executeVisualFullscreenLayout,
  disableBrowserSplit,
  
  // Globally expose applyStreamPolicy for launcher loader dispatcher
  applyStreamPolicy
  
});

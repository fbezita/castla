// English comment: Unified DOM initialization, interactive events, gesture capture, and global event listeners for Castla Web Client.
// Seamlessly preserves 100% of the lifecycle logic under the optimized 1,000-line constraint.

document.addEventListener("DOMContentLoaded", async () => {
  console.log("[Main] DOM Loaded, initializing components...");

  // 1. Explicitly bind all critical HTML DOM elements to the window scope
  // This allows decoupled ESM modules to access and mutate layout properties smoothly.
  window.webLauncher = document.getElementById("web-launcher");
  window.homeBtn = document.getElementById("home-btn");
  window.overlayMenu = document.getElementById("overlay-menu");
  window.overlayMenuToggle = document.getElementById("overlay-menu-toggle");
  window.overlayMenuPanel = document.getElementById("overlay-menu-panel");
  window.densityControl = document.getElementById("density-control");
  window.densityBtn = document.getElementById("density-btn");
  window.densityLabel = document.getElementById("density-label");
  window.densityPopup = document.getElementById("density-popup");
  window.overlay = document.getElementById("overlay");
  window.statusText = document.getElementById("status");
  window.launcherLoading = document.getElementById("launcher-loading");
  window.launcherContent = document.getElementById("launcher-content");
  window.canvas = document.getElementById("display");
  window.playerShell = document.getElementById("player-shell");
  window.streamPane = document.getElementById("stream-pane");
  window.browserSplitPane = document.getElementById("browser-split-pane");
  window.browserSplitFrame = document.getElementById("browser-split-frame");
  window.splitDrawer = document.getElementById("split-drawer");
  window.splitAppList = document.getElementById("split-app-list");
  window.splitHandle = document.getElementById("split-handle");
  window.splitDivider = document.getElementById("split-divider");
  window.splitCloseBtn = document.getElementById("split-close-btn");
  window.splitSwapBtn = document.getElementById("split-swap-btn");
  
  // Fetch correct secondary canvas element from index.html using the display-secondary ID
  window.secondaryCanvas = document.getElementById("display-secondary");
  
  
  // Bind splitToolbar to window scope for strict ESM module visibility
  window.splitToolbar = document.getElementById("split-pane-toolbar");
  

  // Destructure state references from window
  const {
    document: doc,
    navigator,
    homeBtn,
    splitDrawer,
    splitHandle,
    splitDivider,
    splitCloseBtn,
    splitSwapBtn,
    playerShell,
    canvas,
    secondaryCanvas,
    densityBtn,
    densityPopup,
    overlayMenuToggle,
    overlayMenuPanel,
    overlayMenu,
    goHome,
    setBrowserSplitRatio,
    lockBrowserSplitViewports,
    sendViewportSize,
    useBubbleInput,
    focusKeyboardProxy,
    blurKeyboardProxy,
    closeInputBubble,
    composing,
    skipNextInput,
    controlSocket,
    initDecoder,
    connectControl,
    connectVideo,
    waitForControlSocketOpen,
    densityLabel,
    applyDensity,
    buildDensityPopup,
    playbackProfile,
    refreshEffectiveProfile,
    buildProfilePopup,
    ottProfileActive,
    currentThermalLevel,
    userPreferredProfile,
    currentDensity,
    codecMode,
    setStatus,
    showOverlay,
    hideOverlay,
    loadLauncherApps,
    updateSplitToolbarVisibility,
    applyActiveFitModes,
    
    // Destructure splitToolbar for local event handlers reference
    splitToolbar
    
  } = window;

  if (homeBtn) {
    homeBtn.addEventListener("click", goHome);
  }

  // ── Edge Swipe Handlers for Split Drawer ──
  if (splitHandle) {
    splitHandle.addEventListener("click", () => {
      splitDrawer.classList.toggle("open");
    });

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
          splitDrawer.classList.add("open");
        } else if (endX - startX > 15) {
          splitDrawer.classList.remove("open");
        }
      },
      { passive: true },
    );
  }

  if (splitDrawer) {
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
          splitDrawer.classList.remove("open");
        }
      },
      { passive: true },
    );

    
    // Close split drawer when clicking or touching outside of the drawer area
    const handleOutsideTouchOrClick = (e) => {
      if (!splitDrawer.classList.contains("open")) return;
      if (splitDrawer.contains(e.target)) return;
      if (splitHandle && splitHandle.contains(e.target)) return;
      splitDrawer.classList.remove("open");
    };

    document.addEventListener("click", handleOutsideTouchOrClick);
    document.addEventListener("touchstart", handleOutsideTouchOrClick, { passive: true });
    
  }

  // Split toolbar ratio selector bindings
  document.querySelectorAll(".split-ratio-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      const ratio = parseFloat(btn.dataset.ratio);
      if (!ratio || !!!window.state.right) return;
      const rect = playerShell.getBoundingClientRect();
      if (rect.width <= 0) return;

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
      lockBrowserSplitViewports(window.state.right);
      requestAnimationFrame(() => sendViewportSize());
    });
  });

  function swapSplitMode() {
    if (!!!window.state.right) return;
    window.browserSplitState.swapped = !window.browserSplitState.swapped;
    playerShell?.classList.toggle("swapped", window.browserSplitState.swapped);
    if (navigator.vibrate) {
      navigator.vibrate(30);
    }
    console.log(
      "[SplitSwap] Swapped split panes. Swapped status:",
      window.browserSplitState.swapped,
    );

    setBrowserSplitRatio(window.splitRatio);
    lockBrowserSplitViewports(window.state.right);
    requestAnimationFrame(() => sendViewportSize());
  }

  // Draggable Split Divider for real-time resizing
  if (splitDivider) {
    let isDraggingDivider = false;

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
        if (!!!window.state.right) return;
        if (navigator.vibrate) navigator.vibrate(30);

        if (!window.browserSplitState.swapped) {
          console.log("[ExpandLeft] Maximizing Left (Primary).");
          window.disableBrowserSplit({ delayVisual: true });
        } else {
          console.log(
            "[ExpandLeft] Promoting and maximizing Left (Secondary).",
          );
          const secondaryApp = window.state.right;
          if (secondaryApp) {
            window.promoteSecondaryToPrimary(secondaryApp);
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
        if (!!!window.state.right) return;
        if (navigator.vibrate) navigator.vibrate(30);

        if (window.browserSplitState.swapped) {
          console.log("[ExpandRight] Maximizing Right (Primary).");
          window.disableBrowserSplit({ delayVisual: true });
        } else {
          console.log(
            "[ExpandRight] Promoting and maximizing Right (Secondary).",
          );
          const secondaryApp = window.state.right;
          if (secondaryApp) {
            window.promoteSecondaryToPrimary(secondaryApp);
          }
        }
      });
    }

    splitDivider.addEventListener("pointerdown", (e) => {
      if (!!!window.state.right) return;
      isDraggingDivider = true;
      splitDivider.setPointerCapture(e.pointerId);
      playerShell?.classList.add("resizing-divider");
    });

    splitDivider.addEventListener("pointermove", (e) => {
      if (!isDraggingDivider || !!!window.state.right) return;

      const rect = playerShell.getBoundingClientRect();
      if (rect.width <= 0) return;
      const relativeX = e.clientX - rect.left;
      let ratio = relativeX / rect.width;

      if (window.browserSplitState.swapped) {
        ratio = 1 - ratio;
      }

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

      const currentRatio = window.splitRatio;
      if (currentRatio >= 0.85) {
        console.log(
          "[SplitDivider] Dragged extreme right. Maximizing Primary app to 100%.",
        );
        window.disableBrowserSplit({ delayVisual: true });
        return;
      } else if (currentRatio <= 0.15) {
        console.log(
          "[SplitDivider] Dragged extreme left. Promoting and maximizing Secondary app.",
        );
        const secondaryApp = window.state.right;
        if (secondaryApp) {
          window.disableBrowserSplit({ notifyServer: false, delayVisual: true });
          window.launchApp(secondaryApp, false);
        }
        return;
      }

      lockBrowserSplitViewports(window.state.right);
      requestAnimationFrame(() => sendViewportSize());

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
      window.disableBrowserSplit({ delayVisual: true });
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
      if (!window.isLauncherMode && !window.useBubbleInput) focusKeyboardProxy();
    };
    canvas.addEventListener("pointerup", maybeFocusKeyboard);
    canvas.addEventListener("mouseup", maybeFocusKeyboard);
    canvas.addEventListener("touchend", maybeFocusKeyboard, { passive: true });
  }

  const kbInput = document.getElementById("keyboard-input");
  if (kbInput) {
    kbInput.addEventListener("compositionstart", () => {
      if (window.useBubbleInput) return;
      window.composing = true;
      window.skipNextInput = false;
    });
    kbInput.addEventListener("compositionupdate", () => {});
    kbInput.addEventListener("compositionend", (e) => {
      if (window.useBubbleInput) return;
      const finalText = e.data || kbInput.value || "";
      if (
        finalText &&
        window.controlSocket &&
        window.controlSocket.readyState === WebSocket.OPEN
      ) {
        window.controlSocket.send(
          JSON.stringify({ type: "textInput", text: finalText }),
        );
      }
      window.composing = false;
      window.skipNextInput = true;
      kbInput.value = "";
    });
    kbInput.addEventListener("input", (e) => {
      if (window.useBubbleInput || window.composing) return;
      if (window.skipNextInput) {
        window.skipNextInput = false;
        kbInput.value = "";
        return;
      }
      const text = e.data || e.target.value;
      if (
        text &&
        window.controlSocket &&
        window.controlSocket.readyState === WebSocket.OPEN
      ) {
        window.controlSocket.send(JSON.stringify({ type: "textInput", text }));
      }
      kbInput.value = "";
    });
    kbInput.addEventListener("keydown", (e) => {
      if (!window.controlSocket || window.controlSocket.readyState !== WebSocket.OPEN) return;
      if (e.key === "Backspace" && !window.composing) {
        window.controlSocket.send(JSON.stringify({ type: "keyEvent", keyCode: 67 }));
        e.preventDefault();
        return;
      }
      if (window.useBubbleInput) return;
      if (e.key === "Enter") {
        window.controlSocket.send(JSON.stringify({ type: "textInput", text: "\n" }));
        e.preventDefault();
      }
    });
    kbInput.addEventListener("blur", () => {
      kbInput.style.pointerEvents = "none";
      window.composing = false;
      window.skipNextInput = false;
    });
  }

  window.addEventListener("resize", () => {
    if (!!window.state.right) {
      requestAnimationFrame(() => {
        setBrowserSplitRatio(window.splitRatio);
        lockBrowserSplitViewports(window.state.right);
        sendViewportSize();
      });
      return;
    }
    sendViewportSize();
  });

  // Initialize decoder and stream sockets asynchronously to prevent blocking DOMContentLoaded
  (async () => {
    try {
      await initDecoder();
      if (codecMode === "mjpeg") {
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

  window.audioPlayer = new window.AudioPlayer();
  const splashScreen = document.getElementById("splash-screen");
  const splashUnmute = document.getElementById("splash-unmute");
  const splashLoading = document.getElementById("splash-loading");

  window.addEventListener("launcher-ready", () => {
    if (splashLoading) splashLoading.classList.add("hidden");
    if (splashUnmute) splashUnmute.classList.add("visible");

    // Instant seamless auto-dismiss
    if (splashScreen) {
      splashScreen.classList.add("hidden");
      setTimeout(() => splashScreen.classList.add("removed"), 500);
    }

    // Open the split drawer by default so users can select their first app!
    splitDrawer.classList.add("open");
    if (homeBtn) homeBtn.style.display = "block";
  });

  // Initialize audio lazily upon the first actual user interaction
  const initAudioOnFirstGesture = async () => {
    if (
      !window.audioPlayer.socket ||
      window.audioPlayer.socket.readyState === WebSocket.CLOSED
    ) {
      try {
        const wsProtocol =
          window.location.protocol === "https:" ? "wss:" : "ws:";
        await window.audioPlayer.startFromUserGesture(
          `${wsProtocol}//${window.host}/ws/audio`,
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

  // Apply default launcher states
  applyDensity(currentDensity);
  buildDensityPopup();
  refreshEffectiveProfile();
  buildProfilePopup();

  // Load apps list from endpoint
  loadLauncherApps();

  // ── Hamburger Toggle for Overlay controls menu panel ──
  if (overlayMenuToggle && overlayMenuPanel && overlayMenu) {
    overlayMenuToggle.addEventListener("click", (e) => {
      e.stopPropagation();
      const expanded = overlayMenuPanel.style.display === "flex";
      if (expanded) {
        window.collapseOverlayMenu();
      } else {
        overlayMenuPanel.style.display = "flex";
        overlayMenuToggle.setAttribute("aria-expanded", "true");
      }
    });
    document.addEventListener("click", (e) => {
      if (!overlayMenu.contains(e.target)) window.collapseOverlayMenu();
    });
  }

  // Show/hide floating controls with home button
  const profileObserver = new MutationObserver(() =>
    window.updateOverlayControlsVisibility(),
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

  // Prevent default touchmove when dragging an app to block mobile native scrolling
  document.addEventListener(
    "touchmove",
    (e) => {
      if (window.activeDragApp) {
        e.preventDefault();
      }
    },
    { passive: false },
  );

  // Global pointerup and pointercancel to safely clean up dragging state
  window.addEventListener("pointerup", (e) => {
    if (window.activeDragApp) {
      window.handleDragEnd(e.clientX, e.clientY);
    }
  });

  window.addEventListener("pointercancel", (e) => {
    if (window.activeDragApp) {
      window.handleDragEnd(e.clientX, e.clientY);
    }
  });

  // Reset drag state instantly if window loses focus
  window.addEventListener("blur", () => {
    if (window.activeDragApp) {
      window.handleDragEnd(0, 0); // Safely cancel drag without action
    }
  });

  window.updateOverlayControlsVisibility();
  updateSplitToolbarVisibility();
});

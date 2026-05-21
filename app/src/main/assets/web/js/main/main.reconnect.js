// English comment: Reconnection scheduler, Control Socket Connection, and Quality Telemetry Reports for Castla Web Client.
// Strictly preserves 100% of the original logic, comments, and error handlers within the 300-line limit.

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

function handleControlMessage(msg) {
  try {
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

      
      // Do NOT clear cached SPS/PPS to prevent WAITING_SPS_PPS waiting deadlock during resolution changes.
      // Since video streams are persistent, the server won't resend the configuration packet.
      // Keeping the old configuration parameters allows the newly initialized decoder to transition
      // to WAITING_KEYFRAME and DECODING immediately when the recovery keyframe arrives.
      

      
      // Safely hot-refresh the active decoder and instantly request a keyframe (0ms) to ensure zero freeze
      if (pane === "secondary") {
        if (!!state.right) {
          console.log(
            "[Main] Performing hot-refresh on secondary decoder to prevent rainbow artifacts",
          );
          initSecondaryDecoder(false).then(() => {
            // Zero-restart hot-refresh: instantly request new keyframe to resume playback without freeze
            if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
              controlSocket.send(
                JSON.stringify({
                  type: "requestKeyframe",
                  pane: "secondary",
                }),
              );
            }
            // Send a backup keyframe request after a short interval to handle transient packet drops
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
            }, 150);
          });
        }
      } else {
        console.log(
          "[Main] Performing hot-refresh on primary decoder to prevent rainbow artifacts",
        );
        initDecoder(false).then(() => {
          // Zero-restart hot-refresh: instantly request new keyframe to resume playback without freeze
          if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
            controlSocket.send(
              JSON.stringify({
                type: "requestKeyframe",
                pane: "primary",
              }),
            );
          }
          // Send a backup keyframe request after a short interval to handle transient packet drops
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
          }, 150);
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
    

    // Send viewport IMMEDIATELY and BEFORE displayDensity so the
    // server knows the correct dimensions before density triggers a
    // force rebuild (which otherwise uses stale full-screen size).
    sendViewportSize(true);

    
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
    clearInterval(qualityReportInterval);
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
      const src =
        framePacer || (decoder && decoder.getMetrics ? decoder : null);
      if (src) {
        const m = src.getMetrics();
        report.droppedFrames = m.droppedFrames - _prevDropped;
        _prevDropped = m.droppedFrames;
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
      handleControlMessage(msg);
    } catch (_) {}
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

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  scheduleReconnect,
  handleControlMessage,
  connectControl
});

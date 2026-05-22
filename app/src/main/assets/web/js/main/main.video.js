// English comment: Mirroring Video WebSocket Connection, Frame Watchdog, and Stalled Stream Recoveries for Castla Web Client.
// Strictly preserves 100% of the original logic, comments, and error handlers within the 300-line limit.

function connectVideo(isHotRefresh = false) {
  if (window.videoSocket) {
    try {
      window.videoSocket.onopen = null;
      window.videoSocket.onmessage = null;
      window.videoSocket.onerror = null;
      window.videoSocket.onclose = null;
      window.videoSocket.close();
    } catch (_) {}
  }
  /* ### 수정 시작 ### */
  const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  // H264 Wasm software decoder is fully bypassed. Connect H.264 stream using direct URL without profile query negotiation.
  const wsUrl = `${wsProtocol}//${window.host}/ws/video`;
  /* ### 수정 끝 ### */
  if (!window.isLauncherMode) window.setStatus("Connecting...", "");

  window.clearFrameWatchdog();

  // Reset firstFrameReceived so that the reconnected stream's first frame triggers checkReady() and hides the overlay!
  window.firstFrameReceived = false;

  
  // Reset decoder sequence tracking and SPS/PPS cache only when NOT in a hot-refresh transition
  if (!isHotRefresh) {
    window._lastSpsPps = null;
  }
  if (window.decoder) {
    window.decoder._lastSeqNum = undefined;
    if (!isHotRefresh) {
      window.decoder._cachedSpsPps = null;
    }
    if (window.decoder.resetStats) window.decoder.resetStats();
  }
  if (window.secondaryDecoder) {
    window.secondaryDecoder._lastSeqNum = undefined;
    if (!isHotRefresh) {
      window.secondaryDecoder._cachedSpsPps = null;
    }
    if (window.secondaryDecoder.resetStats) window.secondaryDecoder.resetStats();
  }
  

  console.log(`[Main] Connecting video socket to: ${wsUrl}`);
  window.videoSocket = new WebSocket(wsUrl);
  window.videoSocket.binaryType = "arraybuffer";

  window.videoSocket.onopen = () => {
    console.log(`[Main] Video socket connected!`);
    if (!window.isLauncherMode) window.setStatus("Loading...", "");
    if (window.codecMode === "mjpeg") {
      console.log(
        `[Main] Sending codec preference: mjpeg via control socket`,
      );
      window.sendControlMessage?.({ type: "codec", mode: "mjpeg" });
    } else if (window.codecMode === "h264") {
      /* ### 수정 시작 ### */
      // Proactively request a clean H.264 keyframe and trigger stream recovery on open to prevent initial decoder buffer stuck.
      console.log(
        `[Main] Proactively requesting H.264 stream recovery and keyframe on open`,
      );
      window.sendControlMessage?.({ type: "requestKeyframe", pane: "primary" });
      window.requestStreamRecovery?.();
      /* ### 수정 끝 ### */
    }
  };

  window.videoSocket.onmessage = async (event) => {
    if (event.data instanceof ArrayBuffer) {
      window.armFrameWatchdog(window.videoSocket);

      
      // Intercept H.264 SPS/PPS (flags=2) to safeguard against race conditions during decoder re-init
      if (event.data.byteLength >= 9) {
        const view = new DataView(event.data);
        const flags = view.getUint8(0);
        if (flags === 0x02) {
          const spsPpsData = event.data.slice(8);
          window._lastSpsPps = spsPpsData;
          console.log("[VideoSocket] Intercepted H.264 SPS/PPS packet. Size:", spsPpsData.byteLength);
          if (window.decoder) {
            window.decoder._cachedSpsPps = spsPpsData;
          }
        }
      }
      

      if (!window.decoder) return;
      if (window.codecMode === "h264") {
        const v = new Uint8Array(event.data);
        
        if (v.length > 0) {
          const flags = v[0];
          const seqNum = v.length >= 3 ? (v[1] | (v[2] << 8)) : -1;
          // if (flags === 0x01 || seqNum % 60 === 0) {
          //   console.log(`[VideoSocketTelemetry] Recv: size=${v.length}, flags=${flags}, seqNum=${seqNum}, firstFrameReceived=${window.firstFrameReceived}`);
          // }
          if (flags === 0x01 && !window.firstFrameReceived) {
            // console.log(`[VideoSocketTelemetry] Keyframe detected! Activating stream via checkReady().`);
            window.firstFrameReceived = true;
            window.checkReady();
          }
        }
        
      }
      window.decoder.decode(event.data);
    }
  };

  window.videoSocket.onclose = () => {
    window.clearFrameWatchdog();
    if (!window.isLauncherMode) {
      window.setStatus("Disconnected", "error");
      window.showOverlay();
    }
    window.scheduleReconnect?.();
  };

  window.videoSocket.onerror = (error) =>
    console.error("[Main] Video WebSocket error:", error);
}

function checkReady() {
  
  // console.log(`[VideoSocketTelemetry] checkReady() invoked. firstFrameReceived=${window.firstFrameReceived}, isLauncherMode=${window.isLauncherMode}`);
  if (window.firstFrameReceived) {
    if (typeof window.clearLaunchTimeout === "function") {
      window.clearLaunchTimeout();
    }
    const mseVideo = document.getElementById("mse-video");
    /* ### 수정 시작 ### */
    // If MseDecoder is actively driving H.264 stream, skip canvas promotion and route rendering opacity to video element
    const isMseActive = window.decoder && window.decoder.constructor.name === "MseDecoder";
    if (window.isLauncherMode) {
      window.canvas.style.opacity = "0";
      if (mseVideo) {
        mseVideo.style.opacity = "0";
        mseVideo.style.display = "none";
      }
    } else {
      if (isMseActive) {
        window.canvas.style.opacity = "0";
        /* ### 수정 시작 ### */
        // Keep canvas displayed but transparent to fully capture remote pointer events
        window.canvas.style.display = "block";
        /* ### 수정 끝 ### */
        if (mseVideo) {
          mseVideo.style.opacity = "1";
          mseVideo.style.display = "block";
        }
      } else {
        window.canvas.style.opacity = "1";
        window.canvas.style.display = "block";
        if (mseVideo) {
          mseVideo.style.opacity = "0";
          mseVideo.style.display = "none";
        }
      }
    }
    /* ### 수정 끝 ### */
    window.hideOverlay();
    if (window.decoder && window.decoder.play) {
      window.decoder.play();
    }

    // Seamless Promotion Transition Cleanup:
    // If we were waiting for the promoted secondary app to land on primary stream, complete the transition now!
    if (window.isPromotingSecondary) {
      console.log(
        "[Promotion] First primary frame of promoted app received! Completing transition.",
      );

      // First call disableBrowserSplit while isPromotingSecondary is still true
      // to trigger proper full-screen transition and primary temporary fill shield!
      window.disableBrowserSplit({ notifyServer: false });

      // Apply the SSOT UI State changes to cleanly integrate with the reactive layout system
      if (window.state && window._promotedApp) {
        window.state.left = window._promotedApp;
        window.state.right = null;
      }

      window.isPromotingSecondary = false;
      window.playerShell?.classList.remove("secondary-fullscreen");
      window._promotedApp = null;

      if (window.canvas) {
        window.canvas.style.opacity = "1"; // Dismiss transition shield
      }
    }
  }
  
}

// Frame-arrival watchdog: first try to recover an open but quiet stream by
// asking the server/encoder for a fresh frame. Only reconnect if the stream
// stays quiet for the hard timeout.
/* ### 수정 시작 ### */
// Relax watchdog timeout thresholds to prevent false stall recoveries on static screens.
const FRAME_SOFT_TIMEOUT_MS = 8000;
const FRAME_HARD_TIMEOUT_MS = 20000;
/* ### 수정 끝 ### */

function armFrameWatchdog(socket) {
  
  // Always clear any existing timer to prevent async callback leaks and false-positive stall recovery requests
  if (window.frameWatchdogTimer !== null) {
    clearTimeout(window.frameWatchdogTimer);
    window.frameWatchdogTimer = null;
  }
  if (window.isLauncherMode || !socket) return;
  if (socket !== window.videoSocket) return;
  
  window.frameWatchdogTimer = setTimeout(() => {
    // Socket stall watchdog check
    window.onFrameSoftStalled(socket);
  }, FRAME_SOFT_TIMEOUT_MS);
}

function clearFrameWatchdog() {
  if (window.frameWatchdogTimer !== null) clearTimeout(window.frameWatchdogTimer);
  window.frameWatchdogTimer = null;
}

function requestStreamRecovery() {
  if (!window.controlSocket || window.controlSocket.readyState !== WebSocket.OPEN) return;
  try {
    window.controlSocket.send(JSON.stringify({ type: "requestKeyframe" }));
    if (window.codecMode === "mjpeg") {
      window.controlSocket.send(JSON.stringify({ type: "codec", mode: "mjpeg" }));
    }
  } catch (err) {
    console.warn("[Main] Stream recovery request failed:", err);
  }
}

function onFrameSoftStalled(socket) {
  if (socket !== window.videoSocket) return;
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  if (window.isLauncherMode) return;
  console.warn(
    "[Main] Video stream quiet for",
    FRAME_SOFT_TIMEOUT_MS,
    "ms. Requesting recovery frame.",
  );
  window.requestStreamRecovery();
  clearTimeout(window.frameWatchdogTimer);
  window.frameWatchdogTimer = setTimeout(
    () => window.onFrameHardStalled(socket),
    FRAME_HARD_TIMEOUT_MS - FRAME_SOFT_TIMEOUT_MS,
  );
}

function onFrameHardStalled(socket) {
  if (socket !== window.videoSocket) return;
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  if (window.isLauncherMode) return;
  console.warn(
    "[Main] Video stream stalled — no frame for",
    FRAME_HARD_TIMEOUT_MS,
    "ms. Triggering reconnect.",
  );
  window.setStatus("Disconnected", "error");
  window.showOverlay();
  try {
    socket.close();
  } catch (_) {}
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  connectVideo,
  checkReady,
  armFrameWatchdog,
  clearFrameWatchdog,
  requestStreamRecovery,
  onFrameSoftStalled,
  onFrameHardStalled
});

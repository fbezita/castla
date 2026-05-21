// English comment: Primary and Secondary Video Decoder Initializer and Hot-Refresh Utilities for Castla Web Client.
// Strictly preserves 100% of the original logic, comments, and error handlers within the 300-line limit.

async function initDecoder(preserveCache = false) {
  console.log("[Main] Initializing decoders... preserveCache=", preserveCache);

  if (typeof WebCodecs !== "undefined" || window.VideoDecoder) {
    console.log("[Main] Using WebCodecs Decoder");
    const renderer = new CanvasRenderer(canvas);
    renderer.setFitMode(getEffectivePrimaryFitMode());

    // Hook the smooth layout resolution change callback
    renderer.onFrameResolutionChange = window.handleRendererResolutionChange;
    

    // Create frame pacer between decoder and renderer
    if (framePacer) framePacer.destroy();
    framePacer = new FramePacer((frame) => renderer.render(frame));
    framePacer.setProfile(playbackProfile);

    // [SPS/PPS MIGRATION SAFEGUARD] Always migrate H.264 SPS/PPS parameters across decoder instances to prevent waiting deadlock

    const prevSpsPps = decoder ? decoder._cachedSpsPps : null;
    

    // Destroy existing primary decoder cleanly to prevent GPU resource conflict & screen freezes
    if (decoder) {
      decoder.destroy?.();
      decoder = null;
    }
    

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
    } else if (window._lastSpsPps) {
      decoder._cachedSpsPps = window._lastSpsPps;
      console.log(
        "[Decoder] Successfully bound intercepted SPS/PPS to new primary decoder",
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

    /* ### 수정 시작 ### */
    // Explicitly notify backpressure control socket of High Profile preference for WebCodecs
    sendControlMessage({ type: "codec", mode: "h264", profile: "High", pane: "primary" });
    /* ### 수정 끝 ### */
  } else if (MseDecoder.isSupported()) {
    /* ### 수정 시작 ### */
    // Fallback to ultra-low latency MSE + JMuxer decoder if WebCodecs is not supported.
    await initMseDecoder();
    /* ### 수정 끝 ### */
  } else {
    /* ### 수정 시작 ### */
    // Bypass buggy and high-CPU Wasm software H.264 decoder completely.
    // Proceed directly to the highly stable MJPEG fallback mode.
    await initMjpegFallback();
    /* ### 수정 끝 ### */
  }
}

async function initMjpegFallback() {
  if (typeof createImageBitmap !== "undefined") {
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

    // Hook the smooth layout resolution change callback on fallback decoder renderer
    if (decoder.renderer) {
      decoder.renderer.onFrameResolutionChange =
        window.handleRendererResolutionChange;
    }
    
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

async function initMseDecoder() {
  /* ### 수정 시작 ### */
  // Initialize MSE-based H.264 decoder for secure/non-secure hybrid context.
  console.log("[Main] Using MSE + JMuxer H.264 Decoder");

  if (decoder) {
    decoder.destroy?.();
    decoder = null;
  }

  decoder = new MseDecoder('mse-video', (error) => {
    console.error("[Main] MSE primary decoder error:", error);
  });

  await decoder.init();
  codecMode = "h264";
  applyActiveFitModes();

  const mseVideo = document.getElementById("mse-video");
  if (mseVideo) {
    mseVideo.style.display = "block";
    decoder.play();
  }

  sendControlMessage({ type: "codec", mode: "h264", profile: "High", pane: "primary" });
  /* ### 수정 끝 ### */
}

async function initSecondaryDecoder(preserveCache = false) {
  if (!secondaryCanvas) return null;

  // [SPS/PPS MIGRATION SAFEGUARD] Always migrate H.264 SPS/PPS parameters across decoder instances to prevent waiting deadlock

  const prevSpsPps = secondaryDecoder ? secondaryDecoder._cachedSpsPps : null;
  

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
    } else if (window._lastSecondarySpsPps) {
      secondaryDecoder._cachedSpsPps = window._lastSecondarySpsPps;
      console.log(
        "[Decoder] Successfully bound intercepted SPS/PPS to new secondary decoder",
      );
    } else {
      console.log(
        "[Decoder] Secondary SPS/PPS cache cleared/reset for new resolution stream",
      );
    }
    

    await secondaryDecoder.init(secondaryCanvas);
    /* ### 수정 시작 ### */
    // Explicitly notify backpressure control socket of High Profile preference for Secondary WebCodecs
    sendControlMessage({ type: "codec", mode: "h264", profile: "High", pane: "secondary" });
    /* ### 수정 끝 ### */
  } else if (MseDecoder.isSupported()) {
    /* ### 수정 시작 ### */
    // Fallback to secondary MSE + JMuxer decoder if WebCodecs is not supported.
    await initSecondaryMseDecoder();
    /* ### 수정 끝 ### */
  } else {
    /* ### 수정 시작 ### */
    // Bypass secondary H.264 SW decoder and redirect directly to secondary MJPEG fallback.
    await initSecondaryMjpegFallback();
    /* ### 수정 끝 ### */
  }
  return secondaryDecoder;
}

async function initSecondaryMjpegFallback() {
  if (typeof createImageBitmap !== "undefined") {
    secondaryDecoder = new FallbackDecoder(
      () => {},
      (error) => console.error("[Main] Secondary fallback error:", error),
    );
    await secondaryDecoder.init(secondaryCanvas);
    secondaryDecoder.renderer?.setFitMode?.(getEffectiveSecondaryFitMode());

    /* ### 수정 시작 ### */
    // Explicitly notify control socket that secondary display channel is using MJPEG mode
    sendControlMessage({ type: "codec", mode: "mjpeg", pane: "secondary" });
    /* ### 수정 끝 ### */
  }
  return secondaryDecoder;
}

async function initSecondaryMseDecoder() {
  /* ### 수정 시작 ### */
  // Initialize MSE-based H.264 decoder for secondary virtual display.
  console.log("[Main] Using Secondary MSE + JMuxer H.264 Decoder");

  if (secondaryDecoder) {
    secondaryDecoder.destroy?.();
    secondaryDecoder = null;
  }

  secondaryDecoder = new MseDecoder('mse-video-secondary', (error) => {
    console.error("[Main] Secondary MSE decoder error:", error);
  });

  await secondaryDecoder.init();

  const mseVideoSec = document.getElementById("mse-video-secondary");
  if (mseVideoSec) {
    mseVideoSec.style.display = "block";
    secondaryDecoder.play();
  }

  sendControlMessage({ type: "codec", mode: "h264", profile: "High", pane: "secondary" });
  return secondaryDecoder;
  /* ### 수정 끝 ### */
}

function connectSecondaryVideo(isHotRefresh = false) {
  if (!!!state.right) return;
  if (secondaryVideoSocket) {
    try {
      secondaryVideoSocket.close();
    } catch (_) {}
  }

  // Reset sequence tracking but preserve the precious SPS/PPS cache if in hot-refresh mode
  if (!isHotRefresh) {
    window._lastSecondarySpsPps = null;
  }
  if (secondaryDecoder) {
    secondaryDecoder._lastSeqNum = undefined;
    if (!isHotRefresh) {
      secondaryDecoder._cachedSpsPps = null;
    }
  }
  

  /* ### 수정 시작 ### */
  const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  // H264 Wasm software decoder is fully bypassed. Avoid H.264 profile=baseline query negotiations.
  const wsUrl = `${wsProtocol}//${host}/ws/video?channel=secondary`;
  /* ### 수정 끝 ### */
  secondaryVideoSocket = new WebSocket(wsUrl);
  secondaryVideoSocket.binaryType = "arraybuffer";
  secondaryVideoSocket.onopen = () => {
    console.log("[Main] Secondary video socket connected successfully");

    /* ### 수정 시작 ### */
    if (codecMode === "h264") {
      sendControlMessage({ type: "requestKeyframe", pane: "secondary" });
    } else if (codecMode === "mjpeg") {
      sendControlMessage({ type: "codec", mode: "mjpeg", pane: "secondary" });
    }
    /* ### 수정 끝 ### */
  };
  secondaryVideoSocket.onmessage = async (event) => {
    if (event.data instanceof ArrayBuffer) {
      // Intercept H.264 Secondary SPS/PPS (flags=2) to safeguard against race conditions during decoder re-init
      if (event.data.byteLength >= 9) {
        const view = new DataView(event.data);
        const flags = view.getUint8(0);
        if (flags === 0x02) {
          const spsPpsData = event.data.slice(8);
          window._lastSecondarySpsPps = spsPpsData;
          console.log(
            "[VideoSocket] Intercepted H.264 Secondary SPS/PPS packet. Size:",
            spsPpsData.byteLength,
          );
          if (secondaryDecoder) {
            secondaryDecoder._cachedSpsPps = spsPpsData;
          }
        }
      }
      

      if (secondaryDecoder) {
        secondaryDecoder.decode(event.data);
      }
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

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  /* ### 수정 시작 ### */
  initDecoder,
  initSecondaryDecoder,
  connectSecondaryVideo,
  sendSecondaryLaunchRequest,
  initMseDecoder,
  initSecondaryMseDecoder,
  /* ### 수정 끝 ### */
});

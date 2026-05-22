// English comment: Viewport Management, Display Measurement, and Visual Dimension Locking for Castla Web Client.
// Strictly preserves 100% of the original logic, comments, and error handlers within the 300-line limit.

function describeViewport(viewport) {
  return viewport && viewport.width > 0 && viewport.height > 0
    ? `${viewport.width}x${viewport.height}`
    : "none";
}

function sendViewportSize(immediate = false) {
  if (!controlSocket || controlSocket.readyState !== WebSocket.OPEN) return;
  /* ### 수정 시작 ### */
  // Allow viewport transmission in MJPEG mode so that the native virtual display pipeline
  // receives valid initial dimensions to initialize the JpegEncoder.
  /* ### 수정 끝 ### */

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
    // Build declarative virtual display layout updates for the backend
    const layoutUpdatePipelines = [];
    if (window.layoutState && window.layoutState.pipelines && window.layoutState.pipelines.length > 0) {
      window.layoutState.pipelines.forEach((pipe, index) => {
        if (!pipe) return;
        let w = 0;
        let h = 0;
        if (index === 0) {
          w = primaryViewport.width;
          h = primaryViewport.height;
        } else if (index === 1) {
          const secondaryViewport = rightLockedViewport || {
            width: Math.round(browserSplitPane?.clientWidth || window.innerWidth / 2),
            height: Math.round(browserSplitPane?.clientHeight || window.innerHeight)
          };
          w = secondaryViewport.width;
          h = secondaryViewport.height;
        }
        if (w > 0 && h > 0) {
          layoutUpdatePipelines.push({
            id: pipe.id || (index === 0 ? "primary" : "secondary"),
            packageName: pipe.packageName || "",
            className: pipe.className || "",
            width: w,
            height: h
          });
        }
      });
    }

    // Safeguard: Optimize websocket traffic by throttling duplicate layout packets
    const currentLayoutString = JSON.stringify(layoutUpdatePipelines);
    if (window.lastSentLayoutString !== currentLayoutString) {
      window.lastSentLayoutString = currentLayoutString;
      console.log(`[Viewport] Sending layout_update with ${layoutUpdatePipelines.length} pipelines`);
      controlSocket.send(
        JSON.stringify({
          type: "layout_update",
          pipelines: layoutUpdatePipelines
        })
      );
    }
    // ### 수정 끝 ###
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

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  describeViewport,
  sendViewportSize,
  waitForControlSocketOpen
});

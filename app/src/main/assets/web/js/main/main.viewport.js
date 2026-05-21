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

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  describeViewport,
  sendViewportSize,
  waitForControlSocketOpen
});

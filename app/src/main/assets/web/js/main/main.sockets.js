// English comment: Sockets, control messages, and secondary transport cleanups for Castla Web Client.
// Maintains 100% logic representation and keeps lines under the 300-line limit.

function isControlSocketOpen() {
  return !!controlSocket && controlSocket.readyState === WebSocket.OPEN;
}

/**
 * Serializes and sends JSON messages over the active control WebSocket.
 * Includes error handling to avoid socket crashes on serialization errors.
 */
function sendControlMessage(obj) {
  if (isControlSocketOpen()) {
    try {
      controlSocket.send(JSON.stringify(obj));
    } catch (err) {
      console.error(
        "[Socket] Failed to serialize or send message:",
        err,
        obj,
      );
    }
  }
}

function destroySecondaryTransport() {
  if (secondaryTouchHandler) {
    secondaryTouchHandler.destroy();
    secondaryTouchHandler = null;
  }
  if (secondaryVideoSocket) {
    try {
      secondaryVideoSocket.close();
    } catch (_) {}
    secondaryVideoSocket = null;
  }
  if (secondaryFramePacer) {
    secondaryFramePacer.destroy();
    secondaryFramePacer = null;
  }
  if (secondaryDecoder) {
    secondaryDecoder.destroy?.();
    secondaryDecoder = null;
  }
  if (secondaryCanvas) {
    const ctx = secondaryCanvas.getContext("2d");
    ctx?.clearRect(
      0,
      0,
      secondaryCanvas.width || secondaryCanvas.clientWidth || 0,
      secondaryCanvas.height || secondaryCanvas.clientHeight || 0,
    );
  }
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  isControlSocketOpen,
  sendControlMessage,
  destroySecondaryTransport
});

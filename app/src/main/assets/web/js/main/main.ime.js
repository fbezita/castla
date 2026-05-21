// English comment: Keyboard input proxy control and multilingual IME composition bubble management for Castla Web Client.
// Strictly respects 100% functional integrity and keeps code within the 300-line limit.

function focusKeyboardProxy() {
  const kbInput = document.getElementById("keyboard-input");
  if (!kbInput) return;

  kbInput.style.pointerEvents = "auto";
  kbInput.focus({ preventScroll: true });
  if (typeof kbInput.setSelectionRange === "function") {
    const len = kbInput.value.length;
    kbInput.setSelectionRange(len, len);
  }
}

function blurKeyboardProxy() {
  const kbInput = document.getElementById("keyboard-input");
  if (!kbInput) return;

  kbInput.blur();
  kbInput.style.pointerEvents = "none";
  kbInput.value = "";
}

// ── Bubble Composer functions ──
function positionInputBubble(anchor) {
  if (!window.inputBubble) return;
  const bh = 56;
  const margin = 12;
  const bw = window.inputBubble.offsetWidth || 360;

  let cx, top;
  if (anchor) {
    cx = anchor.clientX;
    top = anchor.clientY - bh - margin;
    if (top < margin) top = anchor.clientY + margin;
  } else {
    cx = window.innerWidth / 2;
    top = window.innerHeight - bh - 60;
  }

  let left = cx - bw / 2;
  if (left < margin) left = margin;
  if (left + bw > window.innerWidth - margin)
    left = window.innerWidth - margin - bw;
  if (top < margin) top = margin;
  if (top + bh > window.innerHeight - margin)
    top = window.innerHeight - margin - bh;

  window.inputBubble.style.left = `${left}px`;
  window.inputBubble.style.top = `${top}px`;
}

function openInputBubble(anchor) {
  if (!window.inputBubble || window.bubbleVisible) return;
  window.bubbleVisible = true;
  positionInputBubble(anchor);
  window.inputBubble.classList.add("visible");
  if (window.bubbleText) window.bubbleText.value = "";
  setTimeout(() => window.bubbleText?.focus({ preventScroll: true }), 80);
}

function closeInputBubble(clear = true) {
  if (!window.inputBubble) return;
  window.bubbleVisible = false;
  window.inputBubble.classList.remove("visible");
  if (clear && window.bubbleText) window.bubbleText.value = "";
  window.bubbleText?.blur();
}

function submitBubbleInput() {
  if (!window.bubbleText) return;
  // Read value BEFORE blur — blur() may discard uncommitted Korean
  // IME composition on some WebView implementations instead of
  // committing it, which would leave the value empty.
  const text = window.bubbleText.value;
  window.bubbleText.blur();

  if (text) {
    sendControlMessage({ type: "textInput", text });
  }
  sendControlMessage({ type: "keyEvent", keyCode: 66 });
  window.bubbleText.value = "";
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  focusKeyboardProxy,
  blurKeyboardProxy,
  positionInputBubble,
  openInputBubble,
  closeInputBubble,
  submitBubbleInput
});

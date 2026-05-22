// English comment: Layout rendering engine and aspect ratio/viewport calculators for Castla Web Client.
// Maintains 100% functional integrity and strictly respects the 300-line constraint.

function getActiveRenderer() {
  return decoder && decoder.renderer ? decoder.renderer : null;
}

function getActiveSecondaryRenderer() {
  return secondaryDecoder && secondaryDecoder.renderer
    ? secondaryDecoder.renderer
    : null;
}

/* ### 수정 시작 ### */
// Symmetrical scaling getters configured to return fill fallback instead of contain to absorb viewport gaps
function getEffectivePrimaryFitMode() {
  return !!(window.state && window.state.right) ? "fill" : (window.streamPolicy ? window.streamPolicy.fitMode : "fill");
}

function getEffectiveSecondaryFitMode() {
  return !!(window.state && window.state.right) ? "fill" : (window.streamPolicy ? window.streamPolicy.fitMode : "fill");
}
/* ### 수정 끝 ### */

function alignDimension(value) {
  return Math.max(320, (Math.round(value) + 15) & ~15);
}

function buildLockedViewport(width, height, aspectRatio = null) {
  let nextWidth = Math.max(1, Math.round(width));
  let nextHeight = Math.max(1, Math.round(height));
  if (aspectRatio && Number.isFinite(aspectRatio) && aspectRatio > 0) {
    nextWidth = Math.max(nextWidth, Math.round(nextHeight * aspectRatio));
  }
  return {
    width: alignDimension(nextWidth),
    height: alignDimension(nextHeight),
  };
}

function getAppLayoutHints(app) {
  const packageName = app?.packageName || "";
  const category = app?.category || "";
  const label = (app?.label || "").toLowerCase();
  const isMapApp =
    category === "NAVIGATION" ||
    packageName.includes("map") ||
    packageName.includes("nmap") ||
    packageName.includes("waze") ||
    label.includes("지도") ||
    label.includes("map");
  const isVideoApp =
    category === "VIDEO" ||
    packageName.includes("youtube") ||
    packageName.includes("netflix") ||
    packageName.includes("tving") ||
    packageName.includes("wavve") ||
    packageName.includes("disney") ||
    label.includes("youtube") ||
    label.includes("netflix");
  const isMailOrFeed =
    packageName.includes("gmail") ||
    packageName.includes("mail") ||
    packageName.includes("outlook") ||
    packageName.includes("news") ||
    packageName.includes("reddit") ||
    packageName.includes("x.com") ||
    label.includes("gmail") ||
    label.includes("mail");

  return { isMapApp, isVideoApp, isMailOrFeed };
}

function getAppPreferredAspectRatio(app, role = "secondary") {
  const { isMapApp, isVideoApp, isMailOrFeed } = getAppLayoutHints(app);

  if (isMapApp) return 9 / 16;
  if (isVideoApp) return role === "secondary" ? 0.62 : 0.58;
  if (isMailOrFeed) return 0.56;
  return role === "primary" ? 9 / 16 : 0.58;
}

function computePrimaryPaneRatio(primaryAspectRatio) {
  const shellWidth = Math.round(
    playerShell?.clientWidth || window.innerWidth || 0,
  );
  const shellHeight = Math.round(
    playerShell?.clientHeight || window.innerHeight || 0,
  );
  if (
    shellWidth <= 0 ||
    shellHeight <= 0 ||
    !Number.isFinite(primaryAspectRatio) ||
    primaryAspectRatio <= 0
  ) {
    return DEFAULT_SPLIT_RATIO;
  }

  const desiredPrimaryWidth = Math.round(shellHeight * primaryAspectRatio);
  const minPrimaryWidth = Math.round(shellWidth * 0.25);
  const maxPrimaryWidth = Math.max(minPrimaryWidth, shellWidth - 320);
  const clampedPrimaryWidth = Math.max(
    minPrimaryWidth,
    Math.min(maxPrimaryWidth, desiredPrimaryWidth),
  );
  return clampedPrimaryWidth / shellWidth;
}

function resolveSplitPreset(primaryApp, secondaryApp) {
  const primaryAspectRatio = getAppPreferredAspectRatio(
    primaryApp,
    "primary",
  );
  const secondaryAspectRatio = getAppPreferredAspectRatio(
    secondaryApp,
    "secondary",
  );

  let ratio = 0.5;

  return {
    ratio: Math.max(0.25, Math.min(0.75, ratio)),
    secondaryAspectRatio,
    primaryAspectRatio,
  };
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  getActiveRenderer,
  getActiveSecondaryRenderer,
  getEffectivePrimaryFitMode,
  getEffectiveSecondaryFitMode,
  alignDimension,
  buildLockedViewport,
  getAppLayoutHints,
  getAppPreferredAspectRatio,
  computePrimaryPaneRatio,
  resolveSplitPreset
});

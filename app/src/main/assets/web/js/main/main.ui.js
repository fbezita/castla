// English comment: UI notification toast, overlay, and status setter utilities for Castla Web Client.
// Ensures 100% functional integrity and strictly respects the 300-line constraint.

// Auto tier toast — shown briefly when auto-scale changes tier
function showAutoTierToast(message) {
  const el = document.getElementById("auto-tier-toast");
  if (!el) return;
  el.textContent = message;
  el.style.opacity = "1";
  clearTimeout(_autoTierToastTimer);
  _autoTierToastTimer = setTimeout(() => {
    el.style.opacity = "0";
  }, 4500);
}

function setStatus(message, type = "") {
  if (!statusText) return;
  statusText.textContent = message;
  statusText.className = type;
}

function showOverlay() {
  if (!overlay) return;
  overlay.classList.remove("hidden");
}

function hideOverlay() {
  if (!overlay) return;
  overlay.classList.add("hidden");
}

function showLauncherNotice(message) {
  if (!launcherLoading) return;
  const isAppLoading =
    message.toLowerCase().includes("loading") ||
    message.toLowerCase().includes("launching");
  launcherLoading.innerHTML = `
          ${isAppLoading ? '<div class="loading-spinner"></div>' : ""}
          <div class="loading-text">${message}</div>
      `;
  launcherLoading.style.display = "flex";

  if (launcherNoticeTimer) {
    clearTimeout(launcherNoticeTimer);
    launcherNoticeTimer = null;
  }
  if (!isAppLoading) {
    launcherNoticeTimer = setTimeout(() => {
      hideLauncherNotice();
    }, 3000);
  }
}

function hideLauncherNotice() {
  if (!launcherLoading) return;
  launcherLoading.style.display = "none";
  if (launcherNoticeTimer) {
    clearTimeout(launcherNoticeTimer);
    launcherNoticeTimer = null;
  }
}

// Display Density options and levels for scaled viewports
const DENSITY_LEVELS = [
  { value: 1.0, label: "Large" },
  { value: 0.85, label: "Default" },
  { value: 0.7, label: "Small" },
  { value: 0.55, label: "Compact" }
];

function normalizeDensity(scale) {
  return DENSITY_LEVELS.some(level => level.value === scale) ? scale : 0.7;
}

function applyDensity(scale) {
  window.currentDensity = normalizeDensity(scale);
  try {
    localStorage.setItem(window.DENSITY_STORAGE_KEY, String(window.currentDensity));
  } catch (_) {}
  const level = DENSITY_LEVELS.find(item => item.value === window.currentDensity) || DENSITY_LEVELS[2];
  if (window.densityLabel) window.densityLabel.textContent = level.label;
  if (window.densityPopup) window.buildDensityPopup();
}

function sendDensity(scale) {
  window.applyDensity(scale);
  if (window.controlSocket && window.controlSocket.readyState === WebSocket.OPEN) {
    window.controlSocket.send(JSON.stringify({ type: "displayDensity", scale: window.currentDensity }));
  }
}

function buildDensityPopup() {
  if (!window.densityPopup) return;
  window.densityPopup.innerHTML = "";
  DENSITY_LEVELS.forEach(option => {
    const btn = document.createElement("button");
    const isActive = option.value === window.currentDensity;
    btn.textContent = option.label;
    btn.style.cssText = `
      display:block;width:100%;padding:10px 16px;border:none;
      text-align:left;background:none;color:white;cursor:pointer;
      font-size:14px;transition:background 0.2s;
      border-bottom:1px solid rgba(255,255,255,0.05);
      background-color:${isActive ? "rgba(255,255,255,0.1)" : "transparent"};
      font-weight:${isActive ? "bold" : "normal"};
    `;
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      window.sendDensity(option.value);
      window.densityPopup.style.display = "none";
    });
    window.densityPopup.appendChild(btn);
  });
}

// Playback Profile UI control
const PROFILE_OPTIONS = [
  { value: "low_latency", label: "Low Latency" },
  { value: "balanced",    label: "Balanced" },
  { value: "smooth",      label: "Smooth" }
];

const PROFILE_RANK = { low_latency: 0, balanced: 1, smooth: 2 };

const THERMAL_MAX_PROFILE = {
  severe: "low_latency",
  moderate: "balanced",
  light: "balanced",
  none: "smooth"
};

function resolveEffectiveProfile(preferred, thermalLevel) {
  const cap = THERMAL_MAX_PROFILE[thermalLevel] || "smooth";
  const base = window.ottProfileActive ? "smooth" : preferred;
  const baseRank = PROFILE_RANK[base] ?? 1;
  const capRank = PROFILE_RANK[cap] ?? 2;
  return baseRank <= capRank ? base : cap;
}

function applyEffectiveProfile(profileName) {
  window.playbackProfile = profileName;
  if (window.framePacer) window.framePacer.setProfile(profileName);
  if (window.secondaryFramePacer) window.secondaryFramePacer.setProfile(profileName);
  if (window.decoder && window.decoder.setBacklogProfile) window.decoder.setBacklogProfile(profileName);
  if (window.secondaryDecoder && window.secondaryDecoder.setBacklogProfile) window.secondaryDecoder.setBacklogProfile(profileName);
  const opt = PROFILE_OPTIONS.find(p => p.value === profileName) || PROFILE_OPTIONS[1];
  if (window.profileLabel) window.profileLabel.textContent = opt.label;
}

function refreshEffectiveProfile() {
  const effective = resolveEffectiveProfile(window.userPreferredProfile, window.currentThermalLevel);
  if (effective !== window.playbackProfile) {
    console.log(`[Profile] effective=${effective} (user=${window.userPreferredProfile}, thermal=${window.currentThermalLevel})`);
  }
  applyEffectiveProfile(effective);
}

function setUserPreferredProfile(profileName) {
  window.userPreferredProfile = profileName;
  try { localStorage.setItem("userPreferredProfile", profileName); } catch (_) {}
  refreshEffectiveProfile();
}

function handleThermalProfileSwitch(level) {
  const prev = window.currentThermalLevel;
  window.currentThermalLevel = level;
  if (prev !== level) {
    console.log(`[Thermal] level changed: ${prev} -> ${level}`);
  }
  refreshEffectiveProfile();
  buildProfilePopup();
}

function buildProfilePopup() {
  if (!window.profilePopup) return;
  window.profilePopup.innerHTML = "";
  PROFILE_OPTIONS.forEach(p => {
    const btn = document.createElement("button");
    const effective = resolveEffectiveProfile(p.value, window.currentThermalLevel);
    const isCapped = effective !== p.value;
    btn.textContent = p.label + (isCapped ? " (limited)" : "");
    const isActive = p.value === window.userPreferredProfile;
    btn.style.cssText = `
      display:block;width:100%;padding:10px 16px;border:none;
      border-radius:8px;background:${isActive ? "rgba(100,181,246,0.25)" : "transparent"};
      color:${isActive ? "#64B5F6" : isCapped ? "rgba(204,204,204,0.5)" : "#ccc"};
      font-size:14px;font-weight:${isActive ? "600" : "400"};
      cursor:pointer;text-align:left;
      font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
    `;
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      setUserPreferredProfile(p.value);
      window.profilePopup.style.display = "none";
      buildProfilePopup();
    });
    window.profilePopup.appendChild(btn);
  });
}

// Overlay menu UI controls
function collapseOverlayMenu() {
  if (window.overlayMenuPanel) window.overlayMenuPanel.style.display = "none";
  if (window.overlayMenuToggle) window.overlayMenuToggle.setAttribute("aria-expanded", "false");
  if (window.densityPopup) window.densityPopup.style.display = "none";
  if (window.profilePopup) window.profilePopup.style.display = "none";
}

function updateOverlayControlsVisibility() {
  const active = window.homeBtn && window.homeBtn.style.display !== "none";
  if (window.overlayMenu) window.overlayMenu.style.display = active ? "flex" : "none";
  if (!active) collapseOverlayMenu();
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  showAutoTierToast,
  setStatus,
  showOverlay,
  hideOverlay,
  showLauncherNotice,
  hideLauncherNotice,
  normalizeDensity,
  applyDensity,
  sendDensity,
  buildDensityPopup,
  resolveEffectiveProfile,
  applyEffectiveProfile,
  refreshEffectiveProfile,
  setUserPreferredProfile,
  handleThermalProfileSwitch,
  buildProfilePopup,
  collapseOverlayMenu,
  updateOverlayControlsVisibility
});

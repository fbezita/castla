// English comment: Favorites and auto-run state helpers, and app fetch dispatcher for Castla Web Client.
// Strictly respects 100% functional integrity and keeps code within the 300-line limit.

// ### 수정 시작 ###
function getPairPseudoApps(apps) {
  return window.appPairs.map((pair) => {
    const leftApp = apps.find((a) => a.packageName === pair.left);
    const rightApp = apps.find((a) => a.packageName === pair.right);
    return {
      packageName: `pair:${pair.left}:${pair.right}`,
      isPair: true,
      left: pair.left,
      right: pair.right,
      label: `${leftApp?.label || "Left"} + ${rightApp?.label || "Right"}`,
      category: "PAIR",
    };
  });
}
// ### 수정 끝 ###

function getFavorites() {
  try {
    return JSON.parse(localStorage.getItem("castla_favorites") || "[]");
  } catch (e) {
    return [];
  }
}

function toggleFavorite(packageName) {
  let favorites = getFavorites();
  if (favorites.includes(packageName)) {
    favorites = favorites.filter((pkg) => pkg !== packageName);
  } else {
    favorites.push(packageName);
  }
  localStorage.setItem("castla_favorites", JSON.stringify(favorites));
  // Refresh the launcher lists in real-time instantly without network lag
  refreshLauncherUI();
}

function toggleAutoRun(packageName) {
  const primaryPkg = localStorage.getItem("castla_autorun_primary");
  const secondaryPkg = localStorage.getItem("castla_autorun_secondary");

  if (primaryPkg === packageName) {
    // Primary -> Secondary
    localStorage.removeItem("castla_autorun_primary");
    localStorage.setItem("castla_autorun_secondary", packageName);
  } else if (secondaryPkg === packageName) {
    // Secondary -> Off
    localStorage.removeItem("castla_autorun_secondary");
  } else {
    // Off -> Primary (Clear existing primary and set this)
    localStorage.setItem("castla_autorun_primary", packageName);
    if (secondaryPkg === packageName) {
      localStorage.removeItem("castla_autorun_secondary");
    }
  }
  // Refresh the launcher lists in real-time instantly without network lag
  refreshLauncherUI();
}

async function loadLauncherApps() {
  try {
    const response = await fetch("/api/apps");
    if (!response.ok) throw new Error("Network error");
    const data = await response.json();

    const apps = data.apps || [];
    window.allApps = apps;

    if (typeof window.applyStreamPolicy === "function") {
      window.applyStreamPolicy({
        fitMode: data.fitMode || "contain",
        autoFit: data.autoFit === true,
        layoutMode: data.layoutMode || "single",
      });
    }

    const pairApps = typeof window.getPairPseudoApps === "function" ? window.getPairPseudoApps(apps) : [];
    const combinedApps = [...apps, ...pairApps];

    if (typeof window.renderLauncherApps === "function") {
      window.renderLauncherApps(combinedApps);
    }
    if (typeof window.renderSplitLauncherApps === "function") {
      window.renderSplitLauncherApps(combinedApps);
    }

    // ⚡ Auto-run logic (Execute auto-run once per initial server instance connection)
    const primaryPkg = localStorage.getItem("castla_autorun_primary");
    const secondaryPkg = localStorage.getItem("castla_autorun_secondary");

    if (!window.lastLaunchedInstanceId && window.currentServerInstanceId) {
      window.lastLaunchedInstanceId = window.currentServerInstanceId;
      if (primaryPkg) {
        const primaryApp = apps.find((a) => a.packageName === primaryPkg);
        if (primaryApp) {
          const secondaryApp = secondaryPkg
            ? apps.find((a) => a.packageName === secondaryPkg)
            : null;
          if (secondaryApp) {
            console.log(
              `[AutoRun] Automatically launching dual apps directly: ${primaryApp.packageName} + ${secondaryApp.packageName}`,
            );
            if (typeof window.launchDualAppsDirectly === "function") {
              window.launchDualAppsDirectly(primaryApp, secondaryApp);
            }
          } else {
            console.log(
              `[AutoRun] Automatically launching primary app at startup: ${primaryApp.packageName}`,
            );
            if (typeof window.launchApp === "function") {
              window.launchApp(primaryApp, false);
            }
          }
        }
      }
    }
  } catch (err) {
    console.error("[Launcher]", err);
    if (typeof window.showLauncherNotice === "function") {
      window.showLauncherNotice("Failed to load apps. Try refreshing.");
    }
  } finally {
    // ### 수정 시작 ###
    // Emit launcher-ready event to safely dismiss the splash loading screen.
    window.dispatchEvent(new CustomEvent("launcher-ready"));
    // ### 수정 끝 ###
  }
}

function refreshLauncherUI() {
  const pairApps = typeof window.getPairPseudoApps === "function" ? window.getPairPseudoApps(window.allApps) : [];
  const combinedApps = [...window.allApps, ...pairApps];
  if (typeof window.renderLauncherApps === "function") {
    window.renderLauncherApps(combinedApps);
  }
  if (typeof window.renderSplitLauncherApps === "function") {
    window.renderSplitLauncherApps(combinedApps);
  }
}

// Bind methods globally to window scope for seamless multi-module integration
// ### 수정 시작 ###
Object.assign(window, {
  getPairPseudoApps,
  getFavorites,
  toggleFavorite,
  toggleAutoRun,
  loadLauncherApps,
  refreshLauncherUI
});
// ### 수정 끝 ###

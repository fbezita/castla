// English comment: App pair editing UI, element caching, and hover zone calculations for Castla Web Client.
// Strictly respects 100% functional integrity and keeps code within the 300-line limit.

function initDragAndDropElements() {
  const {
    document,
    currentEditingPair
  } = window;

  window.dragOverlay = document.getElementById("drag-overlay");
  window.dropZoneTop = document.getElementById("drop-zone-top");
  window.dropZoneAutorun = document.getElementById("drop-zone-autorun");
  window.dropZoneLaunchLeft = document.getElementById("drop-zone-launch-left");
  window.dropZoneLaunchRight = document.getElementById("drop-zone-launch-right");
  window.dropZoneBottom = document.getElementById("drop-zone-bottom");
  window.dropZoneAutorunPreview = document.getElementById(
    "drop-zone-autorun-preview",
  );

  window.appPairOverlay = document.getElementById("app-pair-dialog-overlay");
  window.pairAppLeft = document.getElementById("pair-app-left");
  window.pairAppRight = document.getElementById("pair-app-right");
  window.pairSwapBtn = document.getElementById("pair-swap-btn");
  window.pairCancelBtn = document.getElementById("pair-dialog-cancel");
  window.pairDissolveBtn = document.getElementById("pair-dialog-dissolve");
  window.pairSaveBtn = document.getElementById("pair-dialog-save");

  const {
    pairSwapBtn,
    pairCancelBtn,
    pairDissolveBtn,
    pairSaveBtn,
    appPairOverlay
  } = window;

  if (pairSwapBtn && !pairSwapBtn.hasAttribute("data-bound")) {
    pairSwapBtn.setAttribute("data-bound", "true");
    pairSwapBtn.addEventListener("click", () => {
      if (!window.currentEditingPair) return;
      const temp = window.currentEditingPair.left;
      window.currentEditingPair.left = window.currentEditingPair.right;
      window.currentEditingPair.right = temp;
      updatePairDialogUI();
    });

    pairCancelBtn.addEventListener("click", () => {
      appPairOverlay.classList.remove("active");
      window.currentEditingPair = null;
    });

    pairDissolveBtn.addEventListener("click", () => {
      if (!window.currentEditingPair) return;
      window.appPairs = window.appPairs.filter(
        (p) =>
          !(
            p.left === window.currentEditingPair.left &&
            p.right === window.currentEditingPair.right
          ) &&
          !(
            p.left === window.currentEditingPair.right &&
            p.right === window.currentEditingPair.left
          ),
      );
      localStorage.setItem("castla_app_pairs", JSON.stringify(window.appPairs));
      appPairOverlay.classList.remove("active");
      window.currentEditingPair = null;
      refreshLauncherUI();
    });

    pairSaveBtn.addEventListener("click", () => {
      if (!window.currentEditingPair) return;
      const index = window.appPairs.findIndex(
        (p) =>
          (p.left === window.currentEditingPair.left &&
            p.right === window.currentEditingPair.right) ||
          (p.left === window.currentEditingPair.right &&
            p.right === window.currentEditingPair.left),
      );
      if (index !== -1) {
        window.appPairs[index] = window.currentEditingPair;
      } else {
        window.appPairs.push(window.currentEditingPair);
      }
      localStorage.setItem("castla_app_pairs", JSON.stringify(window.appPairs));
      appPairOverlay.classList.remove("active");
      window.currentEditingPair = null;
      refreshLauncherUI();
    });
  }
}

function openAppPairEdit(pair) {
  initDragAndDropElements();
  window.currentEditingPair = { ...pair };
  updatePairDialogUI();
  window.appPairOverlay.classList.add("active");
}

function updatePairDialogUI() {
  if (!window.currentEditingPair) return;
  const leftApp = window.allApps.find(
    (a) => a.packageName === window.currentEditingPair.left,
  );
  const rightApp = window.allApps.find(
    (a) => a.packageName === window.currentEditingPair.right,
  );

  const leftImg = window.pairAppLeft.querySelector("img");
  const leftSpan = window.pairAppLeft.querySelector("span");
  leftImg.src = leftApp ? `/api/icon?pkg=${leftApp.packageName}` : "";
  leftSpan.textContent = leftApp ? leftApp.label : "Unknown";

  const rightImg = window.pairAppRight.querySelector("img");
  const rightSpan = window.pairAppRight.querySelector("span");
  rightImg.src = rightApp ? `/api/icon?pkg=${rightApp.packageName}` : "";
  rightSpan.textContent = rightApp ? rightApp.label : "Unknown";
}

function createAppPair(leftPkg, rightPkg) {
  const { appPairs, showLauncherNotice, refreshLauncherUI } = window;
  const exists = appPairs.some(
    (p) =>
      (p.left === leftPkg && p.right === rightPkg) ||
      (p.left === rightPkg && p.right === leftPkg),
  );
  if (exists) {
    showLauncherNotice("This App Pair already exists.");
    return;
  }

  appPairs.push({
    left: leftPkg,
    right: rightPkg,
  });
  localStorage.setItem("castla_app_pairs", JSON.stringify(appPairs));
  showLauncherNotice("New App Pair created!");
  refreshLauncherUI();
}

function checkHoveredZone(x, y) {
  const {
    dropZoneTop,
    dropZoneAutorun,
    dropZoneLaunchLeft,
    dropZoneLaunchRight,
    dropZoneBottom,
    activeDragIsExisting
  } = window;

  if (!dropZoneTop) return null;

  const topRect = dropZoneTop.getBoundingClientRect();
  if (
    x >= topRect.left &&
    x <= topRect.right &&
    y >= topRect.top &&
    y <= topRect.bottom
  ) {
    return "top";
  }

  if (dropZoneAutorun) {
    const autorunRect = dropZoneAutorun.getBoundingClientRect();
    if (
      x >= autorunRect.left &&
      x <= autorunRect.right &&
      y >= autorunRect.top &&
      y <= autorunRect.bottom
    ) {
      return "autorun";
    }
  }

  if (dropZoneLaunchLeft) {
    const leftRect = dropZoneLaunchLeft.getBoundingClientRect();
    if (
      x >= leftRect.left &&
      x <= leftRect.right &&
      y >= leftRect.top &&
      y <= leftRect.bottom
    ) {
      return "launch_left";
    }
  }

  if (dropZoneLaunchRight) {
    const rightRect = dropZoneLaunchRight.getBoundingClientRect();
    if (
      x >= rightRect.left &&
      x <= rightRect.right &&
      y >= rightRect.top &&
      y <= rightRect.bottom
    ) {
      return "launch_right";
    }
  }

  if (dropZoneBottom && activeDragIsExisting) {
    const bottomRect = dropZoneBottom.getBoundingClientRect();
    if (
      x >= bottomRect.left &&
      x <= bottomRect.right &&
      y >= bottomRect.top &&
      y <= bottomRect.bottom
    ) {
      return "bottom";
    }
  }

  return null;
}

function checkHoveredCell(x, y) {
  const cells = document.querySelectorAll(".app-cell, .split-app-item");
  for (const cell of cells) {
    if (cell.classList.contains("dragging")) continue;
    const rect = cell.getBoundingClientRect();
    if (
      x >= rect.left &&
      x <= rect.right &&
      y >= rect.top &&
      y <= rect.bottom
    ) {
      return cell;
    }
  }
  return null;
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  initDragAndDropElements,
  openAppPairEdit,
  updatePairDialogUI,
  createAppPair,
  checkHoveredZone,
  checkHoveredCell
});

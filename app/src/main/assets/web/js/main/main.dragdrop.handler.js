// English comment: Drag gesture handlers, ghost positioning, drop zone preview rendering, and pointermove tracking.
// Strictly respects 100% functional integrity and keeps code within the 300-line limit.

function startDragging(app, cell, event) {
  const {
    document,
    navigator,
    initDragAndDropElements,
    state,
    updateDropZonePreviews,
    updateGhostPosition
  } = window;

  initDragAndDropElements();
  window.activeDragApp = app;
  cell.classList.add("dragging");

  // Lock body, launcher, and split drawer scrolling dynamically to prevent scroll gesture cancelling pointer capture
  const launcherEl = document.getElementById("web-launcher");
  if (launcherEl) {
    launcherEl.style.overflowY = "hidden";
    launcherEl.style.touchAction = "none";
  }
  const drawerListEl = document.getElementById("split-app-list");
  if (drawerListEl) {
    drawerListEl.style.overflowY = "hidden";
    drawerListEl.style.touchAction = "none";
  }
  document.body.style.overflow = "hidden";
  document.body.style.touchAction = "none";

  if (navigator.vibrate) {
    navigator.vibrate(50);
  }

  const sourceCat = cell.getAttribute("data-source-category");
  window.activeDragIsExisting =
    sourceCat === "FAVORITES" ||
    sourceCat === "AUTORUN" ||
    sourceCat === "PAIR";

  const isFromSidebar = !!cell.closest("#split-drawer");
  window.isFromSidebarDrag = isFromSidebar;
  window.shouldDeferDragOverlay = isFromSidebar;

  if (!window.shouldDeferDragOverlay) {
    window.dragOverlay.classList.add("active");

    // Toggle the bottom trash drop zone wrap dynamically
    const bottomWrap = document.getElementById("drag-row-bottom-wrap");
    if (bottomWrap) {
      bottomWrap.style.display = window.activeDragIsExisting ? "flex" : "none";
    }
  }

  // Update live viewport task guides inside spatial drop zones
  const leftGuide = window.dropZoneLaunchLeft
    ? window.dropZoneLaunchLeft.querySelector("span:last-of-type")
    : null;
  if (leftGuide) {
    if (state.left) {
      leftGuide.innerHTML = `<img src="/api/icon?pkg=${state.left.packageName}" style="width:16px;height:16px;object-fit:contain;vertical-align:middle;margin-right:4px;border-radius:4px;"/> <strong style="color:#00E5FF;">${state.left.label}</strong> 실행 중`;
    } else {
      leftGuide.innerHTML = `<span style="color:rgba(255,255,255,0.35);">빈 화면 (VD_1)</span>`;
    }
  }

  const rightGuide = window.dropZoneLaunchRight
    ? window.dropZoneLaunchRight.querySelector("span:last-of-type")
    : null;
  if (rightGuide) {
    if (!!state.right && state.right) {
      rightGuide.innerHTML = `<img src="/api/icon?pkg=${state.right.packageName}" style="width:16px;height:16px;object-fit:contain;vertical-align:middle;margin-right:4px;border-radius:4px;"/> <strong style="color:#E040FB;">${state.right.label}</strong> 실행 중`;
    } else {
      rightGuide.innerHTML = `<span style="color:rgba(255,255,255,0.35);">빈 화면 (VD_2)</span>`;
    }
  }

  updateDropZonePreviews();

  window.dragGhost = document.createElement("div");
  window.dragGhost.className = "drag-ghost";

  const ghostImg = document.createElement("img");
  if (app.isPair) {
    ghostImg.src = `/api/icon?pkg=${app.left}`;
  } else {
    ghostImg.src = `/api/icon?pkg=${app.packageName}`;
  }
  window.dragGhost.appendChild(ghostImg);
  document.body.appendChild(window.dragGhost);

  updateGhostPosition(event.clientX, event.clientY);
}

function updateGhostPosition(x, y) {
  if (!window.dragGhost) return;
  window.dragGhost.style.left = `${x}px`;
  window.dragGhost.style.top = `${y}px`;
}

function updateDropZonePreviews() {
  const { allApps, localStorage, dropZoneAutorunPreview } = window;
  const primaryPkg = localStorage.getItem("castla_autorun_primary");
  const secondaryPkg = localStorage.getItem("castla_autorun_secondary");

  if (!dropZoneAutorunPreview) return;

  if (primaryPkg && secondaryPkg) {
    const leftApp = allApps.find((a) => a.packageName === primaryPkg);
    const rightApp = allApps.find((a) => a.packageName === secondaryPkg);
    dropZoneAutorunPreview.innerHTML = `
              <div style="display:flex; gap:10px; align-items:center; margin-bottom: 6px;">
                  <img src="/api/icon?pkg=${primaryPkg}" style="width:36px; height:36px; object-fit:contain;" />
                  <span style="font-size:18px; color:#00E5FF; font-weight:bold;">+</span>
                  <img src="/api/icon?pkg=${secondaryPkg}" style="width:36px; height:36px; object-fit:contain;" />
              </div>
              <span>${leftApp ? leftApp.label : "Left"} + ${rightApp ? rightApp.label : "Right"}</span>
          `;
    dropZoneAutorunPreview.style.display = "flex";
  } else if (primaryPkg) {
    const leftApp = allApps.find((a) => a.packageName === primaryPkg);
    dropZoneAutorunPreview.innerHTML = `
              <img src="/api/icon?pkg=${primaryPkg}" />
              <span>${leftApp ? leftApp.label : "Auto-run"}</span>
          `;
    dropZoneAutorunPreview.style.display = "flex";
  } else {
    dropZoneAutorunPreview.style.display = "none";
  }
}

function handleDragMove(x, y) {
  // ### 수정 시작 ###
  // Fix TDZ ReferenceError by removing window from window destructuring assignment
  const {
    document,
    navigator,
    activeDragApp,
    isFromSidebarDrag,
    dragOverlay,
    activeDragIsExisting,
    dropZoneTop,
    dropZoneAutorun,
    dropZoneLaunchLeft,
    dropZoneLaunchRight,
    dropZoneBottom,
    checkHoveredCell,
    checkHoveredZone,
    updateGhostPosition,
    splitDrawer
  } = window;
  // ### 수정 끝 ###

  if (!activeDragApp) return;
  updateGhostPosition(x, y);

  if (isFromSidebarDrag) {
    const drawerEl = document.getElementById("split-drawer");
    const drawerRect = drawerEl?.getBoundingClientRect();
    if (drawerRect) {
      if (x < drawerRect.left) {
        // 드로어 밖으로 이탈
        if (!dragOverlay.classList.contains("active")) {
          dragOverlay.classList.add("active");
          // ### 수정 시작 ###
          // Apply Ghosting UI by adding dragging-active class to the drawer
          drawerEl?.classList.add("dragging-active");
          // ### 수정 끝 ###
          const bottomWrap = document.getElementById("drag-row-bottom-wrap");
          if (bottomWrap) {
            bottomWrap.style.display = activeDragIsExisting ? "flex" : "none";
          }
          if (navigator.vibrate) {
            navigator.vibrate(30);
          }
          console.log(
            "[DragAndDrop] Left split drawer. Activated drag overlay.",
          );
        }
      } else {
        // 드로어 안으로 진입/복귀
        if (dragOverlay.classList.contains("active")) {
          dragOverlay.classList.remove("active");
          // ### 수정 시작 ###
          // Remove Ghosting UI when cursor goes back into the drawer
          drawerEl?.classList.remove("dragging-active");
          // ### 수정 끝 ###
          console.log(
            "[DragAndDrop] Entered split drawer. Deactivated drag overlay.",
          );
        }
      }
    }
  }

  // 드로어에서 시작한 드래그이고, 마우스가 현재 드로어 내부(x >= drawerRect.left)에 있는 경우
  const drawerEl = document.getElementById("split-drawer");
  const drawerRect = drawerEl?.getBoundingClientRect();
  if (isFromSidebarDrag && drawerRect && x >= drawerRect.left) {
    // 바깥 분할 드롭 영역 hover 효과 전부 리셋
    if (dropZoneTop) dropZoneTop.classList.remove("hovered");
    if (dropZoneAutorun) dropZoneAutorun.classList.remove("hovered");
    if (dropZoneLaunchLeft) dropZoneLaunchLeft.classList.remove("hovered");
    if (dropZoneLaunchRight) dropZoneLaunchRight.classList.remove("hovered");
    if (dropZoneBottom) dropZoneBottom.classList.remove("hovered");

    // 드로어 내부 앱 리스트들 중에서 호버된 아이템이 있는지 스캔하여 병합(App Pair) 비주얼 피드백 제공!
    const hoveredCell = checkHoveredCell(x, y);
    if (hoveredCell) {
      window.currentHoveredDropZone = null;
      hoveredCell.style.transform = "scale(1.15)";
      hoveredCell.style.boxShadow = "0 0 15px rgba(0, 229, 255, 0.4)";
      hoveredCell.style.border = "1px solid #00E5FF";
    } else {
      document
        .querySelectorAll(".app-cell, .split-app-item")
        .forEach((cell) => {
          if (cell.classList.contains("dragging")) return;
          cell.style.transform = "";
          cell.style.boxShadow = "";
          cell.style.border = "";
        });
    }
    return;
  }

  // Auto-scroll launcher grid during drag when hovering near top/bottom grid boundaries
  const launcherEl = document.getElementById("web-launcher");
  if (launcherEl) {
    const scrollSpeed = 12;
    if (y > window.innerHeight - 140) {
      launcherEl.scrollTop += scrollSpeed;
    } else if (y > 140 && y < 260) {
      launcherEl.scrollTop -= scrollSpeed;
    }
  }

  if (dropZoneTop) dropZoneTop.classList.remove("hovered");
  if (dropZoneAutorun) dropZoneAutorun.classList.remove("hovered");
  if (dropZoneLaunchLeft) dropZoneLaunchLeft.classList.remove("hovered");
  if (dropZoneLaunchRight) dropZoneLaunchRight.classList.remove("hovered");
  if (dropZoneBottom) dropZoneBottom.classList.remove("hovered");

  const hoveredCell = checkHoveredCell(x, y);
  if (hoveredCell) {
    window.currentHoveredDropZone = null;
    hoveredCell.style.transform = "scale(1.15)";
    hoveredCell.style.boxShadow = "0 0 15px rgba(0, 229, 255, 0.4)";
    hoveredCell.style.border = "1px solid #00E5FF";
  } else {
    document
      .querySelectorAll(".app-cell, .split-app-item")
      .forEach((cell) => {
        if (cell.classList.contains("dragging")) return;
        cell.style.transform = "";
        cell.style.boxShadow = "";
        cell.style.border = "";
      });

    const hoveredZone = checkHoveredZone(x, y);
    window.currentHoveredDropZone = hoveredZone;

    if (hoveredZone === "top" && dropZoneTop)
      dropZoneTop.classList.add("hovered");
    else if (hoveredZone === "autorun" && dropZoneAutorun)
      dropZoneAutorun.classList.add("hovered");
    else if (hoveredZone === "launch_left" && dropZoneLaunchLeft)
      dropZoneLaunchLeft.classList.add("hovered");
    else if (hoveredZone === "launch_right" && dropZoneLaunchRight)
      dropZoneLaunchRight.classList.add("hovered");
    else if (hoveredZone === "bottom" && dropZoneBottom)
      dropZoneBottom.classList.add("hovered");
  }
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  startDragging,
  updateGhostPosition,
  updateDropZonePreviews,
  handleDragMove
});

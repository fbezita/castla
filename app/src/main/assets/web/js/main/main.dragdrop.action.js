// English comment: Drag-and-drop terminal actions, drop evaluation, and drag cancellation for Castla Web Client.
// Strictly respects 100% functional integrity and keeps code within the 300-line limit.

function handleDragEnd(x, y) {
  const {
    document,
    activeDragApp,
    activeDragIsExisting,
    dragOverlay,
    dragGhost,
    splitDrawer,
    checkHoveredCell,
    checkHoveredZone,
    allApps,
    createAppPair,
    triggerDropZoneAction,
  } = window;

  if (!activeDragApp) return;

  const cell = document.querySelector(
    ".app-cell.dragging, .split-app-item.dragging",
  );
  if (cell) cell.classList.remove("dragging");

  document.querySelectorAll(".app-cell, .split-app-item").forEach((cell) => {
    cell.style.transform = "";
    cell.style.boxShadow = "";
    cell.style.border = "";
  });

  window.isFromSidebarDrag = false;
  window.shouldDeferDragOverlay = false;
  dragOverlay.classList.remove("active");

  // Clean up Ghosting UI class on drag end to restore opacity
  if (splitDrawer) {
    splitDrawer.classList.remove("dragging-active");
  }
  
  if (dragGhost) {
    dragGhost.remove();
    window.dragGhost = null;
  }

  const hoveredZone = checkHoveredZone(x, y);
  const hoveredCell = checkHoveredCell(x, y);

  if (hoveredCell) {
    const targetPkg = hoveredCell.getAttribute("data-package");
    if (
      targetPkg &&
      targetPkg !== activeDragApp.packageName &&
      !activeDragApp.isPair
    ) {
      const targetApp = allApps.find((a) => a.packageName === targetPkg);
      if (targetApp && !targetApp.isPair) {
        createAppPair(activeDragApp.packageName, targetApp.packageName);
      }
    }
  } else if (hoveredZone) {
    triggerDropZoneAction(hoveredZone, activeDragApp, cell);
  }

  // Restore body, launcher, and split drawer scrolling dynamically
  const launcherEl = document.getElementById("web-launcher");
  if (launcherEl) {
    launcherEl.style.overflowY = "";
    launcherEl.style.touchAction = "";
  }
  const drawerListEl = document.getElementById("split-app-list");
  if (drawerListEl) {
    drawerListEl.style.overflowY = "";
    drawerListEl.style.touchAction = "";
  }
  document.body.style.overflow = "";
  document.body.style.touchAction = "";

  // Close the sidebar quick launcher automatically only if dropped outside the drawer
  if (splitDrawer) {
    const drawerRect = splitDrawer.getBoundingClientRect();
    if (x < drawerRect.left) {
      splitDrawer.classList.remove("open");
    } else {
      console.log(
        "[DragAndDrop] Dropped inside split drawer. Kept drawer open.",
      );
    }
  }

  window.activeDragApp = null;
  window.currentHoveredDropZone = null;
}

function cancelDrag() {
  const { document, activeDragApp, dragOverlay, dragGhost, splitDrawer } =
    window;

  if (!activeDragApp) return;

  const cell = document.querySelector(
    ".app-cell.dragging, .split-app-item.dragging",
  );
  if (cell) cell.classList.remove("dragging");

  document.querySelectorAll(".app-cell, .split-app-item").forEach((cell) => {
    cell.style.transform = "";
    cell.style.boxShadow = "";
    cell.style.border = "";
  });

  window.isFromSidebarDrag = false;
  window.shouldDeferDragOverlay = false;
  dragOverlay.classList.remove("active");

  // Clean up Ghosting UI class on drag cancel to restore opacity
  if (splitDrawer) {
    splitDrawer.classList.remove("dragging-active");
  }
  
  if (dragGhost) {
    dragGhost.remove();
    window.dragGhost = null;
  }

  // Restore scrolling dynamically
  const launcherEl = document.getElementById("web-launcher");
  if (launcherEl) {
    launcherEl.style.overflowY = "";
    launcherEl.style.touchAction = "";
  }
  const drawerListEl = document.getElementById("split-app-list");
  if (drawerListEl) {
    drawerListEl.style.overflowY = "";
    drawerListEl.style.touchAction = "";
  }
  document.body.style.overflow = "";
  document.body.style.touchAction = "";

  window.activeDragApp = null;
  window.currentHoveredDropZone = null;
  console.log(
    "[DragAndDrop] Drag and drop cancelled safely via Escape key or backout.",
  );
}

// Bind Escape key cancellation globally
window.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && window.activeDragApp) {
    e.preventDefault();
    cancelDrag();
  }
});

function triggerDropZoneAction(zone, app, cell) {
  const {
    localStorage,
    showLauncherNotice,
    refreshLauncherUI,
    getFavorites,
    state,
    launchApp,
  } = window;

  if (app.isPair) {
    if (zone === "autorun") {
      localStorage.setItem("castla_autorun_primary", app.left);
      localStorage.setItem("castla_autorun_secondary", app.right);
      showLauncherNotice(`${app.label} set to Auto-run (Split Screen).`);
      refreshLauncherUI();
      return;
    }
  }

  const pkg = app.packageName;
  const sourceCat = cell ? cell.getAttribute("data-source-category") : "";

  if (zone === "top") {
    let favorites = getFavorites();
    if (!favorites.includes(pkg)) {
      favorites.push(pkg);
      localStorage.setItem("castla_favorites", JSON.stringify(favorites));
      showLauncherNotice(`${app.label} added to Favorites.`);
    }
  } else if (zone === "autorun") {
    localStorage.setItem("castla_autorun_primary", pkg);
    localStorage.removeItem("castla_autorun_secondary");
    showLauncherNotice(`${app.label} set to Auto-run.`);
  } else if (zone === "launch_left") {
    console.log(`[Launcher] Drag-launching Primary (VD_1): ${app.label}`);
    // 1. 이미 동일한 왼쪽 화면에 켜져 있으면 에러 공지 없이 조용히 무음 리턴!
    if (state.left && state.left.packageName === pkg) {
      return;
    }
    // 2. 반대쪽인 오른쪽 화면에 이미 실행 중인 경우에만 경고 알림 후 리턴 차단!
    if (!!state.right && state.right && state.right.packageName === pkg) {
      showLauncherNotice("이미 오른쪽 화면(Secondary)에서 실행 중인 앱입니다.");
      return;
    }
    launchApp(app, false); // 반대쪽 실행 정보가 이미 채워져 있으므로 그대로 런칭!
  } else if (zone === "launch_right") {
    console.log(`[Launcher] Drag-launching Secondary (VD_2): ${app.label}`);
    // 3. 이미 동일한 오른쪽 화면에 켜져 있으면 에러 공지 없이 조용히 무음 리턴!
    if (!!state.right && state.right && state.right.packageName === pkg) {
      return;
    }
    // 4. 반대쪽인 왼쪽 화면에 이미 실행 중인 경우에만 경고 알림 후 리턴 차단!
    if (state.left && state.left.packageName === pkg) {
      showLauncherNotice("이미 왼쪽 화면(Primary)에서 실행 중인 앱입니다.");
      return;
    }
    // 5. 공통 기동 파이프라인 호출로 전후 상황 파악 및 UI 업데이트 자동 위임!
    launchApp(app, true); // 반대쪽 실행 정보가 이미 채워져 있으므로 그대로 런칭!
  } else if (zone === "bottom") {
    if (sourceCat === "FAVORITES") {
      let favorites = getFavorites().filter((p) => p !== pkg);
      localStorage.setItem("castla_favorites", JSON.stringify(favorites));
      showLauncherNotice(`${app.label} removed from Favorites.`);
    } else if (sourceCat === "PAIR") {
      window.appPairs = window.appPairs.filter(
        (p) => !(p.left === app.left && p.right === app.right),
      );
      localStorage.setItem("castla_app_pairs", JSON.stringify(window.appPairs));
      let favorites = getFavorites().filter((p) => p !== pkg);
      localStorage.setItem("castla_favorites", JSON.stringify(favorites));
      showLauncherNotice("App Pair dissolved.");
    } else if (sourceCat === "AUTORUN") {
      const leftPkg = localStorage.getItem("castla_autorun_primary");
      const rightPkg = localStorage.getItem("castla_autorun_secondary");
      if (leftPkg === pkg) localStorage.removeItem("castla_autorun_primary");
      if (rightPkg === pkg) localStorage.removeItem("castla_autorun_secondary");
      showLauncherNotice(`${app.label} removed from Auto-run.`);
    }
  }
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  handleDragEnd,
  cancelDrag,
  triggerDropZoneAction,
});

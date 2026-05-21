// English comment: Main launcher app grid renderer for Castla Web Client.
// Maintains 100% structural and functional integrity under the 300-line constraint.

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

function renderLauncherApps(apps) {
  const {
    localStorage,
    document,
    getFavorites,
    toggleFavorite,
    toggleAutoRun,
    refreshLauncherUI,
    openAppPairEdit,
    launchApp,
    launcherContent,
    isLauncherMode,
    webLauncher,
    hideLauncherNotice,
    showLauncherNotice,
    appPairs,
    startDragging,
    handleDragMove,
    handleDragEnd
  } = window;

  let { longPressTimer, activeDragApp } = window;

  launcherContent.innerHTML = "";
  const primaryPkg = localStorage.getItem("castla_autorun_primary");
  const secondaryPkg = localStorage.getItem("castla_autorun_secondary");

  const favoritesList = getFavorites();
  const grouped = {};

  // Prepend Auto-run category group at the very top if any exist
  if (primaryPkg || secondaryPkg) {
    grouped["AUTORUN"] = { title: "Auto-run", color: "#00E5FF", items: [] };
  }

  // Prepend Favorites category group right below Auto-run if any exist
  if (favoritesList.length > 0) {
    grouped["FAVORITES"] = {
      title: "Favorites",
      color: "#FFD700",
      items: [],
    };
  }

  // Add App Pairs category group if any exist
  if (appPairs.length > 0) {
    grouped["PAIR"] = { title: "App Pairs", color: "#E040FB", items: [] };
  }

  grouped["NAVIGATION"] = {
    title: "Navigation",
    color: "#4CAF50",
    items: [],
  };
  grouped["VIDEO"] = { title: "Video", color: "#FF5722", items: [] };
  grouped["MUSIC"] = { title: "Music", color: "#9C27B0", items: [] };
  grouped["OTHER"] = { title: "Apps", color: "#9E9E9E", items: [] };

  // Check if there is a matching App Pair for the auto-run configuration
  const matchingAutorunPair = apps.find(
    (app) =>
      app.isPair &&
      ((app.left === primaryPkg && app.right === secondaryPkg) ||
        (app.left === secondaryPkg && app.right === primaryPkg)),
  );

  apps.forEach((app) => {
    if (favoritesList.includes(app.packageName)) {
      grouped["FAVORITES"]?.items.push(app);
    }
    if (app.isPair) {
      if (app === matchingAutorunPair) {
        grouped["AUTORUN"]?.items.push(app);
      }
      grouped["PAIR"]?.items.push(app);
      return;
    }
    if (
      !matchingAutorunPair &&
      (app.packageName === primaryPkg || app.packageName === secondaryPkg)
    ) {
      grouped["AUTORUN"]?.items.push(app);
    }
    if (grouped[app.category]) grouped[app.category].items.push(app);
    else grouped["OTHER"].items.push(app);
  });

  Object.keys(grouped).forEach((key) => {
    const group = grouped[key];
    if (group.items.length === 0) return;

    const section = document.createElement("div");
    section.className = "category-section";

    const header = document.createElement("div");
    header.className = "category-header";
    const bar = document.createElement("div");
    bar.className = "category-bar";
    bar.style.backgroundColor = group.color;
    const title = document.createElement("div");
    title.className = "category-title";
    title.textContent = group.title;

    header.appendChild(bar);
    header.appendChild(title);
    section.appendChild(header);

    const grid = document.createElement("div");
    grid.className = "app-grid";

    group.items.forEach((app) => {
      const cell = document.createElement("div");
      cell.className = "app-cell";
      cell.setAttribute("data-package", app.packageName);
      cell.setAttribute("data-source-category", key);

      const iconWrapper = document.createElement("div");

      if (app.isPair) {
        iconWrapper.className = "app-pair-icon-wrapper";

        const leftIcon = document.createElement("img");
        leftIcon.className = "app-pair-icon-left";
        leftIcon.src = `/api/icon?pkg=${app.left}`;
        leftIcon.loading = "lazy";
        iconWrapper.appendChild(leftIcon);

        const rightIcon = document.createElement("img");
        rightIcon.className = "app-pair-icon-right";
        rightIcon.src = `/api/icon?pkg=${app.right}`;
        rightIcon.loading = "lazy";
        iconWrapper.appendChild(rightIcon);

        if (key === "FAVORITES") {
          const delBadge = document.createElement("div");
          delBadge.className = "app-star active";
          delBadge.style.color = "#F44336";
          delBadge.style.right = "-8px";
          delBadge.style.top = "-6px";
          delBadge.style.zIndex = "15";
          delBadge.style.fontSize = "14px";
          delBadge.style.textShadow = "0 1px 3px rgba(0,0,0,0.9)";
          delBadge.innerHTML = "🗑️";
          delBadge.addEventListener("click", (e) => {
            e.stopPropagation();
            let favorites = getFavorites().filter(
              (p) => p !== app.packageName,
            );
            localStorage.setItem(
              "castla_favorites",
              JSON.stringify(favorites),
            );
            showLauncherNotice(`${app.label} removed from Favorites.`);
            refreshLauncherUI();
          });
          iconWrapper.appendChild(delBadge);
        } else if (key === "AUTORUN") {
          const delBadge = document.createElement("div");
          delBadge.className = "app-star active";
          delBadge.style.color = "#F44336";
          delBadge.style.right = "-8px";
          delBadge.style.top = "-6px";
          delBadge.style.zIndex = "15";
          delBadge.style.fontSize = "14px";
          delBadge.style.textShadow = "0 1px 3px rgba(0,0,0,0.9)";
          delBadge.innerHTML = "🗑️";
          delBadge.addEventListener("click", (e) => {
            e.stopPropagation();
            localStorage.removeItem("castla_autorun_primary");
            localStorage.removeItem("castla_autorun_secondary");
            showLauncherNotice("Auto-run apps removed.");
            refreshLauncherUI();
          });
          iconWrapper.appendChild(delBadge);
        } else {
          // Add an elegant edit gear button
          const editBtn = document.createElement("div");
          editBtn.className = "app-star active";
          editBtn.style.color = "#00E5FF";
          editBtn.style.right = "-8px";
          editBtn.style.top = "-6px";
          editBtn.style.zIndex = "15";
          editBtn.style.textShadow =
            "0 1px 3px rgba(0,0,0,0.9), 0 0 2px rgba(0,0,0,0.9)";
          editBtn.innerHTML = "⚙️";
          editBtn.addEventListener("click", (e) => {
            e.stopPropagation();
            openAppPairEdit(app);
          });
          iconWrapper.appendChild(editBtn);
        }
      } else {
        iconWrapper.className = "app-icon-wrapper";

        const icon = document.createElement("img");
        icon.className = "app-icon";
        icon.src = `/api/icon?pkg=${app.packageName}`;
        icon.loading = "lazy";
        iconWrapper.appendChild(icon);

        if (key === "FAVORITES") {
          // Show direct delete badge instead of toggle buttons
          const delBadge = document.createElement("div");
          delBadge.className = "app-star active";
          delBadge.style.color = "#F44336";
          delBadge.style.right = "-8px";
          delBadge.style.top = "-6px";
          delBadge.style.zIndex = "15";
          delBadge.style.fontSize = "14px";
          delBadge.style.textShadow = "0 1px 3px rgba(0,0,0,0.9)";
          delBadge.innerHTML = "🗑️";
          delBadge.addEventListener("click", (e) => {
            e.stopPropagation();
            let favorites = getFavorites().filter(
              (p) => p !== app.packageName,
            );
            localStorage.setItem(
              "castla_favorites",
              JSON.stringify(favorites),
            );
            showLauncherNotice(`${app.label} removed from Favorites.`);
            refreshLauncherUI();
          });
          iconWrapper.appendChild(delBadge);
        } else if (key === "AUTORUN") {
          // Show direct delete badge instead of toggle buttons
          const delBadge = document.createElement("div");
          delBadge.className = "app-star active";
          delBadge.style.color = "#F44336";
          delBadge.style.right = "-8px";
          delBadge.style.top = "-6px";
          delBadge.style.zIndex = "15";
          delBadge.style.fontSize = "14px";
          delBadge.style.textShadow = "0 1px 3px rgba(0,0,0,0.9)";
          delBadge.innerHTML = "🗑️";
          delBadge.addEventListener("click", (e) => {
            e.stopPropagation();
            const leftPkg = localStorage.getItem("castla_autorun_primary");
            const rightPkg = localStorage.getItem("castla_autorun_secondary");
            if (leftPkg === app.packageName)
              localStorage.removeItem("castla_autorun_primary");
            if (rightPkg === app.packageName)
              localStorage.removeItem("castla_autorun_secondary");
            showLauncherNotice(`${app.label} removed from Auto-run.`);
            refreshLauncherUI();
          });
          iconWrapper.appendChild(delBadge);
        } else {
          // Favorite star button
          const star = document.createElement("div");
          const isFav = favoritesList.includes(app.packageName);
          star.className = `app-star ${isFav ? "active" : ""}`;
          star.innerHTML = "&#9733;";

          star.addEventListener("click", (e) => {
            e.stopPropagation(); // Prevent parent launching event
            toggleFavorite(app.packageName);
          });
          iconWrapper.appendChild(star);

          // Auto-run bolt button
          const bolt = document.createElement("div");
          const pPkg = localStorage.getItem("castla_autorun_primary");
          const sPkg = localStorage.getItem("castla_autorun_secondary");

          let boltClass = "app-bolt";
          if (pPkg === app.packageName) {
            boltClass += " active primary";
          } else if (sPkg === app.packageName) {
            boltClass += " active secondary";
          }
          bolt.className = boltClass;
          bolt.innerHTML = "&#9889;";

          bolt.addEventListener("click", (e) => {
            e.stopPropagation(); // Prevent parent launching event
            toggleAutoRun(app.packageName);
          });
          iconWrapper.appendChild(bolt);
        }
      }

      const label = document.createElement("div");
      label.className = "app-label";
      label.textContent = app.label;

      cell.appendChild(iconWrapper);
      cell.appendChild(label);

      // Long-press Drag and Drop pointer events
      let startX = 0,
        startY = 0;
      let isPointerDown = false;
      let wasDragging = false;

      cell.addEventListener("pointerdown", (e) => {
        if (e.button !== 0) return;
        isPointerDown = true;
        wasDragging = false;
        startX = e.clientX;
        startY = e.clientY;

        cell.setPointerCapture(e.pointerId);

        window.longPressTimer = setTimeout(() => {
          if (isPointerDown) {
            startDragging(app, cell, e);
          }
        }, 1000);
      });

      cell.addEventListener("pointermove", (e) => {
        if (!isPointerDown) return;

        const dist = Math.hypot(e.clientX - startX, e.clientY - startY);
        if (dist > 18 && !window.activeDragApp) {
          clearTimeout(window.longPressTimer);
        }

        if (window.activeDragApp) {
          handleDragMove(e.clientX, e.clientY);
        }
      });

      const endPointerHandler = (e) => {
        if (!isPointerDown) return;
        isPointerDown = false;
        clearTimeout(window.longPressTimer);
        try {
          cell.releasePointerCapture(e.pointerId);
        } catch (_) {}

        if (window.activeDragApp) {
          wasDragging = true;
          handleDragEnd(e.clientX, e.clientY);
          // Reset wasDragging after 100ms so that if the pointer was released outside the cell
          // (which triggers no click event on the cell), the cell click is not permanently blocked.
          setTimeout(() => {
            wasDragging = false;
          }, 100);
        }
      };

      cell.addEventListener("pointerup", endPointerHandler);
      cell.addEventListener("pointercancel", endPointerHandler);

      // Launch or Edit Double Click
      cell.addEventListener("dblclick", () => {
        if (app.isPair) {
          openAppPairEdit(app);
        }
      });

      cell.addEventListener("click", () => {
        if (wasDragging) {
          wasDragging = false;
          return;
        }
        launchApp(app, false);
      });

      grid.appendChild(cell);
    });

    section.appendChild(grid);
    launcherContent.appendChild(section);
  });

  hideLauncherNotice();
  launcherContent.style.display = "block";
  if (isLauncherMode) {
    webLauncher.classList.remove("hidden");
  }
  window.dispatchEvent(new Event("launcher-ready"));
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  getPairPseudoApps,
  renderLauncherApps
});

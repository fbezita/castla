// English comment: Sidebar split launcher grid DOM rendering engine for Castla Web Client.
// Strictly respects 100% functional integrity and keeps code within the 300-line limit.

function renderSplitLauncherApps(apps) {
  try {
    // ### 수정 시작 ###
    // Add strict parameter guard and wrap inside try-catch to prevent rendering crash from blocking launcher execution.
    const {
      document,
      splitAppList,
      getPairPseudoApps,
      getFavorites,
      openAppPairEdit,
      toggleFavorite,
      toggleAutoRun,
      startDragging,
      handleDragMove,
      handleDragEnd,
      launchAppPair,
      state,
      launchApp,
      splitDrawer,
      homeBtn
    } = window;

    if (!splitAppList) return;
    splitAppList.innerHTML = "";

    if (!Array.isArray(apps)) {
      console.warn("[LauncherRender] Provided apps is not a valid array, defaulting to empty.");
      apps = [];
    }
    // ### 수정 끝 ###

    const singleApps = apps.filter((app) => !app.isPair);
    const pairPseudoApps = getPairPseudoApps(apps);
    const allDisplayApps = [...singleApps, ...pairPseudoApps];

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

    // Prepend App Pairs category group if any exist
    if (pairPseudoApps.length > 0) {
      grouped["PAIR"] = { title: "App Pairs", color: "#00E5FF", items: [] };
    }

    grouped["NAVIGATION"] = {
      title: "Navigation",
      color: "#4CAF50",
      items: [],
    };
    grouped["VIDEO"] = { title: "Video", color: "#FF5722", items: [] };
    grouped["MUSIC"] = { title: "Music", color: "#9C27B0", items: [] };
    grouped["OTHER"] = { title: "Apps", color: "#9E9E9E", items: [] };

    allDisplayApps.forEach((app) => {
      if (app.packageName === primaryPkg || app.packageName === secondaryPkg) {
        grouped["AUTORUN"]?.items.push(app);
      }
      if (favoritesList.includes(app.packageName)) {
        grouped["FAVORITES"]?.items.push(app);
      }
      if (app.isPair) {
        grouped["PAIR"]?.items.push(app);
      } else {
        if (grouped[app.category]) grouped[app.category].items.push(app);
        else grouped["OTHER"].items.push(app);
      }
    });

    Object.keys(grouped).forEach((key) => {
      const group = grouped[key];
      if (group.items.length === 0) return;

      const section = document.createElement("div");
      section.className = "split-category-section";

      const header = document.createElement("div");
      header.className = "split-category-header";
      const bar = document.createElement("div");
      bar.className = "split-category-bar";
      bar.style.backgroundColor = group.color;
      const title = document.createElement("div");
      title.className = "split-category-title";
      title.textContent = group.title;
      header.appendChild(bar);
      header.appendChild(title);
      section.appendChild(header);

      const items = document.createElement("div");
      items.className = "split-category-items";

      group.items.forEach((app) => {
        const cell = document.createElement("div");
        cell.className = "split-app-item";
        cell.setAttribute("data-package", app.packageName);
        cell.setAttribute("data-source-category", key);

        if (app.isPair) {
          const iconWrapper = document.createElement("div");
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

          cell.appendChild(iconWrapper);
        } else {
          const icon = document.createElement("img");
          icon.className = "split-app-icon";
          icon.src = `/api/icon?pkg=${app.packageName}`;
          icon.loading = "lazy";
          cell.appendChild(icon);
        }

        const label = document.createElement("div");
        label.className = "split-app-label";
        label.textContent = app.label;
        if (app.isPair) {
          label.style.color = "#00E5FF";
        }
        cell.appendChild(label);

        if (app.isPair) {
          // Curved swap / pair edit gear button
          const editBtn = document.createElement("div");
          editBtn.className = "split-app-star active";
          editBtn.style.color = "#00E5FF";
          editBtn.style.fontSize = "14px";
          editBtn.innerHTML = "⚙️";

          editBtn.addEventListener("click", (e) => {
            e.stopPropagation(); // Prevent parent launching event
            openAppPairEdit(app);
          });
          editBtn.addEventListener("pointerdown", (e) => e.stopPropagation());
          editBtn.addEventListener("pointerup", (e) => e.stopPropagation());
          cell.appendChild(editBtn);
        } else {
          // Favorite star button
          const star = document.createElement("div");
          const isFav = favoritesList.includes(app.packageName);
          star.className = `split-app-star ${isFav ? "active" : ""}`;
          star.innerHTML = "&#9733;";

          star.addEventListener("click", (e) => {
            e.stopPropagation(); // Prevent parent launching event
            toggleFavorite(app.packageName);
          });
          star.addEventListener("pointerdown", (e) => e.stopPropagation());
          star.addEventListener("pointerup", (e) => e.stopPropagation());
          cell.appendChild(star);

          // Auto-run bolt button
          const bolt = document.createElement("div");
          const pPkg = localStorage.getItem("castla_autorun_primary");
          const sPkg = localStorage.getItem("castla_autorun_secondary");

          let boltClass = "split-app-bolt";
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
          bolt.addEventListener("pointerdown", (e) => e.stopPropagation());
          bolt.addEventListener("pointerup", (e) => e.stopPropagation());
          cell.appendChild(bolt);
        }

        // Long-press Drag and Drop pointer events for split launcher sidebar app items
        let startX = 0,
          startY = 0;
        let isPointerDown = false;
        let wasDragging = false;

        cell.addEventListener("pointerdown", (e) => {
          if (e.button !== 0) return;
          // Ignore clicks on toggle icons or edit gears
          if (
            e.target.classList.contains("split-app-star") ||
            e.target.classList.contains("split-app-bolt") ||
            e.target.innerHTML === "⚙️"
          ) {
            return;
          }
          isPointerDown = true;
          wasDragging = false;
          startX = e.clientX;
          startY = e.clientY;

          try {
            cell.setPointerCapture(e.pointerId);
          } catch (_) {}

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
            setTimeout(() => {
              wasDragging = false;
            }, 100);
          }
        };

        cell.addEventListener("pointerup", endPointerHandler);
        cell.addEventListener("pointercancel", endPointerHandler);

        cell.addEventListener("click", (e) => {
          // Double safeguard: Block event propagation if hitting toggle icons or edit gears
          if (
            e.target.classList.contains("split-app-star") ||
            e.target.classList.contains("split-app-bolt") ||
            e.target.innerHTML === "⚙️"
          ) {
            return;
          }
          if (wasDragging) {
            wasDragging = false;
            return;
          }
          if (app.isPair) {
            launchAppPair(app.left, app.right);
          } else {
            // 🔴 낱개 앱 일반 클릭 시에는 단독 실행이므로 반대쪽 실행 정보를 SSOT에 맞춰 비워줍니다!
            state.right = null;
            launchApp(app, false);
          }
          splitDrawer.classList.remove("open");
        });

        items.appendChild(cell);
      });

      section.appendChild(items);
      splitAppList.appendChild(section);
    });
  } catch (renderError) {
    console.error("[LauncherRender] Exception caught during split launcher rendering:", renderError);
  }
}

// Bind methods globally to window scope for seamless multi-module integration
Object.assign(window, {
  renderSplitLauncherApps
});

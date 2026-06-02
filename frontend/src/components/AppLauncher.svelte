<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import type { StreamRuntime } from "../runtime/StreamRuntime";
  import { compositorStore } from "../stores/compositorStore";
  import type { PaneId } from "../protocol";

  // Modular components imported for robust Svelte 5 structure
  import LauncherTabs from "./LauncherTabs.svelte";
  import AppRow from "./AppRow.svelte";
  import CategoryAccordion from "./CategoryAccordion.svelte";
  import DragDropOverlay from "./DragDropOverlay.svelte";
  import PairDialog from "./PairDialog.svelte";

  let { runtime } = $props<{ runtime: StreamRuntime }>();

  // Types definitions
  interface AppInfo {
    packageName: string;
    label: string;
    componentName?: string;
    category?: string;
    isWeb?: boolean;
    left?: string;
    right?: string;
    isPair?: boolean;
  }

  interface AppPairRecord {
    left: string;
    right: string;
  }

  interface RecentLaunchRecord {
    packageName: string;
    lastUsedAt: number;
  }

  type LaunchHubTab = "autorun" | "starred" | "recent" | "browse";
  type DropZone = "favorite" | "autorun" | "primary" | "secondary" | "remove" | "";
  type GestureState = "idle" | "pressing" | "dragging";

  const APP_CACHE_KEY = "castla_cached_apps_v1";
  const AUTORUN_SESSION_KEY = "castla_autorun_done";
  const RECENT_APPS_KEY = "castla_recent_apps_v1";
  const ACTIVE_TAB_KEY = "castla_launch_hub_active_tab";
  const MAX_RECENT_APPS = 8;

  const groups = [
    ["PAIR", "App Pairs", "#00e5ff"],
    ["NAVIGATION", "Navigation", "#49d66d"],
    ["VIDEO", "Video", "#ff6b43"],
    ["MUSIC", "Music", "#b46cff"],
    ["OTHER", "All Apps", "#9ea3ad"],
  ] as const;

  // Reactivity State Declarations using Svelte 5 $state Rune
  let apps = $state<AppInfo[]>(readCachedApps());
  let loading = $state(readCachedApps().length === 0);
  let error = $state("");
  let drawerOpen = $state(true);
  let drawerElement = $state<HTMLElement | null>(null);
  let drawerListElement = $state<HTMLDivElement | null>(null);
  let search = $state("");
  let activeTab = $state<LaunchHubTab>(readActiveTab());
  let expandedCategory = $state("");
  let favorites = $state<string[]>(readArray("castla_favorites"));
  let recentEntries = $state<RecentLaunchRecord[]>(readRecentLaunches());
  let appPairs = $state<AppPairRecord[]>(readPairs());
  let primaryAutorun = $state(localStorage.getItem("castla_autorun_primary") ?? "");
  let secondaryAutorun = $state(localStorage.getItem("castla_autorun_secondary") ?? "");
  let notice = $state("");
  let noticeTimer = $state<number | undefined>(undefined);
  let launchedOnce = $state(false);
  let autoClosePending = $state(false);

  // Gesture Tracker States
  let pressTimer = $state(0);
  let pressedApp = $state<AppInfo | null>(null);
  let draggingApp = $state<AppInfo | null>(null);
  let dragX = $state(0);
  let dragY = $state(0);
  let dropZone = $state<DropZone>("");
  let drawerDimmed = $state(false);
  let pressStartX = $state(0);
  let pressStartY = $state(0);
  let pressMoved = $state(false);
  let gestureState = $state<GestureState>("idle");
  let dragSourceElement = $state<HTMLElement | null>(null);
  let previousDrawerTouchAction = $state("");
  let activePointerId = $state<number | null>(null);
  let previousBodyTouchAction = $state("");
  let previousHtmlTouchAction = $state("");
  let previousBodyOverscrollBehavior = $state("");
  let previousHtmlOverscrollBehavior = $state("");
  let pairTarget = $state<AppInfo | null>(null);
  let pairMenuOpen = $state("");
  let editingPair = $state<AppInfo | null>(null);
  let drawerRevision = $state(0);
  let pairTargetTimer = $state<number | undefined>(undefined);
  let pairTargetCandidate = $state("");
  let autoScrollVelocity = $state(0);
  let autoScrollFrame = $state<number | undefined>(undefined);

  // Lifecycle bindings
  onMount(() => {
    if (apps.length > 0) {
      runAutorunOnce();
    }
    loadApps();
  });

  onDestroy(() => {
    window.clearTimeout(pressTimer);
    window.clearTimeout(noticeTimer);
    window.clearTimeout(pairTargetTimer);
    stopAutoScrollDrawer();
    detachDragListeners();
  });

  // Derived state calculations using Svelte 5 $derived Rune
  let hasVisibleStream = $derived(
    Array.from($compositorStore.viewports.values()).some((viewport) => viewport.committed)
  );

  let pairApps = $derived.by(() => {
    void drawerRevision;
    return getPairApps(appPairs, apps);
  });

  let searchableApps = $derived([...pairApps, ...apps]);

  let displayApps = $derived(
    searchableApps.filter((app) =>
      app.label.toLowerCase().includes(search.trim().toLowerCase())
    )
  );

  let starredApps = $derived(
    favorites
      .map((packageName) => displayApps.find((app) => app.packageName === packageName))
      .filter(Boolean) as AppInfo[]
  );

  let recentApps = $derived(
    recentEntries
      .map((entry) => displayApps.find((app) => app.packageName === entry.packageName))
      .filter(Boolean) as AppInfo[]
  );

  let autorunApps = $derived(getAutorunApps(displayApps, apps, pairApps));

  let groupedApps = $derived(
    groups
      .map(([key, title, color]) => ({
        key,
        title,
        color,
        items: displayApps.filter((app) => belongsToGroup(app, key, favorites)),
      }))
      .filter((group) => group.items.length > 0)
  );

  let browseGroups = $derived(groupedApps);

  let activePanelApps = $derived.by(() => {
    if (activeTab === "autorun") return autorunApps;
    if (activeTab === "starred") return starredApps;
    if (activeTab === "recent") return recentApps;
    return [];
  });

  let activePanelEmpty = $derived.by(() => {
    if (activeTab === "autorun") return "Set one app or app pair to auto-run on connect.";
    if (activeTab === "starred") return "Star apps to pin them in your launcher lane.";
    if (activeTab === "recent") return "Launch an app once to build your recent history.";
    return "";
  });

  // Effects bindings using Svelte 5 $effect Rune
  $effect(() => {
    if (autoClosePending && hasVisibleStream) {
      requestAnimationFrame(() => {
        drawerOpen = false;
        autoClosePending = false;
      });
    }
  });

  $effect(() => {
    if (activeTab === "browse") {
      if (search.trim().length > 0) {
        const stillVisible = browseGroups.some((group) => group.key === expandedCategory);
        if (!stillVisible) expandedCategory = browseGroups[0]?.key ?? "";
      } else if (!browseGroups.some((group) => group.key === expandedCategory)) {
        expandedCategory = "";
      }
    }
  });

  // Core Data Actions
  async function loadApps() {
    try {
      const response = await fetch("/api/apps");
      if (!response.ok) throw new Error(`apps ${response.status}`);
      const data = await response.json();
      apps = Array.isArray(data.apps) ? data.apps : [];
      localStorage.setItem(APP_CACHE_KEY, JSON.stringify(apps));
      touchDrawer();
      runAutorunOnce();
      error = "";
    } catch (err) {
      if (apps.length === 0) {
        error = err instanceof Error ? err.message : String(err);
      }
    } finally {
      loading = false;
    }
  }

  function belongsToGroup(app: AppInfo, group: string, favoritePackages: string[]) {
    if (group === "PAIR") return app.isPair === true;
    if (group === "FAVORITES") return favoritePackages.includes(app.packageName);
    if (group === "OTHER") return !["NAVIGATION", "VIDEO", "MUSIC"].includes(app.category ?? "");
    return app.category === group;
  }

  function launch(app: AppInfo, pane: PaneId = "primary") {
    launchedOnce = true;
    autoClosePending = true;
    recordRecentLaunch(app.packageName);
    if (pane === "primary") setSingle("primary");
    else setSplit(true);
    runtime.launchApp(
      app.packageName,
      pane,
      app.componentName,
      app.category === "VIDEO" || app.isWeb === true,
    );
    runtime.requestKeyframe(pane);
    drawerOpen = true;
    toast(`${app.label} launching`);

    setTimeout(() => {
      if (autoClosePending && !hasVisibleStream) {
        drawerOpen = true;
      }
    }, 8000);
  }

  function launchPair(left: AppInfo, right: AppInfo) {
    launchedOnce = true;
    autoClosePending = true;
    recordRecentLaunch(`pair:${left.packageName}:${right.packageName}`);
    setSplit(true);
    runtime.launchApp(
      left.packageName,
      "primary",
      left.componentName,
      left.category === "VIDEO" || left.isWeb === true,
    );
    runtime.requestKeyframe("primary");
    setTimeout(() => {
      runtime.launchApp(
        right.packageName,
        "secondary",
        right.componentName,
        right.category === "VIDEO" || right.isWeb === true,
      );
      runtime.requestKeyframe("secondary");
    }, 260);
    drawerOpen = true;
    toast(`${left.label} + ${right.label}`);

    setTimeout(() => {
      if (autoClosePending && !hasVisibleStream) {
        drawerOpen = true;
      }
    }, 8000);
  }

  function activateApp(app: AppInfo) {
    if (app.isPair) {
      const left = app.left ? apps.find((c) => c.packageName === app.left) : undefined;
      const right = app.right ? apps.find((c) => c.packageName === app.right) : undefined;
      if (left && right) {
        launchPair(left, right);
        return;
      }
    }
    launch(app, "primary");
  }

  function setSingle(pane: PaneId) {
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      const primary = viewports.get("primary") ?? {
        pane: "primary", width: 1280, height: 720, committed: false, generation: 0, visible: true
      };
      const secondary = viewports.get("secondary") ?? {
        pane: "secondary", width: 1280, height: 720, committed: false, generation: 0, visible: false
      };
      viewports.set("primary", { ...primary, visible: pane === "primary" });
      viewports.set("secondary", { ...secondary, visible: pane === "secondary" });
      return { ...state, viewports, layoutMode: "single" };
    });
  }

  function setSplit(active: boolean) {
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      const primary = viewports.get("primary") ?? {
        pane: "primary", width: 1280, height: 720, committed: false, generation: 0, visible: true
      };
      const secondary = viewports.get("secondary") ?? {
        pane: "secondary", width: 1280, height: 720, committed: false, generation: 0, visible: false
      };
      viewports.set("primary", { ...primary, visible: true });
      viewports.set("secondary", { ...secondary, visible: active });
      return { ...state, viewports, layoutMode: active ? "split" : "single" };
    });
  }

  function toggleFavorite(packageName: string) {
    favorites = favorites.includes(packageName)
      ? favorites.filter((pkg) => pkg !== packageName)
      : [...favorites, packageName];
    localStorage.setItem("castla_favorites", JSON.stringify(favorites));
    touchDrawer();
  }

  function recordRecentLaunch(packageName: string) {
    recentEntries = [
      { packageName, lastUsedAt: Date.now() },
      ...recentEntries.filter((entry) => entry.packageName !== packageName),
    ].slice(0, MAX_RECENT_APPS);
    localStorage.setItem(RECENT_APPS_KEY, JSON.stringify(recentEntries));
    touchDrawer();
  }

  function getPairApps(pairs: AppPairRecord[], availableApps: AppInfo[]): AppInfo[] {
    const result: AppInfo[] = [];
    for (const pair of pairs) {
      const leftApp = availableApps.find((app) => app.packageName === pair.left);
      const rightApp = availableApps.find((app) => app.packageName === pair.right);
      if (!leftApp || !rightApp) continue;
      result.push({
        packageName: `pair:${pair.left}:${pair.right}`,
        label: `${leftApp.label} + ${rightApp.label}`,
        category: "PAIR",
        isPair: true,
        left: pair.left,
        right: pair.right,
      });
    }
    return result;
  }

  function createPair(source: AppInfo, target: AppInfo) {
    if (source.isPair || target.isPair) return;
    if (source.packageName === target.packageName) {
      toast("Choose a different app");
      return;
    }
    const exists = appPairs.some(
      (pair) =>
        (pair.left === source.packageName && pair.right === target.packageName) ||
        (pair.left === target.packageName && pair.right === source.packageName),
    );
    if (exists) {
      toast("This App Pair already exists");
      return;
    }
    appPairs = [...appPairs, { left: source.packageName, right: target.packageName }];
    localStorage.setItem("castla_app_pairs", JSON.stringify(appPairs));
    touchDrawer();
    openPairEdit({
      packageName: `pair:${source.packageName}:${target.packageName}`,
      label: `${source.label} + ${target.label}`,
      category: "PAIR",
      isPair: true,
      left: source.packageName,
      right: target.packageName,
    });
    toast(`${source.label} + ${target.label}`);
  }

  function openPairEdit(app: AppInfo) {
    if (!app.left || !app.right) return;
    editingPair = { ...app };
    pairMenuOpen = "";
  }

  function swapEditingPair() {
    if (!editingPair?.left || !editingPair?.right) return;
    editingPair = { ...editingPair, left: editingPair.right, right: editingPair.left };
  }

  function persistPair(app: AppInfo, previousLeft?: string, previousRight?: string) {
    if (!app.left || !app.right) return;
    const oldLeft = previousLeft ?? app.left;
    const oldRight = previousRight ?? app.right;
    const nextPair = { left: app.left, right: app.right };
    const index = appPairs.findIndex(
      (pair) =>
        (pair.left === oldLeft && pair.right === oldRight) ||
        (pair.left === oldRight && pair.right === oldLeft),
    );
    if (index >= 0) {
      appPairs = appPairs.map((pair, idx) => (idx === index ? nextPair : pair));
    } else {
      appPairs = [...appPairs, nextPair];
    }
    localStorage.setItem("castla_app_pairs", JSON.stringify(appPairs));
    touchDrawer();
  }

  function saveEditingPair() {
    if (!editingPair?.left || !editingPair?.right) return;
    const original = getPairApps(appPairs, apps).find((app) => app.packageName === editingPair!.packageName);
    persistPair(editingPair, original?.left, original?.right);
    editingPair = null;
    toast("App Pair updated");
  }

  function removePair(app: AppInfo) {
    if (!app.left || !app.right) return;
    appPairs = appPairs.filter(
      (pair) => !((pair.left === app.left && pair.right === app.right) || (pair.left === app.right && pair.right === app.left))
    );
    localStorage.setItem("castla_app_pairs", JSON.stringify(appPairs));
    favorites = favorites.filter((pkg) => pkg !== app.packageName);
    localStorage.setItem("castla_favorites", JSON.stringify(favorites));
    if (primaryAutorun === app.left && secondaryAutorun === app.right) {
      primaryAutorun = "";
      secondaryAutorun = "";
      updateStorage("castla_autorun_primary", primaryAutorun);
      updateStorage("castla_autorun_secondary", secondaryAutorun);
    }
    touchDrawer();
    if (editingPair?.packageName === app.packageName) editingPair = null;
    pairMenuOpen = "";
    toast("App Pair dissolved");
  }

  function toggleAutorun(packageName: string) {
    if (primaryAutorun === packageName || secondaryAutorun === packageName) {
      if (primaryAutorun === packageName) primaryAutorun = "";
      if (secondaryAutorun === packageName) secondaryAutorun = "";
    } else if (!primaryAutorun) {
      primaryAutorun = packageName;
    } else {
      secondaryAutorun = packageName;
    }
    updateStorage("castla_autorun_primary", primaryAutorun);
    updateStorage("castla_autorun_secondary", secondaryAutorun);
    touchDrawer();
  }

  function isAutorunPair(app: AppInfo) {
    return Boolean(
      app.isPair && app.left && app.right && primaryAutorun === app.left && secondaryAutorun === app.right
    );
  }

  function toggleAutorunForApp(app: AppInfo) {
    if (app.isPair && app.left && app.right) {
      if (isAutorunPair(app)) {
        primaryAutorun = "";
        secondaryAutorun = "";
      } else {
        primaryAutorun = app.left;
        secondaryAutorun = app.right;
      }
      updateStorage("castla_autorun_primary", primaryAutorun);
      updateStorage("castla_autorun_secondary", secondaryAutorun);
      touchDrawer();
      return;
    }
    toggleAutorun(app.packageName);
  }

  function getAutorunApps(visibleApps: AppInfo[], availableApps: AppInfo[], availablePairs: AppInfo[]): AppInfo[] {
    const items: AppInfo[] = [];
    if (primaryAutorun && secondaryAutorun) {
      const pair = availablePairs.find((app) => app.left === primaryAutorun && app.right === secondaryAutorun);
      if (pair) {
        const visiblePair = visibleApps.find((app) => app.packageName === pair.packageName);
        if (visiblePair) return [visiblePair];
      }
    }
    if (primaryAutorun) {
      const primary = visibleApps.find((app) => app.packageName === primaryAutorun);
      if (primary) items.push(primary);
    }
    if (secondaryAutorun && secondaryAutorun !== primaryAutorun) {
      const secondary = visibleApps.find((app) => app.packageName === secondaryAutorun);
      if (secondary) items.push(secondary);
    }
    return items;
  }

  function getRecentMeta(packageName: string) {
    const entry = recentEntries.find((item) => item.packageName === packageName);
    return entry ? formatRelativeTime(entry.lastUsedAt) : "";
  }

  function formatRelativeTime(timestamp: number) {
    const elapsedMs = Math.max(0, Date.now() - timestamp);
    const minute = 60_000;
    const hour = 60 * minute;
    const day = 24 * hour;
    if (elapsedMs < minute) return "Just now";
    if (elapsedMs < hour) return `${Math.floor(elapsedMs / minute)} min ago`;
    if (elapsedMs < day) return `${Math.floor(elapsedMs / hour)} hr ago`;
    if (elapsedMs < day * 2) return "Yesterday";
    return `${Math.floor(elapsedMs / day)} days ago`;
  }

  function selectTab(tab: LaunchHubTab) {
    activeTab = tab;
    localStorage.setItem(ACTIVE_TAB_KEY, tab);
    if (tab === "browse" && search.trim().length > 0 && !expandedCategory) {
      expandedCategory = browseGroups[0]?.key ?? "";
    }
  }

  function toggleCategory(categoryKey: string) {
    expandedCategory = expandedCategory === categoryKey ? "" : categoryKey;
  }

  function isAppAutorun(app: AppInfo) {
    return (!app.isPair && (primaryAutorun === app.packageName || secondaryAutorun === app.packageName)) || isAutorunPair(app);
  }

  // Storage and Reading Utilities
  function readRecentLaunches(): RecentLaunchRecord[] {
    try {
      const value = JSON.parse(localStorage.getItem(RECENT_APPS_KEY) ?? "[]");
      if (!Array.isArray(value)) return [];
      if (value.every((item) => typeof item === "string")) {
        return value
          .filter((item): item is string => typeof item === "string")
          .map((packageName, index) => ({ packageName, lastUsedAt: Date.now() - index * 60_000 }));
      }
      return value.filter(
        (item): item is RecentLaunchRecord =>
          item && typeof item.packageName === "string" && typeof item.lastUsedAt === "number",
      );
    } catch {
      return [];
    }
  }

  function readActiveTab(): LaunchHubTab {
    const value = localStorage.getItem(ACTIVE_TAB_KEY);
    return value === "autorun" || value === "starred" || value === "recent" || value === "browse" ? value : "autorun";
  }

  function readArray(key: string): string[] {
    try {
      const value = JSON.parse(localStorage.getItem(key) ?? "[]");
      return Array.isArray(value) ? value : [];
    } catch {
      return [];
    }
  }

  function readPairs(): AppPairRecord[] {
    try {
      const value = JSON.parse(localStorage.getItem("castla_app_pairs") ?? "[]");
      if (!Array.isArray(value)) return [];
      return value.filter(
        (pair): pair is AppPairRecord =>
          pair && typeof pair.left === "string" && typeof pair.right === "string" && pair.left !== pair.right,
      );
    } catch {
      return [];
    }
  }

  function readCachedApps(): AppInfo[] {
    try {
      const value = JSON.parse(localStorage.getItem(APP_CACHE_KEY) ?? "[]");
      return Array.isArray(value) ? value : [];
    } catch {
      return [];
    }
  }

  function updateStorage(key: string, value: string) {
    if (value) localStorage.setItem(key, value);
    else localStorage.removeItem(key);
  }

  function touchDrawer() {
    drawerRevision += 1;
  }

  function runAutorunOnce() {
    if ((window as any).castlaAutorunDone) return;
    if (sessionStorage.getItem(AUTORUN_SESSION_KEY) === "1") {
      (window as any).castlaAutorunDone = true;
      return;
    }
    if (hasVisibleStream) {
      (window as any).castlaAutorunDone = true;
      sessionStorage.setItem(AUTORUN_SESSION_KEY, "1");
      return;
    }
    (window as any).castlaAutorunDone = true;
    sessionStorage.setItem(AUTORUN_SESSION_KEY, "1");
    const primary = apps.find((app) => app.packageName === primaryAutorun);
    const secondary = apps.find((app) => app.packageName === secondaryAutorun);
    if (primary && secondary) launchPair(primary, secondary);
    else if (primary) launch(primary, "primary");
  }

  // -------------------------------------------------------------
  // Highly-tuned Pointer Gestures Pipeline for In-vehicle Screens
  // -------------------------------------------------------------
  function startPress(event: PointerEvent, app: AppInfo, element: HTMLElement) {
    // Buttons inside elements should never trigger drag start
    const target = event.target as HTMLElement;
    if (target.closest("button")) return;
    pairMenuOpen = "";

    const pointerId = event.pointerId;
    activePointerId = pointerId;

    pressedApp = app;
    dragSourceElement = element;
    gestureState = "pressing";
    pressStartX = event.clientX;
    pressStartY = event.clientY;
    pressMoved = false;
    dragX = event.clientX;
    dragY = event.clientY;

    window.clearTimeout(pressTimer);
    // Optimized 450ms longpress threshold for brisk vehicle control response
    pressTimer = window.setTimeout(() => {
      if (gestureState !== "pressing" || !pressedApp) return;
      beginDraggingSession();
    }, 450);
  }

  function movePress(event: PointerEvent) {
    if (gestureState === "idle") return;
    if (activePointerId !== null && event.pointerId !== activePointerId) return;
    dragX = event.clientX;
    dragY = event.clientY;

    // Advanced 48px anti-jitter threshold for stable control when car shakes
    if (Math.hypot(dragX - pressStartX, dragY - pressStartY) > 48) {
      pressMoved = true;
      if (gestureState === "pressing") {
        window.clearTimeout(pressTimer);
        gestureState = "idle";
        pressedApp = null;
        dragSourceElement = null;
        activePointerId = null;
        return;
      }
    }

    if (gestureState === "dragging") {
      if (event.cancelable) {
        event.preventDefault();
      }
      updateAutoScrollVelocity(dragY);
      updateDropZone(dragX, dragY);
    }
  }

  function cancelPress(event?: PointerEvent) {
    if (gestureState === "dragging" && event && event.currentTarget !== window) {
      return;
    }
    window.clearTimeout(pressTimer);
    resetGestureState();
  }

  function endPress(event?: PointerEvent) {
    window.clearTimeout(pressTimer);
    if (gestureState === "dragging" && draggingApp) {
      if (pairTarget) {
        createPair(draggingApp, pairTarget);
      } else if (dropZone) {
        applyDrop(draggingApp, dropZone);
      }
    } else if (gestureState === "pressing" && pressedApp && !pressMoved) {
      activateApp(pressedApp);
    }

    resetGestureState();
  }

  // Update drops zone coordinates mapping with hover-stabilized pair target recognition
  function updateDropZone(x: number, y: number) {
    // If hovering inside the drawer list bounds
    if (isPointInsideDrawer(x, y)) {
      drawerDimmed = false;
      const hoveredTab = getHoveredLauncherTab(x, y);
      if (hoveredTab) {
        clearPairHoverState();
        dropZone = hoveredTab === "autorun" ? "autorun" : hoveredTab === "starred" ? "favorite" : "";
        return;
      }

      const candidate = findPairTarget(x, y);

      dropZone = "";
      if (candidate) {
        if (pairTarget?.packageName !== candidate.packageName && pairTargetCandidate !== candidate.packageName) {
          window.clearTimeout(pairTargetTimer);
          pairTarget = null;
          pairTargetCandidate = candidate.packageName;
          // Hover stabilization: only trigger merge target after hovering for 260ms
          pairTargetTimer = window.setTimeout(() => {
            pairTarget = candidate;
            pairTargetCandidate = "";
            pairTargetTimer = undefined;
          }, 260);
        }
      } else {
        clearPairHoverState();
      }
      return;
    }

    // Outside the drawer we only activate dimming while over a real drop zone.
    drawerDimmed = false;

    // Outer screen regions for launching panes or removal
    clearPairHoverState();
    dropZone = getExternalDropZone(x, y);
    drawerDimmed = dropZone !== "";
  }

  // Self-calibrating automatic vertical scroll when dragging apps
  function updateAutoScrollVelocity(y: number) {
    if (!drawerListElement || !drawerOpen) {
      stopAutoScrollDrawer();
      return;
    }
    const rect = drawerListElement.getBoundingClientRect();
    const edgeSize = 88;
    const maxStep = 22;
    if (y > rect.bottom - edgeSize && y < rect.bottom + 24) {
      const intensity = Math.min(1, (y - (rect.bottom - edgeSize)) / edgeSize);
      autoScrollVelocity = Math.ceil(maxStep * intensity * intensity);
    } else if (y < rect.top + edgeSize && y > rect.top - 24) {
      const intensity = Math.min(1, (rect.top + edgeSize - y) / edgeSize);
      autoScrollVelocity = -Math.ceil(maxStep * intensity * intensity);
    } else {
      autoScrollVelocity = 0;
    }

    if (autoScrollVelocity !== 0 && autoScrollFrame === undefined) {
      autoScrollDrawer();
    } else if (autoScrollVelocity === 0) {
      stopAutoScrollDrawer();
    }
  }

  function autoScrollDrawer() {
    if (!drawerListElement || !drawerOpen || gestureState !== "dragging" || autoScrollVelocity === 0) {
      stopAutoScrollDrawer();
      return;
    }

    drawerListElement.scrollTop += autoScrollVelocity;
    autoScrollFrame = requestAnimationFrame(autoScrollDrawer);
  }

  function stopAutoScrollDrawer() {
    autoScrollVelocity = 0;
    if (autoScrollFrame !== undefined) {
      cancelAnimationFrame(autoScrollFrame);
      autoScrollFrame = undefined;
    }
  }

  function applyDrop(app: AppInfo, zone: DropZone) {
    if (app.isPair && app.left && app.right) {
      if (zone === "autorun") {
        primaryAutorun = app.left;
        secondaryAutorun = app.right;
        updateStorage("castla_autorun_primary", primaryAutorun);
        updateStorage("castla_autorun_secondary", secondaryAutorun);
        touchDrawer();
        toast(`${app.label} set to Auto-run`);
        return;
      }
      if (zone === "remove") {
        removePair(app);
        return;
      }
    }

    if (zone === "favorite") {
      toggleFavorite(app.packageName);
      toast(favorites.includes(app.packageName) ? "Favorite updated" : "Favorite removed");
    } else if (zone === "autorun") {
      toggleAutorun(app.packageName);
      toast("Auto-run updated");
    } else if (zone === "primary") {
      launch(app, "primary");
    } else if (zone === "secondary") {
      launch(app, "secondary");
    } else if (zone === "remove") {
      favorites = favorites.filter((pkg) => pkg !== app.packageName);
      if (primaryAutorun === app.packageName) primaryAutorun = "";
      if (secondaryAutorun === app.packageName) secondaryAutorun = "";
      localStorage.setItem("castla_favorites", JSON.stringify(favorites));
      updateStorage("castla_autorun_primary", primaryAutorun);
      updateStorage("castla_autorun_secondary", secondaryAutorun);
      touchDrawer();
      toast("Removed from shortcuts");
    }
  }

  function isPointInsideDrawer(x: number, y: number): boolean {
    if (!drawerElement || !drawerOpen) return false;
    const rect = drawerElement.getBoundingClientRect();
    return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
  }

  function getHoveredLauncherTab(x: number, y: number): LaunchHubTab | null {
    const tab = document.elementFromPoint(x, y)?.closest("[data-launcher-tab]") as HTMLElement | null;
    const value = tab?.dataset.launcherTab;
    return value === "autorun" || value === "starred" || value === "recent" || value === "browse"
      ? value
      : null;
  }

  function getExternalDropZone(x: number, y: number): DropZone {
    const w = window.innerWidth;
    const h = window.innerHeight;
    const usableRight = drawerElement?.getBoundingClientRect().left ?? w;
    const topInset = 80;
    const sideInset = 20;
    const bottomInset = 20;
    const bottomZoneHeight = 120;
    const centerGap = 20;

    const removeTop = h - bottomZoneHeight - bottomInset;
    if (y >= removeTop && y <= h - bottomInset && x >= sideInset && x <= usableRight - sideInset) {
      return "remove";
    }

    const verticalBottom = removeTop - centerGap;
    if (y < topInset || y > verticalBottom) {
      return "";
    }

    const midX = usableRight / 2;
    const leftZoneRight = midX - centerGap / 2;
    const rightZoneLeft = midX + centerGap / 2;

    if (x >= sideInset && x <= leftZoneRight) {
      return "primary";
    }
    if (x >= rightZoneLeft && x <= usableRight - sideInset) {
      return "secondary";
    }
    return "";
  }

  function clearPairHoverState() {
    window.clearTimeout(pairTargetTimer);
    pairTargetTimer = undefined;
    pairTargetCandidate = "";
    pairTarget = null;
  }

  function preventTouchScroll(event: TouchEvent) {
    if (gestureState === "dragging" && event.cancelable) {
      event.preventDefault();
    }
  }

  function beginDraggingSession() {
    gestureState = "dragging";
    draggingApp = pressedApp;
    attachDragListeners();
    if (dragSourceElement && activePointerId !== null) {
      try {
        dragSourceElement.setPointerCapture(activePointerId);
      } catch {}
      dragSourceElement.style.touchAction = "none";
    }
    if (drawerListElement) {
      previousDrawerTouchAction = drawerListElement.style.touchAction;
      drawerListElement.style.touchAction = "none";
    }
    previousBodyTouchAction = document.body.style.touchAction;
    previousHtmlTouchAction = document.documentElement.style.touchAction;
    previousBodyOverscrollBehavior = document.body.style.overscrollBehavior;
    previousHtmlOverscrollBehavior = document.documentElement.style.overscrollBehavior;
    document.body.style.touchAction = "none";
    document.documentElement.style.touchAction = "none";
    document.body.style.overscrollBehavior = "none";
    document.documentElement.style.overscrollBehavior = "none";
    navigator.vibrate?.(50);
    drawerOpen = true;
    updateDropZone(dragX, dragY);
  }

  function resetGestureState() {
    window.clearTimeout(pairTargetTimer);
    detachDragListeners();
    stopAutoScrollDrawer();
    pairTargetTimer = undefined;
    pairTargetCandidate = "";
    if (dragSourceElement && activePointerId !== null) {
      try {
        dragSourceElement.releasePointerCapture(activePointerId);
      } catch {}
      dragSourceElement.style.touchAction = "";
    }
    if (drawerListElement) {
      drawerListElement.style.touchAction = previousDrawerTouchAction;
    }
    document.body.style.touchAction = previousBodyTouchAction;
    document.documentElement.style.touchAction = previousHtmlTouchAction;
    document.body.style.overscrollBehavior = previousBodyOverscrollBehavior;
    document.documentElement.style.overscrollBehavior = previousHtmlOverscrollBehavior;
    gestureState = "idle";
    pressedApp = null;
    draggingApp = null;
    dragSourceElement = null;
    previousDrawerTouchAction = "";
    previousBodyTouchAction = "";
    previousHtmlTouchAction = "";
    previousBodyOverscrollBehavior = "";
    previousHtmlOverscrollBehavior = "";
    activePointerId = null;
    dropZone = "";
    drawerDimmed = false;
    pressMoved = false;
    pairTarget = null;
  }

  function attachDragListeners() {
    window.addEventListener("pointermove", movePress, { passive: false });
    window.addEventListener("pointerup", endPress);
    window.addEventListener("pointercancel", cancelPress);
    window.addEventListener("touchmove", preventTouchScroll, { passive: false });
  }

  function detachDragListeners() {
    window.removeEventListener("pointermove", movePress);
    window.removeEventListener("pointerup", endPress);
    window.removeEventListener("pointercancel", cancelPress);
    window.removeEventListener("touchmove", preventTouchScroll);
  }

  function findPairTarget(x: number, y: number): AppInfo | null {
    const hovered = document.elementFromPoint(x, y)?.closest(".split-app-item") as HTMLElement | null;
    const packageName = hovered?.dataset.packageName;
    if (!packageName || !draggingApp || packageName === draggingApp.packageName) return null;
    const target = apps.find((app) => app.packageName === packageName);
    if (!target || target.isPair) return null;
    return target;
  }

  function toast(message: string) {
    notice = message;
    clearTimeout(noticeTimer);
    noticeTimer = window.setTimeout(() => (notice = ""), 2600);
  }

  function triggerToggleDiagnostics() {
    (window as any).castlaDebug?.toggleDiagnostics?.();
  }

</script>

<div class:hidden={hasVisibleStream} class="standby">
  <div class="status-mark">
    {#if autoClosePending}
      <span class="loading-spinner"></span>
    {:else}
      ✓
    {/if}
  </div>
  <div class="standby-logo">CASTLA</div>
  {#if autoClosePending}
    <p>Launching application... Establishing high-fidelity stream link.</p>
  {:else}
    <p>Ready to Stream. Open the sidebar drawer to launch an app.</p>
  {/if}
  <div class="server-pill"><span></span>SERVER ACTIVE</div>
</div>

<aside
  bind:this={drawerElement}
  class:open={drawerOpen}
  class:dimmed={drawerDimmed}
  class:dragging={Boolean(draggingApp)}
  class="split-drawer"
  oncontextmenu={(event) => event.preventDefault()}
>
  <button
    class="split-handle"
    onclick={() => (drawerOpen = !drawerOpen)}
    aria-label={drawerOpen ? "Close launcher" : "Open launcher"}
  >
    <span class="handle-chevron">{drawerOpen ? ">" : "<"}</span>
  </button>

  <header>
    <div class="drawer-heading">
      <strong>Launch Hub</strong>
    </div>
    <div class="drawer-meta">
      <span class="drawer-count">{loading ? "Loading" : `${apps.length} apps`}</span>
      <button
        class="diag-toggle-btn"
        onclick={(event) => {
          event.stopPropagation();
          triggerToggleDiagnostics();
        }}
        title="Settings and diagnostics"
      >
        ⚙
      </button>
    </div>
  </header>

  <div class="search-row">
    <input
      bind:value={search}
      placeholder="Search or launch"
      autocomplete="off"
    />
  </div>

  {#if error}<div class="notice error">{error}</div>{/if}
  {#if notice}<div class="notice">{notice}</div>{/if}

  <LauncherTabs
    {activeTab}
    {selectTab}
    {draggingApp}
    {dropZone}
  />

  <div
    bind:this={drawerListElement}
    class="split-app-list"
    class:no-scroll={draggingApp !== null}
  >
    {#if activeTab !== "browse"}
      <section class="launcher-hero single-panel">
        <div class="panel-shell rows-only" class:priority={activeTab === "autorun"}>
          {#if activePanelApps.length > 0}
            <div class="launcher-row-list">
              {#each activePanelApps as app (app.packageName)}
                <!-- Modularized AppRow item with full touch gestures support -->
                <AppRow
                  {app}
                  {activeTab}
                  isStarred={favorites.includes(app.packageName)}
                  isAutorun={isAppAutorun(app)}
                  isDragActive={draggingApp !== null}
                  recentMeta={getRecentMeta(app.packageName)}
                  onLaunch={activateApp}
                  onToggleStar={toggleFavorite}
                  onToggleAutorun={toggleAutorunForApp}
                  onOpenEdit={openPairEdit}
                  onStartPress={startPress}
                  onPointerMove={movePress}
                  onPointerUp={endPress}
                  onPointerCancel={cancelPress}
                />
              {/each}
            </div>
          {:else}
            <div class="quick-empty">{activePanelEmpty}</div>
          {/if}
        </div>
      </section>
    {:else}
      <section class="library-section">
        <div class="library-header">
          <span>{search ? `${displayApps.length} matches` : "All categories collapsed"}</span>
        </div>
      </section>

      <div class="browse-accordion">
        {#each browseGroups as group (group.key)}
          <!-- Modularized Accordion for clean rendering -->
          <CategoryAccordion
            {group}
            isExpanded={expandedCategory === group.key}
            {draggingApp}
            {pairTarget}
            {favorites}
            isAutorun={isAppAutorun}
            onToggle={toggleCategory}
            onLaunch={activateApp}
            onToggleStar={toggleFavorite}
            onToggleAutorun={toggleAutorunForApp}
            onOpenEdit={openPairEdit}
            onStartPress={startPress}
            onPointerMove={movePress}
            onPointerUp={endPress}
            onPointerCancel={cancelPress}
          />
        {/each}
      </div>
    {/if}
  </div>
</aside>

<!-- Modularized drag-and-drop tracker overlay -->
{#if draggingApp}
  <DragDropOverlay
    {draggingApp}
    {dragX}
    {dragY}
    {dropZone}
    {pairTarget}
    drawerLeft={drawerElement?.getBoundingClientRect().left ?? window.innerWidth}
  />
{/if}

<!-- Modularized dialog configuration pair editor -->
{#if editingPair}
  <PairDialog
    {editingPair}
    {apps}
    onSwap={swapEditingPair}
    onCancel={() => editingPair = null}
    onRemove={removePair}
    onSave={saveEditingPair}
  />
{/if}

<style>
  /* Base Glassmorphic Layouts & Aesthetics */
  .standby {
    position: absolute;
    inset: 0;
    z-index: 8;
    display: grid;
    place-content: center;
    justify-items: center;
    text-align: center;
    color: #eaf7ff;
    background: radial-gradient(circle at center, #131420 0%, #06070c 70%, #030407 100%);
    pointer-events: none;
    transition: opacity 0.3s ease;
  }

  .standby.hidden {
    opacity: 0;
  }

  .status-mark {
    width: 96px;
    height: 96px;
    display: grid;
    place-items: center;
    border: 3px solid #28c9ff;
    border-radius: 50%;
    box-shadow:
      0 0 35px rgb(40 201 255 / 0.35),
      inset 0 0 20px rgb(158 75 255 / 0.2);
    color: #8c74ff;
    font-size: 52px;
    margin-bottom: 34px;
  }

  .loading-spinner {
    width: 36px;
    height: 36px;
    border: 3px solid rgb(40 201 255 / 0.25);
    border-top: 3px solid #8c74ff;
    border-radius: 50%;
    animation: spin 1s linear infinite;
  }

  @keyframes spin {
    0% { transform: rotate(0deg); }
    100% { transform: rotate(360deg); }
  }

  .standby-logo {
    font-size: 36px;
    font-weight: 900;
    letter-spacing: 8px;
    background: linear-gradient(90deg, #22d6ff, #bd5cff);
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
  }

  .standby p {
    width: min(340px, 70vw);
    color: #898a99;
    line-height: 1.45;
    margin: 16px 0 30px;
  }

  .server-pill {
    display: inline-flex;
    align-items: center;
    gap: 9px;
    min-height: 36px;
    padding: 0 18px;
    border: 1px solid rgb(255 255 255 / 0.08);
    border-radius: 18px;
    background: rgb(255 255 255 / 0.04);
    color: #e1e4ee;
    font-size: 12px;
    font-weight: 800;
    letter-spacing: 0.8px;
  }

  .server-pill span {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: #12d8ff;
    box-shadow: 0 0 10px #12d8ff;
  }

  /* Premium Sidebar Drawer styling */
  .split-drawer {
    position: absolute;
    top: 0;
    right: -300px;
    bottom: 0;
    width: 300px;
    z-index: 40;
    display: flex;
    flex-direction: column;
    color: white;
    background: rgba(13, 16, 27, 0.96);
    backdrop-filter: blur(20px);
    border-left: 1px solid rgba(255, 255, 255, 0.06);
    box-shadow: -10px 0 32px rgba(0, 0, 0, 0.45);
    transition:
      right 0.26s cubic-bezier(0.4, 0, 0.2, 1),
      opacity 0.2s ease,
      filter 0.2s ease;
  }

  .split-drawer.open {
    right: 0;
  }

  .split-drawer.dragging {
    z-index: 82;
  }

  .split-drawer.dimmed {
    opacity: 0.35;
    filter: saturate(0.5) blur(1px);
  }

  /* Interactive split handle with glow outline */
  .split-handle {
    position: absolute;
    left: -28px;
    top: 50%;
    width: 28px;
    height: 92px;
    transform: translateY(-50%);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-right: 0;
    border-radius: 14px 0 0 14px;
    background: linear-gradient(180deg, rgba(20, 24, 38, 0.98), rgba(12, 15, 24, 0.92));
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    box-shadow: -4px 0 16px rgba(0, 0, 0, 0.2);
    transition: background 0.2s ease, border-color 0.2s ease;
  }

  .split-handle:hover {
    background: rgba(255, 255, 255, 0.06);
    border-color: rgba(255, 255, 255, 0.15);
  }

  .handle-chevron {
    color: rgb(255 255 255 / 0.72);
    font-size: 14px;
    line-height: 1;
  }

  header,
  .search-row {
    padding: 10px 14px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  }

  .search-row {
    padding-left: 0;
    padding-right: 0;
  }

  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    min-height: 40px;
    padding-top: 6px;
    padding-bottom: 6px;
    background: radial-gradient(circle at top left, rgba(55, 127, 255, 0.08), transparent 50%),
      linear-gradient(180deg, rgba(255, 255, 255, 0.02), transparent);
  }

  header strong {
    font-size: 16px;
    font-weight: 800;
    letter-spacing: -0.02em;
    background: linear-gradient(90deg, #ffffff, #94a3b8);
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
  }

  .drawer-heading {
    display: flex;
    align-items: baseline;
  }

  .drawer-meta {
    display: flex;
    gap: 10px;
    align-items: center;
  }

  .drawer-count {
    color: #94a3b8;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.01em;
  }

  .diag-toggle-btn {
    border: none;
    background: rgba(255, 255, 255, 0.04);
    color: rgb(255 255 255 / 0.65);
    font-size: 15px;
    width: 24px;
    height: 24px;
    padding: 0;
    cursor: pointer;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: background 0.2s ease, transform 0.1s ease, color 0.2s ease;
  }

  .diag-toggle-btn:hover {
    background: rgba(255, 255, 255, 0.08);
    color: #ffffff;
    transform: rotate(45deg);
  }

  .search-row input {
    box-sizing: border-box;
    width: 100%;
    height: 34px;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.04);
    color: white;
    padding: 0 12px;
    font-size: 12px;
    transition:
      border-color 0.2s ease,
      background 0.2s ease,
      box-shadow 0.2s ease;
  }

  .search-row input:focus {
    outline: none;
    border-color: rgba(139, 196, 255, 0.35);
    background: rgba(255, 255, 255, 0.06);
    box-shadow: 0 0 0 3px rgba(139, 196, 255, 0.08);
  }

  .search-row {
    margin: 0 12px;
  }

  .notice {
    margin: 8px 10px;
    padding: 7px 9px;
    border-radius: 8px;
    background: rgba(0, 229, 255, 0.1);
    border: 1px solid rgba(0, 229, 255, 0.15);
    font-size: 12px;
    color: #00e5ff;
  }

  .notice.error {
    background: rgba(239, 68, 68, 0.1);
    border-color: rgba(239, 68, 68, 0.15);
    color: #f87171;
  }

  .split-app-list {
    flex: 1;
    overflow-y: auto;
    overflow-x: hidden;
    padding: 8px 10px 24px;
    scrollbar-width: thin;
    scrollbar-color: rgba(255, 255, 255, 0.08) transparent;
  }

  .split-app-list.no-scroll {
    touch-action: none !important;
  }

  .split-app-list::-webkit-scrollbar {
    width: 4px;
  }

  .split-app-list::-webkit-scrollbar-thumb {
    background: rgba(255, 255, 255, 0.08);
    border-radius: 2px;
  }

  .launcher-hero {
    display: grid;
    gap: 10px;
    margin-bottom: 12px;
  }

  .panel-shell {
    padding: 10px;
    border: 1px solid rgba(255, 255, 255, 0.06);
    border-radius: 18px;
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.03), rgba(255, 255, 255, 0.01)),
      rgba(11, 14, 24, 0.82);
    box-shadow: 0 12px 28px rgba(0, 0, 0, 0.25);
  }

  .panel-shell.rows-only {
    padding: 8px;
  }

  .panel-shell.priority {
    background: linear-gradient(180deg, rgba(60, 92, 160, 0.12), rgba(255, 255, 255, 0.01)),
      rgba(11, 14, 24, 0.86);
    border-color: rgba(60, 92, 160, 0.22);
  }

  .launcher-row-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .quick-empty {
    padding: 18px 6px;
    color: #64748b;
    font-size: 12px;
    text-align: center;
    line-height: 1.45;
  }

  .library-section {
    margin: 2px 0 10px;
  }

  .library-header {
    display: flex;
    align-items: center;
    justify-content: flex-start;
    padding: 0 4px;
  }

  .library-header span {
    color: #64748b;
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.03em;
  }

  .browse-accordion {
    display: grid;
    gap: 8px;
  }
</style>

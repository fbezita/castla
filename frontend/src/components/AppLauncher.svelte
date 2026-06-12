<script lang="ts">
  import { onDestroy, onMount, tick } from "svelte";
  import { get } from "svelte/store";
  import type { StreamRuntime } from "../runtime/StreamRuntime";
  import { compositorStore, setLanguage } from "../stores/compositorStore";
  import { t } from "../lib/i18n";
  import { canReuseHotStream } from "../lib/launchReuse";
  import { canKeepCurrentLaunch } from "../lib/launchRequestReuse";
  import { buildLayoutModeLaunchRequest } from "../lib/layoutModeTransition";
  import type { PaneId } from "../protocol";
  import { debugLog } from "../utils/debugLogger";
  import {
    OVERLAY_UI_SCALE_MAX,
    OVERLAY_UI_SCALE_MIN,
    OVERLAY_UI_SCALE_STEP,
    type OverlayUiScalePreference,
  } from "../utils/overlayUiScalePreference";
  import {
    dedupeAppPairs,
    getDefaultAppPairLayoutMode,
    getAppPairKey,
    normalizeAppPair,
    toStoredAppPair,
    type LayoutMode,
    type AppPair,
  } from "../lib/appPair";
  import type { SplitTargets } from "../lib/splitTargets";

  // Modular components imported for robust Svelte 5 structure
  import LauncherTabs from "./LauncherTabs.svelte";
  import AppRow from "./AppRow.svelte";
  import CategoryAccordion from "./CategoryAccordion.svelte";
  import DragDropOverlay from "./DragDropOverlay.svelte";
  import PairDialog from "./PairDialog.svelte";

  let {
    runtime,
    viewportHost = undefined,
    overlayUiScale,
    overlayUiScalePreference,
    onOverlayUiScalePreferenceChange,
  } = $props<{
    runtime: StreamRuntime;
    viewportHost?: any;
    overlayUiScale: number;
    overlayUiScalePreference: OverlayUiScalePreference;
    onOverlayUiScalePreferenceChange: (preference: OverlayUiScalePreference) => void;
  }>();

  // Types definitions
  interface AppInfo extends Partial<AppPair> {
    packageName: string;
    label: string;
    componentName?: string;
    category?: string;
    isWeb?: boolean;
    isPair?: boolean;
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
  const FRONTEND_GUIDE_URL = "https://github.com/fbezita/castla/blob/master/docs/frontend-user-guide.md";
  const MAX_RECENT_APPS = 8;
  const DRAWER_HANDLE_HOTZONE = 56;

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
  let appPairs = $state<AppPair[]>(readAppPairs());
  let primaryAutorun = $state(localStorage.getItem("castla_autorun_primary") ?? "");
  let secondaryAutorun = $state(localStorage.getItem("castla_autorun_secondary") ?? "");
  let autorunLayoutMode = $state<LayoutMode>(readAutorunLayoutMode());
  let notice = $state("");
  let noticeTimer = $state<number | undefined>(undefined);
  let launchedOnce = $state(false);
  let autoClosePending = $state(false);
  let launchSeqCounter = $state(0);

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
  let editingAppPair = $state<AppInfo | null>(null);
  let drawerRevision = $state(0);
  let pairTargetTimer = $state<number | undefined>(undefined);
  let pairTargetCandidate = $state("");
  let categoryExpandTimer = $state<number | undefined>(undefined);
  let categoryExpandCandidate = $state("");
  let autoScrollVelocity = $state(0);
  let autoScrollFrame = $state<number | undefined>(undefined);
  let drawerAutoCollapsedForDrag = $state(false);
  let settingsOpen = $state(false);

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
    return getAppPairApps(appPairs, apps);
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

  let autorunApps = $derived(getAutorunApps(displayApps, pairApps));

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

  let browseGroups = $derived(
    groupedApps.map(g => ({
      ...g,
      title: t($compositorStore.language, `group_${g.key}` as any)
    }))
  );

  let activePanelApps = $derived.by(() => {
    if (activeTab === "autorun") return autorunApps;
    if (activeTab === "starred") return starredApps;
    if (activeTab === "recent") return recentApps;
    return [];
  });

  let activePanelEmpty = $derived.by(() => {
    if (activeTab === "autorun") return t($compositorStore.language, "emptyAutorun");
    if (activeTab === "starred") return t($compositorStore.language, "emptyStarred");
    if (activeTab === "recent") return t($compositorStore.language, "emptyRecent");
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

  function setLayoutMode(mode: LayoutMode) {
    const store = get(compositorStore);
    const primary = store.viewports.get("primary");
    const secondary = store.viewports.get("secondary");
    if (!primary || !secondary) return;

    const transitionRequest = buildLayoutModeLaunchRequest(mode, store);
    if (transitionRequest) {
      startLaunchSequence(transitionRequest);
      return;
    }

    if (mode === store.layoutMode) return;
    
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      if (mode === "single") {
        viewports.forEach((viewport, key) =>
          viewports.set(key, { ...viewport, visible: key === "primary" }),
        );
      } else {
        viewports.forEach((viewport, key) =>
          viewports.set(key, {
            ...viewport,
            visible: key === "primary" || key === "secondary",
          }),
        );
      }
      return {
        ...state,
        viewports,
        layoutMode: mode,
        popup:
          mode === "popup"
            ? { ...state.popup, visible: true }
            : { ...state.popup, visible: false },
      };
    });
  }

  function swap() {
    const store = get(compositorStore);
    const primaryPkg = store.activePrimaryApp;
    const secondaryPkg = store.activeSecondaryApp;
    const primary = store.viewports.get("primary");
    const secondary = store.viewports.get("secondary");
    if (!primary || !secondary) return;

    if (primaryPkg && secondaryPkg) {
      const pairsRaw = localStorage.getItem("castla_app_pairs");
      if (pairsRaw) {
        try {
          const pairs = JSON.parse(pairsRaw);
          if (Array.isArray(pairs)) {
            const updated = pairs.map((pair) => {
              if (
                Array.isArray(pair.apps) &&
                ((pair.apps[0] === primaryPkg && pair.apps[1] === secondaryPkg) ||
                  (pair.apps[0] === secondaryPkg && pair.apps[1] === primaryPkg))
              ) {
                return { ...pair, apps: [secondaryPkg, primaryPkg] };
              }
              return pair;
            });
            localStorage.setItem("castla_app_pairs", JSON.stringify(updated));
          }
        } catch {}
      }

      const layoutMode = store.layoutMode === "popup" ? "popup" : "split";
      startLaunchSequence({
        primaryPkg: secondaryPkg,
        secondaryPkg: primaryPkg,
        layoutMode: layoutMode,
      });
    }
  }

  class StaleLaunchSequenceError extends Error {
    constructor() {
      super("stale-continuation");
      this.name = "StaleLaunchSequenceError";
    }
  }

  function nextLaunchSeqId(): number {
    launchSeqCounter += 1;
    return launchSeqCounter;
  }

  function isStale(seqId: number): boolean {
    return get(compositorStore).launchSequence.id !== seqId;
  }

  function assertCurrent(seqId: number) {
    if (isStale(seqId)) {
      throw new StaleLaunchSequenceError();
    }
  }

  function emitVerboseLaunchDiag(
    message: string,
    data: Record<string, unknown>,
  ) {
    if (!(window as Window & { __CASTLA_VERBOSE_DIAGNOSTICS__?: boolean }).__CASTLA_VERBOSE_DIAGNOSTICS__) {
      return;
    }
    debugLog(`[LAUNCH_SM] ${message}`, data);
    runtime?.control?.sendFrontendDiag?.("LAUNCH_SM", message, data);
  }

  function buildLaunchSnapshot(seqId: number) {
    const state = get(compositorStore);
    const primary = state.viewports.get("primary");
    const secondary = state.viewports.get("secondary");
    return {
      seqId,
      launchState: state.launchSequence.state,
      degradedReason: state.launchSequence.degradedReason ?? "",
      layoutMode: state.layoutMode,
      activePrimaryApp: state.activePrimaryApp,
      activeSecondaryApp: state.activeSecondaryApp,
      sessionEpoch: runtime.currentSessionEpoch(),
      appLaunchSequence: runtime.currentAppLaunchSequence(),
      controlBuffered: runtime.hasPendingBufferedAmount(),
      primaryViewport: primary
        ? {
            committed: primary.committed,
            generation: primary.generation,
            visible: primary.visible,
            width: primary.width,
            height: primary.height,
            firstFrameReady: runtime.generations.isFirstFrameReady("primary"),
          }
        : null,
      secondaryViewport: secondary
        ? {
            committed: secondary.committed,
            generation: secondary.generation,
            visible: secondary.visible,
            width: secondary.width,
            height: secondary.height,
            firstFrameReady: runtime.generations.isFirstFrameReady("secondary"),
          }
        : null,
    };
  }

  function setLaunchState(
    seqId: number,
    state: typeof $compositorStore.launchSequence.state,
    error?: string,
    degradedReason?: typeof $compositorStore.launchSequence.degradedReason
  ) {
    compositorStore.update((curr) => {
      if (curr.launchSequence.id !== seqId) return curr;
      console.info(`[LAUNCH_SM] seq=${seqId} state=${state} primary=${curr.launchSequence.primaryPkg} secondary=${curr.launchSequence.secondaryPkg || ""} degradedReason=${degradedReason || ""}`);
      emitVerboseLaunchDiag("state", {
        seqId,
        state,
        primaryPkg: curr.launchSequence.primaryPkg,
        secondaryPkg: curr.launchSequence.secondaryPkg || "",
        degradedReason: degradedReason || "",
      });
      return {
        ...curr,
        launchSequence: {
          ...curr.launchSequence,
          state,
          error,
          degradedReason: degradedReason !== undefined ? degradedReason : curr.launchSequence.degradedReason,
        },
      };
    });
  }

  // Await layout_ack packet from backend
  function waitForLayoutAck(seqId: number, expectedMode: string, timeoutMs = 5000): Promise<void> {
    return new Promise((resolve, reject) => {
      let settled = false;
      let timeoutId = 0;
      const finish = (cb: () => void) => {
        if (settled) return;
        settled = true;
        window.clearTimeout(timeoutId);
        unsub();
        cb();
      };
      const unsub = runtime.onAckMessage((msg) => {
        if (isStale(seqId)) { finish(() => reject(new StaleLaunchSequenceError())); return; }
        if (msg.type === "layout_ack" && msg.seqId === seqId) {
          finish(() => {
            if (msg.success) resolve();
            else reject(new Error("layout_ack_failed"));
          });
        }
      });
      timeoutId = window.setTimeout(() => {
        if (settled) return;
        if (isStale(seqId)) {
          finish(() => reject(new StaleLaunchSequenceError()));
        } else {
          finish(() => reject(new Error("layout_ack_timeout")));
        }
      }, timeoutMs);
    });
  }

  // Await launch_ack packet from backend (Catches launch_failed explicitly)
  function waitForLaunchAck(seqId: number, pane: "primary" | "secondary", timeoutMs = 5000): Promise<void> {
    return new Promise((resolve, reject) => {
      let settled = false;
      let timeoutId = 0;
      const finish = (cb: () => void) => {
        if (settled) return;
        settled = true;
        window.clearTimeout(timeoutId);
        unsub();
        cb();
      };
      const unsub = runtime.onAckMessage((msg) => {
        if (isStale(seqId)) { finish(() => reject(new StaleLaunchSequenceError())); return; }
        if (msg.type === "launch_ack" && msg.seqId === seqId && msg.pane === pane) {
          finish(() => {
            if (msg.success) resolve();
            else reject(new Error("launch_failure"));
          });
        } else if (msg.type === "launch_failed" && msg.seqId === seqId && msg.pane === pane) {
          finish(() => reject(new Error("launch_failure")));
        }
      });
      timeoutId = window.setTimeout(() => {
        if (settled) return;
        if (isStale(seqId)) {
          finish(() => reject(new StaleLaunchSequenceError()));
        } else {
          finish(() => reject(new Error("launch_failure")));
        }
      }, timeoutMs);
    });
  }

  function canReuseExistingHotStream(
    pane: "primary" | "secondary",
    startGen: number,
  ): boolean {
    const viewport = get(compositorStore).viewports.get(pane);
    const metadata = runtime.generations.getMetadata(pane);
    return canReuseHotStream(viewport, metadata, startGen);
  }

  // Await session_ready packet from backend
  function waitForSessionReady(
    seqId: number,
    pane: "primary" | "secondary",
    startGen: number,
    timeoutMs = 6000,
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      const startedAt = Date.now();
      let settled = false;
      let timeoutId = 0;
      let unsub = () => {};
      const finish = (cb: () => void) => {
        if (settled) return;
        settled = true;
        window.clearTimeout(timeoutId);
        unsub();
        cb();
      };
      emitVerboseLaunchDiag("session_wait_begin", {
        seqId,
        pane,
        startGen,
        timeoutMs,
        snapshot: buildLaunchSnapshot(seqId),
      });
      if (canReuseExistingHotStream(pane, startGen)) {
        emitVerboseLaunchDiag("session_wait_reuse_existing_stream", {
          seqId,
          pane,
          startGen,
          snapshot: buildLaunchSnapshot(seqId),
        });
        finish(() => resolve());
        return;
      }
      unsub = runtime.onAckMessage((msg) => {
        if (isStale(seqId)) { finish(() => reject(new StaleLaunchSequenceError())); return; }
        if (msg.type === "session_ready" && msg.seqId === seqId && msg.pane === pane) {
          emitVerboseLaunchDiag("session_wait_ack", {
            seqId,
            pane,
            waitedMs: Date.now() - startedAt,
            message: {
              type: msg.type,
              seqId: msg.seqId,
              pane: msg.pane,
            },
            snapshot: buildLaunchSnapshot(seqId),
          });
          finish(() => resolve());
        }
      });
      timeoutId = window.setTimeout(() => {
        if (settled) return;
        if (isStale(seqId)) {
          finish(() => reject(new StaleLaunchSequenceError()));
        } else {
          emitVerboseLaunchDiag("session_wait_timeout", {
            seqId,
            pane,
            waitedMs: Date.now() - startedAt,
            timeoutMs,
            snapshot: buildLaunchSnapshot(seqId),
          });
          finish(() => reject(new Error("session_timeout")));
        }
      }, timeoutMs);
    });
  }

  // Await stream commitment with strict generation validation
  function waitForStreamsToCommit(
    seqId: number,
    hasSecondary: boolean,
    primaryStartGen: number,
    secondaryStartGen: number,
    timeoutMs = 5000
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      let primarySawRecommit = false;
      let secondarySawRecommit = false;
      const initialState = get(compositorStore);
      const initialPrimaryViewport = initialState.viewports.get("primary");
      const initialSecondaryViewport = initialState.viewports.get("secondary");
      let previousPrimaryCommitted = initialPrimaryViewport?.committed === true;
      let previousSecondaryCommitted = initialSecondaryViewport?.committed === true;
      const initialPrimaryMetadata = runtime.generations.getMetadata("primary");
      const initialSecondaryMetadata = runtime.generations.getMetadata("secondary");
      let primaryMetadataReady =
        initialPrimaryMetadata?.firstFrameReady === true &&
        initialPrimaryMetadata.generation > primaryStartGen;
      let secondaryMetadataReady = !hasSecondary || (
        initialSecondaryMetadata?.firstFrameReady === true &&
        initialSecondaryMetadata.generation > secondaryStartGen
      );
      let cleanup = () => {};

      const tryResolve = () => {
        const state = get(compositorStore);
        if (isStale(seqId)) { cleanup(); reject(new StaleLaunchSequenceError()); return; }

        const primary = state.viewports.get("primary");
        const secondary = state.viewports.get("secondary");

        if (
          primary?.committed &&
          (
            primary.generation > primaryStartGen ||
            (!previousPrimaryCommitted && primary.generation === primaryStartGen)
          )
        ) {
          primarySawRecommit = true;
        }
        if (
          hasSecondary &&
          secondary?.committed &&
          (
            secondary.generation > secondaryStartGen ||
            (!previousSecondaryCommitted && secondary.generation === secondaryStartGen)
          )
        ) {
          secondarySawRecommit = true;
        }

        const primaryReady = primary
          ? (
              (primary.committed && (primary.generation > primaryStartGen || primarySawRecommit)) ||
              primaryMetadataReady
            )
          : true;
        const secondaryReady = hasSecondary && secondary
          ? (
              (secondary.committed && (secondary.generation > secondaryStartGen || secondarySawRecommit)) ||
              secondaryMetadataReady
            )
          : true;

        previousPrimaryCommitted = primary?.committed === true;
        previousSecondaryCommitted = secondary?.committed === true;

        if (primaryReady && secondaryReady) {
          cleanup();
          resolve();
        }
      };

      const unsub = compositorStore.subscribe(() => {
        tryResolve();
      });
      const controlUnsub = runtime.control.onMessage((message) => {
        if (isStale(seqId)) {
          cleanup();
          reject(new StaleLaunchSequenceError());
          return;
        }
        if (message.type !== "streamMetadata") return;
        if (message.sessionId === "primary" && message.firstFrameReady && message.generation > primaryStartGen) {
          primaryMetadataReady = true;
        }
        if (
          hasSecondary &&
          message.sessionId === "secondary" &&
          message.firstFrameReady &&
          message.generation > secondaryStartGen
        ) {
          secondaryMetadataReady = true;
        }
        tryResolve();
      });

      cleanup = () => {
        unsub();
        controlUnsub();
      };

      setTimeout(() => {
        cleanup();
        reject(new Error("stream_timeout"));
      }, timeoutMs);
    });
  }

  async function retryAfterStreamTimeout(
    seqId: number,
    request: {
      primaryPkg: string;
      secondaryPkg?: string;
      layoutMode: "single" | "split" | "popup";
    },
  ): Promise<boolean> {
    emitVerboseLaunchDiag("stream_recovery_begin", {
      seqId,
      primaryPkg: request.primaryPkg,
      secondaryPkg: request.secondaryPkg ?? "",
      layoutMode: request.layoutMode,
      snapshot: buildLaunchSnapshot(seqId),
    });

    compositorStore.update((state) => {
      if (state.launchSequence.id !== seqId) return state;
      const viewports = new Map(state.viewports);
      const primary = viewports.get("primary");
      const secondary = viewports.get("secondary");
      if (primary) {
        viewports.set("primary", { ...primary, committed: false });
      }
      if (secondary && request.secondaryPkg) {
        viewports.set("secondary", { ...secondary, committed: false });
      }
      return { ...state, viewports };
    });

    const retryPrimaryStartGen = get(compositorStore).viewports.get("primary")?.generation ?? 0;
    const retrySecondaryStartGen = get(compositorStore).viewports.get("secondary")?.generation ?? 0;

    setLaunchState(seqId, "LAUNCHING_PRIMARY");
    const primaryLaunchAckPromise = runtime.isAckSupported()
      ? waitForLaunchAck(seqId, "primary")
      : null;
    emitVerboseLaunchDiag("stream_recovery_dispatch_primary", {
      seqId,
      primaryPkg: request.primaryPkg,
      retryPrimaryStartGen,
      snapshot: buildLaunchSnapshot(seqId),
    });
    runtime.launchApp(request.primaryPkg, "primary", undefined, false, seqId);

    assertCurrent(seqId);
    setLaunchState(seqId, "PRIMARY_LAUNCH_SENT");

    if (primaryLaunchAckPromise) {
      await primaryLaunchAckPromise;
      assertCurrent(seqId);
      setLaunchState(seqId, "PRIMARY_LAUNCH_ACKED");

      await waitForSessionReady(seqId, "primary", retryPrimaryStartGen);
      assertCurrent(seqId);
      setLaunchState(seqId, "PRIMARY_SESSION_READY");
    }

    if (request.secondaryPkg) {
      setLaunchState(seqId, "LAUNCHING_SECONDARY");
      const secondaryApp = apps.find((app) => app.packageName === request.secondaryPkg);
      const secondaryLaunchAckPromise = runtime.isAckSupported()
        ? waitForLaunchAck(seqId, "secondary")
        : null;
      emitVerboseLaunchDiag("stream_recovery_dispatch_secondary", {
        seqId,
        secondaryPkg: request.secondaryPkg,
        retrySecondaryStartGen,
        snapshot: buildLaunchSnapshot(seqId),
      });
      runtime.launchApp(
        request.secondaryPkg,
        "secondary",
        secondaryApp?.componentName,
        secondaryApp?.category === "VIDEO" || secondaryApp?.isWeb === true,
        seqId
      );

      assertCurrent(seqId);
      setLaunchState(seqId, "SECONDARY_LAUNCH_SENT");

      if (secondaryLaunchAckPromise) {
        await secondaryLaunchAckPromise;
        assertCurrent(seqId);
        setLaunchState(seqId, "SECONDARY_LAUNCH_ACKED");

        await waitForSessionReady(seqId, "secondary", retrySecondaryStartGen);
        assertCurrent(seqId);
        setLaunchState(seqId, "SECONDARY_SESSION_READY");
      }
    }

    setLaunchState(seqId, "STREAM_COMMITTING");
    await waitForStreamsToCommit(
      seqId,
      Boolean(request.secondaryPkg),
      retryPrimaryStartGen,
      retrySecondaryStartGen
    );

    emitVerboseLaunchDiag("stream_recovery_success", {
      seqId,
      primaryPkg: request.primaryPkg,
      secondaryPkg: request.secondaryPkg ?? "",
      retryPrimaryStartGen,
      retrySecondaryStartGen,
      snapshot: buildLaunchSnapshot(seqId),
    });

    return true;
  }

  // Single orchestrator function to handle end-to-end launch sequence deterministically
  export async function startLaunchSequence(request: {
    primaryPkg: string;
    secondaryPkg?: string;
    layoutMode: "single" | "split" | "popup";
  }) {
    const currentState = get(compositorStore);
    const currentPopup =
      request.layoutMode === "popup"
        ? {
            ...currentState.popup,
            visible: true,
            minimized: false,
          }
        : undefined;
    const currentSplitTargets =
      request.layoutMode === "split" && viewportHost
        ? viewportHost.primeLayoutTargets(
            request.layoutMode,
            currentState.splitRatio,
            currentState.popup,
          )
        : null;
    if (
      canKeepCurrentLaunch(
        request,
        currentState,
        runtime.generations.getMetadata("primary"),
        runtime.generations.getMetadata("secondary"),
        {
          splitTargets: currentSplitTargets,
          popup: currentPopup,
        },
      )
    ) {
      compositorStore.update((state) => ({
        ...state,
        launchSequence: {
          ...state.launchSequence,
          state: "RUNNING",
          degradedReason: "",
        },
      }));
      emitVerboseLaunchDiag("sequence_skip_same_target", {
        primaryPkg: request.primaryPkg,
        secondaryPkg: request.secondaryPkg ?? "",
        layoutMode: request.layoutMode,
        snapshot: buildLaunchSnapshot(currentState.launchSequence.id),
      });
      return;
    }

    const seqId = nextLaunchSeqId();
    
    // Capture initial viewport generations for strict stream validation
    const storeSnapshot = get(compositorStore);
    const primViewport = storeSnapshot.viewports.get("primary");
    const secViewport = storeSnapshot.viewports.get("secondary");
    const primaryStartGen = primViewport ? primViewport.generation : 0;
    const secondaryStartGen = secViewport ? secViewport.generation : 0;
    
    console.info(`[LAUNCH_SM] seq=${seqId} state=LAYOUT_ALIGNING primary=${request.primaryPkg} secondary=${request.secondaryPkg ?? ""} layout=${request.layoutMode}`);
    emitVerboseLaunchDiag("sequence_start", {
      seqId,
      primaryPkg: request.primaryPkg,
      secondaryPkg: request.secondaryPkg ?? "",
      layoutMode: request.layoutMode,
      primaryStartGen,
      secondaryStartGen,
    });
    
    let degradedReasonVal: 'launch_failure' | 'session_timeout' | 'stream_timeout' | '' = '';
    const startedAt = Date.now();
    let layoutAlignMs = 0;
    let layoutAckMs = 0;
    let primaryLaunchAckMs = 0;
    let primarySessionReadyMs = 0;
    let streamCommitMs = 0;

    const preLaunchStore = get(compositorStore);
    let primedSplitTargets: SplitTargets | null = null;
    if (viewportHost) {
      primedSplitTargets = viewportHost.primeLayoutTargets(
        request.layoutMode,
        preLaunchStore.splitRatio,
        preLaunchStore.popup,
      );
    }

    // 1. Force Reset committed=false to clean frame remnants of previous session
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      const primary = viewports.get("primary") ?? { pane: "primary", width: 1280, height: 720, committed: false, generation: 0, visible: true };
      const secondary = viewports.get("secondary") ?? { pane: "secondary", width: 1280, height: 720, committed: false, generation: 0, visible: false };
      
      viewports.set("primary", { ...primary, visible: true, committed: false });
      viewports.set("secondary", { ...secondary, visible: Boolean(request.secondaryPkg), committed: false });

      let nextPopup = { ...state.popup };
      if (request.layoutMode === "popup") {
        nextPopup = {
          ...state.popup,
          visible: true,
          minimized: false,
        };
      }

      return {
        ...state,
        viewports,
        layoutMode: request.layoutMode,
        activePrimaryApp: request.primaryPkg,
        activeSecondaryApp: request.secondaryPkg ?? "",
        popup: nextPopup,
        launchSequence: {
          id: seqId,
          primaryPkg: request.primaryPkg,
          secondaryPkg: request.secondaryPkg,
          layoutMode: request.layoutMode,
          state: "LAYOUT_ALIGNING",
          startedAt,
          degradedReason: '',
          primaryStartGen,
          secondaryStartGen,
          expectedPrimaryPaneWidth: primedSplitTargets?.primaryWidth,
          expectedSecondaryPaneWidth: primedSplitTargets?.secondaryWidth,
          expectedPaneHeight: primedSplitTargets?.paneHeight,
        }
      };
    });

    await tick();

    try {
      const layoutAckPromise = runtime.isAckSupported()
        ? waitForLayoutAck(seqId, request.layoutMode)
        : null;

      // 2. Dispatch Layout and Await local socket flushing completion
      const currentStore = get(compositorStore);
      if (viewportHost) {
        await viewportHost.dispatchLayoutNow(request.layoutMode, currentStore.splitRatio, currentStore.popup, seqId);
      }
      layoutAlignMs = Date.now() - startedAt;
      
      // 3. Await Backend Layout ACK if backend supports ACK features, fallback otherwise
      assertCurrent(seqId);
      setLaunchState(seqId, "LAYOUT_SENT");
      
      if (layoutAckPromise) {
        try {
          await layoutAckPromise;
        } catch (err) {
          if (err instanceof StaleLaunchSequenceError) throw err;
          console.warn(`[LAUNCH_SM_LAYOUT_TIMEOUT] seq=${seqId} Layout ACK failed or timed out, proceeding degraded`, err);
          emitVerboseLaunchDiag("layout_timeout", {
            seqId,
            layoutMode: request.layoutMode,
            error: err instanceof Error ? err.message : String(err),
          });
          degradedReasonVal = "layout_timeout";
        }
      }
      layoutAckMs = Date.now() - startedAt;
      
      assertCurrent(seqId);
      setLaunchState(seqId, "LAYOUT_ACKED");

      // 4. Launch Primary App & Await ACK
      setLaunchState(seqId, "LAUNCHING_PRIMARY");
      console.info(`[APP_LAUNCH] seq=${seqId} pane=primary package=${request.primaryPkg}`);
      const primaryLaunchAckPromise = runtime.isAckSupported()
        ? waitForLaunchAck(seqId, "primary")
        : null;
      emitVerboseLaunchDiag("primary_launch_dispatch", {
        seqId,
        primaryPkg: request.primaryPkg,
        snapshot: buildLaunchSnapshot(seqId),
      });
      runtime.launchApp(request.primaryPkg, "primary", undefined, false, seqId);
      
      assertCurrent(seqId);
      setLaunchState(seqId, "PRIMARY_LAUNCH_SENT");
      
      if (primaryLaunchAckPromise) {
        try {
          await primaryLaunchAckPromise;
        } catch (err) {
          if (err instanceof StaleLaunchSequenceError) throw err;
          console.warn(`[LAUNCH_SM_PRIMARY_TIMEOUT] seq=${seqId} Primary Launch ACK failed, continuing degraded`, err);
          emitVerboseLaunchDiag("primary_launch_timeout", {
            seqId,
            primaryPkg: request.primaryPkg,
            error: err instanceof Error ? err.message : String(err),
          });
          degradedReasonVal = "launch_failure";
        }
      }
      primaryLaunchAckMs = Date.now() - startedAt;
      
      assertCurrent(seqId);
      setLaunchState(seqId, "PRIMARY_LAUNCH_ACKED");
      
      // 5. Await Primary Session Ready
      if (runtime.isAckSupported()) {
        try {
          await waitForSessionReady(seqId, "primary", primaryStartGen);
        } catch (err) {
          if (err instanceof StaleLaunchSequenceError) throw err;
          console.warn(`[LAUNCH_SM_SESSION_TIMEOUT] seq=${seqId} Primary Session Ready failed, continuing degraded`, err);
          emitVerboseLaunchDiag("primary_session_timeout", {
            seqId,
            primaryPkg: request.primaryPkg,
            error: err instanceof Error ? err.message : String(err),
          });
          degradedReasonVal = "session_timeout";
        }
      }
      primarySessionReadyMs = Date.now() - startedAt;
      
      assertCurrent(seqId);
      setLaunchState(seqId, "PRIMARY_SESSION_READY");

      let secondaryLaunchFailed = false;

      // 6. Launch Secondary App if present & Await ACK + Ready
      if (request.secondaryPkg) {
        try {
          setLaunchState(seqId, "LAUNCHING_SECONDARY");
          console.info(`[APP_LAUNCH] seq=${seqId} pane=secondary package=${request.secondaryPkg}`);
          
          const secondaryApp = apps.find((app) => app.packageName === request.secondaryPkg);
          const secondaryLaunchAckPromise = runtime.isAckSupported()
            ? waitForLaunchAck(seqId, "secondary")
            : null;
          emitVerboseLaunchDiag("secondary_launch_dispatch", {
            seqId,
            secondaryPkg: request.secondaryPkg,
            snapshot: buildLaunchSnapshot(seqId),
          });
          runtime.launchApp(
            request.secondaryPkg,
            "secondary",
            secondaryApp?.componentName,
            secondaryApp?.category === "VIDEO" || secondaryApp?.isWeb === true,
            seqId
          );
          
          assertCurrent(seqId);
          setLaunchState(seqId, "SECONDARY_LAUNCH_SENT");
          
          if (secondaryLaunchAckPromise) {
            await secondaryLaunchAckPromise;
            assertCurrent(seqId);
            setLaunchState(seqId, "SECONDARY_LAUNCH_ACKED");
            
            await waitForSessionReady(seqId, "secondary", secondaryStartGen);
            assertCurrent(seqId);
            setLaunchState(seqId, "SECONDARY_SESSION_READY");
          }
        } catch (err) {
          if (err instanceof StaleLaunchSequenceError) throw err;
          console.error(`[LAUNCH_SM_SECONDARY_FAIL] seq=${seqId} Secondary pane launch failed, continuing degraded`, err);
          emitVerboseLaunchDiag("secondary_launch_failure", {
            seqId,
            secondaryPkg: request.secondaryPkg ?? "",
            error: err instanceof Error ? err.message : String(err),
          });
          secondaryLaunchFailed = true;
          degradedReasonVal = err.message.includes("session_timeout") ? "session_timeout" : "launch_failure";
        }
      }

      // 7. Await Video Frame Commits (Gen-strict Promise Awaiter)
      setLaunchState(seqId, "STREAM_COMMITTING");
      emitVerboseLaunchDiag("stream_wait_begin", {
        seqId,
        hasSecondary: Boolean(request.secondaryPkg) && !secondaryLaunchFailed,
        primaryStartGen,
        secondaryStartGen,
        snapshot: buildLaunchSnapshot(seqId),
      });
      try {
        await waitForStreamsToCommit(
          seqId,
          Boolean(request.secondaryPkg) && !secondaryLaunchFailed,
          primaryStartGen,
          secondaryStartGen
        );
      } catch (err) {
        if (err instanceof StaleLaunchSequenceError) throw err;
        console.warn(`[STREAM_COMMIT_TIMEOUT] seq=${seqId} Streams failed to commit in time, falling back to degraded`);
        emitVerboseLaunchDiag("stream_timeout", {
          seqId,
          hasSecondary: Boolean(request.secondaryPkg) && !secondaryLaunchFailed,
          primaryStartGen,
          secondaryStartGen,
          snapshot: buildLaunchSnapshot(seqId),
        });
        try {
          await retryAfterStreamTimeout(seqId, request);
        } catch (retryErr) {
          if (retryErr instanceof StaleLaunchSequenceError) throw retryErr;
          console.warn(`[STREAM_COMMIT_RECOVERY_FAIL] seq=${seqId} Automatic relaunch after stream timeout failed`, retryErr);
          emitVerboseLaunchDiag("stream_recovery_failed", {
            seqId,
            error: retryErr instanceof Error ? retryErr.message : String(retryErr),
            snapshot: buildLaunchSnapshot(seqId),
          });
          secondaryLaunchFailed = true;
          degradedReasonVal = "stream_timeout";
        }
      }
      streamCommitMs = Date.now() - startedAt;
      
      assertCurrent(seqId);
      
      // 8. Commit metrics timing object for E2E diagnostics
      const totalLaunchMs = Date.now() - startedAt;
      const metrics: LaunchMetrics = {
        layoutAlignMs,
        layoutAckMs,
        primaryLaunchAckMs,
        primarySessionReadyMs,
        streamCommitMs,
        totalLaunchMs,
      };

      // 9. Final Steady-State transition (RUNNING or DEGRADED)
      const nextState = secondaryLaunchFailed ? "DEGRADED" : "RUNNING";
      
      compositorStore.update((curr) => {
        if (curr.launchSequence.id !== seqId) return curr;
        return {
          ...curr,
          launchSequence: {
            ...curr.launchSequence,
            state: nextState,
            degradedReason: degradedReasonVal,
            metrics,
          }
        };
      });
      console.info(`[LAUNCH_SM_SUCCESS] seq=${seqId} state=${nextState} (E2E ACK sequence completed) degradedReason=${degradedReasonVal} totalTime=${totalLaunchMs}ms`);
      emitVerboseLaunchDiag("sequence_complete", {
        seqId,
        state: nextState,
        degradedReason: degradedReasonVal,
        totalLaunchMs,
        metrics,
        snapshot: buildLaunchSnapshot(seqId),
      });
      
    } catch (err) {
      if (err instanceof StaleLaunchSequenceError) {
        console.warn(`[LAUNCH_SM_ABORT] seq=${seqId} Sequence was aborted by a newer launch`);
        emitVerboseLaunchDiag("sequence_abort", { seqId });
        return;
      }
      
      const errorMsg = err instanceof Error ? err.message : String(err);
      console.error(`[LAUNCH_SM_FAIL] seq=${seqId} state=FAILED degradedReason=${degradedReasonVal} error=${errorMsg}`);
      emitVerboseLaunchDiag("sequence_fail", {
        seqId,
        degradedReason: degradedReasonVal,
        error: errorMsg,
        snapshot: buildLaunchSnapshot(seqId),
      });
      setLaunchState(seqId, "FAILED", errorMsg, degradedReasonVal);
    }
  }

  function launch(app: AppInfo, pane: PaneId = "primary") {
    launchedOnce = true;
    autoClosePending = true;
    recordRecentLaunch(app.packageName);
    
    if (pane === "primary") {
      startLaunchSequence({
        primaryPkg: app.packageName,
        secondaryPkg: undefined,
        layoutMode: "single",
      });
    } else {
      const activePrimary = $compositorStore.activePrimaryApp;
      if (activePrimary) {
        startLaunchSequence({
          primaryPkg: activePrimary,
          secondaryPkg: app.packageName,
          layoutMode: "split",
        });
      } else {
        startLaunchSequence({
          primaryPkg: app.packageName,
          secondaryPkg: undefined,
          layoutMode: "single",
        });
      }
    }
    
    drawerOpen = true;
    toast(`${app.label} launching`);
    setTimeout(() => {
      if (autoClosePending && !hasVisibleStream) {
        drawerOpen = true;
      }
    }, 8000);
  }

  function setPopupLayout() {
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      const primary = viewports.get("primary") ?? {
        pane: "primary", width: 1280, height: 720, committed: false, generation: 0, visible: true
      };
      const secondary = viewports.get("secondary") ?? {
        pane: "secondary", width: 1280, height: 720, committed: false, generation: 0, visible: false
      };
      viewports.set("primary", { ...primary, visible: true });
      viewports.set("secondary", { ...secondary, visible: true });
      return {
        ...state,
        viewports,
        layoutMode: "popup",
        popup: { ...state.popup, visible: true, minimized: false },
      };
    });
  }

  function launchAppPair(record: AppPair) {
    launchedOnce = true;
    autoClosePending = true;
    recordRecentLaunch(`workspace:${getAppPairKey(record)}`);
    const [app0_pkg, app1_pkg] = record.apps;
    const primary = apps.find((app) => app.packageName === app0_pkg);
    const secondary = apps.find((app) => app.packageName === app1_pkg);
    if (!primary || !secondary) return;

    const currentMode = $compositorStore.layoutMode;
    const layout = getDefaultAppPairLayoutMode(currentMode);
    
    if (layout === "popup") {
      compositorStore.update((state) => {
        return {
          ...state,
          popup: {
            ...state.popup,
            visible: true,
            minimized: false,
          }
        };
      });
    }

    startLaunchSequence({
      primaryPkg: primary.packageName,
      secondaryPkg: secondary.packageName,
      layoutMode: layout === "popup" ? "popup" : "split",
    });

    drawerOpen = true;
    toast(`${primary.label} + ${secondary.label}`);

    setTimeout(() => {
      if (autoClosePending && !hasVisibleStream) {
        drawerOpen = true;
      }
    }, 8000);
  }

  function activateApp(app: AppInfo) {
    if (app.isPair) {
      const appPair = appPairFromAppInfo(app);
      if (appPair) {
        launchAppPair(appPair);
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
      return { ...state, viewports, layoutMode: "single", popup: { ...state.popup, visible: false } };
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
      return {
        ...state,
        viewports,
        layoutMode: active ? "split" : "single",
        popup: active ? { ...state.popup, visible: false } : state.popup,
      };
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

  function getAppPairApps(pairs: AppPair[], availableApps: AppInfo[]): AppInfo[] {
    const result: AppInfo[] = [];
    for (const pair of pairs) {
      if (!pair.apps || pair.apps.length !== 2) continue;
      const [appA_pkg, appB_pkg] = pair.apps;
      const appA = availableApps.find((app) => app.packageName === appA_pkg);
      const appB = availableApps.find((app) => app.packageName === appB_pkg);
      if (!appA || !appB) continue;
      result.push({
        packageName: `workspace:${getAppPairKey(pair)}`,
        label: `${appA.label} + ${appB.label}`,
        category: "PAIR",
        isPair: true,
        apps: pair.apps,
      });
    }
    return result;
  }

  function createAppPair(source: AppInfo, target?: AppInfo) {
    if (source.isPair || target?.isPair) return;
    if (target && source.packageName === target.packageName) {
      toast("Choose a different app");
      return;
    }
    if (!target) return;
    editingAppPair = {
      packageName: "workspace:new",
      label: `${source.label} + ${target.label}`,
      category: "PAIR",
      isPair: true,
      apps: [source.packageName, target.packageName],
    };
    pairMenuOpen = "";
  }

  function openAppPairEditor(app?: AppInfo) {
    if (!app) {
      editingAppPair = {
        packageName: "workspace:new",
        label: "New App Pair",
        category: "PAIR",
        isPair: true,
      };
      pairMenuOpen = "";
      return;
    }
    if (!app.isPair) return;
    editingAppPair = { ...app };
    pairMenuOpen = "";
  }

  function persistAppPair(nextPair: AppPair, previousKey?: string) {
    const storedPair = toStoredAppPair(nextPair);
    const index = previousKey
      ? appPairs.findIndex((pair) => getAppPairKey(pair) === previousKey)
      : -1;
    if (index >= 0) {
      appPairs = appPairs.map((pair, idx) => (idx === index ? storedPair : pair));
    } else {
      appPairs = [...appPairs, storedPair];
    }
    appPairs = dedupeAppPairs(appPairs);
    localStorage.setItem("castla_app_pairs", JSON.stringify(appPairs));
    touchDrawer();
  }

  function saveAppPair(nextDraft: AppPair) {
    if (!editingAppPair) return;
    const original = appPairFromAppInfo(editingAppPair);
    persistAppPair(nextDraft, original ? getAppPairKey(original) : undefined);
    editingAppPair = null;
    toast("App Pair updated");
  }

  function removeAppPair(app: AppInfo) {
    const target = appPairFromAppInfo(app);
    if (!target) return;
    appPairs = appPairs.filter((pair) => getAppPairKey(pair) !== getAppPairKey(target));
    localStorage.setItem("castla_app_pairs", JSON.stringify(appPairs));
    favorites = favorites.filter((pkg) => pkg !== app.packageName);
    localStorage.setItem("castla_favorites", JSON.stringify(favorites));
    const [appA, appB] = target.apps;
    if (
      primaryAutorun === appA &&
      secondaryAutorun === appB
    ) {
      primaryAutorun = "";
      secondaryAutorun = "";
      autorunLayoutMode = "split";
      updateStorage("castla_autorun_primary", primaryAutorun);
      updateStorage("castla_autorun_secondary", secondaryAutorun);
      updateStorage("castla_autorun_layout_mode", "");
    }
    touchDrawer();
    if (editingAppPair?.packageName === app.packageName) editingAppPair = null;
    pairMenuOpen = "";
    toast("App Pair removed");
  }

  function toggleAutorun(packageName: string) {
    if (primaryAutorun === packageName || secondaryAutorun === packageName) {
      if (primaryAutorun === packageName) primaryAutorun = "";
      if (secondaryAutorun === packageName) secondaryAutorun = "";
      autorunLayoutMode = "single";
    } else if (!primaryAutorun) {
      primaryAutorun = packageName;
    } else {
      secondaryAutorun = packageName;
    }
    updateStorage("castla_autorun_primary", primaryAutorun);
    updateStorage("castla_autorun_secondary", secondaryAutorun);
    updateStorage(
      "castla_autorun_layout_mode",
      primaryAutorun ? autorunLayoutMode : "",
    );
    touchDrawer();
  }

  function isAutorunAppPair(app: AppInfo) {
    if (!app.isPair || !app.apps) return false;
    const [appA, appB] = app.apps;
    return Boolean(
      primaryAutorun === appA &&
      secondaryAutorun === appB &&
      autorunLayoutMode === (app.layoutMode ?? "split")
    );
  }

  function toggleAutorunForApp(app: AppInfo) {
    if (app.isPair && app.apps) {
      const [appA, appB] = app.apps;
      if (isAutorunAppPair(app)) {
        primaryAutorun = "";
        secondaryAutorun = "";
        autorunLayoutMode = "split";
      } else {
        primaryAutorun = appA;
        secondaryAutorun = appB;
        autorunLayoutMode = app.layoutMode ?? "split";
      }
      updateStorage("castla_autorun_primary", primaryAutorun);
      updateStorage("castla_autorun_secondary", secondaryAutorun);
      updateStorage(
        "castla_autorun_layout_mode",
        primaryAutorun ? autorunLayoutMode : "",
      );
      touchDrawer();
      return;
    }
    toggleAutorun(app.packageName);
  }

  function getAutorunApps(visibleApps: AppInfo[], availablePairs: AppInfo[]): AppInfo[] {
    const items: AppInfo[] = [];
    if (primaryAutorun && secondaryAutorun) {
      const pair = availablePairs.find(
        (app) =>
          app.apps &&
          app.apps[0] === primaryAutorun &&
          app.apps[1] === secondaryAutorun &&
          (app.layoutMode ?? "split") === autorunLayoutMode,
      );
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
    if (elapsedMs < minute) return t($compositorStore.language, "justNow");
    if (elapsedMs < hour) return `${Math.floor(elapsedMs / minute)} ${t($compositorStore.language, "minAgo")}`;
    if (elapsedMs < day) return `${Math.floor(elapsedMs / hour)} ${t($compositorStore.language, "hrAgo")}`;
    if (elapsedMs < day * 2) return t($compositorStore.language, "yesterday");
    return `${Math.floor(elapsedMs / day)} ${t($compositorStore.language, "daysAgo")}`;
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
    return (!app.isPair && (primaryAutorun === app.packageName || secondaryAutorun === app.packageName)) || isAutorunAppPair(app);
  }

  function readAutorunLayoutMode(): LayoutMode {
    const value = localStorage.getItem("castla_autorun_layout_mode");
    return value === "split" || value === "popup"
      ? value
      : "split";
  }

  function appPairFromAppInfo(app: AppInfo): AppPair | null {
    if (!app.apps || app.apps.length !== 2) return null;
    return {
      apps: app.apps,
    };
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

  function readAppPairs(): AppPair[] {
    try {
      const value = JSON.parse(localStorage.getItem("castla_app_pairs") ?? "[]");
      if (!Array.isArray(value)) return [];
      return dedupeAppPairs(
        value
          .map((pair) => normalizeAppPair(pair))
          .filter((pair): pair is AppPair => pair !== null),
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
    if (primary && secondary) {
      launchAppPair({
        layoutMode: autorunLayoutMode,
        apps: [primary.packageName, secondary.packageName],
      });
    }
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
        createAppPair(draggingApp, pairTarget);
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
      reopenDrawerForDrag();
      drawerDimmed = false;
      const hoveredTab = getHoveredLauncherTab(x, y);
      if (hoveredTab) {
        clearPairHoverState();
        clearCategoryHoverState();
        dropZone = hoveredTab === "autorun" ? "autorun" : hoveredTab === "starred" ? "favorite" : "";
        return;
      }

      if (activeTab === "browse") {
        const hoveredCategory = getHoveredBrowseCategory(x, y);
        if (hoveredCategory && hoveredCategory !== expandedCategory) {
          clearPairHoverState();
          dropZone = "";
          if (categoryExpandCandidate !== hoveredCategory) {
            clearCategoryHoverState();
            categoryExpandCandidate = hoveredCategory;
            categoryExpandTimer = window.setTimeout(() => {
              expandedCategory = hoveredCategory;
              categoryExpandCandidate = "";
              categoryExpandTimer = undefined;
            }, 220);
          }
          return;
        }
        clearCategoryHoverState();
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

    if (gestureState === "dragging" && isPointNearDrawerHandle(x, y)) {
      reopenDrawerForDrag();
      drawerDimmed = false;
      clearPairHoverState();
      clearCategoryHoverState();
      dropZone = "";
      return;
    }

    collapseDrawerForDrag();

    // Outside the drawer we only activate dimming while over a real drop zone.
    drawerDimmed = false;

    // Outer screen regions for launching panes or removal
    clearPairHoverState();
    clearCategoryHoverState();
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
    if (app.isPair && app.apps) {
      const [appA, appB] = app.apps;
      if (zone === "autorun") {
        primaryAutorun = appA;
        secondaryAutorun = appB;
        autorunLayoutMode = app.layoutMode ?? "split";
        updateStorage("castla_autorun_primary", primaryAutorun);
        updateStorage("castla_autorun_secondary", secondaryAutorun);
        updateStorage("castla_autorun_layout_mode", autorunLayoutMode);
        touchDrawer();
        toast(`${app.label} set to Auto-run`);
        return;
      }
      if (zone === "remove") {
        removeAppPair(app);
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
      const activePrimary = $compositorStore.activePrimaryApp;
      if (activePrimary) {
        // Leverage the consolidated E2E ACK launch state machine instead of timing-based launches
        const currentMode = $compositorStore.layoutMode;
        const layoutMode = currentMode === "popup" ? "popup" : "split";
        startLaunchSequence({
          primaryPkg: activePrimary,
          secondaryPkg: app.packageName,
          layoutMode: layoutMode,
        });
      } else {
        console.info(`[APP_LAUNCH] Pane: secondary, Package: ${app.packageName}, SessionReady: false (primary missing, fallback launch)`);
        launch(app, "secondary");
      }
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
    const drawerLeft =
      drawerOpen && drawerElement
        ? drawerElement.getBoundingClientRect().left
        : w;
    const usableRight = drawerLeft;
    const bottomInset = 20;
    const bottomZoneHeight = 120;
    const sideInset = 20;

    const removeTop = h - bottomZoneHeight - bottomInset;
    if (y >= removeTop && y <= h - bottomInset && x >= sideInset && x <= usableRight - sideInset) {
      return "remove";
    }

    const hoveredPane = document.elementFromPoint(x, y)?.closest(".viewport-pane") as HTMLElement | null;
    const pane = hoveredPane?.dataset.pane;
    if (pane === "primary" || pane === "secondary") {
      return pane;
    }

    return "";
  }

  function clearPairHoverState() {
    window.clearTimeout(pairTargetTimer);
    pairTargetTimer = undefined;
    pairTargetCandidate = "";
    pairTarget = null;
  }

  function clearCategoryHoverState() {
    window.clearTimeout(categoryExpandTimer);
    categoryExpandTimer = undefined;
    categoryExpandCandidate = "";
  }

  function getHoveredBrowseCategory(x: number, y: number): string | null {
    const header = document.elementFromPoint(x, y)?.closest("[data-category-key]") as HTMLElement | null;
    return header?.dataset.categoryKey ?? null;
  }

  function preventTouchScroll(event: TouchEvent) {
    if (gestureState === "dragging" && event.cancelable) {
      event.preventDefault();
    }
  }

  function beginDraggingSession() {
    gestureState = "dragging";
    draggingApp = pressedApp;
    drawerAutoCollapsedForDrag = false;
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
    window.clearTimeout(categoryExpandTimer);
    detachDragListeners();
    stopAutoScrollDrawer();
    pairTargetTimer = undefined;
    pairTargetCandidate = "";
    categoryExpandTimer = undefined;
    categoryExpandCandidate = "";
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
    drawerAutoCollapsedForDrag = false;
    pressMoved = false;
    pairTarget = null;
  }

  function isPointNearDrawerHandle(x: number, y: number): boolean {
    if (!drawerElement) return false;
    const rect = drawerElement.getBoundingClientRect();
    const handleLeft = rect.left - DRAWER_HANDLE_HOTZONE;
    const handleRight = rect.left + 12;
    const handleTop = rect.top + rect.height * 0.28;
    const handleBottom = rect.bottom - rect.height * 0.28;
    return (
      x >= handleLeft &&
      x <= handleRight &&
      y >= handleTop &&
      y <= handleBottom
    );
  }

  function collapseDrawerForDrag() {
    if (gestureState !== "dragging" || !drawerOpen) return;
    drawerOpen = false;
    drawerAutoCollapsedForDrag = true;
  }

  function reopenDrawerForDrag() {
    if (gestureState !== "dragging" || !drawerAutoCollapsedForDrag) return;
    drawerOpen = true;
    drawerAutoCollapsedForDrag = false;
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

  function settingsLabel(): string {
    return $compositorStore.language === "ko" ? "설정" : "Settings";
  }

  function languageLabel(): string {
    return $compositorStore.language === "ko" ? "언어" : "Language";
  }

  function uiScaleLabel(): string {
    return "UI Scale";
  }

  function diagnosticsLabel(): string {
    return $compositorStore.language === "ko" ? "진단" : "Diagnostics";
  }

  function diagnosticsActionLabel(): string {
    return $compositorStore.language === "ko" ? "열기" : "Open";
  }

  function formatUiScaleOption(option: OverlayUiScalePreference): string {
    return `${Math.round(option * 100)}%`;
  }

  function applyOverlayUiScalePreference(option: OverlayUiScalePreference) {
    onOverlayUiScalePreferenceChange(option);
    toast(`${uiScaleLabel()} ${formatUiScaleOption(option)}`);
  }

  function handleUiScaleSliderInput(event: Event) {
    const target = event.currentTarget as HTMLInputElement;
    applyOverlayUiScalePreference(Number(target.value));
  }

  function applyLanguage(language: "ko" | "en") {
    setLanguage(language);
    toast(language === "ko" ? "언어 KO" : "Language EN");
  }

</script>

<div class:hidden={hasVisibleStream || launchedOnce} class="standby">
  <div class="status-mark">
    {#if autoClosePending}
      <span class="loading-spinner"></span>
    {:else}
      ✓
    {/if}
  </div>
  <div class="standby-logo">CASTLA</div>
  {#if autoClosePending}
    <p>{t($compositorStore.language, "standbyLaunching")}</p>
  {:else}
    <p>{t($compositorStore.language, "standbyReady")}</p>
  {/if}
  <div class="server-pill"><span></span>{t($compositorStore.language, "serverActive")}</div>
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
    aria-label={drawerOpen ? t($compositorStore.language, "closeLauncher") : t($compositorStore.language, "openLauncher")}
  >
    <span class="handle-chevron">{drawerOpen ? ">" : "<"}</span>
  </button>

  <header>
    <div class="drawer-heading">
      <strong>{t($compositorStore.language, "launchHub")}</strong>
    </div>
    <div class="drawer-meta">
      <span class="drawer-count">{loading ? t($compositorStore.language, "loading") : `${apps.length} ${t($compositorStore.language, "appsCount")}`}</span>
      <button
        class="settings-toggle-btn"
        onclick={() => (settingsOpen = !settingsOpen)}
        title={t($compositorStore.language, "settingsDiagnostics")}
        aria-expanded={settingsOpen}
      >
        {settingsLabel()}
      </button>
    </div>
  </header>

  {#if settingsOpen}
    <section class="drawer-settings">
      <div class="settings-section">
        <div class="settings-inline-row">
          <div class="settings-inline-group">
            <strong>{languageLabel()}</strong>
            <div class="lang-switcher">
              <button
                class:active={$compositorStore.language === "ko"}
                onclick={() => applyLanguage("ko")}
              >
                KO
              </button>
              <button
                class:active={$compositorStore.language === "en"}
                onclick={() => applyLanguage("en")}
              >
                EN
              </button>
            </div>
          </div>
          <div class="settings-inline-group diagnostics-inline-group">
            <strong>{diagnosticsLabel()}</strong>
            <button class="diag-toggle-btn" onclick={triggerToggleDiagnostics}>
              {diagnosticsActionLabel()}
            </button>
          </div>
        </div>
      </div>

      <div class="settings-section">
        <div class="settings-title-row">
          <strong>{uiScaleLabel()}</strong>
          <span>{formatUiScaleOption(overlayUiScalePreference)}</span>
        </div>
        <div class="scale-slider">
          <input
            type="range"
            min={OVERLAY_UI_SCALE_MIN}
            max={OVERLAY_UI_SCALE_MAX}
            step={OVERLAY_UI_SCALE_STEP}
            value={overlayUiScalePreference}
            oninput={handleUiScaleSliderInput}
          />
          <div class="scale-slider-labels">
            <span>100%</span>
            <span>150%</span>
            <span>200%</span>
          </div>
        </div>
      </div>

      <div class="settings-section">
        <div class="settings-title-row">
          <strong>Frontend Guide</strong>
          <span>Launcher help</span>
        </div>
        <a
          class="settings-link-btn"
          href={FRONTEND_GUIDE_URL}
          target="_blank"
          rel="noopener noreferrer"
        >
          Open Usage Guide
        </a>
      </div>
    </section>
  {/if}

  <div class="drawer-layout-controls">
    <button
      class:active={$compositorStore.layoutMode === "single"}
      onclick={() => setLayoutMode("single")}
    >
      {t($compositorStore.language, "single")}
    </button>
    <button
      class:active={$compositorStore.layoutMode === "split"}
      onclick={() => setLayoutMode("split")}
    >
      {t($compositorStore.language, "split")}
    </button>
    <button
      class:active={$compositorStore.layoutMode === "popup"}
      onclick={() => setLayoutMode("popup")}
    >
      {t($compositorStore.language, "popup")}
    </button>
    <div class="layout-divider"></div>
    <button class="swap-btn" onclick={swap} title={t($compositorStore.language, "swap")}>
      ⇄
    </button>
  </div>

  <div class="search-row">
    <input
      bind:value={search}
      placeholder={t($compositorStore.language, "searchPlaceholder")}
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
                  onOpenEdit={openAppPairEditor}
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
      {#if search}
        <section class="library-section">
          <div class="library-header">
            <span>{displayApps.length} {t($compositorStore.language, "matches")}</span>
          </div>
        </section>
      {/if}

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
            onOpenEdit={openAppPairEditor}
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
{#if editingAppPair}
  <PairDialog
    editingPair={editingAppPair}
    {apps}
    onCancel={() => (editingAppPair = null)}
    onRemove={removeAppPair}
    onSave={saveAppPair}
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
    right: 0;
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
    transform: translateX(100%);
    will-change: transform, opacity;
    transition:
      transform 0.28s cubic-bezier(0.16, 1, 0.3, 1),
      opacity 0.2s ease,
      filter 0.2s ease;
  }

  .split-drawer.open {
    transform: translateX(0);
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
    opacity: 0.55; /* Default transparent when drawer is closed to reduce clutter */
    transition: background 0.2s ease, border-color 0.2s ease, opacity 0.2s ease;
  }

  .split-drawer:not(.open) .split-handle {
    opacity: 0.55 !important;
  }

  .split-drawer.open .split-handle {
    opacity: 1 !important; /* Fully opaque when drawer is open */
  }

  @media (hover: hover) {
    .split-handle:hover {
      opacity: 1 !important; /* Fully visible on hover interaction only on hover-capable pointer devices to prevent sticky hover on touch screens */
      background: rgba(255, 255, 255, 0.06);
      border-color: rgba(255, 255, 255, 0.15);
    }
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
    align-items: center;
    gap: 10px;
  }



  .drawer-meta {
    display: flex;
    gap: 10px;
    align-items: center;
  }

  .lang-switcher {
    display: flex;
    align-items: center;
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.06);
    border-radius: 8px;
    padding: 2px;
    gap: 1px;
  }

  .lang-switcher button {
    border: 0;
    background: transparent;
    color: rgba(255, 255, 255, 0.45);
    font-size: 9px;
    font-weight: 800;
    height: 18px;
    padding: 0 6px;
    border-radius: 6px;
    cursor: pointer;
    transition: background 0.16s ease, color 0.16s ease, box-shadow 0.16s ease;
  }

  .lang-switcher button:hover {
    color: rgba(255, 255, 255, 0.85);
  }

  .lang-switcher button.active {
    background: rgba(0, 229, 255, 0.16);
    color: #7cf1ff;
    border: 1px solid rgba(0, 229, 255, 0.2);
    box-shadow: 0 1px 4px rgba(0, 229, 255, 0.1);
  }

  .drawer-count {
    color: #94a3b8;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.01em;
  }

  .settings-toggle-btn,
  .diag-toggle-btn {
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(255, 255, 255, 0.04);
    color: rgb(255 255 255 / 0.65);
    font-size: 11px;
    min-width: 24px;
    height: 24px;
    padding: 0 10px;
    cursor: pointer;
    border-radius: 999px;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: background 0.2s ease, transform 0.1s ease, color 0.2s ease;
  }

  .settings-toggle-btn:hover,
  .diag-toggle-btn:hover {
    background: rgba(255, 255, 255, 0.08);
    color: #ffffff;
  }

  .drawer-settings {
    margin: 6px 12px 8px;
    padding: 10px 12px;
    border: 1px solid rgba(255, 255, 255, 0.06);
    border-radius: 14px;
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.03), rgba(255, 255, 255, 0.01)),
      rgba(11, 14, 24, 0.72);
    display: grid;
    gap: 10px;
  }

  .settings-section {
    display: grid;
    gap: 8px;
  }

  .settings-section + .settings-section {
    padding-top: 10px;
    border-top: 1px solid rgba(255, 255, 255, 0.05);
  }

  .settings-inline-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
  }

  .settings-inline-group {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }

  .settings-inline-group strong {
    font-size: 12px;
    color: #f8fafc;
    white-space: nowrap;
  }

  .diagnostics-inline-group {
    margin-left: auto;
  }

  .settings-title-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  .settings-title-row strong {
    font-size: 12px;
    color: #f8fafc;
  }

  .settings-title-row span {
    font-size: 11px;
    color: #94a3b8;
  }

  .scale-slider {
    display: grid;
    gap: 8px;
  }

  .scale-slider input[type="range"] {
    width: 100%;
    margin: 0;
    accent-color: #00e5ff;
  }

  .scale-slider-labels {
    display: flex;
    justify-content: space-between;
    gap: 10px;
    color: #94a3b8;
    font-size: 11px;
    font-weight: 700;
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
    padding: 4px 10px 24px;
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
  .drawer-layout-controls {
    margin: 6px 12px 8px;
    padding: 4px;
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.06);
    border-radius: 12px;
    display: flex;
    align-items: center;
    gap: 2px;
    box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.2);
  }

  .drawer-layout-controls button {
    flex: 1;
    height: 28px;
    border: 0;
    border-radius: 8px;
    background: transparent;
    color: rgba(255, 255, 255, 0.55);
    font-size: 11px;
    font-weight: 600;
    cursor: pointer;
    transition: background 0.16s ease, color 0.16s ease, transform 0.1s ease;
  }

  .drawer-layout-controls button:hover {
    color: rgba(255, 255, 255, 0.9);
    background: rgba(255, 255, 255, 0.03);
  }

  .drawer-layout-controls button.active {
    background: rgba(0, 229, 255, 0.14);
    color: #7cf1ff;
    border: 1px solid rgba(0, 229, 255, 0.18);
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.15);
  }

  .layout-divider {
    width: 1px;
    height: 16px;
    background: rgba(255, 255, 255, 0.08);
    margin: 0 4px;
  }

  .drawer-layout-controls .swap-btn {
    flex: 0 0 32px;
    font-size: 13px;
    font-weight: bold;
    color: rgba(0, 229, 255, 0.7);
  }

  .drawer-layout-controls .swap-btn:hover {
    color: #7cf1ff;
    background: rgba(0, 229, 255, 0.08);
    transform: scale(1.05);
  }

  .drawer-layout-controls .swap-btn:active {
    transform: scale(0.95);
  }
</style>

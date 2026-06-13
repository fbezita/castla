<script lang="ts">
  import { onDestroy, onMount, tick } from "svelte";
  import {
    compositorStore,
    type LayoutMode,
    type PopupLayoutState,
    type ViewportModel,
    type SecondaryPlacement,
  } from "../stores/compositorStore";
  import { t } from "../lib/i18n";
  import { isPaneBarrierReadyForRelease } from "../lib/barrierRelease";
  import {
    buildSplitPaneStyles,
    hasCompleteSplitTargets,
    resolveExpectedSplitTargets,
    type SplitTargets,
  } from "../lib/splitTargets";
  import {
    buildDockedPaneStyles,
    computeDockedPaneLayout,
    isDockedPlacement,
    resolveSecondaryPlacement,
  } from "../lib/secondaryPlacement";
  import { getResizerAxis } from "../lib/resizerAxis";
  import { persistSplitRatioForPlacement } from "../lib/splitRatioByPlacement";
  import { shouldLockExplicitLayoutTargets as shouldLockLayoutTargets } from "../lib/layoutTargetLock";
  import { debugLog } from "../utils/debugLogger";
  import ViewportPane from "./ViewportPane.svelte";
  import type { TouchRouter } from "../touch/TouchRouter";
  import { mapViewportPoint } from "../touch/TouchRouter";
  import type { StreamRuntime } from "../runtime/StreamRuntime";
  import type { PaneId } from "../protocol";
  import { normalizePopupForStreaming } from "../lib/popupLayout";

  export let touchRouter: TouchRouter;
  export let runtime: StreamRuntime;
  export let appLauncher: any = undefined;

  const POPUP_HEADER_HEIGHT = 40;
  const POPUP_MIN_WIDTH = 240;
  const POPUP_MIN_HEIGHT = 160;
  const POPUP_MARGIN = 16;
  const PROVISIONAL_LAYOUT_SETTLE_MS = 220;


  type PopupResizeEdge = "n" | "s" | "e" | "w" | "ne" | "nw" | "se" | "sw";

  interface FrozenLayoutState {
    layoutMode: LayoutMode;
    visibleViewports: ViewportModel[];
    paneStyles: Record<string, string>;
    popup: PopupLayoutState;
    fullPane?: PaneId;
    popupPane?: PaneId;
    splitRatio: number;
    effectiveSecondaryPlacement: SecondaryPlacement;
  }

  const EMPTY_SPLIT_LAYOUT_METRICS = {
    primaryWidth: 0,
    secondaryWidth: 0,
    leftPercent: 50,
    rightPercent: 50,
    boundaryPercent: 50,
  };

  function isPositiveDimension(value: number | undefined): value is number {
    return value !== undefined && Number.isFinite(value) && value > 0;
  }

  let frozenLayoutState: FrozenLayoutState | null = null;
  let safetyReleaseTimer = 0;

  function emitVerboseBarrierDiag(
    message: string,
    data: Record<string, unknown>,
  ) {
    if (!(window as Window & { __CASTLA_VERBOSE_DIAGNOSTICS__?: boolean }).__CASTLA_VERBOSE_DIAGNOSTICS__) {
      return;
    }
    debugLog(`[COMPOSITOR_BARRIER] ${message}`, data);
    runtime?.control?.sendFrontendDiag?.("COMPOSITOR_BARRIER", message, data);
  }

  let host: HTMLDivElement;
  let resizer: HTMLButtonElement;

  let popupBody: HTMLDivElement;
  let resizeObserver: ResizeObserver;
  let detachMetadata: (() => void) | undefined;
  let resizingSplit = false;
  let popupInteracting = false;
  let hostRect = new DOMRect();
  let layoutTrigger = "";
  let layoutFlushScheduled = false;
  let provisionalLayoutTimer = 0;
  const activeTouchPanes = new Map<number, PaneId>();
  let popupDrag: {
    pointerId: number;
    startX: number;
    startY: number;
    originX: number;
    originY: number;
  } | null = null;
  let lastExpandedPopup: PopupLayoutState | null = null;
  let popupResize: {
    pointerId: number;
    edge: PopupResizeEdge;
    startX: number;
    startY: number;
    origin: PopupLayoutState;
  } | null = null;



  let tempPopupX = 0;
  let tempPopupY = 0;
  let tempPopupWidth = 0;
  let tempPopupHeight = 0;
  let layoutMetrics = EMPTY_SPLIT_LAYOUT_METRICS;
  let boundary = layoutMetrics.boundaryPercent;
  let resizePreviewRatio: number | null = null;
  let verticalResizerThumbPercent = 50;
  let horizontalResizerThumbPercent = 50;
  let previousResizerAxis = "none";
  let metadataRevision = 0;
  let pendingBarrierPanes: PaneId[] = [];
  let lastDispatchedSplitTargets: SplitTargets | null = null;

  onMount(() => {
    detachMetadata = runtime.control.onMessage((message) => {
      if (message.type === "streamMetadata") {
        metadataRevision += 1;
      }
    });
    resizeObserver = new ResizeObserver(() => {
      hostRect = host.getBoundingClientRect();
      touchRouter.updateHost(hostRect);
      clampPopupToHost();
      if (!resizingSplit && !popupInteracting && !layoutTransitionActive) {
        dispatchLayout();
      }
    });
    resizeObserver.observe(host);
    hostRect = host.getBoundingClientRect();

    touchRouter.updateHost(hostRect);
    clampPopupToHost();
    dispatchLayout();
  });

  onDestroy(() => {
    detachMetadata?.();
    resizeObserver?.disconnect();
    activeTouchPanes.clear();
    window.clearTimeout(provisionalLayoutTimer);
    window.removeEventListener("pointermove", resizeMove);
    window.removeEventListener("pointerup", endResize);
    window.removeEventListener("pointercancel", endResize);
    window.removeEventListener("pointermove", dragPopupMove);
    window.removeEventListener("pointerup", endPopupDrag);
    window.removeEventListener("pointercancel", endPopupDrag);

    window.removeEventListener("pointermove", resizePopupMove);
    window.removeEventListener("pointerup", endPopupResize);
    window.removeEventListener("pointercancel", endPopupResize);
    window.clearTimeout(safetyReleaseTimer);
  });



  $: if (!popupInteracting) {
    tempPopupX = $compositorStore.popup.x;
    tempPopupY = $compositorStore.popup.y;
    tempPopupWidth = $compositorStore.popup.width;
    tempPopupHeight = $compositorStore.popup.height;
  }

  $: allViewports = Array.from($compositorStore.viewports.values());
  $: visibleViewports = allViewports.filter((viewport) => viewport.visible);
  $: primaryViewport = allViewports.find(
    (viewport) => viewport.pane === "primary",
  );
  $: secondaryViewport = allViewports.find(
    (viewport) => viewport.pane === "secondary",
  );
  $: dualPaneReady = Boolean(primaryViewport && secondaryViewport);
  $: effectiveSecondaryPlacement =
    resolveSecondaryPlacement(
      $compositorStore.layoutMode,
      $compositorStore.secondaryPlacement,
    ) ?? "right";
  $: splitActive =
    dualPaneReady &&
    $compositorStore.layoutMode === "split" &&
    primaryViewport?.visible === true &&
    secondaryViewport?.visible === true;
  $: horizontalSplitActive =
    splitActive &&
    (effectiveSecondaryPlacement === "left" || effectiveSecondaryPlacement === "right");
  $: verticalSplitActive =
    splitActive &&
    (effectiveSecondaryPlacement === "top" || effectiveSecondaryPlacement === "bottom");
  $: fullPopupActive =
    dualPaneReady &&
    $compositorStore.layoutMode === "popup" &&
    primaryViewport?.visible === true &&
    Boolean($compositorStore.activeSecondaryApp);

  // Viewport mapping is physically fixed
  $: leftPane = "primary";
  $: rightPane = "secondary";
  $: fullPane = "primary";
  $: popupPane = "secondary";
  $: fullViewport = allViewports.find(
    (viewport) => viewport.pane === "primary",
  );
  $: popupViewport = allViewports.find(
    (viewport) => viewport.pane === "secondary",
  );

  $: popupVisible = fullPopupActive && $compositorStore.popup.visible;
  $: popupMinimized = popupVisible && $compositorStore.popup.minimized;
  $: popupBodyHeight = Math.max(
    0,
    Math.round($compositorStore.popup.height - POPUP_HEADER_HEIGHT),
  );
  $: layoutTransitionActive = isLayoutTransitionActive(
    $compositorStore.launchSequence.state,
  );

  function isPaneTrackedByBarrier(
    pane: PaneId,
    frozen: FrozenLayoutState,
  ): boolean {
    if (frozen.layoutMode === "single") {
      return pane === "primary";
    }
    if (frozen.layoutMode === "split") {
      return frozen.visibleViewports.some((viewport) => viewport.pane === pane && viewport.visible);
    }
    if (frozen.layoutMode === "popup") {
      if (pane === "primary") return true;
      return frozen.popup.visible && !frozen.popup.minimized;
    }
    return pane === "primary";
  }

  function isPaneReadyForBarrierRelease(
    pane: PaneId,
    launchSequence: typeof $compositorStore.launchSequence,
    primaryViewportSnapshot: ViewportModel | undefined,
    secondaryViewportSnapshot: ViewportModel | undefined,
    primaryMetadataGeneration: number,
    secondaryMetadataGeneration: number,
    primaryMetadataReady: boolean,
    secondaryMetadataReady: boolean,
    expectedPrimaryWidth: number | undefined,
    expectedSecondaryWidth: number | undefined,
    expectedPrimaryHeight: number | undefined,
    expectedSecondaryHeight: number | undefined,
  ): boolean {
    const viewport = pane === "primary"
      ? primaryViewportSnapshot
      : secondaryViewportSnapshot;
    const startGen = pane === "primary"
      ? launchSequence.primaryStartGen
      : launchSequence.secondaryStartGen;
    const metadataGeneration = pane === "primary"
      ? primaryMetadataGeneration
      : secondaryMetadataGeneration;
    const metadataReady = pane === "primary"
      ? primaryMetadataReady
      : secondaryMetadataReady;
    const expectedWidth = pane === "primary"
      ? expectedPrimaryWidth
      : expectedSecondaryWidth;
    const expectedHeight = pane === "primary"
      ? expectedPrimaryHeight
      : expectedSecondaryHeight;
    return isPaneBarrierReadyForRelease({
      pane,
      viewport,
      startGeneration: startGen,
      metadataGeneration,
      metadataReady,
      expectedWidth,
      expectedHeight,
    });
  }

  function getPendingBarrierPanes(
    launchSequence: typeof $compositorStore.launchSequence,
    primaryViewportSnapshot: ViewportModel | undefined,
    secondaryViewportSnapshot: ViewportModel | undefined,
    primaryMetadataGeneration: number,
    secondaryMetadataGeneration: number,
    primaryMetadataReady: boolean,
    secondaryMetadataReady: boolean,
    expectedPrimaryWidth: number | undefined,
    expectedSecondaryWidth: number | undefined,
    expectedPrimaryHeight: number | undefined,
    expectedSecondaryHeight: number | undefined,
  ): PaneId[] {
    if (!frozenLayoutState) return [];
    const panes: PaneId[] = [];
    for (const pane of ["primary", "secondary"] as PaneId[]) {
      if (!isPaneTrackedByBarrier(pane, frozenLayoutState)) continue;
      if (!isPaneReadyForBarrierRelease(
        pane,
        launchSequence,
        primaryViewportSnapshot,
        secondaryViewportSnapshot,
        primaryMetadataGeneration,
        secondaryMetadataGeneration,
        primaryMetadataReady,
        secondaryMetadataReady,
        expectedPrimaryWidth,
        expectedSecondaryWidth,
        expectedPrimaryHeight,
        expectedSecondaryHeight,
      )) {
        panes.push(pane);
      }
    }
    return panes;
  }

  function getCurrentHostRect(): DOMRect {
    if (!host) {
      return hostRect;
    }
    const liveRect = host.getBoundingClientRect();
    if (liveRect.width > 0 && liveRect.height > 0) {
      hostRect = liveRect;
      return liveRect;
    }
    return hostRect;
  }

  function getActiveExpectedSplitTargets(
    launchSequence: typeof $compositorStore.launchSequence,
    frozenLayoutMode: LayoutMode | undefined,
    splitRatio: number,
    hostWidth: number,
    hostHeight: number,
    dispatchedTargets: SplitTargets | null,
  ) {
    if (frozenLayoutMode !== "split") {
      return {
        expectedPrimaryPaneWidth: undefined,
        expectedSecondaryPaneWidth: undefined,
        expectedPaneHeight: undefined,
        source: "inactive",
      };
    }
    const primedTargets = hasCompleteSplitTargets({
      primaryWidth: launchSequence.expectedPrimaryPaneWidth,
      secondaryWidth: launchSequence.expectedSecondaryPaneWidth,
      paneHeight: launchSequence.expectedPaneHeight,
    })
      ? {
          primaryWidth: launchSequence.expectedPrimaryPaneWidth,
          secondaryWidth: launchSequence.expectedSecondaryPaneWidth,
          paneHeight: launchSequence.expectedPaneHeight,
        }
      : null;
    const targets = resolveExpectedSplitTargets({
      hostWidth,
      hostHeight,
      splitRatio,
      primedTargets,
      dispatchedTargets,
    });
    const source =
      hasCompleteSplitTargets(primedTargets)
        ? "launch_sequence"
        : hasCompleteSplitTargets(dispatchedTargets)
        ? "dispatched"
        : "host_rect";

    return {
      expectedPrimaryPaneWidth: targets.primaryWidth,
      expectedSecondaryPaneWidth: targets.secondaryWidth,
      expectedPaneHeight: targets.paneHeight,
      source,
    };
  }

  function getFrozenPaneStyles(layoutMode: LayoutMode, splitRatio: number) {
    if (layoutMode === "split") {
      const rect = getCurrentHostRect();
      const primedTargets = hasCompleteSplitTargets({
        primaryWidth: $compositorStore.launchSequence.expectedPrimaryPaneWidth,
        secondaryWidth: $compositorStore.launchSequence.expectedSecondaryPaneWidth,
        paneHeight: $compositorStore.launchSequence.expectedPaneHeight,
      })
        ? {
            primaryWidth: $compositorStore.launchSequence.expectedPrimaryPaneWidth,
            secondaryWidth: $compositorStore.launchSequence.expectedSecondaryPaneWidth,
            paneHeight: $compositorStore.launchSequence.expectedPaneHeight,
          }
        : null;
      const targets = resolveExpectedSplitTargets({
        hostWidth: rect.width,
        hostHeight: rect.height,
        splitRatio,
        primedTargets,
        dispatchedTargets: lastDispatchedSplitTargets,
      });
      const splitPaneStyles = buildSplitPaneStyles(targets);
      if (splitPaneStyles) {
        return splitPaneStyles;
      }
    }

    return {
      primary: paneStyle("primary"),
      secondary: paneStyle("secondary"),
    };
  }

  function handleTransitionChange(active: boolean) {
    window.clearTimeout(safetyReleaseTimer);
    if (active) {
      if (!frozenLayoutState) {
        hostRect = getCurrentHostRect();
        const layoutMode = $compositorStore.layoutMode;
        const splitRatio = $compositorStore.splitRatio;
        frozenLayoutState = {
          layoutMode,
          visibleViewports: Array.from($compositorStore.viewports.values()).map(v => ({ ...v })),
          paneStyles: getFrozenPaneStyles(layoutMode, splitRatio),
          popup: { ...$compositorStore.popup },
          fullPane: fullPane,
          popupPane: popupPane,
          splitRatio,
          effectiveSecondaryPlacement,
        };
        console.info(`[COMPOSITOR_BARRIER] event=freeze state=${$compositorStore.launchSequence.state} layout=${layoutMode}`);
        emitVerboseBarrierDiag("freeze", {
          state: $compositorStore.launchSequence.state,
          layoutMode,
          splitRatio,
        });

        // 6초 세이프 가드 타이머 시작 (어떤 원인으로든 6초 이상 배리어가 가두지 않도록 보장)
        safetyReleaseTimer = window.setTimeout(() => {
          if (frozenLayoutState) {
            console.warn("[COMPOSITOR_BARRIER] event=safety_unfreeze_timeout stuck protection triggered!");
            emitVerboseBarrierDiag("safety_unfreeze_timeout", {
              state: $compositorStore.launchSequence.state,
            });
            frozenLayoutState = null;
          }
        }, 6000);
      }
    }
  }

  $: handleTransitionChange(layoutTransitionActive);

  $: primaryMetadata = runtime.generations.getMetadata("primary");
  $: secondaryMetadata = runtime.generations.getMetadata("secondary");
  $: primaryMetadataGeneration = primaryMetadata?.generation ?? -1;
  $: secondaryMetadataGeneration = secondaryMetadata?.generation ?? -1;
  $: primaryMetadataReady = primaryMetadata?.firstFrameReady === true;
  $: secondaryMetadataReady = secondaryMetadata?.firstFrameReady === true;
  $: frozenExpectedTargets = getActiveExpectedSplitTargets(
    $compositorStore.launchSequence,
    frozenLayoutState?.layoutMode,
    frozenLayoutState?.splitRatio ?? $compositorStore.splitRatio,
    hostRect.width,
    hostRect.height,
    lastDispatchedSplitTargets,
  );
  $: expectedPaneHeight = frozenExpectedTargets.expectedPaneHeight;
  $: expectedPrimaryPaneWidth = frozenExpectedTargets.expectedPrimaryPaneWidth;
  $: expectedSecondaryPaneWidth = frozenExpectedTargets.expectedSecondaryPaneWidth;
  $: expectedSplitTargetSource = frozenExpectedTargets.source;

  $: pendingBarrierPanes = getPendingBarrierPanes(
    $compositorStore.launchSequence,
    primaryViewport,
    secondaryViewport,
    primaryMetadataGeneration,
    secondaryMetadataGeneration,
    primaryMetadataReady,
    secondaryMetadataReady,
    expectedPrimaryPaneWidth,
    expectedSecondaryPaneWidth,
    expectedPaneHeight,
    expectedPaneHeight,
  );

  $: if (frozenLayoutState) {
    void metadataRevision;
    emitVerboseBarrierDiag("pending_update", {
      state: $compositorStore.launchSequence.state,
      layoutMode: frozenLayoutState.layoutMode,
      pendingPanes: pendingBarrierPanes,
      primaryViewportGeneration: primaryViewport?.generation ?? -1,
      primaryViewportCommitted: primaryViewport?.committed ?? false,
      secondaryViewportGeneration: secondaryViewport?.generation ?? -1,
      secondaryViewportCommitted: secondaryViewport?.committed ?? false,
      primaryMetadataGeneration,
      primaryMetadataReady,
      secondaryMetadataGeneration,
      secondaryMetadataReady,
      expectedPrimaryPaneWidth: expectedPrimaryPaneWidth ?? -1,
      expectedSecondaryPaneWidth: expectedSecondaryPaneWidth ?? -1,
      expectedPaneHeight: expectedPaneHeight ?? -1,
      expectedSplitTargetSource,
    });
  }

  $: if (
    frozenLayoutState?.layoutMode === "split" &&
    isPositiveDimension(lastDispatchedSplitTargets?.primaryWidth) &&
    isPositiveDimension(lastDispatchedSplitTargets?.secondaryWidth)
  ) {
    const nextPaneStyles = buildSplitPaneStyles({
      primaryWidth: lastDispatchedSplitTargets.primaryWidth,
      secondaryWidth: lastDispatchedSplitTargets.secondaryWidth,
    });
    if (
      nextPaneStyles &&
      (
        frozenLayoutState.paneStyles.primary !== nextPaneStyles.primary ||
        frozenLayoutState.paneStyles.secondary !== nextPaneStyles.secondary
      )
    ) {
      frozenLayoutState = {
        ...frozenLayoutState,
        paneStyles: nextPaneStyles,
      };
      emitVerboseBarrierDiag("freeze_split_targets_applied", {
        state: $compositorStore.launchSequence.state,
        primaryWidth: lastDispatchedSplitTargets.primaryWidth,
        secondaryWidth: lastDispatchedSplitTargets.secondaryWidth,
        paneHeight: lastDispatchedSplitTargets.paneHeight ?? -1,
        source: "dispatched",
      });
    }
  }

  function releaseFrozenBarrier(reason: string) {
    if (!frozenLayoutState) return;
    console.info(`[COMPOSITOR_BARRIER] event=release state=${$compositorStore.launchSequence.state} reason=${reason}`);
    emitVerboseBarrierDiag("release", {
      state: $compositorStore.launchSequence.state,
      reason,
      pendingPanes: pendingBarrierPanes,
    });
    frozenLayoutState = null;
    activeTouchPanes.clear();
    touchRouter.reset();
  }

  $: if (frozenLayoutState && !layoutTransitionActive) {
    if (pendingBarrierPanes.length === 0) {
      releaseFrozenBarrier("pane_barriers_complete");
    }
  }

  function shouldShowPaneBarrier(pane: PaneId): boolean {
    return frozenLayoutState !== null && pendingBarrierPanes.includes(pane);
  }



  $: currentSplitActive = frozenLayoutState ? (frozenLayoutState.layoutMode === "split" && frozenLayoutState.visibleViewports.some(v => v.pane === "primary" && v.visible) && frozenLayoutState.visibleViewports.some(v => v.pane === "secondary" && v.visible)) : splitActive;
  $: currentFullPopupActive = frozenLayoutState ? (frozenLayoutState.layoutMode === "popup" && frozenLayoutState.visibleViewports.some(v => v.pane === "primary" && v.visible) && frozenLayoutState.visibleViewports.some(v => v.pane === "secondary" && v.visible)) : fullPopupActive;
  $: currentPopupVisible = frozenLayoutState ? (frozenLayoutState.layoutMode === "popup" && frozenLayoutState.popup.visible) : popupVisible;
  $: currentPopupMinimized = frozenLayoutState ? (frozenLayoutState.popup.minimized) : popupMinimized;
  $: currentTempPopupX = frozenLayoutState ? frozenLayoutState.popup.x : tempPopupX;
  $: currentTempPopupY = frozenLayoutState ? frozenLayoutState.popup.y : tempPopupY;
  $: currentTempPopupWidth = frozenLayoutState ? frozenLayoutState.popup.width : tempPopupWidth;
  $: currentTempPopupHeight = frozenLayoutState ? frozenLayoutState.popup.height : tempPopupHeight;
  $: currentVisibleViewports = frozenLayoutState ? frozenLayoutState.visibleViewports : visibleViewports;
  $: currentFullViewport = frozenLayoutState ? frozenLayoutState.visibleViewports.find(v => v.pane === "primary") : fullViewport;
  $: currentPopupViewport = frozenLayoutState ? frozenLayoutState.visibleViewports.find(v => v.pane === "secondary") : popupViewport;
  $: currentPrimaryViewport = frozenLayoutState ? frozenLayoutState.visibleViewports.find(v => v.pane === "primary") : primaryViewport;
  $: currentSecondaryViewport = frozenLayoutState ? frozenLayoutState.visibleViewports.find(v => v.pane === "secondary") : secondaryViewport;
  $: layoutMetrics = computeSplitLayoutMetrics(
    hostRect.width,
    hostRect.height,
    $compositorStore.splitRatio,
  );
  $: activeSplitRatio = frozenLayoutState ? frozenLayoutState.splitRatio : $compositorStore.splitRatio;
  $: activeSecondaryPlacement = frozenLayoutState ? frozenLayoutState.effectiveSecondaryPlacement : effectiveSecondaryPlacement;
  $: currentResizerAxis = getResizerAxis(activeSecondaryPlacement);
  $: if (currentResizerAxis !== previousResizerAxis) {
    verticalResizerThumbPercent = 50;
    horizontalResizerThumbPercent = 50;
    previousResizerAxis = currentResizerAxis;
  }
  $: boundary =
    splitActive && isDockedPlacement(activeSecondaryPlacement)
      ? computeDockedPaneLayout(
          hostRect.width,
          hostRect.height,
          activeSplitRatio,
          activeSecondaryPlacement,
        ).boundaryPercent
      : layoutMetrics.boundaryPercent;
  $: layoutTrigger = [
    Math.round(hostRect.width),
    Math.round(hostRect.height),
    $compositorStore.layoutMode,
    $compositorStore.splitRatio.toFixed(4),
    `${$compositorStore.popup.visible ? 1 : 0}:${$compositorStore.popup.minimized ? 1 : 0}:${Math.round($compositorStore.popup.x)}:${Math.round($compositorStore.popup.y)}:${Math.round($compositorStore.popup.width)}:${Math.round($compositorStore.popup.height)}`,
    visibleViewports
      .map(
        (viewport) =>
          `${viewport.pane}:${viewport.visible ? 1 : 0}:${viewport.generation}:${viewport.width}x${viewport.height}`,
      )
      .join("|"),
  ].join(";");

  $: if (host && layoutTrigger) {
    updateChrome();
    if (!resizingSplit && !popupInteracting && !layoutTransitionActive && !frozenLayoutState) {
      dispatchLayout();
    }
  }

  $: if (
    splitActive &&
    secondaryViewport?.committed === true &&
    !layoutTransitionActive &&
    !frozenLayoutState &&
    !resizingSplit
  ) {
    dispatchLayout(true);
  }

  function paneStyle(pane: PaneId): string {
    if (splitActive && isDockedPlacement(effectiveSecondaryPlacement)) {
      const layout = computeDockedPaneLayout(
        hostRect.width,
        hostRect.height,
        $compositorStore.splitRatio,
        effectiveSecondaryPlacement,
      );
      const styles = buildDockedPaneStyles(layout);
      return pane === "secondary" ? styles.secondary : styles.primary;
    }
    return "left:0;top:0;width:100%;height:100%;";
  }

  function setLayoutMode(mode: LayoutMode) {
    if (!dualPaneReady) return;

    // Restore popup window if the mode is already popup and user clicks popup tab again
    if (mode === "popup" && $compositorStore.layoutMode === "popup") {
      restorePopup();
      return;
    }

    if (mode === $compositorStore.layoutMode) return;
    activeTouchPanes.clear();
    touchRouter.reset();
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
    scheduleLayoutFlush();
    if (mode === "popup") {
      requestPaneKeyframes("primary", "secondary");
    }
  }

  function expand(pane: PaneId) {
    activeTouchPanes.clear();
    touchRouter.reset();
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      viewports.forEach((viewport, key) =>
        viewports.set(key, { ...viewport, visible: key === pane }),
      );
      return { ...state, viewports, layoutMode: "single" };
    });
    scheduleLayoutFlush();
    runtime.requestKeyframe(pane);
  }

  function swap() {
    if (!dualPaneReady) return;
    activeTouchPanes.clear();
    touchRouter.reset();

    const primaryPkg = $compositorStore.activePrimaryApp;
    const secondaryPkg = $compositorStore.activeSecondaryApp;

    if (primaryPkg && secondaryPkg) {
      // Swap apps in saved AppPairs too
      const pairsRaw = localStorage.getItem("castla_app_pairs");
      if (pairsRaw) {
        try {
          const pairs = JSON.parse(pairsRaw);
          if (Array.isArray(pairs)) {
            const updated = pairs.map((pair) => {
              if (
                Array.isArray(pair.apps) &&
                ((pair.apps[0] === primaryPkg &&
                  pair.apps[1] === secondaryPkg) ||
                  (pair.apps[0] === secondaryPkg &&
                    pair.apps[1] === primaryPkg))
              ) {
                return { ...pair, apps: [secondaryPkg, primaryPkg] };
              }
              // Support legacy WorkspaceRecord migration
              if (
                (pair.primaryApp === primaryPkg &&
                  pair.secondaryApp === secondaryPkg) ||
                (pair.primaryApp === secondaryPkg &&
                  pair.secondaryApp === primaryPkg)
              ) {
                return {
                  ...pair,
                  primaryApp: secondaryPkg,
                  secondaryApp: primaryPkg,
                };
              }
              return pair;
            });
            localStorage.setItem("castla_app_pairs", JSON.stringify(updated));
          }
        } catch {}
      }

      // Delegate to E2E ACK launch state machine instead of timing-based manual launches
      if (appLauncher) {
        const layoutMode = $compositorStore.layoutMode === "popup" ? "popup" : "split";
        appLauncher.startLaunchSequence({
          primaryPkg: secondaryPkg,
          secondaryPkg: primaryPkg,
          layoutMode: layoutMode,
          secondaryPlacement:
            layoutMode === "popup"
              ? "popup"
              : resolveSecondaryPlacement(
                  $compositorStore.layoutMode,
                  $compositorStore.secondaryPlacement,
                ) ?? "right",
        });
        appLauncher.closeDrawer?.();
      } else {
        // Fallback for edge cases without appLauncher ref
        console.warn("[SWAP] appLauncher ref missing, falling back to manual swap");
        compositorStore.update((state) => ({
          ...state,
          activePrimaryApp: secondaryPkg,
          activeSecondaryApp: primaryPkg,
        }));
        dispatchLayout(true);
        runtime.launchApp(secondaryPkg, "primary", undefined, false);
        runtime.requestKeyframe("primary");
        setTimeout(() => {
          runtime.launchApp(primaryPkg, "secondary", undefined, false);
          runtime.requestKeyframe("secondary");
        }, 80);
        scheduleLayoutFlush();
      }
    }
  }

  function beginResize(event: PointerEvent) {
    event.preventDefault();
    activeTouchPanes.clear();
    touchRouter.reset();
    resizingSplit = true;
    resizePreviewRatio = $compositorStore.splitRatio;
    updateChrome();
    window.addEventListener("pointermove", resizeMove);
    window.addEventListener("pointerup", endResize, { once: true });
    window.addEventListener("pointercancel", endResize, { once: true });
  }

  function resizeMove(event: PointerEvent) {
    if (!resizingSplit || !host) return;
    const rect = host.getBoundingClientRect();
    if (effectiveSecondaryPlacement === "left" || effectiveSecondaryPlacement === "right") {
      verticalResizerThumbPercent = clamp(
        ((event.clientY - rect.top) / rect.height) * 100,
        10,
        90,
      );
    } else if (effectiveSecondaryPlacement === "top" || effectiveSecondaryPlacement === "bottom") {
      horizontalResizerThumbPercent = clamp(
        ((event.clientX - rect.left) / rect.width) * 100,
        10,
        90,
      );
    }
    const nextRatio =
      effectiveSecondaryPlacement === "top"
        ? clamp(1 - ((event.clientY - rect.top) / rect.height), 0.1, 0.9)
        : effectiveSecondaryPlacement === "bottom"
          ? clamp((event.clientY - rect.top) / rect.height, 0.1, 0.9)
          : effectiveSecondaryPlacement === "left"
            ? clamp(1 - ((event.clientX - rect.left) / rect.width), 0.22, 0.78)
            : clamp((event.clientX - rect.left) / rect.width, 0.22, 0.78);
    resizePreviewRatio = nextRatio;
    updateChrome(nextRatio);
  }

  function endResize() {
    const committedRatio = resizePreviewRatio ?? $compositorStore.splitRatio;
    const persistedRatio = persistSplitRatioForPlacement(
      committedRatio,
      effectiveSecondaryPlacement,
    );
    localStorage.setItem("castla_split_ratio", String(persistedRatio));
    compositorStore.update((state) => ({ ...state, splitRatio: persistedRatio }));
    resizePreviewRatio = null;
    resizingSplit = false;
    window.removeEventListener("pointermove", resizeMove);
    window.removeEventListener("pointerup", endResize);
    window.removeEventListener("pointercancel", endResize);
    lastDispatchedSplitTargets = null;
    hostRect = getCurrentHostRect();
    touchRouter.updateHost(hostRect);
    updateChrome(committedRatio);
    dispatchLayout(true);
    requestPaneKeyframes("primary", "secondary");
    touchRouter.reset();
  }

  function beginPopupDrag(event: PointerEvent) {
    if (!popupVisible) return;
    event.preventDefault();
    event.stopPropagation();
    const header = event.currentTarget as HTMLElement;
    header.setPointerCapture?.(event.pointerId);
    popupInteracting = true;
    activeTouchPanes.clear();
    touchRouter.reset();
    popupDrag = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      originX: $compositorStore.popup.x,
      originY: $compositorStore.popup.y,
    };
    window.addEventListener("pointermove", dragPopupMove);
    window.addEventListener("pointerup", endPopupDrag, { once: true });
    window.addEventListener("pointercancel", endPopupDrag, { once: true });
  }

  function dragPopupMove(event: PointerEvent) {
    if (!popupDrag || event.pointerId !== popupDrag.pointerId) return;
    const nextPopup = constrainPopup({
      ...$compositorStore.popup,
      x: popupDrag.originX + (event.clientX - popupDrag.startX),
      y: popupDrag.originY + (event.clientY - popupDrag.startY),
    });
    applyPopupState(nextPopup, false);
  }

  function endPopupDrag(event?: PointerEvent) {
    if (popupDrag && event && popupDrag.pointerId !== event.pointerId) return;

    // Tap detection: trigger restore if pointer was tapped with minimal dragging movement
    if (popupDrag && event && $compositorStore.popup.minimized) {
      const distance = Math.hypot(
        event.clientX - popupDrag.startX,
        event.clientY - popupDrag.startY,
      );
      if (distance < 6) {
        restorePopup();
      }
    }

    popupDrag = null;
    popupInteracting = false;
    window.removeEventListener("pointermove", dragPopupMove);
    window.removeEventListener("pointerup", endPopupDrag);
    window.removeEventListener("pointercancel", endPopupDrag);
    persistPopupState($compositorStore.popup);
    logPopupState($compositorStore.popup);
  }



  function beginPopupResize(event: PointerEvent, edge: PopupResizeEdge) {
    if (!popupVisible) return;
    event.preventDefault();
    event.stopPropagation();
    const handle = event.currentTarget as HTMLElement;
    handle.setPointerCapture?.(event.pointerId);
    popupInteracting = true;
    activeTouchPanes.clear();
    touchRouter.reset();
    popupResize = {
      pointerId: event.pointerId,
      edge,
      startX: event.clientX,
      startY: event.clientY,
      origin: { ...$compositorStore.popup },
    };

    // Copy popup bounds to temporary state variables
    tempPopupX = $compositorStore.popup.x;
    tempPopupY = $compositorStore.popup.y;
    tempPopupWidth = $compositorStore.popup.width;
    tempPopupHeight = $compositorStore.popup.height;

    window.addEventListener("pointermove", resizePopupMove);
    window.addEventListener("pointerup", endPopupResize, { once: true });
    window.addEventListener("pointercancel", endPopupResize, { once: true });
  }

  function resizePopupMove(event: PointerEvent) {
    if (!popupResize || event.pointerId !== popupResize.pointerId) return;
    const dx = event.clientX - popupResize.startX;
    const dy = event.clientY - popupResize.startY;
    let nextX = popupResize.origin.x;
    let nextY = popupResize.origin.y;
    let nextWidth = popupResize.origin.width;
    let nextHeight = popupResize.origin.height;

    if (popupResize.edge.includes("e")) {
      nextWidth += dx;
    }
    if (popupResize.edge.includes("s")) {
      nextHeight += dy;
    }
    if (popupResize.edge.includes("w")) {
      nextWidth -= dx;
      nextX += dx;
    }
    if (popupResize.edge.includes("n")) {
      nextHeight -= dy;
      nextY += dy;
    }

    // Constrain temporary bounds locally
    const maxWidth = Math.max(
      POPUP_MIN_WIDTH,
      hostRect.width - POPUP_MARGIN * 2,
    );
    const maxHeight = Math.max(
      POPUP_MIN_HEIGHT,
      hostRect.height - POPUP_MARGIN * 2,
    );
    const width = clamp(nextWidth, POPUP_MIN_WIDTH, maxWidth);
    const height = clamp(nextHeight, POPUP_MIN_HEIGHT, maxHeight);
    const maxX = Math.max(POPUP_MARGIN, hostRect.width - width - POPUP_MARGIN);
    const maxY = Math.max(
      POPUP_MARGIN,
      hostRect.height - height - POPUP_MARGIN,
    );

    tempPopupX = clamp(nextX, POPUP_MARGIN, maxX);
    tempPopupY = clamp(nextY, POPUP_MARGIN, maxY);
    tempPopupWidth = width;
    tempPopupHeight = height;
  }

  function endPopupResize(event?: PointerEvent) {
    if (popupResize && event && popupResize.pointerId !== event.pointerId)
      return;
    popupResize = null;
    popupInteracting = false;
    window.removeEventListener("pointermove", resizePopupMove);
    window.removeEventListener("pointerup", endPopupResize);
    window.removeEventListener("pointercancel", endPopupResize);

    // Commit the final resized bounds and trigger render / layout dispatch at the very end
    const finalPopup = normalizePopupForStreaming({
      ...$compositorStore.popup,
      x: tempPopupX,
      y: tempPopupY,
      width: tempPopupWidth,
      height: tempPopupHeight,
    });
    applyPopupState(finalPopup, true);

    scheduleLayoutFlush();
    requestPaneKeyframes("primary", "secondary");
    touchRouter.reset();
  }

  function minimizePopup() {
    const current = $compositorStore.popup;
    if (!current.minimized) {
      lastExpandedPopup = {
        ...current,
        minimized: false,
      };
    }
    const targetX = Math.max(POPUP_MARGIN, hostRect.width - 60 - POPUP_MARGIN);
    const targetY = Math.max(POPUP_MARGIN, hostRect.height - 60 - POPUP_MARGIN);

    const nextPopup = constrainPopup({
      ...current,
      x: targetX,
      y: targetY,
      minimized: true,
    });
    applyPopupState(nextPopup, true);
    scheduleLayoutFlush();
  }

  function getPopupStreamLayout(popup: PopupLayoutState) {
    const source = lastExpandedPopup ?? popup;
    return {
      width: align16(source.width),
      height: align16(Math.max(POPUP_MIN_HEIGHT, source.height - POPUP_HEADER_HEIGHT)),
    };
  }

  function restorePopup() {
    const current = $compositorStore.popup;
    const restoreSource = lastExpandedPopup ?? current;

    const maxWidth = Math.max(
      POPUP_MIN_WIDTH,
      hostRect.width - POPUP_MARGIN * 2,
    );
    const maxHeight = Math.max(
      POPUP_MIN_HEIGHT,
      hostRect.height - POPUP_MARGIN * 2,
    );
    const width = clamp(current.width, POPUP_MIN_WIDTH, maxWidth);
    const height = clamp(current.height, POPUP_MIN_HEIGHT, maxHeight);

    const nextPopup = constrainPopup({
      ...current,
      visible: true,
      minimized: false,
      width,
      height,
      x: restoreSource.x,
      y: restoreSource.y,
    });
    applyPopupState(nextPopup, true);
    scheduleLayoutFlush();
    if (popupPane) {
      runtime.requestKeyframe(popupPane);
    }
  }

  function toggleMinimizePopup(event: MouseEvent | PointerEvent) {
    event.stopPropagation();
    if ($compositorStore.popup.minimized) {
      restorePopup();
    } else {
      minimizePopup();
    }
  }

  function hidePopup() {
    hideSecondary();
  }

  export function hideSecondary() {
    activeTouchPanes.clear();
    touchRouter.reset();
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      viewports.forEach((viewport, key) =>
        viewports.set(key, { ...viewport, visible: key === "primary" }),
      );
      return {
        ...state,
        viewports,
        layoutMode: "single",
        popup: { ...state.popup, visible: false, minimized: false },
      };
    });
    scheduleLayoutFlush();
    runtime.requestKeyframe("primary");
  }

  interface CachedAppInfo {
    packageName: string;
    label: string;
  }

  function getRealAppLabel(packageName: string): string {
    if (!packageName) return t($compositorStore.language, "subWindow");
    if (packageName.startsWith("workspace:")) return t($compositorStore.language, "appPair");

    try {
      const cached = localStorage.getItem("castla_cached_apps_v1");
      if (cached) {
        const parsed = JSON.parse(cached) as CachedAppInfo[];
        if (Array.isArray(parsed)) {
          const matched = parsed.find((app) => app.packageName === packageName);
          if (matched && matched.label) {
            return matched.label;
          }
        }
      }
    } catch {}

    // Fallback parser if not found in local storage cache
    const parts = packageName.split(".");
    const lastPart = parts[parts.length - 1];
    if (!lastPart) return t($compositorStore.language, "subWindow");
    return lastPart.charAt(0).toUpperCase() + lastPart.slice(1);
  }

  function shouldDelayProvisionalLayout(): boolean {
    if (resizingSplit || popupInteracting) return false;
    const hasCommittedStream = visibleViewports.some(
      (viewport) => viewport.committed,
    );
    if (hasCommittedStream) return false;
    return runtime.currentAppLaunchSequence() === 0;
  }

  function shouldLockExplicitLayoutTargets(): boolean {
    return shouldLockLayoutTargets({
      resizingSplit,
      popupInteracting,
      layoutTransitionActive,
      frozenLayoutActive: frozenLayoutState !== null,
    });
  }

  function shouldBlockLayoutDispatchForMissingSplitTargets(): boolean {
    const effectiveLayoutMode =
      frozenLayoutState?.layoutMode ?? $compositorStore.layoutMode;
    if (effectiveLayoutMode !== "split") {
      return false;
    }
    if (
      effectiveSecondaryPlacement === "top" ||
      effectiveSecondaryPlacement === "bottom"
    ) {
      return false;
    }
    return shouldLockExplicitLayoutTargets() && !lastDispatchedSplitTargets;
  }

  function getLockedSplitTargets(alignedHeight: number) {
    if (!shouldLockExplicitLayoutTargets()) {
      return null;
    }
    if (
      lastDispatchedSplitTargets?.primaryWidth &&
      lastDispatchedSplitTargets?.secondaryWidth &&
      lastDispatchedSplitTargets?.paneHeight
    ) {
      return lastDispatchedSplitTargets;
    }
    return {
      primaryWidth: layoutMetrics.primaryWidth,
      secondaryWidth: layoutMetrics.secondaryWidth,
      paneHeight: alignedHeight,
    };
  }

  // Explicit immediate layout dispatch to backend with control queue promise resolution
  export function dispatchLayoutNow(
    mode: LayoutMode,
    splitRatio: number,
    popup: PopupLayoutState,
    seqId?: number
  ): Promise<void> {
    return new Promise((resolve) => {
      if (!host) {
        resolve();
        return;
      }
      
      const rect = hostRect.width > 0 && hostRect.height > 0 ? hostRect : host.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) {
        resolve();
        return;
      }
      const alignedHeight = align16(rect.height);

      console.info(`[LAYOUT_DISPATCH] seq=${seqId ?? $compositorStore.launchSequence.id} mode=${mode} splitRatio=${splitRatio.toFixed(4)} popupVisible=${popup.visible}`);

      if (mode === "popup" && primaryViewport && secondaryViewport) {
        lastDispatchedSplitTargets = null;
        const popupWidth = align16(popup.width);
        const popupHeight = align16(popup.visible && !popup.minimized ? Math.max(POPUP_MIN_HEIGHT, popup.height - POPUP_HEADER_HEIGHT) : rect.height);
        const layoutPipelines = [
          { id: "primary", width: align16(rect.width), height: alignedHeight, visible: true },
          { id: "secondary", width: popupWidth, height: popupHeight, visible: popup.visible && !popup.minimized }
        ];
        syncViewportLayout(layoutPipelines);
        runtime.sendLayout(layoutPipelines, seqId);
      } else if (mode === "split") {
        const placement =
          resolveSecondaryPlacement(
            $compositorStore.layoutMode,
            $compositorStore.secondaryPlacement,
          ) ?? "right";
        const dockedLayout = isDockedPlacement(placement)
          ? computeDockedPaneLayout(rect.width, rect.height, splitRatio, placement)
          : null;
        lastDispatchedSplitTargets =
          placement === "left" || placement === "right"
            ? {
                primaryWidth: dockedLayout?.primary.width ?? align16(rect.width * splitRatio),
                secondaryWidth: dockedLayout?.secondary.width ?? align16(rect.width * (1 - splitRatio)),
                paneHeight: alignedHeight,
              }
            : null;
        const layoutPipelines = [
          {
            id: "primary",
            width: dockedLayout?.primary.width ?? align16(rect.width),
            height: dockedLayout?.primary.height ?? alignedHeight,
            visible: true,
          },
          {
            id: "secondary",
            width: dockedLayout?.secondary.width ?? align16(rect.width),
            height: dockedLayout?.secondary.height ?? alignedHeight,
            visible: true,
          }
        ];
        syncViewportLayout(layoutPipelines);
        runtime.sendLayout(layoutPipelines, seqId);
      } else {
        lastDispatchedSplitTargets = null;
        const activePane = visibleViewports[0]?.pane ?? "primary";
        const hiddenPane = activePane === "primary" ? "secondary" : "primary";
        const alignedWidth = align16(rect.width);
        const layoutPipelines = [
          { id: activePane, width: alignedWidth, height: alignedHeight, visible: true },
          { id: hiddenPane, width: alignedWidth, height: alignedHeight, visible: false }
        ];
        syncViewportLayout(layoutPipelines);
        runtime.sendLayout(layoutPipelines, seqId);
      }

      // Explicitly wait until the socket bufferedAmount drops to 0, ensuring complete network flush
      const checkInterval = setInterval(() => {
        if (!runtime.hasPendingBufferedAmount()) {
          clearInterval(checkInterval);
          resolve();
        }
      }, 5);

      // Safe guard guard-timeout to prevent blocking forever if disconnected
      setTimeout(() => {
        clearInterval(checkInterval);
        resolve();
      }, 100);
    });
  }

  export function primeLayoutTargets(
    mode: LayoutMode,
    splitRatio: number,
    popup: PopupLayoutState,
    secondaryPlacement?: "left" | "right" | "top" | "bottom" | "popup" | null,
  ): SplitTargets | null {
    if (!host) return null;
    hostRect = getCurrentHostRect();
    const rect = hostRect.width > 0 && hostRect.height > 0 ? hostRect : host.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return null;

    const placement =
      mode === "split"
        ? resolveSecondaryPlacement("split", secondaryPlacement) ?? "right"
        : resolveSecondaryPlacement(
            $compositorStore.layoutMode,
            $compositorStore.secondaryPlacement,
          ) ?? "right";

    if (mode === "split" && (placement === "left" || placement === "right")) {
      const layout = computeDockedPaneLayout(rect.width, rect.height, splitRatio, placement);
      lastDispatchedSplitTargets = {
        primaryWidth: layout.primary.width,
        secondaryWidth: layout.secondary.width,
        paneHeight: align16(rect.height),
      };
      emitVerboseBarrierDiag("layout_targets_primed", {
        mode,
        splitRatio,
        primaryWidth: layout.primary.width,
        secondaryWidth: layout.secondary.width,
        paneHeight: align16(rect.height),
      });
      return lastDispatchedSplitTargets;
    }

    lastDispatchedSplitTargets = null;
    if (mode === "popup") {
      emitVerboseBarrierDiag("layout_targets_primed", {
        mode,
        popupVisible: popup.visible,
        popupMinimized: popup.minimized,
      });
    }
    return null;
  }

  function dispatchLayout(forceImmediate = false) {
    window.clearTimeout(provisionalLayoutTimer);
    if (shouldBlockLayoutDispatchForMissingSplitTargets()) {
      return;
    }
    if (!forceImmediate && shouldDelayProvisionalLayout()) {
      provisionalLayoutTimer = window.setTimeout(() => {
        provisionalLayoutTimer = 0;
        sendCurrentLayout();
      }, PROVISIONAL_LAYOUT_SETTLE_MS);
      return;
    }
    sendCurrentLayout();
  }

  function sendCurrentLayout() {
    if (!host) return;
    const rect =
      hostRect.width > 0 && hostRect.height > 0
        ? hostRect
        : host.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    const alignedHeight = align16(rect.height);
    const effectiveLayoutMode = frozenLayoutState?.layoutMode ?? $compositorStore.layoutMode;

    if (effectiveLayoutMode === "popup" && fullViewport && popupViewport) {
      lastDispatchedSplitTargets = null;
      const fullPaneId = fullViewport.pane as PaneId;
      const popupPaneId = popupViewport.pane as PaneId;
      const popupState = frozenLayoutState?.popup ?? $compositorStore.popup;
      const popupWidth = align16(popupState.width);
      const popupHeight = align16(
        popupState.visible && !popupState.minimized
          ? Math.max(POPUP_MIN_HEIGHT, popupState.height - POPUP_HEADER_HEIGHT)
          : rect.height,
      );
      const layoutPipelines = [
        {
          id: fullPaneId,
          width: align16(rect.width),
          height: alignedHeight,
          visible: true,
        },
        {
          id: popupPaneId,
          width: popupWidth,
          height: popupHeight,
          visible: popupState.visible && !popupState.minimized,
        },
      ];
      syncViewportLayout(layoutPipelines);
      runtime.sendLayout(layoutPipelines);
      return;
    }

    if (effectiveLayoutMode === "split" && isDockedPlacement(effectiveSecondaryPlacement)) {
      const verticalDock =
        effectiveSecondaryPlacement === "top" ||
        effectiveSecondaryPlacement === "bottom";
      const dockedLayout = computeDockedPaneLayout(
        rect.width,
        rect.height,
        $compositorStore.splitRatio,
        effectiveSecondaryPlacement,
      );
      const lockedSplitTargets = verticalDock ? null : getLockedSplitTargets(alignedHeight);
      const primaryWidth =
        lockedSplitTargets?.primaryWidth ??
        expectedPrimaryPaneWidth ??
        dockedLayout.primary.width;
      const secondaryWidth =
        lockedSplitTargets?.secondaryWidth ??
        expectedSecondaryPaneWidth ??
        dockedLayout.secondary.width;
      const effectiveHeight =
        lockedSplitTargets?.paneHeight ??
        expectedPaneHeight ??
        alignedHeight;
      lastDispatchedSplitTargets = verticalDock
        ? null
        : {
            primaryWidth,
            secondaryWidth,
            paneHeight: effectiveHeight,
          };
      const layoutPipelines = [
        {
          id: leftPane,
          width: verticalDock ? dockedLayout.primary.width : primaryWidth,
          height: verticalDock ? dockedLayout.primary.height : effectiveHeight,
          visible: true,
        },
        {
          id: rightPane,
          width: verticalDock ? dockedLayout.secondary.width : secondaryWidth,
          height: verticalDock ? dockedLayout.secondary.height : effectiveHeight,
          visible: true,
        },
      ];
      syncViewportLayout(layoutPipelines);
      runtime.sendLayout(layoutPipelines);
      return;
    }

    lastDispatchedSplitTargets = null;
    const activePane = visibleViewports[0]?.pane ?? "primary";
    const hiddenPane: PaneId =
      activePane === "primary" ? "secondary" : "primary";
    const alignedWidth = align16(rect.width);
    const layoutPipelines = [
      {
        id: activePane,
        width: alignedWidth,
        height: alignedHeight,
        visible: true,
      },
      {
        id: hiddenPane,
        width: alignedWidth,
        height: alignedHeight,
        visible: false,
      },
    ];
    syncViewportLayout(layoutPipelines);
    runtime.sendLayout(layoutPipelines);
  }

  function syncViewportLayout(
    layout: Array<{
      id: PaneId;
      width: number;
      height: number;
      visible?: boolean;
    }>,
  ) {
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      for (const pipeline of layout) {
        const previous = viewports.get(pipeline.id);
        viewports.set(pipeline.id, {
          pane: pipeline.id,
          width: pipeline.width,
          height: pipeline.height,
          streamWidth: previous?.streamWidth,
          streamHeight: previous?.streamHeight,
          committed: previous?.committed ?? false,
          generation: previous?.generation ?? 0,
          visible: pipeline.visible ?? previous?.visible ?? pipeline.id === "primary",
        });
      }
      return { ...state, viewports };
    });
  }

  // Exact DOMRect based viewport hit testing
  function findPaneByCoords(
    clientX: number,
    clientY: number,
  ): {
    pane: PaneId;
    element: HTMLElement;
    fitMode: "contain" | "fill";
  } | null {
    if (!host) return null;

    // Check popup window rect first if popup mode active
    if (fullPopupActive && popupVisible && !popupMinimized) {
      const popupEl = host.querySelector(".popup-window") as HTMLElement;
      if (popupEl) {
        const target = document.elementFromPoint(
          clientX,
          clientY,
        ) as HTMLElement | null;
        if (
          target?.closest(".popup-header") ||
          target?.closest(".popup-resize-handle")
        ) {
          return null;
        }
        const popupBodyEl = popupEl.querySelector(".popup-body") as HTMLElement;
        if (popupBodyEl) {
          const rect = popupBodyEl.getBoundingClientRect();
          if (
            clientX >= rect.left &&
            clientX <= rect.right &&
            clientY >= rect.top &&
            clientY <= rect.bottom
          ) {
            const paneEl = popupBodyEl.querySelector(".viewport-pane") as HTMLElement;
            if (paneEl) {
              return { pane: "secondary", element: paneEl, fitMode: "fill" };
            }
          }
        }
      }

      const fullEl = host.querySelector(
        `.viewport-pane[data-pane="primary"]`,
      ) as HTMLElement;
      if (fullEl) {
        const rect = fullEl.getBoundingClientRect();
        if (
          clientX >= rect.left &&
          clientX <= rect.right &&
          clientY >= rect.top &&
          clientY <= rect.bottom
        ) {
          return { pane: "primary", element: fullEl, fitMode: "contain" };
        }
      }
    }

    // Check split panes rects if split mode active
    if (splitActive) {
      const leftEl = host.querySelector(
        `.viewport-pane[data-pane="primary"]`,
      ) as HTMLElement;
      const rightEl = host.querySelector(
        `.viewport-pane[data-pane="secondary"]`,
      ) as HTMLElement;
      if (leftEl) {
        const rect = leftEl.getBoundingClientRect();
        if (
          clientX >= rect.left &&
          clientX <= rect.right &&
          clientY >= rect.top &&
          clientY <= rect.bottom
        ) {
          return { pane: "primary", element: leftEl, fitMode: "fill" };
        }
      }
      if (rightEl) {
        const rect = rightEl.getBoundingClientRect();
        if (
          clientX >= rect.left &&
          clientX <= rect.right &&
          clientY >= rect.top &&
          clientY <= rect.bottom
        ) {
          return { pane: "secondary", element: rightEl, fitMode: "fill" };
        }
      }
    }

    // Default to active single pane if visible
    const activeViewport = currentVisibleViewports[0];
    if (activeViewport) {
      const paneEl = host.querySelector(
        `.viewport-pane[data-pane="${activeViewport.pane}"]`,
      ) as HTMLElement;
      if (paneEl) {
        const rect = paneEl.getBoundingClientRect();
        if (
          clientX >= rect.left &&
          clientX <= rect.right &&
          clientY >= rect.top &&
          clientY <= rect.bottom
        ) {
          return {
            pane: activeViewport.pane,
            element: paneEl,
            fitMode: "contain",
          };
        }
      }
    }

    return null;
  }

  function getRenderedViewportByPane(pane: PaneId): ViewportModel | undefined {
    if (currentSplitActive) {
      return currentVisibleViewports.find((entry) => entry.pane === pane);
    }
    if (currentFullPopupActive) {
      if (pane === "primary") return currentFullViewport ?? undefined;
      if (pane === "secondary") return currentPopupViewport ?? undefined;
    }
    return currentVisibleViewports.find((entry) => entry.pane === pane);
  }

  function handleBarrierInput(event: PointerEvent) {
    console.info(`[COMPOSITOR_BARRIER] event=blocked_input reason=transition-active clientX=${event.clientX} clientY=${event.clientY}`);
  }

  function handlePointer(event: PointerEvent) {
    if (layoutTransitionActive || frozenLayoutState) {
      console.info(`[COMPOSITOR_BARRIER] event=blocked_input reason=transition-active clientX=${event.clientX} clientY=${event.clientY}`);
      return;
    }
    if (resizingSplit || popupInteracting) return;
    const target = event.target as HTMLElement | null;
    const action =
      event.type === "pointerdown"
        ? "down"
        : event.type === "pointermove"
          ? "move"
          : "up";

    // Handle UI elements separately without injecting Android touch events
    if (
      target?.closest(".split-resizer") ||
      target?.closest(".popup-header") ||
      target?.closest(".popup-resize-handle")
    ) {
      if (action === "down") {
        const uiReason = target?.closest(".split-resizer")
          ? "split-resizer"
          : target?.closest(".popup-header")
            ? "popup-header"
            : "resize";
        logTouchRoute(event, "-", undefined, uiReason as any);
      }
      return;
    }

    const pointerKey = event.pointerId & 0xff;
    const routing = findPaneByCoords(event.clientX, event.clientY);

    // Utilize activePointer map if dragging ongoing to capture out-of-bounds pointer capture
    const pane = routing?.pane ?? activeTouchPanes.get(pointerKey);
    const paneElement =
      routing?.element ??
      (pane
        ? (host.querySelector(
            `.viewport-pane[data-pane="${pane}"]`,
          ) as HTMLElement)
        : null);
    const mediaElement =
      (paneElement?.querySelector(
        "canvas:not(.hidden), video:not(.hidden)",
      ) as HTMLElement | null) ?? paneElement;
    const fitMode = routing?.fitMode ?? (currentSplitActive ? "fill" : "contain");

    if (!pane || !paneElement || !mediaElement) {
      if (action === "down") {
        logTouchRoute(event, "-", undefined, "outside");
      }
      return;
    }

    const viewport = getRenderedViewportByPane(pane);
    if (!viewport) return;

    if (action === "down") {
      activeTouchPanes.set(pointerKey, pane);
      // logTouchRoute(event, pane, viewport, "video", paneElement, fitMode);
    }

    touchRouter.pointer(event, viewport, fitMode, mediaElement);

    if (action === "up") {
      activeTouchPanes.delete(pointerKey);
    }
  }

  async function scheduleLayoutFlush() {
    if (layoutFlushScheduled) return;
    layoutFlushScheduled = true;
    await tick();
    await tick();
    layoutFlushScheduled = false;
    if (!host) return;
    hostRect = host.getBoundingClientRect();
    touchRouter.updateHost(hostRect);
    if (shouldBlockLayoutDispatchForMissingSplitTargets()) {
      return;
    }
    dispatchLayout(true);
  }

  function updateChrome(ratio = (frozenLayoutState ? frozenLayoutState.splitRatio : $compositorStore.splitRatio)) {
    const activePlacement = frozenLayoutState ? frozenLayoutState.effectiveSecondaryPlacement : effectiveSecondaryPlacement;
    if ((horizontalSplitActive || verticalSplitActive) && resizer) {
      const boundaryValue = isDockedPlacement(activePlacement)
        ? computeDockedPaneLayout(
            hostRect.width,
            hostRect.height,
            ratio,
            activePlacement,
          ).boundaryPercent
        : computeSplitLayoutMetrics(
            hostRect.width,
            hostRect.height,
            ratio,
          ).boundaryPercent;
      if (horizontalSplitActive) {
        resizer.style.left = `${boundaryValue}%`;
        resizer.style.top = `${verticalResizerThumbPercent}%`;
      } else {
        resizer.style.top = `${boundaryValue}%`;
        resizer.style.left = `${horizontalResizerThumbPercent}%`;
      }
    }
  }

  function constrainPopup(popup: PopupLayoutState): PopupLayoutState {
    const currentWidth = popup.minimized ? 60 : popup.width;
    const currentHeight = popup.minimized ? 60 : popup.height;

    const maxWidth = Math.max(
      POPUP_MIN_WIDTH,
      hostRect.width - POPUP_MARGIN * 2,
    );
    const maxHeight = Math.max(
      POPUP_MIN_HEIGHT,
      hostRect.height - POPUP_MARGIN * 2,
    );
    const width = clamp(popup.width, POPUP_MIN_WIDTH, maxWidth);
    const height = clamp(popup.height, POPUP_MIN_HEIGHT, maxHeight);

    const nextWidth = popup.minimized ? 60 : width;
    const nextHeight = popup.minimized ? 60 : height;

    return {
      ...popup,
      width,
      height,
      x: clamp(popup.x, POPUP_MARGIN, Math.max(POPUP_MARGIN, hostRect.width - nextWidth - POPUP_MARGIN)),
      y: clamp(popup.y, POPUP_MARGIN, Math.max(POPUP_MARGIN, hostRect.height - nextHeight - POPUP_MARGIN)),
    };
  }

  function clampPopupToHost() {
    if (!hostRect.width || !hostRect.height) return;
    const nextPopup = constrainPopup($compositorStore.popup);
    if (!isSamePopup(nextPopup, $compositorStore.popup)) {
      applyPopupState(nextPopup, true);
    }
  }

  function applyPopupState(nextPopup: PopupLayoutState, persist: boolean) {
    compositorStore.update((state) => ({ ...state, popup: nextPopup }));
    if (persist) {
      persistPopupState(nextPopup);
      logPopupState(nextPopup);
    }
  }

  function persistPopupState(popup: PopupLayoutState) {
    localStorage.setItem("castla_full_popup_state", JSON.stringify(popup));
  }

  function logPopupState(popup: PopupLayoutState) {
    // console.info(
    //   `[POPUP] x=${Math.round(popup.x)} y=${Math.round(popup.y)} width=${Math.round(popup.width)} height=${Math.round(popup.height)} minimized=${popup.minimized}`,
    // );
  }

  function logTouchRoute(
    event: PointerEvent,
    pane: PaneId | "-",
    viewport?: ViewportModel,
    reason:
      | "video"
      | "popup-header"
      | "resize"
      | "outside"
      | "ui"
      | "split-resizer" = "video",
    surface?: HTMLElement,
    fitMode: "contain" | "fill" = "contain",
  ) {
    let normalizedX = "-";
    let normalizedY = "-";
    if (pane !== "-" && viewport && surface) {
      const mapped = mapViewportPoint(
        event.clientX,
        event.clientY,
        surface.getBoundingClientRect(),
        viewport.width,
        viewport.height,
        fitMode,
        reason !== "video" || event.type !== "pointerdown",
      );
      if (mapped) {
        normalizedX = mapped.x.toFixed(4);
        normalizedY = mapped.y.toFixed(4);
      }
    }
    console.info(
      `[TOUCH_ROUTE] clientX=${Math.round(event.clientX)} clientY=${Math.round(event.clientY)} targetSlot=${describeTargetSlot(pane)} normalizedX=${normalizedX} normalizedY=${normalizedY} reason=${reason}`,
    );
  }

  function describeTargetSlot(pane: PaneId | "-"): string {
    if (pane === "-") return "-";
    if (fullPopupActive) {
      return pane === "primary" ? "full" : "popup";
    }
    return pane === "primary" ? "left" : "right";
  }

  function requestPaneKeyframes(...panes: PaneId[]) {
    panes.forEach((pane) => runtime.requestKeyframe(pane));
  }

  function isLayoutTransitionActive(
    state: (typeof $compositorStore.launchSequence)["state"],
  ): boolean {
    return state !== "IDLE" && state !== "RUNNING" && state !== "FAILED" && (state as string) !== "DEGRADED";
  }



  function getCurrentPaneStyle(pane: PaneId): string {
    if (frozenLayoutState) {
      return frozenLayoutState.paneStyles[pane] ?? paneStyle(pane);
    }
    return paneStyle(pane);
  }

  function getPaneBarrierStyle(pane: PaneId): string {
    if (
      frozenLayoutState?.layoutMode === "popup" &&
      pane === "secondary"
    ) {
      return "left:0;top:0;width:100%;height:100%;";
    }
    return getCurrentPaneStyle(pane);
  }



  function computeSplitLayoutMetrics(
    width: number,
    height: number,
    ratio: number,
  ) {
    const safeWidth = Math.max(0, Math.round(width));
    const safeHeight = Math.max(0, Math.round(height));
    if (safeWidth <= 0 || safeHeight <= 0) {
      return {
        primaryWidth: 0,
        secondaryWidth: 0,
        leftPercent: 50,
        rightPercent: 50,
        boundaryPercent: 50,
      };
    }

    const rawPrimaryWidth = Math.max(320, Math.round(safeWidth * ratio));
    const rawSecondaryWidth = Math.max(320, safeWidth - rawPrimaryWidth);
    const alignedPrimaryWidth = align16(rawPrimaryWidth);
    const alignedSecondaryWidth = align16(rawSecondaryWidth);
    const totalAlignedWidth = alignedPrimaryWidth + alignedSecondaryWidth;
    const primaryPercent = (alignedPrimaryWidth / totalAlignedWidth) * 100;
    const secondaryPercent = (alignedSecondaryWidth / totalAlignedWidth) * 100;

    return {
      primaryWidth: alignedPrimaryWidth,
      secondaryWidth: alignedSecondaryWidth,
      leftPercent: primaryPercent,
      rightPercent: secondaryPercent,
      boundaryPercent: primaryPercent,
    };
  }

  function align16(value: number): number {
    return Math.max(320, (Math.round(value) + 15) & ~15);
  }

  function splitResizerStyle(
    boundaryPercent: number,
    placement: SecondaryPlacement,
  ): string {
    if (placement === "left" || placement === "right") {
      return `left:${boundaryPercent}%; top:${verticalResizerThumbPercent}%;`;
    }
    if (placement === "top" || placement === "bottom") {
      return `top:${boundaryPercent}%; left:${horizontalResizerThumbPercent}%;`;
    }
    return `left:${boundaryPercent}%; top:${verticalResizerThumbPercent}%;`;
  }

  function clamp(value: number, min: number, max: number): number {
    return Math.min(max, Math.max(min, value));
  }

  function isSamePopup(a: PopupLayoutState, b: PopupLayoutState): boolean {
    return (
      a.visible === b.visible &&
      a.minimized === b.minimized &&
      Math.round(a.x) === Math.round(b.x) &&
      Math.round(a.y) === Math.round(b.y) &&
      Math.round(a.width) === Math.round(b.width) &&
      Math.round(a.height) === Math.round(b.height)
    );
  }
</script>

<div
  bind:this={host}
  class="viewport-host"
  role="presentation"
  on:pointerdown={handlePointer}
  on:pointermove={handlePointer}
  on:pointerup={handlePointer}
  on:pointercancel={handlePointer}
  on:lostpointercapture={handlePointer}
>
  {#if currentSplitActive}
    {#each currentVisibleViewports as viewport (viewport.pane)}
      <ViewportPane
        {viewport}
        {runtime}
        paneStyle={getCurrentPaneStyle(viewport.pane)}
        fitMode="fill"
        {resizingSplit}
        activeTouchPanesSize={activeTouchPanes.size}
      />
      {#if shouldShowPaneBarrier(viewport.pane)}
        <div
          class="compositor-pane-barrier"
          style={getPaneBarrierStyle(viewport.pane)}
          role="presentation"
          on:pointerdown|stopPropagation|preventDefault={handleBarrierInput}
        >
          <div class="premium-loader">
            <div class="loader-circle"></div>
            <div class="loader-pulse"></div>
          </div>
          <p class="barrier-text">{t($compositorStore.language, "barrierText")}</p>
        </div>
      {/if}
    {/each}
  {:else if currentFullPopupActive && currentFullViewport}
    <ViewportPane
      viewport={currentFullViewport}
      {runtime}
      paneStyle={getCurrentPaneStyle(currentFullViewport.pane)}
      fitMode="contain"
      {resizingSplit}
      activeTouchPanesSize={activeTouchPanes.size}
    />
    {#if shouldShowPaneBarrier(currentFullViewport.pane)}
      <div
        class="compositor-pane-barrier"
        style={getPaneBarrierStyle(currentFullViewport.pane)}
        role="presentation"
        on:pointerdown|stopPropagation|preventDefault={handleBarrierInput}
      >
        <div class="premium-loader">
          <div class="loader-circle"></div>
          <div class="loader-pulse"></div>
        </div>
        <p class="barrier-text">{t($compositorStore.language, "barrierText")}</p>
      </div>
    {/if}
    {#if currentPopupVisible && currentPopupViewport}
      {#if currentPopupMinimized}
        <!-- Minimized premium circular floating app icon bubble -->
        <div
          class="popup-minimized-bubble"
          style={`left:${frozenLayoutState ? frozenLayoutState.popup.x : $compositorStore.popup.x}px;top:${frozenLayoutState ? frozenLayoutState.popup.y : $compositorStore.popup.y}px;`}
          role="button"
          tabindex="0"
          aria-label="Restore minimized popup display"
          on:pointerdown={beginPopupDrag}
        >
          <img
            class="minimized-app-icon"
            src={`/api/icon?pkg=${encodeURIComponent($compositorStore.activeSecondaryApp)}`}
            alt="App Icon"
            draggable="false"
            on:error={(e) => {
              const target = e.currentTarget as HTMLImageElement;
              target.style.display = "none";
            }}
          />
          <span class="minimized-badge-dot"></span>
        </div>
      {:else}
        <!-- Original expanded popup window markup with real app title -->
        <div
          class="popup-window"
          style={`left:${currentTempPopupX}px;top:${currentTempPopupY}px;width:${currentTempPopupWidth}px;height:${currentTempPopupHeight}px;`}
        >
          <div
            class="popup-header"
            role="button"
            tabindex="0"
            aria-label="Drag popup window"
            on:pointerdown={beginPopupDrag}
            on:dblclick={toggleMinimizePopup}
          >
            <div class="popup-title">
              {getRealAppLabel($compositorStore.activeSecondaryApp)}
            </div>
            <div class="popup-actions">
              <button
                class="popup-action"
                title={t($compositorStore.language, "minimize")}
                on:pointerdown|stopPropagation
                on:click={minimizePopup}>−</button
              >
              <button class="popup-action" title={t($compositorStore.language, "close")} on:pointerdown|stopPropagation on:click={hidePopup}
                >×</button
              >
            </div>
          </div>
          <div
            bind:this={popupBody}
            class="popup-body"
            style={popupInteracting
              ? `width:${currentTempPopupWidth}px;height:${Math.max(0, currentTempPopupHeight - POPUP_HEADER_HEIGHT)}px;`
              : ""}
          >
            <ViewportPane
              viewport={currentPopupViewport}
              {runtime}
              paneStyle="left:0;top:0;width:100%;height:100%;"
              fitMode="fill"
              {resizingSplit}
              activeTouchPanesSize={activeTouchPanes.size}
            />
            {#if shouldShowPaneBarrier(currentPopupViewport.pane)}
              <div
                class="compositor-pane-barrier popup-pane-barrier"
                style={getPaneBarrierStyle(currentPopupViewport.pane)}
                role="presentation"
                on:pointerdown|stopPropagation|preventDefault={handleBarrierInput}
              >
                <div class="premium-loader">
                  <div class="loader-circle"></div>
                  <div class="loader-pulse"></div>
                </div>
                <p class="barrier-text">{t($compositorStore.language, "barrierText")}</p>
              </div>
            {/if}
          </div>
          <button
            class="popup-resize-handle edge-n"
            aria-label="Resize popup north"
            title="Resize popup north"
            on:pointerdown={(event) => beginPopupResize(event, "n")}
          ></button>
          <button
            class="popup-resize-handle edge-s"
            aria-label="Resize popup south"
            title="Resize popup south"
            on:pointerdown={(event) => beginPopupResize(event, "s")}
          ></button>
          <button
            class="popup-resize-handle edge-e"
            aria-label="Resize popup east"
            title="Resize popup east"
            on:pointerdown={(event) => beginPopupResize(event, "e")}
          ></button>
          <button
            class="popup-resize-handle edge-w"
            aria-label="Resize popup west"
            title="Resize popup west"
            on:pointerdown={(event) => beginPopupResize(event, "w")}
          ></button>
          <button
            class="popup-resize-handle corner-ne"
            aria-label="Resize popup northeast"
            title="Resize popup northeast"
            on:pointerdown={(event) => beginPopupResize(event, "ne")}
          ></button>
          <button
            class="popup-resize-handle corner-nw"
            aria-label="Resize popup northwest"
            title="Resize popup northwest"
            on:pointerdown={(event) => beginPopupResize(event, "nw")}
          ></button>
          <button
            class="popup-resize-handle corner-se"
            aria-label="Resize popup southeast"
            title="Resize popup southeast"
            on:pointerdown={(event) => beginPopupResize(event, "se")}
          ></button>
          <button
            class="popup-resize-handle corner-sw"
            aria-label="Resize popup southwest"
            title="Resize popup southwest"
            on:pointerdown={(event) => beginPopupResize(event, "sw")}
          ></button>
        </div>
      {/if}
    {/if}
  {:else}
    {#each currentVisibleViewports as viewport (viewport.pane)}
      <ViewportPane
        {viewport}
        {runtime}
        paneStyle={getCurrentPaneStyle(viewport.pane)}
        fitMode="contain"
        {resizingSplit}
        activeTouchPanesSize={activeTouchPanes.size}
      />
      {#if shouldShowPaneBarrier(viewport.pane)}
        <div
          class="compositor-pane-barrier"
          style={getPaneBarrierStyle(viewport.pane)}
          role="presentation"
          on:pointerdown|stopPropagation|preventDefault={handleBarrierInput}
        >
          <div class="premium-loader">
            <div class="loader-circle"></div>
            <div class="loader-pulse"></div>
          </div>
          <p class="barrier-text">{t($compositorStore.language, "barrierText")}</p>
        </div>
      {/if}
    {/each}
  {/if}



  {#if currentSplitActive && (horizontalSplitActive || verticalSplitActive)}
    <button
      bind:this={resizer}
      class="split-resizer"
      class:split-resizer-vertical={horizontalSplitActive}
      class:split-resizer-horizontal={verticalSplitActive}
      style={splitResizerStyle(boundary, activeSecondaryPlacement)}
      aria-label="Resize split"
      on:pointerdown={beginResize}
    ></button>
  {/if}

</div>

<style>
  .viewport-host {
    position: relative;
    width: 100%;
    height: 100%;
    background: #05070a;
    overflow: hidden;
  }

  .popup-action,
  .popup-resize-handle {
    border: 0;
    background: transparent;
    color: white;
  }

  .split-resizer {
    position: absolute;
    z-index: 35;
    border: 0;
    background: transparent;
    touch-action: none;
  }

  .split-resizer-vertical {
    width: 18px;
    height: 56px;
    transform: translate(-50%, -50%);
    cursor: ew-resize;
    overflow: visible;
  }

  .split-resizer-horizontal {
    width: 56px;
    height: 18px;
    cursor: ns-resize;
    transform: translate(-50%, -50%);
    overflow: visible;
  }

  .split-resizer-vertical::before {
    content: "";
    position: absolute;
    top: -200vh;
    bottom: -200vh;
    left: 8px;
    width: 2px;
    background: rgb(255 255 255 / 0.64);
    box-shadow: 0 0 0 1px rgb(5 7 10 / 0.16), 0 0 10px rgb(0 229 255 / 0.22);
    transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
    pointer-events: none;
  }

  .split-resizer-vertical:hover::before,
  .split-resizer-vertical:active::before {
    background: rgb(0 229 255 / 0.98);
    box-shadow: 0 0 0 1px rgb(0 229 255 / 0.32), 0 0 16px rgb(0 229 255 / 0.56);
  }

  .split-resizer-vertical::after {
    content: "";
    position: absolute;
    left: 6px;
    top: 50%;
    width: 6px;
    height: 34px;
    transform: translateY(-50%);
    border-radius: 999px;
    background: rgb(248 250 252 / 0.96);
    border: 1px solid rgb(8 12 18 / 0.12);
    box-shadow: 0 2px 10px rgb(0 0 0 / 0.22);
    transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  }

  .split-resizer-vertical:hover::after,
  .split-resizer-vertical:active::after {
    background: rgb(0 229 255 / 0.95);
    border-color: rgba(8, 12, 18, 0.1);
  }

  .split-resizer-horizontal::before {
    content: "";
    position: absolute;
    left: -200vw;
    right: -200vw;
    top: 8px;
    height: 2px;
    background: rgb(255 255 255 / 0.64);
    box-shadow: 0 0 0 1px rgb(5 7 10 / 0.16), 0 0 10px rgb(0 229 255 / 0.22);
    transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
    pointer-events: none;
  }

  .split-resizer-horizontal:hover::before,
  .split-resizer-horizontal:active::before {
    background: rgb(0 229 255 / 0.98);
    box-shadow: 0 0 0 1px rgb(0 229 255 / 0.32), 0 0 16px rgb(0 229 255 / 0.56);
  }

  .split-resizer-horizontal::after {
    content: "";
    position: absolute;
    top: 6px;
    right: 0;
    width: 26px;
    height: 6px;
    border-radius: 999px;
    background: rgb(248 250 252 / 0.96);
    border: 1px solid rgb(8 12 18 / 0.12);
    box-shadow: 0 2px 10px rgb(0 0 0 / 0.22);
    transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  }

  .split-resizer-horizontal:hover::after,
  .split-resizer-horizontal:active::after {
    background: rgb(0 229 255 / 0.95);
    border-color: rgba(8, 12, 18, 0.1);
  }

  .popup-window {
    position: absolute;
    z-index: 37;
    display: flex;
    flex-direction: column;
    overflow: visible;
    border: 1px solid rgb(255 255 255 / 0.14);
    border-radius: 16px;
    background: rgb(4 10 18 / 0.94);
    box-shadow: 0 18px 40px rgb(0 0 0 / 0.42);
    backdrop-filter: blur(12px);
  }

  .popup-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    height: 40px;
    padding: 0 12px;
    border-bottom: 1px solid rgb(255 255 255 / 0.08);
    cursor: grab;
    user-select: none;
    touch-action: none;
  }

  .popup-title {
    color: #8feeff;
    font-size: 12px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.08em;
  }

  .popup-actions {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .popup-action {
    width: 28px;
    height: 28px;
    border-radius: 999px;
    background: rgb(255 255 255 / 0.08);
    font-size: 16px;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    line-height: 0;
    padding: 0;
  }

  .popup-body {
    position: relative;
    flex: 1;
    min-height: 120px;
    overflow: hidden;
    border-radius: 0 0 16px 16px;
  }

  .popup-resize-handle {
    position: absolute;
    z-index: 2;
    padding: 0;
    touch-action: none;
    cursor: nwse-resize;
  }

  .edge-n,
  .edge-s {
    left: 16px;
    right: 16px;
    height: 10px;
    cursor: ns-resize;
  }

  .edge-n {
    top: -5px;
  }

  .edge-s {
    bottom: -5px;
  }

  .edge-e,
  .edge-w {
    top: 16px;
    bottom: 16px;
    width: 10px;
    cursor: ew-resize;
  }

  .edge-e {
    right: -5px;
  }

  .edge-w {
    left: -5px;
  }

  .corner-ne,
  .corner-nw,
  .corner-se,
  .corner-sw {
    width: 16px;
    height: 16px;
  }

  .corner-ne {
    top: -5px;
    right: -5px;
    cursor: nesw-resize;
  }

  .corner-nw {
    top: -5px;
    left: -5px;
    cursor: nwse-resize;
  }

  .corner-se {
    right: -5px;
    bottom: -5px;
    cursor: nwse-resize;
  }

  .corner-sw {
    left: -5px;
    bottom: -5px;
    cursor: nesw-resize;
  }

  /* Minimized Circular Floating Bubble Styling */
  .popup-minimized-bubble {
    position: absolute;
    z-index: 37;
    width: 60px;
    height: 60px;
    border-radius: 50%;
    border: 2px solid rgba(0, 229, 255, 0.45);
    background: radial-gradient(
      circle at center,
      rgb(16 32 50 / 0.96) 0%,
      rgb(4 10 18 / 0.98) 100%
    );
    box-shadow:
      0 10px 28px rgba(0, 0, 0, 0.55),
      0 0 16px rgba(0, 229, 255, 0.25),
      inset 0 0 10px rgba(0, 229, 255, 0.1);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: grab;
    touch-action: none;
    transition:
      transform 0.2s cubic-bezier(0.34, 1.56, 0.64, 1),
      border-color 0.2s ease,
      box-shadow 0.2s ease;
  }

  .popup-minimized-bubble:hover {
    transform: scale(1.12);
    border-color: rgba(0, 229, 255, 0.85);
    box-shadow:
      0 12px 32px rgba(0, 0, 0, 0.65),
      0 0 24px rgba(0, 229, 255, 0.5),
      inset 0 0 12px rgba(0, 229, 255, 0.2);
  }

  .popup-minimized-bubble:active {
    cursor: grabbing;
    transform: scale(0.96);
  }

  .minimized-app-icon {
    width: 44px;
    height: 44px;
    border-radius: 50%;
    object-fit: cover;
    pointer-events: none;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.35);
  }

  .minimized-badge-dot {
    position: absolute;
    bottom: 2px;
    right: 2px;
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: #00e5ff;
    border: 2px solid #040a12;
    box-shadow: 0 0 8px #00e5ff;
    animation: neon-pulse 2s infinite ease-in-out;
  }

  @keyframes neon-pulse {
    0%,
    100% {
      opacity: 0.85;
      box-shadow: 0 0 6px #00e5ff;
    }
    50% {
      opacity: 1;
      box-shadow: 0 0 14px #00e5ff;
    }
  }



  .compositor-pane-barrier {
    position: absolute;
    inset: 0;
    z-index: 36;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    background: radial-gradient(circle at center, rgba(12, 22, 34, 0.7) 0%, rgba(4, 8, 14, 0.92) 100%);
    backdrop-filter: blur(16px) saturate(120%);
    gap: 20px;
    animation: fadeInBarrier 0.25s cubic-bezier(0.16, 1, 0.3, 1) forwards;
    pointer-events: auto;
  }

  .popup-pane-barrier {
    border-radius: 0 0 16px 16px;
  }

  .premium-loader {
    position: relative;
    width: 60px;
    height: 60px;
  }

  .loader-circle {
    box-sizing: border-box;
    width: 100%;
    height: 100%;
    border: 3px solid rgba(0, 229, 255, 0.08);
    border-top: 3px solid #00e5ff;
    border-radius: 50%;
    animation: spin 1s cubic-bezier(0.5, 0.1, 0.5, 0.9) infinite;
  }

  .loader-pulse {
    position: absolute;
    inset: 10px;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(0, 229, 255, 0.2) 0%, transparent 70%);
    animation: pulseGlow 1.8s ease-in-out infinite;
  }

  .barrier-text {
    color: #e2e8f0;
    font-family: "Outfit", "Inter", sans-serif;
    font-size: 13px;
    font-weight: 500;
    letter-spacing: 0.5px;
    text-shadow: 0 2px 8px rgba(0, 0, 0, 0.4);
    opacity: 0.9;
  }

  @keyframes fadeInBarrier {
    from { opacity: 0; backdrop-filter: blur(0px); }
    to { opacity: 1; backdrop-filter: blur(16px); }
  }

  @keyframes pulseGlow {
    0%, 100% { transform: scale(0.85); opacity: 0.5; }
    50% { transform: scale(1.15); opacity: 1; filter: drop-shadow(0 0 8px rgba(0, 229, 255, 0.5)); }
  }
</style>

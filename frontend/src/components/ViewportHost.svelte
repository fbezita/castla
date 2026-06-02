<script lang="ts">
  import { onDestroy, onMount, tick } from "svelte";
  import {
    compositorStore,
    type LayoutMode,
    type PopupLayoutState,
    type ViewportModel,
  } from "../stores/compositorStore";
  import { t } from "../lib/i18n";
  import ViewportPane from "./ViewportPane.svelte";
  import type { TouchRouter } from "../touch/TouchRouter";
  import { mapViewportPoint } from "../touch/TouchRouter";
  import type { StreamRuntime } from "../runtime/StreamRuntime";
  import type { PaneId } from "../protocol";

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
  }

  let frozenLayoutState: FrozenLayoutState | null = null;
  let safetyReleaseTimer = 0;

  let host: HTMLDivElement;
  let resizer: HTMLButtonElement;

  let popupBody: HTMLDivElement;
  let resizeObserver: ResizeObserver;
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

  onMount(() => {
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
  $: splitActive =
    dualPaneReady &&
    $compositorStore.layoutMode === "split" &&
    primaryViewport?.visible === true &&
    secondaryViewport?.visible === true;
  $: fullPopupActive =
    dualPaneReady &&
    $compositorStore.layoutMode === "popup" &&
    primaryViewport?.visible === true &&
    secondaryViewport?.visible === true;

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

  function handleTransitionChange(active: boolean) {
    window.clearTimeout(safetyReleaseTimer);
    if (active) {
      if (!frozenLayoutState) {
        frozenLayoutState = {
          layoutMode: $compositorStore.layoutMode,
          visibleViewports: Array.from($compositorStore.viewports.values()).map(v => ({ ...v })),
          paneStyles: {
            primary: paneStyle("primary"),
            secondary: paneStyle("secondary"),
          },
          popup: { ...$compositorStore.popup },
          fullPane: fullPane,
          popupPane: popupPane,
          splitRatio: $compositorStore.splitRatio,
        };
        console.info(`[COMPOSITOR_BARRIER] event=freeze state=${$compositorStore.launchSequence.state} layout=${$compositorStore.layoutMode}`);

        // 6초 세이프 가드 타이머 시작 (어떤 원인으로든 6초 이상 배리어가 가두지 않도록 보장)
        safetyReleaseTimer = window.setTimeout(() => {
          if (frozenLayoutState) {
            console.warn("[COMPOSITOR_BARRIER] event=safety_unfreeze_timeout stuck protection triggered!");
            frozenLayoutState = null;
          }
        }, 6000);
      }
    } else {
      if (frozenLayoutState) {
        console.info(`[COMPOSITOR_BARRIER] event=release state=${$compositorStore.launchSequence.state}`);
        frozenLayoutState = null;
      }
    }
  }

  $: handleTransitionChange(layoutTransitionActive);



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
  $: boundary = layoutMetrics.boundaryPercent;
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
    if (!resizingSplit && !popupInteracting && !layoutTransitionActive) {
      dispatchLayout();
    }
  }

  $: if (
    splitActive &&
    secondaryViewport?.committed === true &&
    !layoutTransitionActive
  ) {
    dispatchLayout(true);
  }

  function paneStyle(pane: PaneId): string {
    if (splitActive) {
      const { leftPercent, rightPercent } = layoutMetrics;
      if (pane === "secondary") {
        return `left:${leftPercent}%;right:0;width:${rightPercent}%;`;
      }
      return `left:0;width:${leftPercent}%;right:auto;`;
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
        });
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
    updateChrome();
    window.addEventListener("pointermove", resizeMove);
    window.addEventListener("pointerup", endResize, { once: true });
    window.addEventListener("pointercancel", endResize, { once: true });
  }

  function resizeMove(event: PointerEvent) {
    if (!resizingSplit || !host) return;
    const rect = host.getBoundingClientRect();
    const pos = clamp((event.clientX - rect.left) / rect.width, 0.22, 0.78);
    const nextRatio = pos;
    localStorage.setItem("castla_split_ratio", String(nextRatio));
    compositorStore.update((state) => ({ ...state, splitRatio: nextRatio }));
    updateChrome(nextRatio);
  }

  function endResize() {
    resizingSplit = false;
    window.removeEventListener("pointermove", resizeMove);
    window.removeEventListener("pointerup", endResize);
    window.removeEventListener("pointercancel", endResize);
    scheduleLayoutFlush();
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
    const finalPopup = {
      ...$compositorStore.popup,
      x: tempPopupX,
      y: tempPopupY,
      width: tempPopupWidth,
      height: tempPopupHeight,
    };
    applyPopupState(finalPopup, true);

    scheduleLayoutFlush();
    if (popupPane) {
      runtime.requestKeyframe(popupPane);
    }
  }

  function minimizePopup() {
    const current = $compositorStore.popup;
    const targetX = current.x + current.width - 60;
    const targetY = current.y + current.height - 60;

    // Constrain the minimized 60px bubble within host bounds
    const maxX = Math.max(POPUP_MARGIN, hostRect.width - 60 - POPUP_MARGIN);
    const maxY = Math.max(POPUP_MARGIN, hostRect.height - 60 - POPUP_MARGIN);

    const nextPopup = {
      ...current,
      x: clamp(targetX, POPUP_MARGIN, maxX),
      y: clamp(targetY, POPUP_MARGIN, maxY),
      minimized: true,
    };
    applyPopupState(nextPopup, true);
    scheduleLayoutFlush();
  }

  function restorePopup() {
    const current = $compositorStore.popup;
    const targetX = current.x + 60 - current.width;
    const targetY = current.y + 60 - current.height;

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

    const maxX = Math.max(POPUP_MARGIN, hostRect.width - width - POPUP_MARGIN);
    const maxY = Math.max(
      POPUP_MARGIN,
      hostRect.height - height - POPUP_MARGIN,
    );

    const nextPopup = {
      ...current,
      visible: true,
      minimized: false,
      x: clamp(targetX, POPUP_MARGIN, maxX),
      y: clamp(targetY, POPUP_MARGIN, maxY),
    };
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
    const nextPopup = { ...$compositorStore.popup, visible: false };
    applyPopupState(nextPopup, true);
    scheduleLayoutFlush();
  }

  function hideSecondary() {
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
        popup: { ...state.popup, visible: false },
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
        const popupWidth = align16(popup.width);
        const popupHeight = align16(popup.visible && !popup.minimized ? Math.max(POPUP_MIN_HEIGHT, popup.height - POPUP_HEADER_HEIGHT) : rect.height);
        runtime.sendLayout([
          { id: "primary", width: align16(rect.width), height: alignedHeight, visible: true },
          { id: "secondary", width: popupWidth, height: popupHeight, visible: popup.visible && !popup.minimized }
        ], seqId);
      } else if (mode === "split") {
        const layoutMetrics = computeSplitLayoutMetrics(rect.width, rect.height, splitRatio);
        runtime.sendLayout([
          { id: "primary", width: layoutMetrics.primaryWidth, height: alignedHeight, visible: true },
          { id: "secondary", width: layoutMetrics.secondaryWidth, height: alignedHeight, visible: true }
        ], seqId);
      } else {
        const activePane = visibleViewports[0]?.pane ?? "primary";
        const hiddenPane = activePane === "primary" ? "secondary" : "primary";
        const alignedWidth = align16(rect.width);
        runtime.sendLayout([
          { id: activePane, width: alignedWidth, height: alignedHeight, visible: true },
          { id: hiddenPane, width: alignedWidth, height: alignedHeight, visible: false }
        ], seqId);
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

  function dispatchLayout(forceImmediate = false) {
    window.clearTimeout(provisionalLayoutTimer);
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

    if (fullPopupActive && fullViewport && popupViewport) {
      const fullPaneId = fullViewport.pane as PaneId;
      const popupPaneId = popupViewport.pane as PaneId;
      const popupWidth = align16($compositorStore.popup.width);
      const popupHeight = align16(
        popupVisible && !popupMinimized
          ? Math.max(POPUP_MIN_HEIGHT, popupBodyHeight)
          : rect.height,
      );
      runtime.sendLayout([
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
          visible: popupVisible && !popupMinimized,
        },
      ]);
      return;
    }

    if (splitActive) {
      const { primaryWidth, secondaryWidth } = layoutMetrics;
      runtime.sendLayout([
        {
          id: leftPane,
          width: primaryWidth,
          height: alignedHeight,
          visible: true,
        },
        {
          id: rightPane,
          width: secondaryWidth,
          height: alignedHeight,
          visible: true,
        },
      ]);
      return;
    }

    const activePane = visibleViewports[0]?.pane ?? "primary";
    const hiddenPane: PaneId =
      activePane === "primary" ? "secondary" : "primary";
    const alignedWidth = align16(rect.width);
    runtime.sendLayout([
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
    ]);
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
        const rect = popupEl.getBoundingClientRect();
        if (
          clientX >= rect.left &&
          clientX <= rect.right &&
          clientY >= rect.top &&
          clientY <= rect.bottom
        ) {
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
          const paneEl = popupEl.querySelector(".viewport-pane") as HTMLElement;
          if (paneEl)
            return { pane: "secondary", element: paneEl, fitMode: "fill" };
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
    const activeViewport = visibleViewports[0];
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
    const fitMode = routing?.fitMode ?? (splitActive ? "fill" : "contain");

    if (!pane || !paneElement) {
      if (action === "down") {
        logTouchRoute(event, "-", undefined, "outside");
      }
      return;
    }

    const viewport = allViewports.find((entry) => entry.pane === pane);
    if (!viewport) return;

    if (action === "down") {
      activeTouchPanes.set(pointerKey, pane);
      logTouchRoute(event, pane, viewport, "video", paneElement, fitMode);
    }

    touchRouter.pointer(event, viewport, fitMode, paneElement);

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
    dispatchLayout(true);
  }

  function updateChrome(ratio = $compositorStore.splitRatio) {
    if (splitActive && resizer) {
      const boundaryValue = computeSplitLayoutMetrics(
        hostRect.width,
        hostRect.height,
        ratio,
      ).boundaryPercent;
      resizer.style.left = `${boundaryValue}%`;
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

    const maxX = Math.max(
      POPUP_MARGIN,
      hostRect.width - currentWidth - POPUP_MARGIN,
    );
    const maxY = Math.max(
      POPUP_MARGIN,
      hostRect.height - currentHeight - POPUP_MARGIN,
    );

    return {
      ...popup,
      width,
      height,
      x: clamp(popup.x, POPUP_MARGIN, maxX),
      y: clamp(popup.y, POPUP_MARGIN, maxY),
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
    console.info(
      `[POPUP] x=${Math.round(popup.x)} y=${Math.round(popup.y)} width=${Math.round(popup.width)} height=${Math.round(popup.height)} minimized=${popup.minimized}`,
    );
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
      />
    {/each}
  {:else if currentFullPopupActive && currentFullViewport}
    <ViewportPane
      viewport={currentFullViewport}
      {runtime}
      paneStyle={getCurrentPaneStyle(currentFullViewport.pane)}
      fitMode="contain"
    />
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
                on:click={minimizePopup}>−</button
              >
              <button class="popup-action" title={t($compositorStore.language, "close")} on:click={hidePopup}
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
            />
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
      />
    {/each}
  {/if}



  {#if currentSplitActive}
    <button
      bind:this={resizer}
      class="split-resizer"
      style={`left:${boundary}%`}
      aria-label="Resize split"
      on:pointerdown={beginResize}
    ></button>
  {/if}

  {#if layoutTransitionActive || frozenLayoutState}
    <div class="compositor-barrier-overlay" role="presentation" on:pointerdown|stopPropagation|preventDefault={handleBarrierInput}>
      <div class="premium-loader">
        <div class="loader-circle"></div>
        <div class="loader-pulse"></div>
      </div>
      <p class="barrier-text">{t($compositorStore.language, "barrierText")}</p>
    </div>
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
    top: 0;
    bottom: 0;
    z-index: 35;
    width: 22px;
    transform: translateX(-50%);
    border: 0;
    background: transparent;
    cursor: ew-resize;
    touch-action: none;
  }

  .split-resizer::before {
    content: "";
    position: absolute;
    top: 0;
    bottom: 0;
    left: 10px;
    width: 2px;
    background: rgb(0 229 255 / 0.65);
    box-shadow: 0 0 12px rgb(0 229 255 / 0.65);
  }

  .split-resizer::after {
    content: "";
    position: absolute;
    left: 6px;
    top: 50%;
    width: 10px;
    height: 72px;
    transform: translateY(-50%);
    border-radius: 6px;
    background: #13dff5;
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



  .compositor-barrier-overlay {
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

import type { PaneId } from "../protocol";
import type { StreamRuntime } from "../runtime/StreamRuntime";
import type { ViewportModel } from "../stores/compositorStore";

type PendingMove = {
  pane: PaneId;
  id: number;
  x: number;
  y: number;
  epoch: number;
};

type SentMove = {
  x: number;
  y: number;
  sentAt: number;
};

type GestureStats = {
  rawMoveEvents: number;
  emittedMovePackets: number;
  observedDistance: number;
  lastObservedX: number;
  lastObservedY: number;
};

export class TouchRouter {
  private static readonly MOVE_FLUSH_INTERVAL_MS = 40;
  private static readonly MOVE_EPSILON = 0.0025;
  private static nextRouterId = 1;
  private static liveRouterCount = 0;
  private hostRect = new DOMRect();
  private activePointers = new Map<
    number,
    { browserPointerId: number; target: HTMLElement }
  >();
  private pendingMoves = new Map<number, PendingMove>();
  private lastSentMoves = new Map<number, SentMove>();
  private moveFlushScheduled = false;
  private sessionEpoch = 0;
  private readonly cleanupFns: Array<() => void> = [];
  private readonly routerId = TouchRouter.nextRouterId++;
  private sentPackets = 0;
  private coalescedMoveFrames = 0;
  private droppedMoveCount = 0;
  private packetCounts = { down: 0, move: 0, up: 0 };
  private gesturePacketCounts = new Map<number, number>();
  private gestureStats = new Map<number, GestureStats>();
  private lastMoveFlushAt = 0;
  private movePacketsThisSecond = 0;
  private movePacketsPerSecond = 0;
  private movePpsWindowStartedAt = performance.now();
  private anomalousEvents = 0;
  private recentEvents: Array<Record<string, unknown>> = [];

  constructor(private readonly runtime: StreamRuntime) {
    TouchRouter.liveRouterCount += 1;
    this.sessionEpoch = runtime.currentSessionEpoch();
    this.cleanupFns.push(
      runtime.onSessionChange((epoch) => {
        this.sessionEpoch = epoch;
        this.reset();
      }),
    );
    this.cleanupFns.push(
      runtime.onTouchStateReset((reason) => {
        this.clearAll();
      }),
    );
  }

  static getLiveRouterCount(): number {
    return TouchRouter.liveRouterCount;
  }

  dispose(): void {
    this.clearAll();
    this.cleanupFns.splice(0).forEach((cleanup) => cleanup());
    TouchRouter.liveRouterCount = Math.max(0, TouchRouter.liveRouterCount - 1);
  }

  updateHost(rect: DOMRect): void {
    this.hostRect = rect;
  }

  pointer(
    event: PointerEvent,
    viewport: ViewportModel,
    fitMode: "contain" | "fill" = "contain",
    surface?: HTMLElement,
  ): void {
    const captureTarget = event.currentTarget as HTMLElement;
    const rect = (surface ?? captureTarget).getBoundingClientRect();
    const pointerId = event.pointerId & 0xff;
    const action = pointerAction(event.type);
    this.recordEvent("pointer_input", {
      action,
      browserPointerId: event.pointerId,
      remotePointerId: pointerId,
      pane: viewport.pane,
      targetPane: surface?.dataset?.pane ?? null,
      activePointersBefore: this.activePointers.size,
      sessionEpoch: this.sessionEpoch,
      launchSequence: this.runtime.currentAppLaunchSequence(),
    });
    const mapped = mapViewportPoint(
      event.clientX,
      event.clientY,
      rect,
      viewport.width,
      viewport.height,
      fitMode,
      action !== "down",
    );
    if (!mapped) {
      if (action === "down") {
        this.logAnomaly("down_outside_viewport", {
          browserPointerId: event.pointerId,
          remotePointerId: pointerId,
          pane: viewport.pane,
          fitMode,
        });
      }
      if (action !== "down") {
        this.clearPointer(event.pointerId);
      }
      return;
    }
    if (action === "down" && this.activePointers.has(pointerId)) {
      this.logAnomaly("duplicate_down_active_pointer", {
        browserPointerId: event.pointerId,
        remotePointerId: pointerId,
        pane: viewport.pane,
        activePointers: this.activePointers.size,
      });
    }
    if (action !== "down" && !this.activePointers.has(pointerId)) {
      this.logAnomaly("non_down_without_active_pointer", {
        action,
        browserPointerId: event.pointerId,
        remotePointerId: pointerId,
        pane: viewport.pane,
        activePointers: this.activePointers.size,
      });
      return;
    }

    event.preventDefault();
    if (action === "move") {
      const stats = this.gestureStats.get(pointerId);
      if (stats) {
        stats.rawMoveEvents += 1;
        stats.observedDistance += Math.hypot(
          mapped.x - stats.lastObservedX,
          mapped.y - stats.lastObservedY,
        );
        stats.lastObservedX = mapped.x;
        stats.lastObservedY = mapped.y;
      }
      const pending = this.pendingMoves.get(pointerId);
      if (
        pending &&
        this.isSameMove(pending.x, pending.y, mapped.x, mapped.y)
      ) {
        this.droppedMoveCount += 1;
        return;
      }
      this.pendingMoves.set(pointerId, {
        pane: viewport.pane,
        id: pointerId,
        x: mapped.x,
        y: mapped.y,
        epoch: this.sessionEpoch,
      });
      this.scheduleMoveFlush();
      return;
    }
    if (action === "down") {
      this.capturePointer(pointerId, event.pointerId, captureTarget);
      this.gestureStats.set(pointerId, {
        rawMoveEvents: 0,
        emittedMovePackets: 0,
        observedDistance: 0,
        lastObservedX: mapped.x,
        lastObservedY: mapped.y,
      });
    } else if (action === "up") {
      this.pendingMoves.delete(pointerId);
      this.lastSentMoves.delete(pointerId);
      this.clearPointer(event.pointerId);
    }

    this.sendTouch({
      type: "touch",
      pane: viewport.pane,
      action,
      id: pointerId,
      x: mapped.x,
      y: mapped.y,
      epoch: this.sessionEpoch,
      clientTs: Date.now(),
    });
  }

  clearPointer(pointerId: number): void {
    const remotePointerId = pointerId & 0xff;
    const capture = this.activePointers.get(remotePointerId);
    if (capture) {
      try {
        capture.target.releasePointerCapture?.(capture.browserPointerId);
      } catch {}
    }
    this.activePointers.delete(remotePointerId);
  }

  clearAll(): void {
    for (const capture of this.activePointers.values()) {
      try {
        capture.target.releasePointerCapture?.(capture.browserPointerId);
      } catch {}
    }
    this.activePointers.clear();
    this.pendingMoves.clear();
    this.lastSentMoves.clear();
    this.moveFlushScheduled = false;
  }

  reset(): void {
    this.clearAll();
    this.runtime.resetTouchState("router_reset");
  }

  private capturePointer(
    remotePointerId: number,
    browserPointerId: number,
    target: HTMLElement,
  ): void {
    this.clearPointer(browserPointerId);
    this.activePointers.set(remotePointerId, { browserPointerId, target });
    try {
      target.setPointerCapture?.(browserPointerId);
    } catch {}
  }

  private scheduleMoveFlush(): void {
    if (this.moveFlushScheduled) return;
    this.moveFlushScheduled = true;
    requestAnimationFrame(() => {
      const now = performance.now();
      const sinceLastFlush = now - this.lastMoveFlushAt;
      if (sinceLastFlush < TouchRouter.MOVE_FLUSH_INTERVAL_MS) {
        window.setTimeout(
          () => {
            this.moveFlushScheduled = false;
            this.scheduleMoveFlush();
          },
          Math.max(
            1,
            Math.ceil(TouchRouter.MOVE_FLUSH_INTERVAL_MS - sinceLastFlush),
          ),
        );
        return;
      }
      this.moveFlushScheduled = false;
      this.lastMoveFlushAt = now;
      const moves = Array.from(this.pendingMoves.values());
      this.pendingMoves.clear();
      if (moves.length > 1) this.coalescedMoveFrames += 1;
      for (const move of moves) {
        const lastSent = this.lastSentMoves.get(move.id);
        if (
          lastSent &&
          this.isSameMove(lastSent.x, lastSent.y, move.x, move.y)
        ) {
          this.droppedMoveCount += 1;
          continue;
        }
        this.lastSentMoves.set(move.id, { x: move.x, y: move.y, sentAt: now });
        const stats = this.gestureStats.get(move.id);
        if (stats) {
          stats.emittedMovePackets += 1;
        }
        this.sendTouch({
          type: "touch",
          pane: move.pane,
          action: "move",
          id: move.id,
          x: move.x,
          y: move.y,
          epoch: move.epoch,
          clientTs: Date.now(),
        });
      }
    });
  }

  private sendTouch(message: {
    type: "touch";
    pane: PaneId;
    action: "down" | "move" | "up";
    id: number;
    x: number;
    y: number;
    epoch: number;
    clientTs?: number;
  }): void {
    this.recordEvent("touch_send", {
      action: message.action,
      pane: message.pane,
      id: message.id,
      epoch: message.epoch,
      launchSequence: this.runtime.currentAppLaunchSequence(),
      activePointers: this.activePointers.size,
    });
    this.sentPackets += 1;
    this.packetCounts[message.action] += 1;
    if (message.action === "move") {
      this.movePacketsThisSecond += 1;
      const now = performance.now();
      if (now - this.movePpsWindowStartedAt >= 1000) {
        this.movePacketsPerSecond = this.movePacketsThisSecond;
        this.movePacketsThisSecond = 0;
        this.movePpsWindowStartedAt = now;
      }
    }
    const gesturePackets = (this.gesturePacketCounts.get(message.id) ?? 0) + 1;
    this.gesturePacketCounts.set(message.id, gesturePackets);
    if (message.action === "up") {
      const stats = this.gestureStats.get(message.id);
      // console.info("[CastlaTouch] gesture complete", {
      //   routerId: this.routerId,
      //   pointerId: message.id,
      //   launchSequence: this.runtime.currentAppLaunchSequence(),
      //   sessionEpoch: message.epoch,
      //   gesturePackets,
      //   rawMoveEvents: stats?.rawMoveEvents ?? 0,
      //   emittedMovePackets: stats?.emittedMovePackets ?? 0,
      //   observedDistance: Number((stats?.observedDistance ?? 0).toFixed(4)),
      //   emittedMovesPerUnitDistance:
      //     stats && stats.observedDistance > 0
      //       ? Number(
      //           (stats.emittedMovePackets / stats.observedDistance).toFixed(2),
      //         )
      //       : 0,
      //   packetCounts: { ...this.packetCounts },
      //   activePointers: this.activePointers.size,
      //   pendingMoves: this.pendingMoves.size,
      //   coalescedMoveFrames: this.coalescedMoveFrames,
      //   droppedMoveCount: this.droppedMoveCount,
      // });
      this.gesturePacketCounts.delete(message.id);
      this.gestureStats.delete(message.id);
    }
    this.runtime.control.send({
      ...message,
      clientTs: message.clientTs ?? Date.now(),
    });
  }

  private releaseEventCapture(event: PointerEvent): void {
    const target = event.target as HTMLElement | null;
    try {
      target?.releasePointerCapture?.(event.pointerId);
    } catch {}
  }

  debugSnapshot(): Record<string, unknown> {
    return {
      routerId: this.routerId,
      liveRouterCount: TouchRouter.liveRouterCount,
      sessionEpoch: this.sessionEpoch,
      launchSequence: this.runtime.currentAppLaunchSequence(),
      activePointers: this.activePointers.size,
      pendingMoves: this.pendingMoves.size,
      sentPackets: this.sentPackets,
      packetCounts: { ...this.packetCounts },
      coalescedMoveFrames: this.coalescedMoveFrames,
      droppedMoveCount: this.droppedMoveCount,
      movePacketsPerSecond: this.movePacketsPerSecond,
      anomalousEvents: this.anomalousEvents,
      activePointerIds: Array.from(this.activePointers.keys()),
      pendingMoveIds: Array.from(this.pendingMoves.keys()),
      recentEvents: [...this.recentEvents],
    };
  }

  private logAnomaly(type: string, detail: Record<string, unknown>): void {
    this.anomalousEvents += 1;
    console.warn("[CastlaTouch] anomaly", {
      type,
      routerId: this.routerId,
      launchSequence: this.runtime.currentAppLaunchSequence(),
      sessionEpoch: this.sessionEpoch,
      sentPackets: this.sentPackets,
      packetCounts: { ...this.packetCounts },
      ...detail,
    });
    this.recordEvent("anomaly", { type, ...detail });
  }

  private recordEvent(type: string, detail: Record<string, unknown>): void {
    this.recentEvents.push({
      ts: Date.now(),
      type,
      ...detail,
    });
    if (this.recentEvents.length > 40) {
      this.recentEvents.splice(0, this.recentEvents.length - 40);
    }
  }

  private isSameMove(
    prevX: number,
    prevY: number,
    nextX: number,
    nextY: number,
  ): boolean {
    return (
      Math.abs(prevX - nextX) < TouchRouter.MOVE_EPSILON &&
      Math.abs(prevY - nextY) < TouchRouter.MOVE_EPSILON
    );
  }
}

function pointerAction(type: string): "down" | "move" | "up" {
  if (type === "pointerdown") return "down";
  if (type === "pointermove") return "move";
  return "up";
}

export function mapViewportPoint(
  clientX: number,
  clientY: number,
  rect: DOMRect,
  displayWidth: number,
  displayHeight: number,
  fitMode: "contain" | "fill",
  clampToViewport = false,
): { x: number; y: number } | null {
  if (fitMode === "fill") {
    const rawX = (clientX - rect.left) / rect.width;
    const rawY = (clientY - rect.top) / rect.height;
    const x = clampToViewport ? clamp(rawX, 0, 1) : rawX;
    const y = clampToViewport ? clamp(rawY, 0, 1) : rawY;
    if (x < 0 || x > 1 || y < 0 || y > 1) return null;
    return { x, y };
  }

  const viewAspect = rect.width / rect.height;
  const displayAspect = displayWidth / displayHeight;
  let contentWidth = rect.width;
  let contentHeight = rect.height;
  let offsetX = 0;
  let offsetY = 0;
  if (viewAspect > displayAspect) {
    contentWidth = rect.height * displayAspect;
    offsetX = (rect.width - contentWidth) / 2;
  } else {
    contentHeight = rect.width / displayAspect;
    offsetY = (rect.height - contentHeight) / 2;
  }
  const rawX = (clientX - rect.left - offsetX) / contentWidth;
  const rawY = (clientY - rect.top - offsetY) / contentHeight;
  const x = clampToViewport ? clamp(rawX, 0, 1) : rawX;
  const y = clampToViewport ? clamp(rawY, 0, 1) : rawY;
  if (x < 0 || x > 1 || y < 0 || y > 1) return null;
  return { x, y };
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

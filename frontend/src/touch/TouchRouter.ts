import type { PaneId } from '../protocol';
import type { StreamRuntime } from '../runtime/StreamRuntime';
import type { ViewportModel } from '../stores/compositorStore';

type PendingMove = {
  pane: PaneId;
  id: number;
  x: number;
  y: number;
  epoch: number;
};

export class TouchRouter {
  private static nextRouterId = 1;
  private static liveRouterCount = 0;
  private hostRect = new DOMRect();
  private activePointers = new Map<number, { browserPointerId: number; target: HTMLElement }>();
  private pendingMoves = new Map<number, PendingMove>();
  private moveFlushScheduled = false;
  private sessionEpoch = 0;
  private readonly cleanupFns: Array<() => void> = [];
  private readonly routerId = TouchRouter.nextRouterId++;
  private sentPackets = 0;
  private coalescedMoveFrames = 0;
  private packetCounts = { down: 0, move: 0, up: 0 };
  private gesturePacketCounts = new Map<number, number>();
  private lastMoveFlushAt = 0;
  private movePacketsThisSecond = 0;
  private movePacketsPerSecond = 0;
  private movePpsWindowStartedAt = performance.now();

  constructor(private readonly runtime: StreamRuntime) {
    TouchRouter.liveRouterCount += 1;
    this.sessionEpoch = runtime.currentSessionEpoch();
    console.info('[CastlaTouch] router created', this.debugSnapshot());
    this.cleanupFns.push(runtime.onSessionChange((epoch) => {
      this.sessionEpoch = epoch;
      this.reset();
    }));
    this.cleanupFns.push(runtime.onTouchStateReset((reason) => {
      this.clearAll();
    }));
  }

  dispose(): void {
    console.info('[CastlaTouch] router dispose', this.debugSnapshot());
    this.clearAll();
    this.cleanupFns.splice(0).forEach((cleanup) => cleanup());
    TouchRouter.liveRouterCount = Math.max(0, TouchRouter.liveRouterCount - 1);
  }

  updateHost(rect: DOMRect): void {
    this.hostRect = rect;
  }

  pointer(event: PointerEvent, viewport: ViewportModel, fitMode: 'contain' | 'fill' = 'contain'): void {
    const target = event.currentTarget as HTMLElement;
    const rect = target.getBoundingClientRect();
    const pointerId = event.pointerId & 0xff;
    const action = pointerAction(event.type);
    const mapped = mapViewportPoint(event.clientX, event.clientY, rect, viewport.width, viewport.height, fitMode, action !== 'down');
    if (!mapped) {
      if (action !== 'down') {
        this.clearPointer(event.pointerId);
      }
      return;
    }
    if (action !== 'down' && !this.activePointers.has(pointerId)) {
      return;
    }

    event.preventDefault();
    if (action === 'move') {
      this.pendingMoves.set(pointerId, {
        pane: viewport.pane,
        id: pointerId,
        x: mapped.x,
        y: mapped.y,
        epoch: this.sessionEpoch
      });
      this.scheduleMoveFlush();
      return;
    }
    if (action === 'down') {
      this.capturePointer(pointerId, event.pointerId, target);
    } else if (action === 'up') {
      this.pendingMoves.delete(pointerId);
      this.clearPointer(event.pointerId);
    }

    this.sendTouch({
      type: 'touch',
      pane: viewport.pane,
      action,
      id: pointerId,
      x: mapped.x,
      y: mapped.y,
      epoch: this.sessionEpoch,
      clientTs: Date.now()
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
    this.moveFlushScheduled = false;
  }

  reset(): void {
    console.info('[CastlaTouch] router reset', this.debugSnapshot());
    this.clearAll();
    this.runtime.resetTouchState('router_reset');
  }

  private capturePointer(remotePointerId: number, browserPointerId: number, target: HTMLElement): void {
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
      if (sinceLastFlush < 16) {
        window.setTimeout(() => {
          this.moveFlushScheduled = false;
          this.scheduleMoveFlush();
        }, Math.max(1, Math.ceil(16 - sinceLastFlush)));
        return;
      }
      this.moveFlushScheduled = false;
      this.lastMoveFlushAt = now;
      const moves = Array.from(this.pendingMoves.values());
      this.pendingMoves.clear();
      if (moves.length > 1) {
        this.coalescedMoveFrames += 1;
        console.info('[CastlaTouch] move coalesced', {
          routerId: this.routerId,
          moves: moves.length,
          coalescedMoveFrames: this.coalescedMoveFrames,
          launchSequence: this.runtime.currentAppLaunchSequence(),
          sessionEpoch: this.sessionEpoch
        });
      }
      for (const move of moves) {
        this.sendTouch({
          type: 'touch',
          pane: move.pane,
          action: 'move',
          id: move.id,
          x: move.x,
          y: move.y,
          epoch: move.epoch,
          clientTs: Date.now()
        });
      }
    });
  }

  private sendTouch(message: {
    type: 'touch';
    pane: PaneId;
    action: 'down' | 'move' | 'up';
    id: number;
    x: number;
    y: number;
    epoch: number;
    clientTs?: number;
  }): void {
    this.sentPackets += 1;
    this.packetCounts[message.action] += 1;
    if (message.action === 'move') {
      this.movePacketsThisSecond += 1;
      const now = performance.now();
      if (now - this.movePpsWindowStartedAt >= 1000) {
        this.movePacketsPerSecond = this.movePacketsThisSecond;
        this.movePacketsThisSecond = 0;
        this.movePpsWindowStartedAt = now;
        console.info('[CastlaTouch] move pps', {
          routerId: this.routerId,
          launchSequence: this.runtime.currentAppLaunchSequence(),
          sessionEpoch: this.sessionEpoch,
          movePacketsPerSecond: this.movePacketsPerSecond,
          pendingMoves: this.pendingMoves.size,
          activePointers: this.activePointers.size
        });
      }
    }
    const gesturePackets = (this.gesturePacketCounts.get(message.id) ?? 0) + 1;
    this.gesturePacketCounts.set(message.id, gesturePackets);
    if (message.action === 'down') {
      console.info('[CastlaTouch] gesture start', {
        routerId: this.routerId,
        pointerId: message.id,
        launchSequence: this.runtime.currentAppLaunchSequence(),
        sessionEpoch: message.epoch,
        control: this.runtime.control.debugSnapshot()
      });
    }
    if (message.action === 'up') {
      console.info('[CastlaTouch] gesture complete', {
        routerId: this.routerId,
        pointerId: message.id,
        launchSequence: this.runtime.currentAppLaunchSequence(),
        sessionEpoch: message.epoch,
        gesturePackets,
        packetCounts: { ...this.packetCounts },
        activePointers: this.activePointers.size,
        pendingMoves: this.pendingMoves.size,
        coalescedMoveFrames: this.coalescedMoveFrames
      });
      this.gesturePacketCounts.delete(message.id);
    }
    this.runtime.control.send({
      ...message,
      clientTs: message.clientTs ?? Date.now()
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
      movePacketsPerSecond: this.movePacketsPerSecond
    };
  }
}

function pointerAction(type: string): 'down' | 'move' | 'up' {
  if (type === 'pointerdown') return 'down';
  if (type === 'pointermove') return 'move';
  return 'up';
}

function mapViewportPoint(
  clientX: number,
  clientY: number,
  rect: DOMRect,
  displayWidth: number,
  displayHeight: number,
  fitMode: 'contain' | 'fill',
  clampToViewport = false
): { x: number; y: number } | null {
  if (fitMode === 'fill') {
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

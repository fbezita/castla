import type { ViewportModel } from '../stores/compositorStore';
import type { ControlTransport } from '../transport/ControlTransport';

export class TouchRouter {
  private hostRect = new DOMRect();

  constructor(private readonly control: ControlTransport) {}

  updateHost(rect: DOMRect): void {
    this.hostRect = rect;
  }

  pointer(event: PointerEvent, viewport: ViewportModel): void {
    const target = event.currentTarget as HTMLElement;
    const rect = target.getBoundingClientRect();
    const mapped = mapViewportPoint(event.clientX, event.clientY, rect, viewport.width, viewport.height);
    if (!mapped) return;
    event.preventDefault();
    target.setPointerCapture?.(event.pointerId);
    this.control.send({
      type: 'touch',
      pane: viewport.pane,
      action: pointerAction(event.type),
      id: event.pointerId & 0xff,
      x: mapped.x,
      y: mapped.y
    });
  }
}

function pointerAction(type: string): 'down' | 'move' | 'up' {
  if (type === 'pointerdown') return 'down';
  if (type === 'pointermove') return 'move';
  return 'up';
}

function mapViewportPoint(clientX: number, clientY: number, rect: DOMRect, displayWidth: number, displayHeight: number): { x: number; y: number } | null {
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
  const x = (clientX - rect.left - offsetX) / contentWidth;
  const y = (clientY - rect.top - offsetY) / contentHeight;
  if (x < 0 || x > 1 || y < 0 || y > 1) return null;
  return { x, y };
}

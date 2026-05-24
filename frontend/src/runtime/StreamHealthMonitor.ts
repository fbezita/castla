import type { PaneId } from '../protocol';

export interface StreamHealth {
  pane: PaneId;
  reconnectCount: number;
  lastFrameAt: number;
  stale: boolean;
  decoderStalled: boolean;
  blackFrameSuspected: boolean;
}

export class StreamHealthMonitor {
  private states = new Map<PaneId, StreamHealth>();

  reconnect(pane: PaneId): void {
    const state = this.ensure(pane);
    state.reconnectCount++;
  }

  frame(pane: PaneId): void {
    const state = this.ensure(pane);
    state.lastFrameAt = performance.now();
    state.stale = false;
    state.decoderStalled = false;
  }

  sample(pane: PaneId): StreamHealth {
    const state = this.ensure(pane);
    const age = performance.now() - state.lastFrameAt;
    state.stale = age > 1500;
    state.decoderStalled = age > 3000;
    return state;
  }

  observeVideoElement(pane: PaneId, video: HTMLVideoElement): void {
    const callback = () => {
      this.frame(pane);
      video.requestVideoFrameCallback?.(callback);
    };
    video.requestVideoFrameCallback?.(callback);
  }

  private ensure(pane: PaneId): StreamHealth {
    let state = this.states.get(pane);
    if (!state) {
      state = { pane, reconnectCount: 0, lastFrameAt: 0, stale: true, decoderStalled: false, blackFrameSuspected: false };
      this.states.set(pane, state);
    }
    return state;
  }
}

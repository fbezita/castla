import type { Writable } from 'svelte/store';
import type { ControlMessage, DiagnosticsMessage, StreamMetadata } from '../protocol';
import type { CompositorState } from '../stores/compositorStore';
import { StreamRuntime } from '../runtime/StreamRuntime';

export class BrowserCompositor {
  constructor(
    private readonly runtime: StreamRuntime,
    private readonly store: Writable<CompositorState>
  ) {}

  start(): void {
    this.runtime.start();
    this.runtime.control.onMessage((message) => this.handleControl(message));
    this.runtime.onConnectionChange((connected) => {
      if (connected) return;
      this.store.update((state) => {
        const viewports = new Map(state.viewports);
        viewports.forEach((viewport, key) => {
          viewports.set(key, { ...viewport, committed: false });
        });
        return { ...state, viewports };
      });
    });
  }

  private handleControl(message: ControlMessage): void {
    if (message.type === 'streamMetadata') {
      this.applyStreamMetadata(message as StreamMetadata);
    }
    if (message.type === 'diagnostics') {
      const diagnostics = message as DiagnosticsMessage;
      this.store.update((state) => ({ ...state, diagnostics: diagnostics.displays ?? [] }));
    }
  }

  private applyStreamMetadata(metadata: StreamMetadata): void {
    this.store.update((state) => {
      const viewports = new Map(state.viewports);
      const previous = viewports.get(metadata.sessionId);
      viewports.set(metadata.sessionId, {
        pane: metadata.sessionId,
        width: metadata.width,
        height: metadata.height,
        committed: metadata.firstFrameReady,
        generation: metadata.generation,
        visible: previous?.visible ?? metadata.sessionId === 'primary'
      });
      return { ...state, viewports };
    });
  }
}

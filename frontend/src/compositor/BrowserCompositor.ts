import type { Writable } from 'svelte/store';
import type { ControlMessage, DiagnosticsMessage, StreamMetadata } from '../protocol';
import type { CompositorState } from '../stores/compositorStore';
import { StreamRuntime } from '../runtime/StreamRuntime';

export class BrowserCompositor {
  private cleanupFns: Array<() => void> = [];

  constructor(
    private readonly runtime: StreamRuntime,
    private readonly store: Writable<CompositorState>
  ) {}

  start(): void {
    this.runtime.start();
    this.cleanupFns.push(this.runtime.control.onMessage((message) => this.handleControl(message)));
    this.cleanupFns.push(this.runtime.onSessionChange(() => {
      this.resetCommittedState();
    }));
  }

  dispose(): void {
    this.cleanupFns.splice(0).forEach((cleanup) => cleanup());
  }

  private resetCommittedState(): void {
    this.store.update((state) => {
      const viewports = new Map(state.viewports);
      viewports.forEach((viewport, key) => {
        viewports.set(key, { ...viewport, committed: false });
      });
      return { ...state, viewports };
    });
  }

  private handleControl(message: ControlMessage): void {
    if (message.type === 'streamMetadata') {
      this.applyStreamMetadata(message as StreamMetadata);
    }
    if (message.type === 'videoLatency') {
      const pane = String(message.pane ?? 'primary');
      const videoLatencyMs = Number(message.videoLatencyMs ?? 0);
      this.store.update((state) => {
        const viewports = new Map(state.viewports);
        const previous = viewports.get(pane);
        if (previous) viewports.set(pane, { ...previous, videoLatencyMs });
        return { ...state, viewports };
      });
    }
    if (message.type === 'diagnostics') {
      const diagnostics = message as DiagnosticsMessage;
      this.store.update((state) => ({
        ...state,
        diagnostics: diagnostics.displays ?? state.diagnostics,
        serverDiagnostics: diagnostics.server ?? state.serverDiagnostics
      }));
    }
  }

  private applyStreamMetadata(metadata: StreamMetadata): void {
    this.store.update((state) => {
      const viewports = new Map(state.viewports);
      const previous = viewports.get(metadata.sessionId);
      viewports.set(metadata.sessionId, {
        pane: metadata.sessionId,
        width: previous?.width ?? metadata.width,
        height: previous?.height ?? metadata.height,
        streamWidth: metadata.width,
        streamHeight: metadata.height,
        committed: metadata.firstFrameReady,
        generation: metadata.generation,
        visible: previous?.visible ?? metadata.sessionId === 'primary',
        videoLatencyMs: metadata.videoLatencyMs ?? previous?.videoLatencyMs ?? 0
      });
      return { ...state, viewports };
    });
  }
}

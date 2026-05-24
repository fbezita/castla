import { writable } from 'svelte/store';
import type { DiagnosticsDisplay, PaneId } from '../protocol';

export interface ViewportModel {
  pane: PaneId;
  width: number;
  height: number;
  committed: boolean;
  generation: number;
  visible: boolean;
}

export interface CompositorState {
  viewports: Map<PaneId, ViewportModel>;
  diagnostics: DiagnosticsDisplay[];
  layoutMode: 'single' | 'split';
  splitRatio: number;
  splitReversed: boolean;
}

function readStoredSplitRatio(): number {
  if (typeof localStorage === 'undefined') return 0.5;
  const value = Number(localStorage.getItem('castla_split_ratio'));
  if (!Number.isFinite(value)) return 0.5;
  return Math.min(0.78, Math.max(0.22, value));
}

export const compositorStore = writable<CompositorState>({
  viewports: new Map([
    ['primary', { pane: 'primary', width: 1280, height: 720, committed: false, generation: 0, visible: true }]
  ]),
  diagnostics: [],
  layoutMode: 'single',
  splitRatio: readStoredSplitRatio(),
  splitReversed: false
});

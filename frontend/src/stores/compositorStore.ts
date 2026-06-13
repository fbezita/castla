import { writable } from 'svelte/store';
import type { Language } from '../lib/i18n';
import type { DiagnosticsDisplay, PaneId, ServerDiagnostics } from '../protocol';

export type LayoutMode = 'single' | 'split' | 'popup';
export type SecondaryPlacement = 'left' | 'right' | 'top' | 'bottom' | 'popup';

export type LaunchState =
  | 'IDLE'
  | 'LAYOUT_ALIGNING'
  | 'LAYOUT_ALIGNED'
  | 'LAUNCHING_PRIMARY'
  | 'PRIMARY_LAUNCHED'
  | 'LAUNCHING_SECONDARY'
  | 'SECONDARY_LAUNCHED'
  | 'STREAM_COMMITTING'
  | 'RUNNING'
  | 'FAILED';

export interface LaunchMetrics {
  layoutAlignMs: number;
  layoutAckMs: number;
  primaryLaunchAckMs: number;
  primarySessionReadyMs: number;
  streamCommitMs: number;
  totalLaunchMs: number;
}

export interface LaunchSequence {
  id: number;
  primaryPkg: string;
  secondaryPkg?: string;
  layoutMode: 'single' | 'split' | 'popup';
  state: LaunchState;
  startedAt: number;
  error?: string;
  degradedReason?: 'launch_failure' | 'session_timeout' | 'stream_timeout' | 'layout_timeout' | '';
  metrics?: LaunchMetrics;
  primaryStartGen: number;
  secondaryStartGen: number;
  expectedPrimaryPaneWidth?: number;
  expectedSecondaryPaneWidth?: number;
  expectedPaneHeight?: number;
}

export interface PopupLayoutState {
  visible: boolean;
  minimized: boolean;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface ViewportModel {
  pane: PaneId;
  width: number;
  height: number;
  streamWidth?: number;
  streamHeight?: number;
  committed: boolean;
  generation: number;
  visible: boolean;
}

export interface CompositorState {
  viewports: Map<PaneId, ViewportModel>;
  diagnostics: DiagnosticsDisplay[];
  serverDiagnostics: ServerDiagnostics | null;
  layoutMode: LayoutMode;
  splitRatio: number;
  activePrimaryApp: string;
  activeSecondaryApp: string;
  secondaryPlacement?: SecondaryPlacement | null;
  popup: PopupLayoutState;
  launchSequence: LaunchSequence; // Holds the active sequence and current state machine stage
  language: Language;
}

function readStoredLanguage(): Language {
  if (typeof localStorage === 'undefined') return 'ko';
  const stored = localStorage.getItem('castla_language');
  return stored === 'en' ? 'en' : 'ko';
}

function readStoredSplitRatio(): number {
  if (typeof localStorage === 'undefined') return 0.5;
  const value = Number(localStorage.getItem('castla_split_ratio'));
  if (!Number.isFinite(value)) return 0.5;
  return Math.min(0.78, Math.max(0.22, value));
}

function readStoredPopupState(): PopupLayoutState {
  const fallback: PopupLayoutState = {
    visible: true,
    minimized: false,
    x: 48,
    y: 72,
    width: 420,
    height: 280,
  };
  if (typeof localStorage === 'undefined') return fallback;
  try {
    const raw = localStorage.getItem('castla_full_popup_state');
    if (!raw) return fallback;
    const parsed = JSON.parse(raw) as Partial<PopupLayoutState> | null;
    if (!parsed) return fallback;
    return {
      visible: parsed.visible !== false,
      minimized: parsed.minimized === true,
      x: Number.isFinite(parsed.x) ? Number(parsed.x) : fallback.x,
      y: Number.isFinite(parsed.y) ? Number(parsed.y) : fallback.y,
      width: Number.isFinite(parsed.width) ? Number(parsed.width) : fallback.width,
      height: Number.isFinite(parsed.height) ? Number(parsed.height) : fallback.height,
    };
  } catch {
    return fallback;
  }
}

export function createInitialCompositorState(): CompositorState {
  return {
    viewports: new Map([
      ['primary', { pane: 'primary', width: 1280, height: 720, committed: false, generation: 0, visible: true }]
    ]),
    diagnostics: [],
    serverDiagnostics: null,
    layoutMode: 'single',
    splitRatio: readStoredSplitRatio(),
    activePrimaryApp: '',
    activeSecondaryApp: '',
    secondaryPlacement: null,
    popup: readStoredPopupState(),
    launchSequence: {
      id: 0,
      primaryPkg: '',
      secondaryPkg: '',
      layoutMode: 'split',
      state: 'IDLE',
      startedAt: 0,
      degradedReason: '',
      primaryStartGen: 0,
      secondaryStartGen: 0,
    },
    language: readStoredLanguage(),
  };
}

export const compositorStore = writable<CompositorState>(createInitialCompositorState());

export function resetCompositorStore(): void {
  compositorStore.set(createInitialCompositorState());
}

export function setLanguage(lang: Language): void {
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem('castla_language', lang);
  }
  compositorStore.update((state) => ({ ...state, language: lang }));
}

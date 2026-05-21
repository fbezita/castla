// English comment: Global State Bridge and State Definitions for Castla Web Client
// Ensures strict 100% logic preservation with Getter/Setter mappings to allow cross-module reassignments.

const host = window.location.host;

let _videoSocket = null;
let _controlSocket = null;
let _audioPlayer = null;
let _touchHandler = null;
let _secondaryVideoSocket = null;
let _secondaryTouchHandler = null;
let _secondaryDecoder = null;

let _decoder = null;
let _framePacer = null;
let _secondaryFramePacer = null;
let _currentPrimaryApp = null;
let _isLauncherMode = false;
let _currentServerInstanceId = null;
let _lastLaunchedInstanceId = null;
let _launchGuardUntil = 0;
let _lastLaunchTime = 0;
let _codecMode = "h264";
let _isPromotingSecondary = false;

let _userPreferredProfile = (() => {
  try {
    const saved = localStorage.getItem("userPreferredProfile");
    if (saved) return saved;
    const legacy = localStorage.getItem("playbackProfile");
    if (legacy) {
      localStorage.setItem("userPreferredProfile", legacy);
      localStorage.removeItem("playbackProfile");
      return legacy;
    }
    return "balanced";
  } catch (_) {
    return "balanced";
  }
})();
let _currentThermalLevel = "none";
let _ottProfileActive = false;
let _playbackProfile = _userPreferredProfile;
const DENSITY_STORAGE_KEY = "castla_display_density";
let _currentDensity = (() => {
  try {
    const saved = parseFloat(localStorage.getItem(DENSITY_STORAGE_KEY));
    return Number.isFinite(saved) ? saved : 0.7;
  } catch (_) {
    return 0.7;
  }
})();
let _streamPolicy = {
  fitMode: "contain",
  autoFit: false,
  layoutMode: "single",
};

let _autoTierToastTimer = null;
const DEFAULT_SPLIT_RATIO = 0.5;
let _splitRatio = DEFAULT_SPLIT_RATIO;
let _isResizing = false;
let _leftLockedViewport = null;
let _rightLockedViewport = null;

let _leftApp = null;
let _rightApp = null;

// Reactive state engine to track VD_1 (left) and VD_2 (right) virtual displays (SSOT)
let _state = {
  get left() { return _leftApp; },
  set left(app) {
    if (_leftApp === app) return;
    console.log(`[State] Left display app changed: ${app ? app.label : 'null'}`);
    _leftApp = app;
    if (typeof window.updateLayoutUI === 'function') {
      requestAnimationFrame(() => window.updateLayoutUI());
    }
  },
  get right() { return _rightApp; },
  set right(app) {
    if (_rightApp === app) return;
    console.log(`[State] Right display app changed: ${app ? app.label : 'null'}`);
    _rightApp = app;
    if (typeof window.updateLayoutUI === 'function') {
      requestAnimationFrame(() => window.updateLayoutUI());
    }
  }
};

let _browserSplitState = { url: null, preset: null, swapped: false };
const BROWSER_PRESETS = [
  { label: "YouTube", url: "https://m.youtube.com" },
  { label: "Netflix", url: "https://www.netflix.com" },
  { label: "Disney+", url: "https://www.disneyplus.com" },
  { label: "Wavve", url: "https://m.wavve.com" },
  { label: "TVING", url: "https://www.tving.com" },
  { label: "Coupang Play", url: "https://www.coupangplay.com" },
  { label: "Google", url: "https://www.google.com" },
];

let _firstFrameReceived = false;
let _launchTimeout = null;
let _composing = false;
let _skipNextInput = false;

let _useBubbleInput = localStorage.getItem("castla_use_bubble") === "true";
let _bubbleVisible = false;

let _launcherNoticeTimer = null;

let _allApps = [];
let _appPairs = (() => {
  try {
    const saved = localStorage.getItem("castla_app_pairs");
    return saved ? JSON.parse(saved) : [];
  } catch (_) {
    return [];
  }
})();

let _activeDragApp = null;
let _activeDragIsExisting = false;
let _longPressTimer = null;
let _dragGhost = null;
let _currentHoveredDropZone = null;
let _shouldDeferDragOverlay = false;
let _isFromSidebarDrag = false;
let _currentEditingPair = null;

let _reconnectTimer = null;
let _isReconnecting = false;
let _qualityReportInterval = null;
let _frameWatchdogTimer = null;
let _resizeTimer = null;

let _lastSentPrimary = {
  width: 0,
  height: 0,
  fitMode: null,
  layoutMode: null,
};
let _lastSentSecondary = {
  width: 0,
  height: 0,
  fitMode: null,
  layoutMode: null,
};
let _pendingLayoutSwitch = null;

// DOM Element references (bound dynamically in init module)
let _elements = {};

// Register all bindings to window scope to allow frictionless multi-module variable modifications
const properties = {
  host: { get() { return host; } },
  videoSocket: { get() { return _videoSocket; }, set(v) { _videoSocket = v; } },
  controlSocket: { get() { return _controlSocket; }, set(v) { _controlSocket = v; } },
  audioPlayer: { get() { return _audioPlayer; }, set(v) { _audioPlayer = v; } },
  touchHandler: { get() { return _touchHandler; }, set(v) { _touchHandler = v; } },
  secondaryVideoSocket: { get() { return _secondaryVideoSocket; }, set(v) { _secondaryVideoSocket = v; } },
  secondaryTouchHandler: { get() { return _secondaryTouchHandler; }, set(v) { _secondaryTouchHandler = v; } },
  secondaryDecoder: { get() { return _secondaryDecoder; }, set(v) { _secondaryDecoder = v; } },
  decoder: { get() { return _decoder; }, set(v) { _decoder = v; } },
  framePacer: { get() { return _framePacer; }, set(v) { _framePacer = v; } },
  secondaryFramePacer: { get() { return _secondaryFramePacer; }, set(v) { _secondaryFramePacer = v; } },
  currentPrimaryApp: { get() { return _currentPrimaryApp; }, set(v) { _currentPrimaryApp = v; } },
  isLauncherMode: { get() { return _isLauncherMode; }, set(v) { _isLauncherMode = v; } },
  currentServerInstanceId: { get() { return _currentServerInstanceId; }, set(v) { _currentServerInstanceId = v; } },
  lastLaunchedInstanceId: { get() { return _lastLaunchedInstanceId; }, set(v) { _lastLaunchedInstanceId = v; } },
  launchGuardUntil: { get() { return _launchGuardUntil; }, set(v) { _launchGuardUntil = v; } },
  lastLaunchTime: { get() { return _lastLaunchTime; }, set(v) { _lastLaunchTime = v; } },
  codecMode: { get() { return _codecMode; }, set(v) { _codecMode = v; } },
  isPromotingSecondary: { get() { return _isPromotingSecondary; }, set(v) { _isPromotingSecondary = v; } },
  userPreferredProfile: { get() { return _userPreferredProfile; }, set(v) { _userPreferredProfile = v; } },
  currentThermalLevel: { get() { return _currentThermalLevel; }, set(v) { _currentThermalLevel = v; } },
  ottProfileActive: { get() { return _ottProfileActive; }, set(v) { _ottProfileActive = v; } },
  playbackProfile: { get() { return _playbackProfile; }, set(v) { _playbackProfile = v; } },
  DENSITY_STORAGE_KEY: { get() { return DENSITY_STORAGE_KEY; } },
  currentDensity: { get() { return _currentDensity; }, set(v) { _currentDensity = v; } },
  streamPolicy: { get() { return _streamPolicy; }, set(v) { _streamPolicy = v; } },
  _autoTierToastTimer: { get() { return _autoTierToastTimer; }, set(v) { _autoTierToastTimer = v; } },
  DEFAULT_SPLIT_RATIO: { get() { return DEFAULT_SPLIT_RATIO; } },
  splitRatio: { get() { return _splitRatio; }, set(v) { _splitRatio = v; } },
  isResizing: { get() { return _isResizing; }, set(v) { _isResizing = v; } },
  leftLockedViewport: { get() { return _leftLockedViewport; }, set(v) { _leftLockedViewport = v; } },
  rightLockedViewport: { get() { return _rightLockedViewport; }, set(v) { _rightLockedViewport = v; } },
  _leftApp: { get() { return _leftApp; }, set(v) { _leftApp = v; } },
  _rightApp: { get() { return _rightApp; }, set(v) { _rightApp = v; } },
  state: { get() { return _state; }, set(v) { _state = v; } },
  browserSplitState: { get() { return _browserSplitState; }, set(v) { _browserSplitState = v; } },
  BROWSER_PRESETS: { get() { return BROWSER_PRESETS; } },
  firstFrameReceived: { get() { return _firstFrameReceived; }, set(v) { _firstFrameReceived = v; } },
  launchTimeout: { get() { return _launchTimeout; }, set(v) { _launchTimeout = v; } },
  composing: { get() { return _composing; }, set(v) { _composing = v; } },
  skipNextInput: { get() { return _skipNextInput; }, set(v) { _skipNextInput = v; } },
  useBubbleInput: { get() { return _useBubbleInput; }, set(v) { _useBubbleInput = v; } },
  bubbleVisible: { get() { return _bubbleVisible; }, set(v) { _bubbleVisible = v; } },
  launcherNoticeTimer: { get() { return _launcherNoticeTimer; }, set(v) { _launcherNoticeTimer = v; } },
  allApps: { get() { return _allApps; }, set(v) { _allApps = v; } },
  appPairs: { get() { return _appPairs; }, set(v) { _appPairs = v; } },
  activeDragApp: { get() { return _activeDragApp; }, set(v) { _activeDragApp = v; } },
  activeDragIsExisting: { get() { return _activeDragIsExisting; }, set(v) { _activeDragIsExisting = v; } },
  longPressTimer: { get() { return _longPressTimer; }, set(v) { _longPressTimer = v; } },
  dragGhost: { get() { return _dragGhost; }, set(v) { _dragGhost = v; } },
  currentHoveredDropZone: { get() { return _currentHoveredDropZone; }, set(v) { _currentHoveredDropZone = v; } },
  shouldDeferDragOverlay: { get() { return _shouldDeferDragOverlay; }, set(v) { _shouldDeferDragOverlay = v; } },
  isFromSidebarDrag: { get() { return _isFromSidebarDrag; }, set(v) { _isFromSidebarDrag = v; } },
  currentEditingPair: { get() { return _currentEditingPair; }, set(v) { _currentEditingPair = v; } },
  reconnectTimer: { get() { return _reconnectTimer; }, set(v) { _reconnectTimer = v; } },
  isReconnecting: { get() { return _isReconnecting; }, set(v) { _isReconnecting = v; } },
  qualityReportInterval: { get() { return _qualityReportInterval; }, set(v) { _qualityReportInterval = v; } },
  frameWatchdogTimer: { get() { return _frameWatchdogTimer; }, set(v) { _frameWatchdogTimer = v; } },
  resizeTimer: { get() { return _resizeTimer; }, set(v) { _resizeTimer = v; } },
  lastSentPrimary: { get() { return _lastSentPrimary; }, set(v) { _lastSentPrimary = v; } },
  lastSentSecondary: { get() { return _lastSentSecondary; }, set(v) { _lastSentSecondary = v; } },
  pendingLayoutSwitch: { get() { return _pendingLayoutSwitch; }, set(v) { _pendingLayoutSwitch = v; } },
  elements: { get() { return _elements; } }
};

Object.defineProperties(window, properties);

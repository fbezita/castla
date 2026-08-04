export type Language = "ko" | "en";

export const TRANSLATIONS = {
  ko: {
    // App Drawer Header & Controls
    launchHub: "시작 허브",
    appsCount: "개의 앱",
    loading: "로딩 중...",
    searchPlaceholder: "검색 또는 실행",
    single: "싱글",
    split: "분할",
    popup: "팝업",
    swap: "화면 전환",
    settingsDiagnostics: "설정 및 진단",
    closeLauncher: "런처 닫기",
    openLauncher: "런처 열기",
    placementChange: "배치 변경",
    left: "왼쪽",
    right: "오른쪽",
    top: "위쪽",
    bottom: "아래쪽",
    moveSecondaryTitle: "보조 화면 위치 변경",
    moveSecondaryDesc: "보조 화면이 위치할 영역을 선택하세요.",
    multiwindow: "멀티윈도우",
    placement: "화면 배치",

    // Tabs
    tab_autorun: "자동 실행",
    tab_starred: "즐겨찾기",
    tab_recent: "최근 사용",
    tab_notifications: "알림",
    tab_browse: "앱 보관함",

    // Tab Empty Panels
    emptyAutorun: "연결 시 자동 실행할 앱 또는 앱 페어를 설정하세요.",
    emptyStarred: "앱에 별을 표시하여 바로가기 레인에 고정하세요.",
    emptyRecent: "최근 사용 기록을 만들려면 앱을 실행해 보세요.",
    emptyNotifications: "알림을 받을 앱을 이 탭으로 끌어오세요.",
    allCategoriesCollapsed: "모든 카테고리가 접혀 있습니다",
    matches: "개 검색됨",
    releaseAutoRun: "자동 실행에 추가하려면 놓으세요",
    releaseStarred: "즐겨찾기에 추가하려면 놓으세요",
    releaseNotifications: "알림 허용 목록에 추가하려면 놓으세요",

    // Time Ago Formatters
    justNow: "방금 전",
    minAgo: "분 전",
    hrAgo: "시간 전",
    yesterday: "어제",
    daysAgo: "일 전",

    // Group Categories
    group_PAIR: "앱 페어",
    group_NAVIGATION: "내비게이션",
    group_VIDEO: "동영상",
    group_MUSIC: "음악",
    group_OTHER: "모든 앱",

    // Toast Notifications
    toast_launching: "실행 중",
    toast_favorite_updated: "즐겨찾기가 업데이트되었습니다",
    toast_favorite_removed: "즐겨찾기가 제거되었습니다",
    toast_autorun_updated: "자동 실행이 업데이트되었습니다",
    toast_notifications_updated: "알림 허용 목록이 업데이트되었습니다",
    toast_removed_shortcuts: "바로가기에서 제거되었습니다",
    toast_choose_different: "다른 앱을 선택하세요",
    toast_app_pair_updated: "앱 페어가 업데이트되었습니다",
    toast_app_pair_removed: "앱 페어가 제거되었습니다",

    // Standby Logo Screen
    standbyReady:
      "스트리밍 준비 완료. 앱을 실행하려면 사이드바 드로어를 여세요.",
    standbyLaunching:
      "애플리케이션 실행 중... 고해상도 스트림 링크 설정 중입니다.",
    serverActive: "서버 활성",

    // Viewport Host & Popups
    barrierText: "화면 레이아웃 최적화 중...",
    minimize: "최소화",
    close: "닫기",
    subWindow: "보조 화면",
    appPair: "앱 페어",
    notificationHistory: "알림 기록",
    notificationHistoryClose: "알림 기록 닫기",
    notificationExpand: "펴기 ▼",
    notificationCollapse: "접기 ▲",
    notificationContentHidden: "🔒 내용을 숨겼습니다.",
    notificationContainsImage: "사진이 포함된 알림",
    reconnecting: "연결이 일시적으로 중단되었습니다. 복구 중...",
  },
  en: {
    // App Drawer Header & Controls
    launchHub: "Launch Hub",
    appsCount: "apps",
    loading: "Loading",
    searchPlaceholder: "Search or launch",
    single: "Single",
    split: "Split",
    popup: "Popup",
    swap: "Swap",
    settingsDiagnostics: "Settings and diagnostics",
    closeLauncher: "Close launcher",
    openLauncher: "Open launcher",
    placementChange: "Placement",
    left: "Left",
    right: "Right",
    top: "Top",
    bottom: "Bottom",
    moveSecondaryTitle: "Move Secondary Window",
    moveSecondaryDesc: "Select where the current secondary app should go.",
    multiwindow: "Multiwindow",
    placement: "Placement",

    // Tabs
    tab_autorun: "Auto-run",
    tab_starred: "Starred",
    tab_recent: "Recent",
    tab_notifications: "Alerts",
    tab_browse: "Browse",

    // Tab Empty Panels
    emptyAutorun: "Set one app or app pair to auto-run on connect.",
    emptyStarred: "Star apps to pin them in your launcher lane.",
    emptyRecent: "Launch an app once to build your recent history.",
    emptyNotifications: "Drag apps here to allow mirrored notifications.",
    allCategoriesCollapsed: "All categories collapsed",
    matches: "matches",
    releaseAutoRun: "Release to add to Auto Run",
    releaseStarred: "Release to add to Starred",
    releaseNotifications: "Release to allow notifications",

    // Time Ago Formatters
    justNow: "Just now",
    minAgo: "min ago",
    hrAgo: "hr ago",
    yesterday: "Yesterday",
    daysAgo: "days ago",

    // Group Categories
    group_PAIR: "App Pairs",
    group_NAVIGATION: "Navigation",
    group_VIDEO: "Video",
    group_MUSIC: "Music",
    group_OTHER: "All Apps",

    // Toast Notifications
    toast_launching: "launching",
    toast_favorite_updated: "Favorite updated",
    toast_favorite_removed: "Favorite removed",
    toast_autorun_updated: "Auto-run updated",
    toast_notifications_updated: "Notification list updated",
    toast_removed_shortcuts: "Removed from shortcuts",
    toast_choose_different: "Choose a different app",
    toast_app_pair_updated: "App Pair updated",
    toast_app_pair_removed: "App Pair removed",

    // Standby Logo Screen
    standbyReady: "Ready to Stream. Open the sidebar drawer to launch an app.",
    standbyLaunching:
      "Launching application... Establishing high-fidelity stream link.",
    serverActive: "SERVER ACTIVE",

    // Viewport Host & Popups
    barrierText: "Optimizing screen layout...",
    minimize: "Minimize",
    close: "Close",
    subWindow: "Sub Window",
    appPair: "App Pair",
    notificationHistory: "Notification history",
    notificationHistoryClose: "Close notification history",
    notificationExpand: "Expand ▼",
    notificationCollapse: "Collapse ▲",
    notificationContentHidden: "🔒 Content hidden.",
    notificationContainsImage: "Notification contains an image",
    reconnecting: "Connection was temporarily interrupted. Recovering...",
  },
};

export function t(
  lang: Language,
  key: keyof (typeof TRANSLATIONS)["en"],
): string {
  const dictionary = TRANSLATIONS[lang] || TRANSLATIONS["en"];
  return dictionary[key] || TRANSLATIONS["en"][key] || String(key);
}

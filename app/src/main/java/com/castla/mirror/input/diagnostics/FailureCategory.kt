package com.castla.mirror.input.diagnostics

enum class FailureCategory {
    FOCUS_FAILURE,               // 1. Target field lacks accessibility editable focus
    INPUT_CONNECTION_NULL,       // 2. ImeService has no InputConnection instance
    INPUT_CONNECTION_STALE,      // 3. Cached InputConnection's binder IPC is dead (DeadObjectException)
    IME_LIFECYCLE_MISMATCH,      // 4. InputMethodManager is in finishView state
    WEBVIEW_INCOMPATIBILITY,     // 5. Chromium web core drops caret/focus state
    COMPOSE_INCOMPATIBILITY,     // 6. Jetpack Compose recompose state flow de-syncs
    ACCESSIBILITY_MISMATCH,      // 7. Node hierarchy tree shows stale focused edit bounds
    DISPLAY_MISMATCH,            // 8. Virtual display context routing is incorrect
    SELECTION_SYNC_FAILURE,      // 9. Cursor index is placed in out-of-bounds index
    COMPOSING_FAILURE            // 10. setComposingText character blocks are mangled
}

package com.castla.mirror.input

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Encapsulates the precise state of the currently focused editable node
 * across multiple virtual displays to detect focus drift.
 */
data class EditableFocusState(
    val hasEditableFocus: Boolean = false,
    val packageName: String = "",
    val className: String = "",
    val text: String = "",
    val selectionStart: Int = -1,
    val selectionEnd: Int = -1,
    val windowId: Int = -1,
    val displayId: Int = -1,
    val isFocused: Boolean = false,
    val timestamp: Long = 0L
)

class AccessibilityFocusManager {
    private val _state = MutableStateFlow(EditableFocusState())
    val state: StateFlow<EditableFocusState> = _state

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val sourceNode = event.source
                if (sourceNode == null) {
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                        event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) {
                        publishFocusState(EditableFocusState(timestamp = System.currentTimeMillis()))
                    }
                    return
                }
                try {
                    val isEditable = sourceNode.isEditable
                    val className = sourceNode.className?.toString().orEmpty()
                    val packageName = sourceNode.packageName?.toString().orEmpty()
                    val isNodeFocused = sourceNode.isFocused
                    val currentText = sourceNode.text?.toString().orEmpty()
                    val selectStart = sourceNode.textSelectionStart
                    val selectEnd = sourceNode.textSelectionEnd
                    val windowId = event.windowId // Direct non-reflection field

                    val focusState = EditableFocusState(
                        hasEditableFocus = isEditable,
                        packageName = packageName,
                        className = className,
                        text = currentText,
                        selectionStart = selectStart,
                        selectionEnd = selectEnd,
                        windowId = windowId,
                        displayId = -1, // Do not rely on displayId reflection on Samsung/OEM devices
                        isFocused = isNodeFocused,
                        timestamp = System.currentTimeMillis()
                    )

                    publishFocusState(focusState)

                    // Dynamically switch/restore default IME on remote focus transitions
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                        val myPkg = com.castla.mirror.service.MirrorForegroundService.instance?.packageName ?: ""
                        if (isEditable && isNodeFocused) {
                            if (myPkg.isNotEmpty() && packageName != myPkg) {
                                Log.i("AccessibilityFocus", "Editable field focused in target app '$packageName'. Switching to Castla IME.")
                                com.castla.mirror.service.MirrorForegroundService.instance?.ensureCastlaImeActiveDynamically()
                            }
                        } else if (!isEditable) {
                            Log.i("AccessibilityFocus", "Non-editable field focused. Restoring user keyboard.")
                            com.castla.mirror.service.MirrorForegroundService.instance?.restoreUserKeyboardSilently()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AccessibilityFocus", "Failed to extract node diagnostics", e)
                } finally {
                    sourceNode.recycle()
                }
            }
        }
    }
    private fun publishFocusState(state: EditableFocusState) {
        _state.value = state
        CastlaTextInputRouter.getInstance().updateFocusRegistry(state)
    }

}

class CastlaFocusAccessibilityService : AccessibilityService() {
    private val focusManager = AccessibilityFocusManager()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            focusManager.onAccessibilityEvent(event)
        }
    }

    override fun onInterrupt() = Unit
}

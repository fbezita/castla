package com.castla.mirror.input

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EditableFocusState(
    val hasEditableFocus: Boolean = false,
    val packageName: String = "",
    val className: String = "",
    val text: String = "",
    val fromIndex: Int = -1,
    val toIndex: Int = -1
)

class AccessibilityFocusManager {
    private val _state = MutableStateFlow(EditableFocusState())
    val state: StateFlow<EditableFocusState> = _state

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val className = event.className?.toString().orEmpty()
                val editable = className.contains("EditText", ignoreCase = true) ||
                    className.contains("TextField", ignoreCase = true) ||
                    event.isPassword
                _state.value = EditableFocusState(
                    hasEditableFocus = editable,
                    packageName = event.packageName?.toString().orEmpty(),
                    className = className,
                    text = event.text?.joinToString("").orEmpty(),
                    fromIndex = event.fromIndex,
                    toIndex = event.toIndex
                )
            }
        }
    }
}

abstract class CastlaFocusAccessibilityService : AccessibilityService() {
    protected val focusManager = AccessibilityFocusManager()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) focusManager.onAccessibilityEvent(event)
    }

    override fun onInterrupt() = Unit
}

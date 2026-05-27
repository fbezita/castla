package com.castla.mirror.input

import android.os.DeadObjectException
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.castla.mirror.input.diagnostics.FailureCategory
import com.castla.mirror.input.diagnostics.TextInputLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class CastlaTextInputRouter private constructor() {

    private val logger = TextInputLogger.getInstance()
    
    @Volatile private var activeConnection: InputConnection? = null
    @Volatile private var activeEditorInfo: EditorInfo? = null
    
    // Accessibility focus registry mapping to track node consistency
    @Volatile private var cachedFocusState: EditableFocusState? = null
    private val focusStateFlow = MutableStateFlow(EditableFocusState())

    // Provision of privileged service provider and display id references externally
    private var privilegedServiceProvider: (() -> com.castla.mirror.shizuku.IPrivilegedService?)? = null
    private var displayIdProvider: (() -> Int)? = null

    companion object {
        @Volatile private var instance: CastlaTextInputRouter? = null
        
        fun getInstance(): CastlaTextInputRouter {
            return instance ?: synchronized(this) {
                instance ?: CastlaTextInputRouter().also { instance = it }
            }
        }
    }

    fun configureProviders(
        privilegedService: () -> com.castla.mirror.shizuku.IPrivilegedService?,
        displayId: () -> Int
    ) {
        privilegedServiceProvider = privilegedService
        displayIdProvider = displayId
    }

    fun refreshCurrentConnection(connection: InputConnection, info: EditorInfo) {
        activeConnection = connection
        activeEditorInfo = info
        logger.logInputConnectionState("REFRESHED: targetPkg=${info.packageName}")
    }

    fun getActiveEditorInfo(): android.view.inputmethod.EditorInfo? = activeEditorInfo

    fun updateFocusRegistry(state: EditableFocusState) {
        cachedFocusState = state
        focusStateFlow.value = state
    }

    fun getCachedFocusState(): EditableFocusState? = cachedFocusState

    fun isEditableFocusedRecently(maxAgeMs: Long = 700L): Boolean {
        val focus = cachedFocusState ?: return false
        val ageMs = System.currentTimeMillis() - focus.timestamp
        return focus.hasEditableFocus && focus.isFocused && ageMs in 0..maxAgeMs
    }

    suspend fun waitUntilEditableFocusCleared(timeoutMs: Long = 500L): Boolean {
        if (!isEditableFocusedRecently(Long.MAX_VALUE)) return true
        return withTimeoutOrNull(timeoutMs) {
            focusStateFlow
                .filter { !it.hasEditableFocus || !it.isFocused }
                .first()
            true
        } ?: false
    }

    fun invalidateInputConnection() {
        activeConnection = null
        activeEditorInfo = null
        logger.logInputConnectionState("INVALIDATED / CLEARED")
    }

    /**
     * Verifies connection integrity using DeadObjectException and checks focus alignment
     * using the reliability hierarchy: Node > Package > windowId > displayId
     */
    fun validateConnectionForTarget(targetDisplayId: Int): Pair<Boolean, InputConnection?> {
        val conn = activeConnection
        val info = activeEditorInfo
        val focus = cachedFocusState

        if (conn == null || info == null) {
            logger.logFailure(FailureCategory.INPUT_CONNECTION_NULL, "No active InputConnection cached in IME context")
            return false to null
        }

        // 1. Binder Connection Integrity Check
        try {
            // Safe batch operation test to probe binder transaction state
            conn.beginBatchEdit()
            conn.endBatchEdit()
        } catch (e: DeadObjectException) {
            logger.logFailure(FailureCategory.INPUT_CONNECTION_STALE, "InputConnection Binder is DEAD (DeadObjectException)")
            invalidateInputConnection()
            return false to null
        } catch (e: Exception) {
            logger.logFailure(FailureCategory.INPUT_CONNECTION_STALE, "IPC verification failed: ${e.message}")
            invalidateInputConnection()
            return false to null
        }

        // 2. Focused Editable Node Consistency Alignment (Trust Hierarchy)
        if (focus == null || !focus.hasEditableFocus) {
            logger.logFailure(FailureCategory.FOCUS_FAILURE, "Accessibility focus node is not ready or not editable")
            return false to conn
        }

        // Package validation
        if (focus.packageName != info.packageName) {
            logger.logFailure(
                FailureCategory.IME_LIFECYCLE_MISMATCH,
                "Package mismatch: focusName=${focus.packageName} vs imeTarget=${info.packageName}"
            )
            return false to conn
        }

        // Window & Display awareness check for diagnostic logging
        if (focus.displayId >= 0 && focus.displayId != targetDisplayId) {
            logger.logFailure(
                FailureCategory.DISPLAY_MISMATCH,
                "Display mismatch: focusDisplay=${focus.displayId} vs requestedDisplay=$targetDisplayId"
            )
        }

        return true to conn
    }

    /**
     * Focus Nudge: Injects a lightweight virtual tap event on the target display
     * to wake up focused state and trigger IME re-binding on target VirtualDisplay.
     */
    fun triggerRecoveryFocusNudge() {
        val service = privilegedServiceProvider?.invoke()
        val displayId = displayIdProvider?.invoke() ?: return
        
        if (service == null) {
            Log.w("TextInputRouter", "Nudge failed: PrivilegedService unavailable")
            return
        }

        Log.i("TextInputRouter", "Triggering recovery focus nudge tap on display $displayId")
        try {
            // Execute shell input tap at standard screen coordinates to awaken window focus
            service.execCommand(if (displayId > 0) "input -d $displayId tap 100 100" else "input tap 100 100")
        } catch (e: Exception) {
            Log.e("TextInputRouter", "Failed to inject focus nudge tap", e)
        }
    }
}

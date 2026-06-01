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

/**
 * Registry state tracking for IME text input to bypass accessibility dependencies.
 */
data class ImeFocusState(
    val sessionId: Long = 0L,
    val packageName: String? = null,
    val inputType: Int = 0,
    val imeOptions: Int = 0,
    val privateImeOptions: String? = null,
    val isFocused: Boolean = false,
    val timestamp: Long = 0L
)

class CastlaTextInputRouter private constructor() {

    private val logger = TextInputLogger.getInstance()
    
    @Volatile private var activeConnection: InputConnection? = null
    @Volatile private var activeEditorInfo: EditorInfo? = null

    // Pure hybrid IME state registry (accessible across all builds without accessibility)
    private val imeFocusStateFlow = MutableStateFlow(ImeFocusState())
    @Volatile private var cachedImeFocusState: ImeFocusState = ImeFocusState()

    @Volatile private var remoteTextDirty = false

    fun isRemoteTextDirty(): Boolean = remoteTextDirty

    fun setRemoteTextDirty(dirty: Boolean) {
        remoteTextDirty = dirty
    }

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

    fun updateImeFocusState(state: ImeFocusState) {
        if (state.sessionId != cachedImeFocusState.sessionId) {
            remoteTextDirty = false
            Log.i("TextInputRouter", "Resetting remoteTextDirty to false for new sessionId=${state.sessionId}")
        }
        cachedImeFocusState = state
        imeFocusStateFlow.value = state
        logger.logInputConnectionState("IME FOCUS STATE UPDATED: sessionId=${state.sessionId}, pkg=${state.packageName}, isFocused=${state.isFocused}")
    }

    fun getCachedImeFocusState(): ImeFocusState = cachedImeFocusState

    fun isEditableFocusedRecently(maxAgeMs: Long = 700L): Boolean {
        val focus = cachedImeFocusState
        // If actively focused, it is always considered recently editable regardless of timestamp
        if (focus.isFocused) {
            return true
        }
        // If recently blurred, check if the elapsed time is within the allowed grace period
        val ageMs = System.currentTimeMillis() - focus.timestamp
        return ageMs in 0..maxAgeMs
    }

    suspend fun waitUntilEditableFocusCleared(timeoutMs: Long = 500L): Boolean {
        if (!isEditableFocusedRecently(Long.MAX_VALUE)) return true
        return withTimeoutOrNull(timeoutMs) {
            imeFocusStateFlow
                .filter { !it.isFocused }
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
     * Verifies connection integrity and checks focus alignment.
     * Mismatches in metadata are handled as soft-warnings to accommodate
     * dynamic webviews and app integrations without breaking active input.
     */
    fun validateConnectionForTarget(targetDisplayId: Int): Pair<Boolean, InputConnection?> {
        val conn = activeConnection
        val info = activeEditorInfo

        Log.i("TextInputRouter", "[VALIDATE] active=${(conn != null)} pkg=${info?.packageName}")

        if (conn == null || info == null) {
            Log.i("TextInputRouter", "[VALIDATE] FAIL reason=No active InputConnection cached in IME context")
            logger.logFailure(FailureCategory.INPUT_CONNECTION_NULL, "No active InputConnection cached in IME context")
            return false to null
        }

        // Hybrid IME state registry validation (applied across standard and advanced configurations)
        val imeState = cachedImeFocusState

        // Validate package name (soft-warning for OAuth, Chrome Custom Tabs, or Samsung Pass redirection)
        if (imeState.packageName != null && info.packageName != imeState.packageName) {
            logger.logFailure(
                FailureCategory.IME_LIFECYCLE_MISMATCH,
                "IME Package mismatch: imeState=${imeState.packageName} vs activeInfo=${info.packageName}"
            )
            Log.w("TextInputRouter", "IME Package mismatch: imeState=${imeState.packageName} vs activeInfo=${info.packageName}. Soft-warning, proceeding.")
        }

        // Soft-Fail: Log warnings for dynamic IME option updates instead of breaking session
        if (imeState.inputType != 0 && info.inputType != imeState.inputType) {
            Log.w("TextInputRouter", "IME InputType mismatch (Soft-fail): imeState=${imeState.inputType} vs activeInfo=${info.inputType}")
        }
        if (imeState.imeOptions != 0 && info.imeOptions != imeState.imeOptions) {
            Log.w("TextInputRouter", "IME ImeOptions mismatch (Soft-fail): imeState=${imeState.imeOptions} vs activeInfo=${info.imeOptions}")
        }
        if (imeState.privateImeOptions != null && info.privateImeOptions != imeState.privateImeOptions) {
            Log.w("TextInputRouter", "IME PrivateImeOptions mismatch (Soft-fail): imeState=${imeState.privateImeOptions} vs activeInfo=${info.privateImeOptions}")
        }

        Log.i("TextInputRouter", "[VALIDATE] PASS")
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

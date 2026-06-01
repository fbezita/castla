package com.castla.mirror.input.diagnostics

import android.util.Log
import android.view.inputmethod.EditorInfo
import com.castla.mirror.input.ImeFocusState
import com.castla.mirror.input.CastlaTextInputRouter

/**
 * Captures a unique failure pattern linked to a specific package
 * to trigger specialized app-specific workarounds.
 */
data class InputFailureFingerprint(
    val packageName: String,
    val category: FailureCategory,
    val errorLogSnippet: String,
    val occurrenceCount: Int = 1
)

class FailureFingerprintDatabase private constructor() {

    private val fingerprintMap = java.util.concurrent.ConcurrentHashMap<String, MutableList<InputFailureFingerprint>>()

    companion object {
        @Volatile private var instance: FailureFingerprintDatabase? = null
        
        fun getInstance(): FailureFingerprintDatabase {
            return instance ?: synchronized(this) {
                instance ?: FailureFingerprintDatabase().also { instance = it }
            }
        }
    }

    /**
     * Records an input failure and triggers reactive hot-fixes or logs details
     */
    fun recordFailure(packageName: String, category: FailureCategory, details: String) {
        val list = fingerprintMap.getOrPut(packageName) { mutableListOf() }
        val existing = list.find { it.category == category }
        
        if (existing != null) {
            list[list.indexOf(existing)] = existing.copy(occurrenceCount = existing.occurrenceCount + 1)
        } else {
            list.add(InputFailureFingerprint(packageName, category, details))
        }

        // App-specific hardening dispatcher
        Log.i("CastlaFingerprint", "Recorded failure for $packageName: category=$category (count=${list.find { it.category == category }?.occurrenceCount ?: 1})")
    }
}

class TextInputLogger private constructor() {

    companion object {
        private const val TAG = "CastlaTextBridge"
        @Volatile private var instance: TextInputLogger? = null
        
        fun getInstance(): TextInputLogger {
            return instance ?: synchronized(this) {
                instance ?: TextInputLogger().also { instance = it }
            }
        }
    }

    fun logRemoteInputRx(actionType: String, text: String, deleteCount: Int, rxTimestamp: Long) {
        Log.i(TAG, "[TEXT_RX] actionType=$actionType, text='$text', deleteCount=$deleteCount, rxTime=$rxTimestamp")
    }

    fun logFocus(state: ImeFocusState) {
        Log.i(TAG, "[FOCUS_IME] isFocused=${state.isFocused}, pkg=${state.packageName}, sessionId=${state.sessionId}")
    }

    fun logImeLifecycle(event: String, info: EditorInfo? = null) {
        val editorDetails = if (info != null) "targetPkg=${info.packageName}, inputType=${info.inputType}" else "none"
        Log.i(TAG, "[IME_STATE] event=$event, editorInfo=[$editorDetails]")
    }

    fun logInputConnectionState(state: String) {
        Log.i(TAG, "[INPUT_CONNECTION] state=$state")
    }

    fun logComposeAction(action: String) {
        Log.i(TAG, "[COMPOSE] action=$action")
    }

    fun logCommitAction(action: String) {
        Log.i(TAG, "[COMMIT] action=$action")
    }

    fun logVerify(result: String) {
        Log.i(TAG, "[VERIFY] result=$result")
    }

    fun logFailure(category: FailureCategory, details: String) {
        Log.e(TAG, "[FAILURE_CLASSIFIED] category=${category.name}, details='$details'")
        
        // Push error to Fingerprint Database automatically
        val focusState = CastlaTextInputRouter.getInstance().getCachedImeFocusState()
        val activePkg = focusState.packageName ?: "unknown"
        FailureFingerprintDatabase.getInstance().recordFailure(activePkg, category, details)
    }
}

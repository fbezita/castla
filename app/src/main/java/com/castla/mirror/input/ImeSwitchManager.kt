package com.castla.mirror.input

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ImeState {
    IDLE,
    SAVING_PREVIOUS,
    ENABLING_CASTLA,
    SWITCHING_TO_CASTLA,
    CASTLA_ACTIVE,
    RESTORING_PREVIOUS,
    RESTORE_PENDING,
    ERROR
}

enum class ImeEvent {
    RemoteTextFocus,
    RemoteTextBlur,
    PhoneEditableFocus,
    MirroringStopped,
    Timeout,
    ShizukuReady,
    SwitchSuccess,
    SwitchFailure,
    RestoreSuccess,
    RestoreFailure,
    AppStartupRecovery
}

/**
 * Manages session-scoped keyboard switching for Castla remote input
 * implemented as an explicit Finite State Machine (FSM) that controls global Android IME secure settings.
 * All state transitions and settings modifications are completely serialized and reentrancy-proof
 * via a strict coroutine Mutex lock.
 */
object ImeSwitchManager {
    private const val TAG = "ImeSwitchManager"
    private const val PREFS_NAME = "castla_ime_restore_prefs"
    private const val KEY_PREVIOUS_IME = "previous_ime_id"
    private const val KEY_RESTORE_PENDING = "restore_pending"
    private const val CASTLA_IME_ID = "com.castla.mirror/.input.CastlaImeService"

    private val mutex = Mutex()
    @Volatile private var currentState = ImeState.IDLE

    fun getCurrentState(): ImeState = currentState

    /**
     * Sends an event to the IME Switch FSM.
     * Guaranteed to be race-safe and reentrancy-proof using a strict Mutex lock.
     */
    suspend fun sendEvent(context: Context, event: ImeEvent, execCommand: (String) -> String?) = mutex.withLock {
        val oldState = currentState
        Log.i(TAG, "[FSM] Event received: $event (Current State: $oldState)")

        try {
            when (oldState) {
                ImeState.IDLE -> handleIdle(context, event, execCommand)
                ImeState.SAVING_PREVIOUS -> handleSavingPrevious(context, event, execCommand)
                ImeState.ENABLING_CASTLA -> handleEnablingCastla(context, event, execCommand)
                ImeState.SWITCHING_TO_CASTLA -> handleSwitchingToCastla(context, event, execCommand)
                ImeState.CASTLA_ACTIVE -> handleCastlaActive(context, event, execCommand)
                ImeState.RESTORING_PREVIOUS -> handleRestoringPrevious(context, event, execCommand)
                ImeState.RESTORE_PENDING -> handleRestorePending(context, event, execCommand)
                ImeState.ERROR -> handleErrorState(context, event, execCommand)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[FSM] Critical failure during state transition from $oldState on event $event", e)
            currentState = ImeState.ERROR
        }

        if (currentState != oldState) {
            Log.i(TAG, "[FSM] State Transition: $oldState -> $currentState")
        }
    }

    private fun handleIdle(context: Context, event: ImeEvent, execCommand: (String) -> String?) {
        when (event) {
            ImeEvent.RemoteTextFocus -> {
                currentState = ImeState.SAVING_PREVIOUS
                performSaveAndSwitchFlow(context, execCommand)
            }
            ImeEvent.AppStartupRecovery -> {
                currentState = ImeState.RESTORE_PENDING
                performSelfHealingFlow(context, execCommand)
            }
            ImeEvent.ShizukuReady -> {
                // Ensure Castla IME is enabled silently in system keyboards on Shizuku binding
                performSilentEnable(context, execCommand)
            }
            ImeEvent.MirroringStopped -> {
                // Failsafe recovery for manual restore even if FSM is already idle
                currentState = ImeState.RESTORE_PENDING
                performSelfHealingFlow(context, execCommand)
            }
            else -> { /* Ignore irrelevant events in IDLE */ }
        }
    }

    private fun handleSavingPrevious(context: Context, event: ImeEvent, execCommand: (String) -> String?) {
        // Transitional state, should usually transition automatically, but handle failures
        if (event == ImeEvent.SwitchFailure) {
            currentState = ImeState.ERROR
        }
    }

    private fun handleEnablingCastla(context: Context, event: ImeEvent, execCommand: (String) -> String?) {
        if (event == ImeEvent.SwitchFailure) {
            currentState = ImeState.ERROR
        }
    }

    private fun handleSwitchingToCastla(context: Context, event: ImeEvent, execCommand: (String) -> String?) {
        when (event) {
            ImeEvent.SwitchSuccess -> {
                currentState = ImeState.CASTLA_ACTIVE
            }
            ImeEvent.SwitchFailure -> {
                currentState = ImeState.ERROR
            }
            else -> {}
        }
    }

    private fun handleCastlaActive(context: Context, event: ImeEvent, execCommand: (String) -> String?) {
        when (event) {
            ImeEvent.RemoteTextBlur,
            ImeEvent.PhoneEditableFocus,
            ImeEvent.MirroringStopped,
            ImeEvent.Timeout -> {
                currentState = ImeState.RESTORING_PREVIOUS
                performRestoreFlow(context, execCommand)
            }
            else -> {}
        }
    }

    private fun handleRestoringPrevious(context: Context, event: ImeEvent, execCommand: (String) -> String?) {
        when (event) {
            ImeEvent.RestoreSuccess -> {
                currentState = ImeState.IDLE
            }
            ImeEvent.RestoreFailure -> {
                currentState = ImeState.ERROR
            }
            else -> {}
        }
    }

    private fun handleRestorePending(context: Context, event: ImeEvent, execCommand: (String) -> String?) {
        when (event) {
            ImeEvent.RestoreSuccess -> {
                currentState = ImeState.IDLE
            }
            ImeEvent.RestoreFailure -> {
                currentState = ImeState.ERROR
            }
            else -> {}
        }
    }

    private fun handleErrorState(context: Context, event: ImeEvent, execCommand: (String) -> String?) {
        // Allow recovery on manual or startup triggers even from error state
        if (event == ImeEvent.AppStartupRecovery || event == ImeEvent.RemoteTextBlur || event == ImeEvent.MirroringStopped) {
            currentState = ImeState.RESTORE_PENDING
            performSelfHealingFlow(context, execCommand)
        }
    }

    // --- Core Database Settings Operations ---

    private fun performSilentEnable(context: Context, execCommand: (String) -> String?) {
        try {
            val targetIme = "${context.packageName}/com.castla.mirror.input.CastlaImeService"
            val enabledStr = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS) ?: ""
            // toMutableSet() in Kotlin returns a LinkedHashSet, which strictly preserves the original IME insertion order.
            val enabled = enabledStr.split(":").filter { it.isNotBlank() && it != "null" }.toMutableSet()

            if (!enabled.contains(targetIme) && !enabled.contains(CASTLA_IME_ID)) {
                enabled.add(targetIme)
                val newEnabledList = enabled.joinToString(":")
                Log.i(TAG, "[FSM] Enabling Castla IME preserving existing: $newEnabledList")
                execCommand("settings put secure enabled_input_methods $newEnabledList")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[FSM] Failed during silent enable prep", e)
        }
    }

    private fun performSaveAndSwitchFlow(context: Context, execCommand: (String) -> String?) {
        try {
            val currentIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val targetIme = "${context.packageName}/com.castla.mirror.input.CastlaImeService"

            // Abort transition if previous IME is null or empty to prevent invalid states
            if (currentIme.isNullOrEmpty()) {
                Log.w(TAG, "[FSM] Aborting switch: previous default input method is null or empty.")
                currentState = ImeState.ERROR
                return
            }

            if (currentIme == targetIme || currentIme == CASTLA_IME_ID) {
                Log.d(TAG, "[FSM] Castla IME is already default. Switch skipped.")
                currentState = ImeState.CASTLA_ACTIVE
                return
            }

            // 1. Save state safely to persistent storage
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_PREVIOUS_IME, currentIme)
                .putBoolean(KEY_RESTORE_PENDING, true)
                .apply()
            Log.i(TAG, "[FSM] Persistent State saved: previousIme='$currentIme'")

            currentState = ImeState.ENABLING_CASTLA
            performSilentEnable(context, execCommand)

            currentState = ImeState.SWITCHING_TO_CASTLA
            Log.i(TAG, "[FSM] Switching default_input_method to Castla IME '$targetIme'")
            execCommand("settings put secure default_input_method $targetIme")
            
            currentState = ImeState.CASTLA_ACTIVE
        } catch (e: Exception) {
            Log.e(TAG, "[FSM] Switch flow crashed", e)
            currentState = ImeState.ERROR
        }
    }

    private fun performRestoreFlow(context: Context, execCommand: (String) -> String?) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val previousIme = prefs.getString(KEY_PREVIOUS_IME, null)
            val restorePending = prefs.getBoolean(KEY_RESTORE_PENDING, false)

            if (restorePending && !previousIme.isNullOrEmpty()) {
                val targetIme = "${context.packageName}/com.castla.mirror.input.CastlaImeService"
                val currentIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""

                if (currentIme == targetIme || currentIme == CASTLA_IME_ID) {
                    Log.i(TAG, "[FSM] Restoring previous default IME '$previousIme' programmatically.")
                    val result = execCommand("settings put secure default_input_method $previousIme")
                    if (result == null) {
                        Log.e(TAG, "[FSM] Restore command failed (returned null). Retaining backup for self-healing.")
                        currentState = ImeState.ERROR
                        return
                    }
                }
            }

            // Clear persistent state safely
            prefs.edit()
                .remove(KEY_PREVIOUS_IME)
                .putBoolean(KEY_RESTORE_PENDING, false)
                .apply()

            currentState = ImeState.IDLE
        } catch (e: Exception) {
            Log.e(TAG, "[FSM] Restore flow crashed", e)
            currentState = ImeState.ERROR
        }
    }

    private fun performSelfHealingFlow(context: Context, execCommand: (String) -> String?) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val restorePending = prefs.getBoolean(KEY_RESTORE_PENDING, false)
            val targetIme = "${context.packageName}/com.castla.mirror.input.CastlaImeService"
            val currentIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""

            if (restorePending || currentIme == targetIme || currentIme == CASTLA_IME_ID) {
                var previousIme = prefs.getString(KEY_PREVIOUS_IME, null)

                // Fallback discovery if cache is lost
                if (previousIme.isNullOrEmpty() || previousIme == targetIme || previousIme == CASTLA_IME_ID) {
                    val enabledStr = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS) ?: ""
                    val enabled = enabledStr.split(":").filter { it.isNotBlank() && it != "null" }
                    previousIme = enabled.firstOrNull { it.isNotEmpty() && !it.contains(context.packageName) }
                }

                if (!previousIme.isNullOrEmpty()) {
                    Log.i(TAG, "[FSM] Self-healing: Restored previous default IME to '$previousIme'")
                    val result = execCommand("settings put secure default_input_method $previousIme")
                    if (result == null) {
                        Log.e(TAG, "[FSM] Self-healing restore command failed (returned null). Retaining backup.")
                        currentState = ImeState.ERROR
                        return
                    }
                }

                prefs.edit()
                    .remove(KEY_PREVIOUS_IME)
                    .putBoolean(KEY_RESTORE_PENDING, false)
                    .apply()
            }

            currentState = ImeState.IDLE
        } catch (e: Exception) {
            Log.e(TAG, "[FSM] Self-healing crashed", e)
            currentState = ImeState.ERROR
        }
    }

    /**
     * Unconditionally restores the user's previous IME, bypassing FSM state guards
     * but strictly serialized via the Mutex lock for race-safety.
     */
    suspend fun restorePreviousIme(context: Context, execCommand: (String) -> String?) = mutex.withLock {
        Log.i(TAG, "[FSM] Unconditional manual restore triggered.")
        currentState = ImeState.RESTORING_PREVIOUS
        performRestoreFlow(context, execCommand)
    }

    /**
     * Checks if Castla IME is currently selected as the active default input method.
     */
    fun isCastlaImeActive(context: Context): Boolean {
        val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val targetIme = "${context.packageName}/com.castla.mirror.input.CastlaImeService"
        return defaultIme == targetIme || defaultIme == CASTLA_IME_ID
    }
}

package com.castla.mirror.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.castla.mirror.shizuku.IPrivilegedService
import com.castla.mirror.input.diagnostics.TextInputLogger
import com.castla.mirror.input.diagnostics.FailureCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Represents the finite input composition states to prevent double commits and overlap
 */
enum class ComposeState {
    IDLE,
    COMPOSING,
    COMMITTING,
    CANCELLING,
    FROZEN // Suppressed during window layout transition
}

sealed class ImeCommand {
    data class CommitText(val compositionId: Long, val text: String) : ImeCommand()
    data class SetComposingText(
        val compositionId: Long,
        val text: String,
        val selectionStart: Int = -1,
        val selectionEnd: Int = -1
    ) : ImeCommand()
    data class DeleteSurroundingText(val beforeLength: Int, val afterLength: Int = 0) : ImeCommand()
    data object FinishComposingText : ImeCommand()
    data object PerformEnter : ImeCommand()
}

class RemoteImeBridge(
    private val privilegedServiceProvider: () -> IPrivilegedService?,
    private val displayIdProvider: () -> Int,
    dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "remote-ime-bridge").apply { isDaemon = true }
    }.asCoroutineDispatcher()
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val logger = TextInputLogger.getInstance()
    private val router = CastlaTextInputRouter.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Strict Finite State Machine
    @Volatile private var currentState: ComposeState = ComposeState.IDLE
    
    // Incrementing Generation ID to ignore stale packet delivery
    @Volatile private var currentCompositionGenerationId: Long = 0L

    // Safety timeout runner to prevent permanent freeze lock during transition
    private val safetyTimeoutRunnable = Runnable {
        if (currentState == ComposeState.FROZEN) {
            logger.logFailure(
                FailureCategory.IME_LIFECYCLE_MISMATCH,
                "FROZEN State timed out after 800ms! Enforcing recovery to IDLE and trigger Nudge."
            )
            forceRecoverFromFreeze()
        }
    }

    init {
        // Wire providers back into central router
        router.configureProviders(privilegedServiceProvider, displayIdProvider)
    }

    fun setComposeState(state: ComposeState) {
        val old = currentState
        currentState = state
        logger.logImeLifecycle("FSM_TRANSITION: $old -> $state")

        if (state == ComposeState.FROZEN) {
            mainHandler.removeCallbacks(safetyTimeoutRunnable)
            mainHandler.postDelayed(safetyTimeoutRunnable, 800L) // 800ms safety timeout
        } else {
            mainHandler.removeCallbacks(safetyTimeoutRunnable)
        }
    }

    private fun forceRecoverFromFreeze() {
        currentState = ComposeState.IDLE
        router.triggerRecoveryFocusNudge()
    }

    fun dispatch(command: ImeCommand) {
        scope.launch {
            if (currentState == ComposeState.FROZEN) {
                logger.logVerify("Input suppressed: Composition state is currently FROZEN")
                return@launch
            }

            val displayId = displayIdProvider()
            val (isValid, connection) = router.validateConnectionForTarget(displayId)
            if (!isValid || connection == null) {
                Log.w("RemoteImeBridge", "Input ignored: InputConnection is currently invalid for display $displayId")
                return@launch
            }

            val rxTimestamp = System.currentTimeMillis()

            try {
                // Auto-wrap composition commands in transaction batch to protect Compose state flows
                connection.beginBatchEdit()

                when (command) {
                    is ImeCommand.SetComposingText -> {
                        if (command.compositionId < currentCompositionGenerationId) {
                            logger.logFailure(
                                FailureCategory.COMPOSING_FAILURE,
                                "Ignored stale composition packet: rxGenId=${command.compositionId} vs activeGenId=$currentCompositionGenerationId"
                            )
                            return@launch
                        }
                        currentCompositionGenerationId = command.compositionId
                        setComposeState(ComposeState.COMPOSING)

                        logger.logRemoteInputRx("composing", command.text, 0, rxTimestamp)
                        connection.setComposingText(command.text, 1)
                        
                        // Force cursor sync if explicit browser indices are supplied to prevent cursor drifts
                        if (command.selectionStart >= 0 && command.selectionEnd >= 0) {
                            connection.setSelection(command.selectionStart, command.selectionEnd)
                        }
                        logger.logComposeAction("setComposingText: '${command.text}', selection=[${command.selectionStart}, ${command.selectionEnd}]")
                    }

                    is ImeCommand.CommitText -> {
                        if (command.compositionId < currentCompositionGenerationId) {
                            logger.logFailure(
                                FailureCategory.COMPOSING_FAILURE,
                                "Ignored stale commit packet: rxGenId=${command.compositionId} vs activeGenId=$currentCompositionGenerationId"
                            )
                            return@launch
                        }
                        currentCompositionGenerationId = command.compositionId
                        setComposeState(ComposeState.COMMITTING)

                        logger.logRemoteInputRx("commit", command.text, 0, rxTimestamp)
                        connection.commitText(command.text, 1)
                        connection.finishComposingText()
                        setComposeState(ComposeState.IDLE)
                        logger.logCommitAction("commitText: '${command.text}'")
                    }

                    is ImeCommand.DeleteSurroundingText -> {
                        logger.logRemoteInputRx("delete", "", command.beforeLength, rxTimestamp)
                        if (command.beforeLength > 0) {
                            connection.deleteSurroundingText(command.beforeLength, 0)
                            logger.logCommitAction("deleteSurroundingText: count=${command.beforeLength}")
                        }
                    }

                    ImeCommand.FinishComposingText -> {
                        setComposeState(ComposeState.CANCELLING)
                        connection.finishComposingText()
                        setComposeState(ComposeState.IDLE)
                        logger.logComposeAction("finishComposingText")
                    }

                    ImeCommand.PerformEnter -> {
                        logger.logRemoteInputRx("enter", "", 0, rxTimestamp)
                        val info = router.getActiveEditorInfo()
                        val actionId = info?.actionId ?: android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
                        
                        Log.i("RemoteImeBridge", "Performing Enter: actionId=$actionId, package=${info?.packageName}")
                        
                        val handled = if (
                            actionId != EditorInfo.IME_ACTION_UNSPECIFIED &&
                            actionId != EditorInfo.IME_ACTION_NONE
                        ) {
                            try {
                                connection.performEditorAction(actionId)
                            } catch (e: Exception) {
                                Log.w("RemoteImeBridge", "performEditorAction failed", e)
                                false
                            }
                        } else {
                            false
                        }

                        if (!handled) {
                            val now = SystemClock.uptimeMillis()
                            val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_EDITOR_ACTION

                            connection.sendKeyEvent(
                                KeyEvent(
                                    now, now,
                                    KeyEvent.ACTION_DOWN,
                                    KeyEvent.KEYCODE_ENTER,
                                    0,
                                    0,
                                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                                    0,
                                    flags
                                )
                            )

                            connection.sendKeyEvent(
                                KeyEvent(
                                    now, now,
                                    KeyEvent.ACTION_UP,
                                    KeyEvent.KEYCODE_ENTER,
                                    0,
                                    0,
                                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                                    0,
                                    flags
                                )
                            )
                        }
                    }
                }
                
                val completeTimestamp = System.currentTimeMillis()
                logger.logVerify("State synced. Text applied, durationMs=${completeTimestamp - rxTimestamp}")
            } catch (e: Exception) {
                Log.e("RemoteImeBridge", "Failed to dispatch composing action to target connection", e)
            } finally {
                try {
                    connection.endBatchEdit()
                } catch (_: Exception) {}
            }
        }
    }
}

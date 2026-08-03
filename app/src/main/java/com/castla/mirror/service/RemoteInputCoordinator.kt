package com.castla.mirror.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.castla.mirror.diagnostics.FileLogger
import com.castla.mirror.input.CastlaTextInputRouter
import com.castla.mirror.input.ImeCommand
import com.castla.mirror.input.ImeEvent
import com.castla.mirror.input.ImeFocusState
import com.castla.mirror.input.ImeSwitchManager
import com.castla.mirror.input.RemoteImeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class RemoteInputCoordinator(
    private val host: MirrorForegroundService,
    private val proxyEnabled: () -> Boolean,
) {
    companion object { private const val TAG = "MirrorForegroundService" }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val compositionDispatcher = kotlinx.coroutines.newSingleThreadContext("composition")
    private var watchdogJob: Job? = null
    private var bridge: RemoteImeBridge? = null
    var lastTouchPane = "primary"

    private val timeoutRunnable = Runnable {
        if (!proxyEnabled()) {
            FileLogger.i("IME_ROUTING", "timeout_ignored reason=system_ime_mode")
            return@Runnable
        }
        sendImeEvent(ImeEvent.Timeout, "FSM Timeout event failed")
    }

    fun initialize() {
        bridge = RemoteImeBridge(
            privilegedServiceProvider = { host.currentPrivilegedService() },
            displayIdProvider = ::activeInputDisplayId,
        )
    }

    fun resetTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
        if (!proxyEnabled()) {
            FileLogger.i("IME_ROUTING", "resetImeTimeoutTimer skipped reason=system_ime_mode")
            return
        }
        ensureActive()
        mainHandler.postDelayed(timeoutRunnable, 30_000L)
    }

    fun ensureActive() {
        if (!proxyEnabled()) {
            FileLogger.i("IME_ROUTING", "ensureCastlaImeActiveDynamically skipped reason=system_ime_mode")
            return
        }
        sendImeEvent(ImeEvent.RemoteTextFocus, "FSM remote text focus event failed")
    }

    fun restoreKeyboard() {
        if (!proxyEnabled()) {
            FileLogger.i("IME_ROUTING", "restoreUserKeyboardSilently skipped reason=system_ime_mode")
            return
        }
        sendImeEvent(ImeEvent.RemoteTextBlur, "FSM remote text blur event failed")
    }

    fun onFocusLost() {
        mainHandler.removeCallbacks(timeoutRunnable)
        if (!proxyEnabled()) {
            FileLogger.i("IME_ROUTING", "onRemoteFocusLost ignored reason=system_ime_mode")
            return
        }
        restoreKeyboard()
    }

    fun handleFocusHint(packageName: String?, inputType: Int, imeOptions: Int, privateImeOptions: String?) {
        if (!proxyEnabled()) {
            FileLogger.i("IME_ROUTING", "remoteFocusHint ignored reason=system_ime_mode pkg=${packageName ?: ""} inputType=$inputType imeOptions=$imeOptions")
            return
        }
        watchdogJob?.cancel()
        watchdogJob = null
        val router = CastlaTextInputRouter.getInstance()
        router.updateImeFocusState(
            ImeFocusState(
                router.getCachedImeFocusState().sessionId,
                packageName,
                inputType,
                imeOptions,
                privateImeOptions,
                true,
                System.currentTimeMillis(),
            )
        )
        resetTimeout()
    }

    fun handleBlurHint() {
        if (!proxyEnabled()) {
            FileLogger.i("IME_ROUTING", "remoteBlurHint ignored reason=system_ime_mode")
            return
        }
        watchdogJob?.cancel()
        watchdogJob = host.serviceScope.launch(Dispatchers.Main) {
            delay(500L)
            val router = CastlaTextInputRouter.getInstance()
            val state = router.getCachedImeFocusState()
            if (state.isFocused) router.updateImeFocusState(state.copy(isFocused = false, timestamp = System.currentTimeMillis()))
            delay(2500L)
            if (!router.getCachedImeFocusState().isFocused) onFocusLost()
        }
    }

    fun injectText(text: String) {
        resetTimeout()
        val displayId = activeInputDisplayId()
        val router = CastlaTextInputRouter.getInstance()
        if (router.validateConnectionForTarget(displayId).first) {
            router.setRemoteTextDirty(true)
            bridge?.dispatch(ImeCommand.CommitText(System.currentTimeMillis(), text))
        } else launchComposition { host.currentPrivilegedService()?.injectText(text, displayId) }
    }

    fun injectComposition(backspaces: Int, text: String) {
        resetTimeout()
        val displayId = activeInputDisplayId()
        val router = CastlaTextInputRouter.getInstance()
        if (router.validateConnectionForTarget(displayId).first) {
            router.setRemoteTextDirty(true)
            if (text.isEmpty() && backspaces == 0) bridge?.dispatch(ImeCommand.FinishComposingText)
            else bridge?.dispatch(ImeCommand.SetComposingText(System.currentTimeMillis(), text, -1, -1))
        } else launchComposition { host.currentPrivilegedService()?.injectComposingText(backspaces, text, displayId) }
    }

    fun injectKeyEvent(keyCode: Int) {
        resetTimeout()
        val displayId = activeInputDisplayId()
        val router = CastlaTextInputRouter.getInstance()
        val valid = router.validateConnectionForTarget(displayId).first
        when {
            valid && keyCode == 67 -> {
                router.setRemoteTextDirty(true)
                bridge?.dispatch(ImeCommand.DeleteSurroundingText(1, 0))
            }
            valid && keyCode == 66 -> {
                router.setRemoteTextDirty(true)
                bridge?.dispatch(ImeCommand.PerformEnter)
            }
            else -> launchComposition {
                host.currentPrivilegedService()?.execCommand(
                    if (displayId > 0) "input -d $displayId keyevent $keyCode" else "input keyevent $keyCode"
                )
            }
        }
    }

    fun activeInputDisplayId(): Int = host.pipelines[lastTouchPane]?.displayId
        ?: host.pipelines["primary"]?.displayId
        ?: -1

    fun cleanup() {
        mainHandler.removeCallbacks(timeoutRunnable)
        watchdogJob?.cancel()
        watchdogJob = null
        compositionDispatcher.close()
    }

    private fun launchComposition(block: suspend () -> Unit) {
        host.serviceScope.launch(compositionDispatcher) { try { block() } catch (_: Exception) {} }
    }

    private fun sendImeEvent(event: ImeEvent, errorMessage: String) {
        val service = host.currentPrivilegedService() ?: return
        host.serviceScope.launch {
            try { ImeSwitchManager.sendEvent(host, event) { service.execCommand(it) } }
            catch (e: Exception) { Log.e(TAG, errorMessage, e) }
        }
    }
}

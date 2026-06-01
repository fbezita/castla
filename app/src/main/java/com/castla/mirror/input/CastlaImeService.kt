package com.castla.mirror.input

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import com.castla.mirror.input.diagnostics.TextInputLogger
import com.castla.mirror.R

class CastlaImeService : InputMethodService() {

    private val logger = TextInputLogger.getInstance()
    private val textInputRouter = CastlaTextInputRouter.getInstance()

    private val imeSessionCounter = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var currentSessionId: Long = 0L

    @Volatile private var lastBroadcastFocused: Boolean? = null
    @Volatile private var lastBroadcastPackage: String? = null
    @Volatile private var lastBroadcastSessionId: Long? = null
    @Volatile private var lastBroadcastTime: Long = 0L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingBlurRunnable: Runnable? = null

    private val sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ime-active-notifier").apply { isDaemon = true }
    }

    companion object {
        @Volatile var instance: CastlaImeService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        logger.logImeLifecycle("onCreate")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): android.view.View {
        // Inflate transparent 1dp minimal IME layout to avoid Extraction UI breakage
        return layoutInflater.inflate(R.layout.castla_ime_layout, null)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val sessionId = imeSessionCounter.incrementAndGet()
        currentSessionId = sessionId
        logger.logImeLifecycle("onStartInput: restarting=$restarting, sessionId=$sessionId", attribute)
        refreshActiveConnection(sessionId)
        notifyImeActive(true, attribute?.packageName)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val sessionId = imeSessionCounter.incrementAndGet()
        currentSessionId = sessionId
        logger.logImeLifecycle("onStartInputView: restarting=$restarting, sessionId=$sessionId", info)
        refreshActiveConnection(sessionId)
        notifyImeActive(true, info?.packageName)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        logger.logImeLifecycle("onUpdateSelection: newSel=[$newSelStart, $newSelEnd]")
        refreshActiveConnection(currentSessionId)
    }

    override fun onFinishInput() {
        // ### 수정 시작 ###
        // Diagnostic log to track native focus release
        android.util.Log.i("CastlaImeService", "[IME] onFinishInput started: sessionId=$currentSessionId")
        // ### 수정 끝 ###
        val sessionIdAtFinish = currentSessionId
        logger.logImeLifecycle("onFinishInput entered: sessionId=$sessionIdAtFinish")
        
        try {
            currentInputConnection?.finishComposingText()
        } catch (e: Exception) {
            logger.logImeLifecycle("Failed to finishComposingText during onFinishInput: ${e.message}")
        }

        super.onFinishInput()

        if (currentSessionId == sessionIdAtFinish) {
            logger.logImeLifecycle("onFinishInput - Session matches. Invalidating connection for sessionId=$sessionIdAtFinish")
            textInputRouter.invalidateInputConnection()
            
            val currentState = textInputRouter.getCachedImeFocusState()
            if (currentState.sessionId == sessionIdAtFinish) {
                textInputRouter.updateImeFocusState(currentState.copy(isFocused = false, timestamp = System.currentTimeMillis()))
            }
            
            android.util.Log.i("CastlaImeService", "[IME] onFinishInput -> notifyImeActive(false) sessionId=$sessionIdAtFinish")
            notifyImeActive(false)
            com.castla.mirror.service.MirrorForegroundService.instance?.onRemoteFocusLost()
        } else {
            logger.logImeLifecycle("onFinishInput - Stale call ignored. Current session $currentSessionId vs finish session $sessionIdAtFinish")
        }
    }

    private fun refreshActiveConnection(sessionId: Long) {
        // Fetch current live proxy layers from InputMethodService directly to avoid stale wrappers
        val conn = currentInputConnection
        val info = currentInputEditorInfo
        
        if (conn != null && info != null) {
            textInputRouter.refreshCurrentConnection(conn, info)
            
            val imeState = ImeFocusState(
                sessionId = sessionId,
                packageName = info.packageName,
                inputType = info.inputType,
                imeOptions = info.imeOptions,
                privateImeOptions = info.privateImeOptions,
                isFocused = true,
                timestamp = System.currentTimeMillis()
            )
            textInputRouter.updateImeFocusState(imeState)
        } else {
            logger.logImeLifecycle("refreshActiveConnection - Connection is currently unavailable")
        }
    }

    private fun notifyImeActive(focused: Boolean, targetPackage: String? = null, sessionId: Long = currentSessionId) {
        if (targetPackage == packageName) {
            logger.logImeLifecycle("skip androidFocusChanged for self package: $targetPackage")
            return
        }

        handler.post {
            if (focused) {
                // Cancel pending blur if focused=true arrives
                pendingBlurRunnable?.let {
                    handler.removeCallbacks(it)
                    pendingBlurRunnable = null
                    logger.logImeLifecycle("NOTIFY_IME_ACTIVE: Pending blur canceled due to rapid focused=true")
                }
                
                // Deduplicate focused=true within 100ms for same package/session
                val now = System.currentTimeMillis()
                if (lastBroadcastFocused == true &&
                    lastBroadcastPackage == targetPackage &&
                    lastBroadcastSessionId == sessionId &&
                    (now - lastBroadcastTime) < 100L
                ) {
                    logger.logImeLifecycle("NOTIFY_IME_ACTIVE: focused=true duplicate ignored (session=$sessionId, pkg=$targetPackage)")
                    return@post
                }
                
                executeBroadcast(true, targetPackage, sessionId)
            } else {
                // Buffer focused=false to allow rapid focused=true to intercept and deduplicate
                pendingBlurRunnable?.let { handler.removeCallbacks(it) }
                val runnable = Runnable {
                    executeBroadcast(false, targetPackage, sessionId)
                    pendingBlurRunnable = null
                }
                pendingBlurRunnable = runnable
                handler.postDelayed(runnable, 80L) // 80ms transition window
            }
        }
    }

    private fun executeBroadcast(focused: Boolean, targetPackage: String?, sessionId: Long) {
        val server = com.castla.mirror.service.MirrorForegroundService.instance?.getMirrorServer()
        if (server != null) {
            val json = org.json.JSONObject().apply {
                put("type", "ime")
                put("op", "androidFocusChanged")
                put("focused", focused)
                put("sessionId", sessionId)
                if (targetPackage != null) {
                    put("packageName", targetPackage)
                }
            }
            lastBroadcastFocused = focused
            lastBroadcastPackage = targetPackage
            lastBroadcastSessionId = sessionId
            lastBroadcastTime = System.currentTimeMillis()

            sendExecutor.execute {
                try {
                    server.broadcastControlMessage(json.toString())
                    logger.logImeLifecycle("NOTIFY_IME_ACTIVE (androidFocusChanged): focused=$focused, pkg=$targetPackage, session=$sessionId")
                } catch (e: Exception) {
                    android.util.Log.e("CastlaImeService", "Failed to broadcast androidFocusChanged", e)
                }
            }
        }
    }
}

package com.castla.mirror.input

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import com.castla.mirror.input.diagnostics.TextInputLogger
import com.castla.mirror.R

class CastlaImeService : InputMethodService() {

    private val logger = TextInputLogger.getInstance()
    private val textInputRouter = CastlaTextInputRouter.getInstance()

    private val sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ime-active-notifier").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        logger.logImeLifecycle("onCreate")
    }

    override fun onCreateInputView(): android.view.View {
        // Inflate transparent 1dp minimal IME layout to avoid Extraction UI breakage
        return layoutInflater.inflate(R.layout.castla_ime_layout, null)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        logger.logImeLifecycle("onStartInput: restarting=$restarting", attribute)
        refreshActiveConnection()
        notifyImeActive(true)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        logger.logImeLifecycle("onStartInputView: restarting=$restarting", info)
        refreshActiveConnection()
        notifyImeActive(true)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        logger.logImeLifecycle("onUpdateSelection: newSel=[$newSelStart, $newSelEnd]")
        refreshActiveConnection()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        logger.logImeLifecycle("onFinishInput")
        textInputRouter.invalidateInputConnection()
        notifyImeActive(false)
        com.castla.mirror.service.MirrorForegroundService.instance?.onRemoteFocusLost()
    }

    private fun refreshActiveConnection() {
        // Fetch current live proxy layers from InputMethodService directly to avoid stale wrappers
        val conn = currentInputConnection
        val info = currentInputEditorInfo
        
        if (conn != null && info != null) {
            textInputRouter.refreshCurrentConnection(conn, info)
        } else {
            logger.logImeLifecycle("refreshActiveConnection - Connection is currently unavailable")
        }
    }

    private fun notifyImeActive(focused: Boolean) {
        val server = com.castla.mirror.service.MirrorForegroundService.instance?.getMirrorServer()
        if (server != null) {
            val json = org.json.JSONObject().apply {
                put("type", "ime_active")
                put("focused", focused)
            }
            sendExecutor.execute {
                try {
                    server.broadcastControlMessage(json.toString())
                    logger.logImeLifecycle("NOTIFY_IME_ACTIVE: focused=$focused")
                } catch (e: Exception) {
                    android.util.Log.e("CastlaImeService", "Failed to broadcast ime_active", e)
                }
            }
        }
    }
}

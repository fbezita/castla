package com.castla.mirror.input

import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputBinding
import com.castla.mirror.BuildConfig
import com.castla.mirror.diagnostics.FileLogger
import com.castla.mirror.input.diagnostics.TextInputLogger
import com.castla.mirror.R

class CastlaImeService : InputMethodService() {
    private val vdImeLogPrefix = "[VDIME]"

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
        private const val TAG = "CastlaImeService"
        @Volatile var instance: CastlaImeService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        logImeServiceSelectionState("onCreate")
        val buildLine =
            "marker=ime_guard_v4 appId=${BuildConfig.APPLICATION_ID} versionName=${BuildConfig.VERSION_NAME} " +
                "versionCode=${BuildConfig.VERSION_CODE} buildTimestamp=${BuildConfig.BUILD_TIMESTAMP} debug=${BuildConfig.DEBUG}"
        Log.i(TAG, "[BUILD_MARKER] $buildLine")
        FileLogger.i("BUILD_MARKER", "CastlaImeService $buildLine")
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
        logImeGuardEntry(
            event = "onStartInput",
            editorInfo = attribute,
            restarting = restarting,
            sessionId = sessionId
        )
        logger.logImeLifecycle("onStartInput: restarting=$restarting, sessionId=$sessionId", attribute)
        refreshActiveConnection(sessionId)
        notifyImeActive(
            focused = true,
            targetPackage = attribute?.packageName,
            sessionId = sessionId,
            editorInfoHint = attribute,
            source = "onStartInput",
            restarting = restarting
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val sessionId = imeSessionCounter.incrementAndGet()
        currentSessionId = sessionId
        logImeGuardEntry(
            event = "onStartInputView",
            editorInfo = info,
            restarting = restarting,
            sessionId = sessionId
        )
        logger.logImeLifecycle("onStartInputView: restarting=$restarting, sessionId=$sessionId", info)
        refreshActiveConnection(sessionId)
        notifyImeActive(
            focused = true,
            targetPackage = info?.packageName,
            sessionId = sessionId,
            editorInfoHint = info,
            source = "onStartInputView",
            restarting = restarting
        )
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        logImeGuardEntry(
            event = "onUpdateSelection",
            editorInfo = currentInputEditorInfo,
            restarting = false,
            sessionId = currentSessionId,
            extra =
                "oldSel=[$oldSelStart,$oldSelEnd] newSel=[$newSelStart,$newSelEnd] " +
                    "candidates=[$candidatesStart,$candidatesEnd]"
        )
        logger.logImeLifecycle("onUpdateSelection: newSel=[$newSelStart, $newSelEnd]")
        refreshActiveConnection(currentSessionId)
    }

    override fun onBindInput() {
        super.onBindInput()
        logImeGuardEntry(
            event = "onBindInput",
            editorInfo = currentInputEditorInfo,
            restarting = false,
            sessionId = currentSessionId,
            extra = "bindingPresent=${currentInputBinding != null}"
        )
    }

    override fun onStartCandidatesView(info: EditorInfo?, restarting: Boolean) {
        super.onStartCandidatesView(info, restarting)
        logImeGuardEntry(
            event = "onStartCandidatesView",
            editorInfo = info,
            restarting = restarting,
            sessionId = currentSessionId
        )
    }

    override fun onWindowShown() {
        super.onWindowShown()
        logImeGuardEntry(
            event = "onWindowShown",
            editorInfo = currentInputEditorInfo,
            restarting = false,
            sessionId = currentSessionId
        )
    }

    override fun onFinishInput() {
        // Diagnostic log to track native focus release
        android.util.Log.i("CastlaImeService", "[IME] onFinishInput started: sessionId=$currentSessionId")
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

    private fun isRealEditable(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        val inputType = editorInfo.inputType
        if (inputType == 0) return false

        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        return inputClass == InputType.TYPE_CLASS_TEXT ||
            inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE ||
            inputClass == InputType.TYPE_CLASS_DATETIME
    }

    private fun logImeGuardEntry(
        event: String,
        editorInfo: EditorInfo?,
        restarting: Boolean,
        sessionId: Long,
        extra: String = ""
    ) {
        val inputType = editorInfo?.inputType ?: 0
        val packageName = editorInfo?.packageName ?: ""
        val connectionPresent = currentInputConnection != null
        val connectionTextCapable = connectionSeemsTextCapable()
        val service = com.castla.mirror.service.MirrorForegroundService.instance
        val recentViewportTapAgeMs = service?.recentViewportFocusAcquisitionAgeMs() ?: -1L
        val recentViewportTapWindow = service?.isRecentViewportFocusAcquisitionWindow() == true
        val line =
            "event=$event pkg=$packageName inputType=$inputType restarting=$restarting session=$sessionId " +
                "connectionPresent=$connectionPresent connectionTextCapable=$connectionTextCapable " +
                "recentViewportTapWindow=$recentViewportTapWindow recentViewportTapAgeMs=$recentViewportTapAgeMs " +
                "currentEditorInputType=${currentInputEditorInfo?.inputType ?: 0}" +
                if (extra.isNotBlank()) " $extra" else ""
        Log.i(TAG, "[IME_GUARD_ENTRY] $line")
        FileLogger.i("IME_GUARD_ENTRY", line)
    }

    private fun connectionSeemsTextCapable(): Boolean {
        val connection = currentInputConnection ?: return false
        return try {
            val extracted = connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            extracted != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun logImeServiceSelectionState(event: String) {
        val serviceClass = this::class.java.name
        val defaultIme = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (_: Throwable) {
            null
        }
        val enabledImes = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
        } catch (_: Throwable) {
            null
        }
        val myImeId = "${packageName}/com.castla.mirror.input.CastlaImeService"
        val line =
            "event=$event serviceClass=$serviceClass myImeId=$myImeId defaultInputMethod=${defaultIme ?: ""} " +
                "enabledInputMethods=${enabledImes ?: ""}"
        Log.i(TAG, "$vdImeLogPrefix [IME_SERVICE_STATE] $line")
        FileLogger.i("IME_SERVICE_STATE", "$vdImeLogPrefix $line")
    }

    private fun notifyImeActive(
        focused: Boolean,
        targetPackage: String? = null,
        sessionId: Long = currentSessionId,
        editorInfoHint: EditorInfo? = null,
        source: String = "unknown",
        restarting: Boolean = false
    ) {
        val editorInfo = editorInfoHint ?: currentInputEditorInfo
        val effectiveTargetPackage = targetPackage ?: editorInfo?.packageName
        val inputType = editorInfo?.inputType ?: 0
        val connectionPresent = currentInputConnection != null
        val service = com.castla.mirror.service.MirrorForegroundService.instance
        val recentViewportTapAgeMs = service?.recentViewportFocusAcquisitionAgeMs()
        val recentViewportTapWindow = service?.isRecentViewportFocusAcquisitionWindow() == true

        val realEditable = isRealEditable(editorInfo)
        val editableConfirmed =
            realEditable || (
                focused &&
                    inputType == 0 &&
                    source == "onStartInputView" &&
                    !restarting &&
                    connectionPresent &&
                    recentViewportTapWindow
            )
        val allowBroadcast = !focused || editableConfirmed
        val reason = when {
            effectiveTargetPackage == packageName -> "suppress_self_package"
            !focused -> "blur_event"
            inputType != 0 -> "allow_editable_input_type"
            source == "onStartInput" -> "suppress_onStartInput_inputType0"
            source != "onStartInputView" -> "suppress_unknown_source_inputType0"
            restarting -> "suppress_onStartInputView_restarting"
            !connectionPresent -> "suppress_onStartInputView_no_connection"
            !recentViewportTapWindow -> "suppress_onStartInputView_no_recent_viewport_tap"
            else -> "allow_onStartInputView_recent_viewport_tap"
        }

        if (focused) {
            val guardLine =
                "event=$source pkg=${effectiveTargetPackage ?: ""} inputType=$inputType restarting=$restarting " +
                    "connectionPresent=$connectionPresent editableConfirmed=$editableConfirmed allowBroadcast=$allowBroadcast " +
                    "reason=$reason recentViewportTapAgeMs=${recentViewportTapAgeMs ?: -1L}"
            Log.i(TAG, "[IME_GUARD] $guardLine")
            FileLogger.i("IME_GUARD", guardLine)
        }

        if (effectiveTargetPackage == packageName) {
            logger.logImeLifecycle("skip androidFocusChanged for self package: $targetPackage")
            return
        }

        if (focused && !allowBroadcast) {
            Log.i(
                TAG,
                "[IME_DEBUG] skip focused=true broadcast inputType=$inputType " +
                    "hintInputType=${editorInfoHint?.inputType ?: 0} currentInputType=${currentInputEditorInfo?.inputType ?: 0} " +
                    "source=$source restarting=$restarting connectionPresent=$connectionPresent reason=$reason " +
                    "recentViewportTapAgeMs=${recentViewportTapAgeMs ?: -1L}"
            )
            FileLogger.i(
                "IME_DEBUG",
                "skip focused=true broadcast inputType=$inputType " +
                    "hintInputType=${editorInfoHint?.inputType ?: 0} currentInputType=${currentInputEditorInfo?.inputType ?: 0} " +
                    "pkg=$effectiveTargetPackage session=$sessionId source=$source restarting=$restarting " +
                    "connectionPresent=$connectionPresent reason=$reason recentViewportTapAgeMs=${recentViewportTapAgeMs ?: -1L}"
            )
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
                    lastBroadcastPackage == effectiveTargetPackage &&
                    lastBroadcastSessionId == sessionId &&
                    (now - lastBroadcastTime) < 100L
                ) {
                    logger.logImeLifecycle("NOTIFY_IME_ACTIVE: focused=true duplicate ignored (session=$sessionId, pkg=$effectiveTargetPackage)")
                    return@post
                }
                
                executeBroadcast(
                    focused = true,
                    targetPackage = effectiveTargetPackage,
                    sessionId = sessionId,
                    editorInfoHint = editorInfo,
                    editableConfirmed = editableConfirmed,
                    source = source
                )
            } else {
                // Buffer focused=false to allow rapid focused=true to intercept and deduplicate
                pendingBlurRunnable?.let { handler.removeCallbacks(it) }
                val runnable = Runnable {
                    executeBroadcast(
                        focused = false,
                        targetPackage = effectiveTargetPackage,
                        sessionId = sessionId,
                        editorInfoHint = editorInfo,
                        editableConfirmed = false,
                        source = source
                    )
                    pendingBlurRunnable = null
                }
                pendingBlurRunnable = runnable
                handler.postDelayed(runnable, 80L) // 80ms transition window
            }
        }
    }

    private fun executeBroadcast(
        focused: Boolean,
        targetPackage: String?,
        sessionId: Long,
        editorInfoHint: EditorInfo? = null,
        editableConfirmed: Boolean = false,
        source: String = "unknown"
    ) {
        val server = com.castla.mirror.service.MirrorForegroundService.instance?.getMirrorServer()
        if (server != null) {
            val editorInfo = editorInfoHint ?: currentInputEditorInfo
            val connectionPresent = currentInputConnection != null
            val cachedState = textInputRouter.getCachedImeFocusState()
            android.util.Log.i(
                "CastlaImeService",
                "[IME_DEBUG] androidFocusChanged focused=$focused session=$sessionId targetPkg=$targetPackage " +
                    "editorPkg=${editorInfo?.packageName} connectionPresent=$connectionPresent " +
                    "cachedFocused=${cachedState.isFocused} cachedPkg=${cachedState.packageName} cachedSession=${cachedState.sessionId} " +
                    "editableConfirmed=$editableConfirmed source=$source"
            )
            if (focused) {
                FileLogger.i(
                    "IME_DEBUG",
                    "androidFocusChanged focused=true pkg=$targetPackage inputType=${editorInfo?.inputType ?: 0} session=$sessionId " +
                        "editorPkg=${editorInfo?.packageName} connectionPresent=$connectionPresent cachedSession=${cachedState.sessionId} " +
                        "editableConfirmed=$editableConfirmed source=$source"
                )
            }
            val json = org.json.JSONObject().apply {
                put("type", "ime")
                put("op", "androidFocusChanged")
                put("focused", focused)
                put("sessionId", sessionId)
                put("inputType", editorInfo?.inputType ?: 0)
                put("editableConfirmed", editableConfirmed)
                put("focusSource", source)
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

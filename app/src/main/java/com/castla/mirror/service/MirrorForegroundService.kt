package com.castla.mirror.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.castla.mirror.R
import com.castla.mirror.widget.MirrorWidgetProvider
import com.castla.mirror.capture.AudioCapture
import com.castla.mirror.capture.JpegEncoder
import com.castla.mirror.capture.VideoEncoder
import com.castla.mirror.capture.VirtualDisplayController
import com.castla.mirror.compositor.DisplayTier
import com.castla.mirror.input.TouchInjector
import com.castla.mirror.input.RemoteImeBridge
import com.castla.mirror.input.ImeCommand
import com.castla.mirror.input.CastlaTextInputRouter
import com.castla.mirror.server.MirrorServer
import com.castla.mirror.server.TouchEvent
import com.castla.mirror.shizuku.BinderConnectionTracker
import com.castla.mirror.shizuku.IPrivilegedService
import com.castla.mirror.shizuku.ShizukuSetup
import com.castla.mirror.ott.BrowserResolver
import com.castla.mirror.ott.OttCatalog
import com.castla.mirror.utils.LaunchMode
import com.castla.mirror.policy.AutoScaleDecision
import com.castla.mirror.policy.AutoScaleInput
import com.castla.mirror.policy.AutoScalePolicy
import com.castla.mirror.policy.CodecModeTransition
import com.castla.mirror.policy.DisconnectPolicy
import com.castla.mirror.policy.ScreenOffAction
import com.castla.mirror.policy.ScreenOffPolicy
import com.castla.mirror.policy.ScreenOffState
import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.FileLogger
import com.castla.mirror.diagnostics.MirrorDiagnostics
import com.castla.mirror.diagnostics.TerminalReason
import com.castla.mirror.utils.AppLaunchRequest
import com.castla.mirror.utils.StreamMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

@OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class
)
class MirrorForegroundService : Service() {
    private val vdDispatcher = kotlinx.coroutines.newSingleThreadContext("vd-operations")

    private suspend fun <T> runBinderSafe(timeoutMs: Long = 3000L, block: suspend () -> T): T? {
        return withTimeoutOrNull(timeoutMs) { block() }
    }

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "castla_mirror"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.castla.mirror.ACTION_STOP"
        const val ACTION_RESTORE_IME = "com.castla.mirror.ACTION_RESTORE_IME"
        const val EXTRA_MAX_RESOLUTION = "max_resolution"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUDIO = "audio_enabled"
        const val EXTRA_MIRRORING_MODE = "mirroring_mode"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_RELAY_PUBLISH_IP = "relay_publish_ip"

        private val _serviceRunningFlow = MutableStateFlow(false)
        val serviceRunningFlow: StateFlow<Boolean> = _serviceRunningFlow

        private val _cleanupInProgressFlow = MutableStateFlow(false)
        val cleanupInProgressFlow: StateFlow<Boolean> = _cleanupInProgressFlow

        private val _panelOffStateFlow = MutableStateFlow(ScreenOffState.ACTIVE)
        val panelOffStateFlow: StateFlow<ScreenOffState> = _panelOffStateFlow

        @Volatile private var isAppLaunchingContext = false

        var isServiceRunning: Boolean
            get() = _serviceRunningFlow.value
            set(value) { _serviceRunningFlow.value = value }

        var isCleanupInProgress: Boolean
            get() = _cleanupInProgressFlow.value
            set(value) { _cleanupInProgressFlow.value = value }

        @JvmStatic
        var instance: MirrorForegroundService? = null
            private set

        private const val VD_KEEP_ALIVE_INTERVAL_MS = 1_000L
    }

    inner class LocalBinder : Binder() {
        val service: MirrorForegroundService get() = this@MirrorForegroundService
    }

    private val binder = LocalBinder()
    // ### 수정 시작 ###
    @Volatile private var backFallbackLastTriggeredTime = 0L
    // ### 수정 끝 ###
    private var mirrorServer: MirrorServer? = null
    fun getMirrorServer(): MirrorServer? = mirrorServer

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val imeTimeoutRunnable = Runnable {
        Log.i(TAG, "IME input inactivity timeout (30 seconds) reached. Triggering FSM Timeout.")
        val svc = shizukuSetup?.privilegedService
        if (svc != null) {
            serviceScope.launch {
                try {
                    com.castla.mirror.input.ImeSwitchManager.sendEvent(
                        this@MirrorForegroundService,
                        com.castla.mirror.input.ImeEvent.Timeout
                    ) { cmd ->
                        svc.execCommand(cmd)
                    }
                } catch (e: java.lang.Exception) {
                    Log.e(TAG, "FSM Timeout event failed", e)
                }
            }
        }
    }

    fun resetImeTimeoutTimer() {
        mainHandler.removeCallbacks(imeTimeoutRunnable)
        // Switch to Castla IME dynamically if not already active when remote input occurs
        ensureCastlaImeActiveDynamically()
        mainHandler.postDelayed(imeTimeoutRunnable, 30000L) // 30 seconds
    }

    fun ensureCastlaImeActiveDynamically() {
        val svc = shizukuSetup?.privilegedService
        if (svc != null) {
            serviceScope.launch {
                try {
                    com.castla.mirror.input.ImeSwitchManager.sendEvent(
                        this@MirrorForegroundService,
                        com.castla.mirror.input.ImeEvent.RemoteTextFocus
                    ) { cmd ->
                        svc.execCommand(cmd)
                    }
                } catch (e: java.lang.Exception) {
                    Log.e(TAG, "FSM remote text focus event failed", e)
                }
            }
        }
    }

    fun restoreUserKeyboardSilently() {
        val svc = shizukuSetup?.privilegedService
        if (svc != null) {
            serviceScope.launch {
                try {
                    com.castla.mirror.input.ImeSwitchManager.sendEvent(
                        this@MirrorForegroundService,
                        com.castla.mirror.input.ImeEvent.RemoteTextBlur
                    ) { cmd ->
                        svc.execCommand(cmd)
                    }
                } catch (e: java.lang.Exception) {
                    Log.e(TAG, "FSM remote text blur event failed", e)
                }
            }
        }
    }

    fun onRemoteFocusLost() {
        Log.i(TAG, "onRemoteFocusLost -> restoring user keyboard silently")
        mainHandler.removeCallbacks(imeTimeoutRunnable)
        restoreUserKeyboardSilently()
    }

    fun handleRemoteFocusHint(packageName: String?, inputType: Int, imeOptions: Int, privateImeOptions: String?) {
        Log.i(TAG, "handleRemoteFocusHint: pkg=$packageName, inputType=$inputType, options=$imeOptions")
        
        remoteImeWatchdogJob?.cancel()
        remoteImeWatchdogJob = null

        val router = com.castla.mirror.input.CastlaTextInputRouter.getInstance()
        val currentSessionId = router.getCachedImeFocusState().sessionId
        val imeState = com.castla.mirror.input.ImeFocusState(
            sessionId = currentSessionId,
            packageName = packageName,
            inputType = inputType,
            imeOptions = imeOptions,
            privateImeOptions = privateImeOptions,
            isFocused = true,
            timestamp = System.currentTimeMillis()
        )
        router.updateImeFocusState(imeState)

        resetImeTimeoutTimer()
    }

    fun handleRemoteBlurHint() {
        Log.i(TAG, "handleRemoteBlurHint received. Scheduling 500ms blur debounce & 3s watchdog.")
        
        remoteImeWatchdogJob?.cancel()
        remoteImeWatchdogJob = serviceScope.launch(Dispatchers.Main) {
            // 500ms hold interval
            kotlinx.coroutines.delay(500L)
            
            val router = com.castla.mirror.input.CastlaTextInputRouter.getInstance()
            val state = router.getCachedImeFocusState()
            if (state.isFocused) {
                Log.i(TAG, "Watchdog: 500ms blur delay expired. Setting IME registry focused = false.")
                router.updateImeFocusState(state.copy(isFocused = false, timestamp = System.currentTimeMillis()))
            }
            
            // Wait total 3 seconds (2500ms more)
            kotlinx.coroutines.delay(2500L)
            
            val activeState = router.getCachedImeFocusState()
            if (!activeState.isFocused) {
                Log.w(TAG, "Watchdog: 3s of remote blur inactivity. Forcing restore phone keyboard.")
                onRemoteFocusLost()
            }
        }
    }
    
    // Core map collection for symmetric pipeline extension
    val pipelines = java.util.concurrent.ConcurrentHashMap<String, MirroringPipeline>()

    private lateinit var powerLockManager: PowerLockManager
    private lateinit var thermalThrottleManager: ThermalThrottleManager
    private lateinit var adaptiveBitrateManager: AdaptiveBitrateManager
    lateinit var contentAwareQualityEngine: ContentAwareQualityEngine

    val thermalStatus: kotlinx.coroutines.flow.StateFlow<Int>
        get() = thermalThrottleManager.thermalStatus

    private var thermalFpsOverride: Int?
        get() = thermalThrottleManager.thermalFpsOverride
        set(value) { thermalThrottleManager.thermalFpsOverride = value }
    private var thermalTransformationOverride: Int? = null
    private var thermalMaxHeight: Int?
        get() = thermalThrottleManager.thermalMaxHeight
        set(value) { thermalThrottleManager.thermalMaxHeight = value }

    private var lastCongestionTimeMs: Long
        get() = adaptiveBitrateManager.lastCongestionTimeMs
        set(value) { adaptiveBitrateManager.lastCongestionTimeMs = value }
    private var lastQualityDroppedFrames: Int
        get() = adaptiveBitrateManager.lastQualityDroppedFrames
        set(value) { adaptiveBitrateManager.lastQualityDroppedFrames = value }
    private var lastQualityAvgDelayMs: Double
        get() = adaptiveBitrateManager.lastQualityAvgDelayMs
        set(value) { adaptiveBitrateManager.lastQualityAvgDelayMs = value }
    private var lastQualityBacklogDrops: Int
        get() = adaptiveBitrateManager.lastQualityBacklogDrops
        set(value) { adaptiveBitrateManager.lastQualityBacklogDrops = value }        

    private var audioCapture: AudioCapture? = null
    private var audioOrchestrator: AudioCaptureOrchestrator? = null
    private var shizukuSetup: ShizukuSetup? = null
    private var remoteImeBridge: RemoteImeBridge? = null
    private var remoteImeWatchdogJob: Job? = null
    private var mirroringMode: String = "FULL_SCREEN"
    private var targetPackage: String = ""
    private var browserConnectionListener: ((Boolean) -> Unit)? = null
    @Volatile private var stopRequested = false
    @Volatile private var cleanupCompleted = false
    private var isWakingUpFromPowerButton = false
    private val terminalReason = java.util.concurrent.atomic.AtomicReference<TerminalReason?>(null)
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var browserConnected = false
    private var isInitialRebuildTriggered = false
    @Volatile private var currentCodecMode: String = "h264"
    private val paneVisibility = java.util.concurrent.ConcurrentHashMap<String, Boolean>().apply {
        put("primary", true)
        put("secondary", false)
    }

    private val virtualDisplayHardwareMutex = Mutex()
    private val vdOperationGlobalMutex = Mutex()

    
    // Hardware request envelope to sequentialize all VirtualDisplay operations
    sealed class VdHardwareRequest {
        data class Rebuild(
            val pipelineName: String,
            val targetWidth: Int,
            val targetHeight: Int,
            val force: Boolean,
            val forceSingle: Boolean,
            val onComplete: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        ) : VdHardwareRequest()
    }

    private val vdRequestChannel = kotlinx.coroutines.channels.Channel<VdHardwareRequest>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var vdWorkerJob: Job? = null
    

    enum class PipelineState { IDLE, REBUILDING }
    enum class RebuildPriority { LOW, NORMAL, HIGH, IMMEDIATE }
    data class RebuildRequest(
        val pipelineName: String,
        val reason: String,
        val priority: RebuildPriority,
        val width: Int,
        val height: Int,
        val force: Boolean,
        val forceSingle: Boolean,
        val onComplete: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    )
    private data class RebuildRequestSnapshot(
        val width: Int,
        val height: Int,
        val force: Boolean,
        val forceSingle: Boolean,
        val reason: String,
        val requestedAt: Long
    )

    private val rebuildRequestMutex = Mutex()
    private val lastRebuildRequestByPane = java.util.concurrent.ConcurrentHashMap<String, RebuildRequestSnapshot>()

    private var dpiScale: Float = 0.7f
    private val shizukuSetupMutex = Mutex()
    private var shizukuBindRetryCount = 0
    private val SHIZUKU_MAX_RETRIES = 2
    private val BIND_WAIT_BUDGET_MS = 8_000L

    private var reconnectJob: Job? = null
    private var pendingAudioEnabled = false
    private var deferredAudioStartJob: Job? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var vdKeepAliveJob: Job? = null
    private var appExitMonitorJob: Job? = null
    private var pendingBrowserDisconnectJob: Job? = null
    @Volatile private var browserTeardownPhase: String = "idle"
    private val inputDebugLaunchSeq = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var currentInputDebugLaunchSeq = 0
    private val inputDebugPacketCounts = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
    private val inputDebugMovePacketCounts = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
    private val inputDebugLaunchStartElapsedMs = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val recentServerTouchTrace = java.util.ArrayDeque<String>()
    private val recentServerTouchTraceMutex = Any()
    @Volatile private var lastRejectProbeSummary: String = ""

    @Volatile private var lastAppLaunchTime: Long = 0L
    private val paneLastLaunchTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val paneLastLaunchPackages = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val screenOffPolicy = ScreenOffPolicy()
    private val keyguardManager by lazy { getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager }

    val isRunning: Boolean get() = mirrorServer != null
    val isPanelOffSupported: Boolean get() = screenOffPolicy.isPanelOffSupported

    private fun beginInputDebugLaunch(pane: String, pkg: String) {
        currentInputDebugLaunchSeq = inputDebugLaunchSeq.incrementAndGet()
        inputDebugPacketCounts[currentInputDebugLaunchSeq] = java.util.concurrent.atomic.AtomicInteger(0)
        inputDebugMovePacketCounts[currentInputDebugLaunchSeq] = java.util.concurrent.atomic.AtomicInteger(0)
        inputDebugLaunchStartElapsedMs[currentInputDebugLaunchSeq] = android.os.SystemClock.elapsedRealtime()
        pipelines[pane]?.markInputDebugLaunch(currentInputDebugLaunchSeq)
        logInputDebugSnapshot("launch_begin#$currentInputDebugLaunchSeq pane=$pane pkg=$pkg")
    }

    private fun recordInputDebugPacket(event: TouchEvent, pipeline: MirroringPipeline?) {
        val packetCount = inputDebugPacketCounts
            .getOrPut(currentInputDebugLaunchSeq) { java.util.concurrent.atomic.AtomicInteger(0) }
            .incrementAndGet()
        if (event.action == "move") {
            inputDebugMovePacketCounts
                .getOrPut(currentInputDebugLaunchSeq) { java.util.concurrent.atomic.AtomicInteger(0) }
                .incrementAndGet()
        }
        if (event.action != "move") {
            logInputDebugSnapshot("touch_${event.action}#$currentInputDebugLaunchSeq")
        }
    }

    private fun logInputDebugSnapshot(reason: String) {
        val server = mirrorServer
        val launchElapsedMs = (android.os.SystemClock.elapsedRealtime() - (inputDebugLaunchStartElapsedMs[currentInputDebugLaunchSeq] ?: android.os.SystemClock.elapsedRealtime())).coerceAtLeast(1L)
        val movePackets = inputDebugMovePacketCounts[currentInputDebugLaunchSeq]?.get() ?: 0
        val movePacketsPerSecond = (movePackets * 1000.0) / launchElapsedMs.toDouble()
//        val pipelineStates = pipelines.values.joinToString(" | ") { pipeline ->
//            val injectorState = try { pipeline.touchInjector?.debugState() ?: "injector=null" } catch (_: Exception) { "injector=error" }
//            "${pipeline.name}:displayId=${pipeline.displayId},app=${pipeline.currentApp},requested=${pipeline.requestedWidth}x${pipeline.requestedHeight},${pipeline.inputDebugSummary()},$injectorState"
//        }
    }

    private fun countActiveServiceJobs(): Int {
        val rootJob = serviceScope.coroutineContext[Job] ?: return -1
        return rootJob.children.count { it.isActive }
    }

    private fun pipelineTouchSnapshot(): String {
        return pipelines.values.joinToString(" | ") { pipeline ->
            val injectorState = try {
                pipeline.touchInjector?.debugState() ?: "injector=null"
            } catch (_: Exception) {
                "injector=error"
            }
            "${pipeline.name}:displayId=${pipeline.displayId},app=${pipeline.currentApp},touchActive=${pipeline.isTouchInteractionActive()}," +
                "focusGate=${pipeline.focusGateSummary()},$injectorState"
        }
    }

    private fun injectorTouchSnapshot(): String {
        return pipelines.values.joinToString(" | ") { pipeline ->
            val injectorState = try {
                pipeline.touchInjector?.debugState() ?: "injector=null"
            } catch (_: Exception) {
                "injector=error"
            }
            "${pipeline.name}:$injectorState"
        }
    }

    private fun appendRecentServerTouchTrace(line: String) {
        synchronized(recentServerTouchTraceMutex) {
            recentServerTouchTrace.addLast(line)
            while (recentServerTouchTrace.size > 18) {
                recentServerTouchTrace.removeFirst()
            }
        }
    }

    private fun recentServerTouchTraceSnapshot(): List<String> {
        return synchronized(recentServerTouchTraceMutex) {
            recentServerTouchTrace.toList()
        }
    }

    private fun broadcastWebDiagnostics(reason: String) {
        val server = mirrorServer ?: return
        try {
            server.broadcastControlMessage(
                JSONObject().apply {
                    put("type", "diagnostics")
                    put(
                        "server",
                        JSONObject().apply {
                            put("reason", reason)
                            put("browserConnected", browserConnected)
                            put("serverBrowserConnected", server.isBrowserConnected())
                            put("pendingDisconnect", pendingBrowserDisconnectJob != null)
                            put("disconnectGraceMs", DisconnectPolicy.graceMs(screenOffPolicy.isScreenOff))
                            put("screenOff", screenOffPolicy.isScreenOff)
                            put("teardownPhase", browserTeardownPhase)
                            put("socketSummary", server.socketDebugSummary())
                            put("pipelineSnapshot", pipelineTouchSnapshot())
                            put("injectorSnapshot", injectorTouchSnapshot())
                            put("launchSeq", currentInputDebugLaunchSeq)
                            put("lastTouchPane", lastTouchPane)
                            put("timestampMs", System.currentTimeMillis())
                            put("touchTrace", JSONArray(recentServerTouchTraceSnapshot()))
                            put("rejectProbe", lastRejectProbeSummary)
                        }
                    )
                }.toString()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast web diagnostics", e)
        }
    }

    private fun isAnyTouchInteractionActive(): Boolean {
        return pipelines.values.any { it.isTouchInteractionActive() }
    }

    private fun <T> runBlockingBinderSafe(timeoutMs: Long = 1000L, block: suspend () -> T): T? {
        return try {
            kotlinx.coroutines.runBlocking {
                runBinderSafe(timeoutMs, block)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isLaunchLikeActivity(line: String, cleanPkg: String): Boolean {
        if (line.isBlank()) return true
        val normalized = line.lowercase(java.util.Locale.US)
        if (!normalized.contains(cleanPkg.lowercase(java.util.Locale.US))) return true
        return normalized.contains("launchactivity") ||
            normalized.contains("introactivity") ||
            normalized.contains("splash")
    }

    private fun extractTopResumedLineForDisplay(raw: String, displayId: Int): String {
        if (raw.isBlank() || displayId < 0) return ""
        val lines = raw.lineSequence().map { it.trim() }.toList()
        var inTargetDisplay = false
        for (line in lines) {
            if (line.startsWith("Display #")) {
                inTargetDisplay = line.contains("Display #$displayId", ignoreCase = true)
            }
            if (!inTargetDisplay) continue
            if (line.contains("topResumedActivity", ignoreCase = true) || line.contains("mResumedActivity", ignoreCase = true)) {
                return line
            }
        }
        return ""
    }

    private fun mostRecentTouchAgeMs(now: Long = android.os.SystemClock.elapsedRealtime()): Long? {
        val lastTouchAt = pipelines.values
            .map { it.lastTouchEventAt }
            .filter { it > 0L }
            .maxOrNull()
            ?: return null
        return (now - lastTouchAt).coerceAtLeast(0L)
    }

    private suspend fun requestRebuild(request: RebuildRequest) {
        val pipeline = pipelines[request.pipelineName]
        if (pipeline == null || isAppLaunchingContext || request.width <= 0 || request.height <= 0) {
            request.onComplete?.complete(Unit)
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        var coalesced = false
        rebuildRequestMutex.withLock {
            val last = lastRebuildRequestByPane[request.pipelineName]
            val duplicate = last != null &&
                last.width == request.width &&
                last.height == request.height &&
                last.force == request.force &&
                last.forceSingle == request.forceSingle &&
                now - last.requestedAt <= 120L

            if (duplicate && request.onComplete == null && request.priority != RebuildPriority.IMMEDIATE) {
                coalesced = true
            } else {
                lastRebuildRequestByPane[request.pipelineName] = RebuildRequestSnapshot(
                    request.width,
                    request.height,
                    request.force,
                    request.forceSingle,
                    request.reason,
                    now
                )
            }
        }
        if (coalesced) {
            Log.d(
                TAG,
                "[RebuildCoordinator] coalesced pane=${request.pipelineName} reason=${request.reason} " +
                    "target=${request.width}x${request.height}"
            )
            return
        }

        val deferStart = android.os.SystemClock.elapsedRealtime()
        var deferredForTouch = false
        var skippedForTouchQuietWindow = false
        while (request.priority != RebuildPriority.IMMEDIATE) {
            val recentTouchAgeMs = mostRecentTouchAgeMs()
            val requiresQuietWindow = request.priority == RebuildPriority.LOW
            val touchBlocked = isAnyTouchInteractionActive() ||
                (requiresQuietWindow && recentTouchAgeMs != null && recentTouchAgeMs < 2500L)
            if (!touchBlocked) break

            deferredForTouch = true
            delay(60L)
            if (android.os.SystemClock.elapsedRealtime() - deferStart >= 1500L) {
                if (requiresQuietWindow) skippedForTouchQuietWindow = true
                break
            }
        }
        if (deferredForTouch) {
        }
        if (skippedForTouchQuietWindow) {
            Log.i(
                TAG,
                "[RebuildCoordinator] skipped pane=${request.pipelineName} reason=${request.reason} priority=${request.priority} " +
                    "source=touch_quiet_window recentTouchAgeMs=${mostRecentTouchAgeMs() ?: -1L} target=${request.width}x${request.height}"
            )
            request.onComplete?.complete(Unit)
            return
        }

        pipeline.debugRebuildRequests += 1
        val enqueueResult = vdRequestChannel.trySend(
            VdHardwareRequest.Rebuild(
                request.pipelineName,
                request.width,
                request.height,
                request.force,
                request.forceSingle,
                request.onComplete
            )
        )
        if (enqueueResult.isFailure) {
            val error = enqueueResult.exceptionOrNull()
            if (error != null) {
                request.onComplete?.completeExceptionally(error)
                throw error
            }
            request.onComplete?.complete(Unit)
        }
    }

    
    // Start sequential background worker loop to process all virtual display operations FIFO
    private fun startVdHardwareWorker() {
        vdWorkerJob?.cancel()
        vdWorkerJob = serviceScope.launch(vdDispatcher) {
            for (request in vdRequestChannel) {
                if (!isActive) break
                try {
                    when (request) {
                        is VdHardwareRequest.Rebuild -> {
                            try {
                                val pipeline = pipelines[request.pipelineName]
                                if (pipeline != null) {
                                    pipeline.executeActualRebuild(
                                        request.targetWidth,
                                        request.targetHeight,
                                        request.force,
                                        request.forceSingle
                                    )
                                }
                            } finally {
                                request.onComplete?.complete(Unit)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "[VdWorker] Failed to process sequential hardware request", e)
                }
            }
        }
    }
    

    private fun logScreenState(event: String) {
        val keyguardLocked = keyguardManager.isKeyguardLocked
        val deviceLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) keyguardManager.isDeviceLocked else keyguardLocked
        val firstVdId = pipelines.values.firstOrNull()?.controller?.getDisplayId() ?: -1
        Log.i(TAG, "[BUILD:screen-off-v3] $event -> state=${screenOffPolicy.state}, keyguardLocked=$keyguardLocked, deviceLocked=$deviceLocked, browserConnected=$browserConnected, vdId=$firstVdId, panelOffSupported=${screenOffPolicy.isPanelOffSupported}")
    }

    fun turnPanelOffForMirroring(): Boolean {
        if (!isRunning) { Log.w(TAG, "turnPanelOffForMirroring: service not running"); return false }
        if (!browserConnected) { Log.w(TAG, "turnPanelOffForMirroring: browser not connected"); return false }
        if (pipelines.values.none { it.controller.hasVirtualDisplay() }) { Log.w(TAG, "turnPanelOffForMirroring: no active virtual display"); return false }
        if (screenOffPolicy.isScreenOff) { Log.d(TAG, "turnPanelOffForMirroring: already off"); return true }

        powerLockManager.acquireWakeLocks()
        val action = screenOffPolicy.onScreenOff(panelOffSupported = true)
        logScreenState("Panel OFF requested via button (action=$action)")
        executeScreenOffAction(action)
        _panelOffStateFlow.value = screenOffPolicy.state
        return screenOffPolicy.state == ScreenOffState.PANEL_OFF_ACTIVE
    }

    fun restorePhysicalPanel() {
        if (!screenOffPolicy.isScreenOff) return
        val action = screenOffPolicy.onScreenOn()
        logScreenState("Panel ON requested via button (action=$action)")
        executeScreenOnAction(action)
        _panelOffStateFlow.value = screenOffPolicy.state
    }

    fun setBrowserConnectionListener(listener: ((Boolean) -> Unit)?) {
        browserConnectionListener = listener
        mirrorServer?.setBrowserConnectionListener(listener)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate() - Initializing Symmetrical Pipeline Context Map Pool")
        
        
        // Start the sequential hardware worker to handle rebuild tasks sequentially
        startVdHardwareWorker()
        
        
        pipelines["primary"] = MirroringPipeline("primary", "Castla")
        pipelines["secondary"] = MirroringPipeline("secondary", "Castla_Sec")
        
        instance = this
        isServiceRunning = true
        remoteImeBridge = RemoteImeBridge(
            privilegedServiceProvider = { shizukuSetup?.privilegedService },
            displayIdProvider = { activeInputDisplayId() }
        )
        isCleanupInProgress = false
        createNotificationChannel()
        observeAppLaunchRequests()

        powerLockManager = PowerLockManager(this@MirrorForegroundService)
        
        thermalThrottleManager = ThermalThrottleManager(
            context = this@MirrorForegroundService,
            mainExecutor = androidx.core.content.ContextCompat.getMainExecutor(this@MirrorForegroundService),
            getPipelines = { pipelines },
            getAudioOrchestrator = { audioOrchestrator },
            onThermalThrottled = { adaptiveBitrateManager.resetTiers() },
            getMirrorServer = { mirrorServer },
        )
        
        adaptiveBitrateManager = AdaptiveBitrateManager(
            context = this@MirrorForegroundService,
            serviceScope = serviceScope,
            getPipelines = { pipelines },
            getBrowserConnected = { browserConnected },
            getIsServiceRunning = { isServiceRunning },
            getThermalActive = { thermalThrottleManager.thermalStatus.value >= PowerManager.THERMAL_STATUS_LIGHT },
            getThermalFpsOverride = { thermalThrottleManager.thermalFpsOverride },
            getThermalMaxHeight = { thermalThrottleManager.thermalMaxHeight },
            getMirrorServer = { mirrorServer },
        )

        contentAwareQualityEngine = ContentAwareQualityEngine(
            getGlobalBudget = { adaptiveBitrateManager.globalBitrateBudget }, // ABR budget linkage
            broadcastControlMessage = { json -> mirrorServer?.broadcastControlMessage(json) } // WebSocket linkage
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalThrottleManager.register()
        }

        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        onPhoneScreenOff()
                        mainHandler.postDelayed({ if (keyguardManager.isKeyguardLocked) MirrorDiagnostics.log(DiagnosticEvent.KEYGUARD_LOCKED) }, 500)
                    }
                    Intent.ACTION_SCREEN_ON -> onPhoneScreenOn()
                    Intent.ACTION_USER_PRESENT -> MirrorDiagnostics.log(DiagnosticEvent.KEYGUARD_UNLOCKED)
                }
            }
        }
        registerReceiver(screenOffReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy() - Service is being destroyed by stopService() or system.")
        
        
        // Terminate the sequential virtual display hardware worker loop
        vdWorkerJob?.cancel()
        vdWorkerJob = null
        
        
        screenOffReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
                Log.i(TAG, "screenOffReceiver unregistered successfully during onDestroy().")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister screenOffReceiver during onDestroy: ${e.message}")
            }
            screenOffReceiver = null
        }

        if (!cleanupCompleted) {
            performCleanup("service_ondestroy")
        }
        super.onDestroy()
    }
        
    private var lastBitrateChangeMs = 0L

    private fun observeAppLaunchRequests() {
        serviceScope.launch {
            // Continuously monitor packets injected via AppLaunchBus.requestLaunch() or emitEvent()
            com.castla.mirror.utils.AppLaunchBus.events.collect { request ->
                val pane = request.pane ?: "primary"
                val targetPipeline = pipelines[pane] ?: return@collect

                // Prevent rapid multi-tap bounce and OS window manager stacking hazards.
                val now = android.os.SystemClock.elapsedRealtime()
                val lastLaunchTime = paneLastLaunchTimes[pane] ?: 0L
                val lastPackage = paneLastLaunchPackages[pane] ?: ""

                // Rule 1: Block duplicate launch of the exact same app on the same display pane within 1.5 seconds.
                val isDuplicate = lastPackage == request.packageName && (now - lastLaunchTime < 1500L)

                if (isDuplicate) {
                    Log.d(TAG, "[AppLaunchBus Observer] Debounced duplicate request for ${request.packageName} on $pane pane (elapsed=${now - lastLaunchTime}ms)")
                    return@collect
                }

                // Update launch state cache
                paneLastLaunchTimes[pane] = now
                paneLastLaunchPackages[pane] = request.packageName

                Log.i(TAG, "[AppLaunchBus Observer] Event Captured! Processing pipeline architecture setup for: ${request.packageName} ($pane pane)")
                try {
                    val shouldForceCancel =
                        targetPipeline.isTouchInteractionActive() ||
                            (targetPipeline.touchInjector?.hasTrackedPointers() == true)
                    targetPipeline.touchInjector?.release(
                        forceFallbackCancel = shouldForceCancel,
                        reason = "app_launch_transition"
                    );
                    lastTouchPane = "primary"
                    Log.i(TAG, "[Touch] Cleared pane touch state before app launch pane=$pane pkg=${request.packageName}")
                } catch (_: Exception) {}

                paneVisibility[pane] = true
                if (pane == "secondary") {
                    val fallbackW = targetPipeline.requestedWidth.takeIf { it > 1 }
                        ?: pipelines["primary"]?.requestedWidth?.takeIf { it > 1 }
                        ?: pipelines["primary"]?.width?.takeIf { it > 1 }
                        ?: targetPipeline.lastValidWidth.coerceAtLeast(720)
                    val fallbackH = targetPipeline.requestedHeight.takeIf { it > 1 }
                        ?: pipelines["primary"]?.requestedHeight?.takeIf { it > 1 }
                        ?: pipelines["primary"]?.height?.takeIf { it > 1 }
                        ?: targetPipeline.lastValidHeight.coerceAtLeast(720)
                    targetPipeline.setTier(DisplayTier.VISIBLE, "secondary_launch_requested")
                    if (!targetPipeline.isEncoderRunning() || targetPipeline.width <= 1 || targetPipeline.height <= 1) {
                        val rebuildDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                        targetPipeline.requestRebuild(
                            reason = "secondary_launch_prepare",
                            priority = RebuildPriority.HIGH,
                            newWidth = fallbackW,
                            newHeight = fallbackH,
                            force = true,
                            onComplete = rebuildDeferred
                        )
                        withTimeoutOrNull(2500L) { rebuildDeferred.await() }
                    }
                } else {
                    targetPipeline.setTier(DisplayTier.ACTIVE, "primary_launch_requested")
                }

                // ─────────────────────────────────────────────────────────────────
                // 💡 [Optimization 1] Proactive execution of encoder profile optimization (must run *before* app starts)
                // ─────────────────────────────────────────────────────────────────

                // 1-1. Before the app starts, first backup the profile state of the existing pipeline.
                val oldProfile = contentAwareQualityEngine.resolveContentProfile(
                    targetPipeline.currentApp,
                    targetPipeline.isVideoApp
                )

                // 1-2. Reflected the new guideline (isVideoApp) written in the ticket immediately to the pipeline context.
                targetPipeline.isVideoApp = request.isVideoApp

                // 1-3. Calculate the target profile based on the identification info of the app scheduled to start.
                val newProfile = contentAwareQualityEngine.resolveContentProfile(
                    request.packageName, // targetPipeline.currentApp is still the old app, so we retrieve it from the request.
                    request.isVideoApp
                )

                // 1-4. Realizes and schedules substantial image quality engine profile transitions (e.g. text mode -> motion mode)
                val profileChanged = oldProfile != newProfile

                if (profileChanged && now - lastBitrateChangeMs > 500) {
                    lastBitrateChangeMs = now
                    Log.d(TAG, "[Architecture Sync] Profile shift detected (${oldProfile.name} -> ${newProfile.name}). Rebalancing bandwidth ahead of app launch.")

                    // 💥 Proactively execute bitrate distribution and QP range tuning completely before the app pours its first pixel buffer!
                    contentAwareQualityEngine.rebalanceMultiDisplayBitrates(pipelines.values.toList())
                }

                // ─────────────────────────────────────────────────────────────────
                // 💡 [Optimization 2] Execute final hardware startup at a secure timing when the encoder parameters are perfectly locked
                // ─────────────────────────────────────────────────────────────────

                // 2-1. Call the integrated helper function that handles complex external browser redirection, package validation, etc.
                // Pass forceDisplayId constraint dynamically from the launch request.
                targetPipeline.launchAppFromWebLauncher(request.packageName, request.className, forceDisplayId = request.forceDisplayId)

                // 2-2. Link subsequent autoscale (resolution and FPS tiering) evaluations
                if (targetPipeline.autoResolution || targetPipeline.autoFps) {
                    adaptiveBitrateManager.evaluateSinglePipelineScale(targetPipeline)
                }

                // 2-3. Maintain OTT profile hint synchronization transmission to the web frontend receiver
                mirrorServer?.broadcastControlMessage(JSONObject().apply {
                    put("type", "ottProfileHint")
                    put("pane", pane)
                    put("active", targetPipeline.isVideoApp)
                }.toString())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "onStartCommand() - Stop action broadcast received via notification panel")
            requestStopAsync("notification_action")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTORE_IME) {
            Log.i(TAG, "onStartCommand() - Restore IME action received via notification panel")
            val svc = shizukuSetup?.privilegedService
            if (svc != null) {
                serviceScope.launch {
                    try {
                        com.castla.mirror.input.ImeSwitchManager.restorePreviousIme(this@MirrorForegroundService) { cmd ->
                            svc.execCommand(cmd)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Notification restore keyboard action failed", e)
                    }
                }
            }
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        
        val hostIp = intent?.getStringExtra("EXTRA_HOST_IP") ?: "0.0.0.0"
        val relayPublishIp =
            intent?.getStringExtra(EXTRA_RELAY_PUBLISH_IP)
                ?.takeIf { it.isNotBlank() }
                ?: hostIp        


        val rawMaxHeight = intent?.getIntExtra(EXTRA_MAX_RESOLUTION, 0) ?: 0
        val rawFps = intent?.getIntExtra(EXTRA_FPS, 0) ?: 0
        pendingAudioEnabled = intent?.getBooleanExtra(EXTRA_AUDIO, false) ?: false
        mirroringMode = intent?.getStringExtra(EXTRA_MIRRORING_MODE) ?: "FULL_SCREEN"
        targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""

        Log.i(TAG, "onStartCommand() - Frame profiling parameters input. HeightHint=$rawMaxHeight, FpsHint=$rawFps, Audio=$pendingAudioEnabled, hostIp=$hostIp, relayPublishIp=$relayPublishIp")

        pipelines.values.forEach { pipeline ->
            pipeline.autoResolution = (rawMaxHeight == 0)
            pipeline.currentMaxHeight = if (pipeline.autoResolution) 720 else rawMaxHeight
            pipeline.autoFps = (rawFps == 0)
            pipeline.targetFps = if (pipeline.autoFps) 30 else rawFps
        }

        serviceScope.launch(Dispatchers.IO) {
            startPipeline(
                audioEnabled = pendingAudioEnabled,
                relayPublishIp = relayPublishIp
            )
        }
        return START_NOT_STICKY
    }

    private fun requestStopAsync(reason: String) {
        if (stopRequested) return
        stopRequested = true
        Log.i(TAG, "requestStopAsync() - Gracefully tearing down foreground service loop. Reason: $reason")
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        serviceScope.launch(Dispatchers.IO) {
            performCleanup("request_stop:$reason")
            mainHandler.post { 
                MirrorWidgetProvider.updateAllWidgets(this@MirrorForegroundService)
                stopSelf() 
            }
        }
    }

    private fun onPhoneScreenOff() {
        MirrorDiagnostics.log(DiagnosticEvent.SCREEN_OFF)
        logScreenState("onPhoneScreenOff() called")
        
        if (screenOffPolicy.state == ScreenOffState.ACTIVE) {
            isWakingUpFromPowerButton = true
            val action = screenOffPolicy.onScreenOff(panelOffSupported = screenOffPolicy.isPanelOffSupported)
            executeScreenOffAction(action)
            _panelOffStateFlow.value = screenOffPolicy.state
        } else {
            isWakingUpFromPowerButton = false
            val action = screenOffPolicy.onScreenOn()
            executeScreenOnAction(action)
            _panelOffStateFlow.value = screenOffPolicy.state
        }
    }

    private fun onPhoneScreenOn() {
        MirrorDiagnostics.log(DiagnosticEvent.SCREEN_ON)
        logScreenState("onPhoneScreenOn() called")
        
        if (isWakingUpFromPowerButton) { isWakingUpFromPowerButton = false; return }
        val action = screenOffPolicy.onScreenOn()
        executeScreenOnAction(action)
        _panelOffStateFlow.value = screenOffPolicy.state

        cancelPendingBrowserDisconnect("screen_on")
        if (mirrorServer?.isBrowserConnected() != true && browserConnected && !isCleanupInProgress) {
            Log.w(TAG, "Screen ON -> Web display disconnected while screen was black. Scheduling deferred teardown.")
            serviceScope.launch { onBrowserDisconnected(); browserConnectionListener?.invoke(false) }
        }
    }

    private fun executeScreenOffAction(action: ScreenOffAction) {
        when (action) {
            ScreenOffAction.TURN_PANEL_OFF -> {
                val anyController = pipelines.values.firstOrNull()?.controller
                if (anyController?.isBound() != true) {
                    Log.w(TAG, "Panel-off requested but VirtualDisplay binder architecture not stabilized yet.")
                    executeScreenOffAction(screenOffPolicy.onPanelOffResult(success = false))
                    return
                }
                try {
                    anyController.getPrivilegedService()?.execCommand("input keyevent 224")
                    anyController.getPrivilegedService()?.execCommand("wm dismiss-keyguard")
                } catch (_: Exception) {}

                serviceScope.launch {
                    var success = false
                    for (i in 1..10) {
                        try { success = anyController.setPhysicalDisplayPower(false) } catch (_: Exception) {}
                        kotlinx.coroutines.delay(100)
                    }
                    Log.i(TAG, "[ScreenOff] Physical power burst complete. Success=$success")
                    serviceScope.launch(Dispatchers.Main) {
                        val fallback = screenOffPolicy.onPanelOffResult(success)
                        if (fallback != ScreenOffAction.NONE) executeScreenOffAction(fallback)
                    }
                }
            }
            ScreenOffAction.START_KEEP_ALIVE -> startVdKeepAlive()
            else -> {}
        }
    }

    private fun executeScreenOnAction(action: ScreenOffAction) {
        when (action) {
            ScreenOffAction.RESTORE_PANEL -> {
                stopVdKeepAlive()
                val restored = pipelines.values.firstOrNull()?.controller?.setPhysicalDisplayPower(true) ?: false
                Log.i(TAG, "[ScreenOn] Physical panel power restoration executed. Success=$restored")
            }
            ScreenOffAction.STOP_KEEP_ALIVE -> stopVdKeepAlive()
            else -> {}
        }
    }

    private fun startVdKeepAlive() {
        stopVdKeepAlive()
        vdKeepAliveJob = serviceScope.launch {
            Log.i(TAG, "[KeepAlive] Symmetrical VD keep-awake pulse generator active.")
            while (true) {
                for (pipeline in pipelines.values) {
                    if (pipeline.controller.hasVirtualDisplay()) pipeline.controller.keepDisplayAwake()
                }
                kotlinx.coroutines.delay(VD_KEEP_ALIVE_INTERVAL_MS)
            }
        }
        startAppExitMonitor()
    }

    private fun stopVdKeepAlive() {
        vdKeepAliveJob?.cancel(); vdKeepAliveJob = null
        stopAppExitMonitor()
    }

    private fun startAppExitMonitor() {
        stopAppExitMonitor()
        appExitMonitorJob = serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000L)
                pipelines.values.forEach { pipeline ->
                    val displayId = pipeline.displayId
                    if (displayId < 0) return@forEach
                    val service = pipeline.controller.getPrivilegedService() ?: return@forEach
                    if (pipeline.currentApp.isNotBlank() && pipeline.currentApp != "HOME" && pipeline.currentApp != "com.android.settings") {
                        try {
                            val activeTasks = service.getRunningTasksOnDisplay(displayId) ?: emptyList()
                            if (activeTasks.firstOrNull()?.contains("VirtualDisplayHomeActivity") == true) {
                                Log.i(TAG, "[ExitMonitor] Home activity detected at the top of pane (${pipeline.name}). BroadCasting APP_STREAM_STOPPED.")
                                pipeline.currentApp = "HOME"
                                mirrorServer?.broadcastControlMessage("{\"type\":\"APP_STREAM_STOPPED\", \"pane\":\"${pipeline.name}\"}")
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun stopAppExitMonitor() { appExitMonitorJob?.cancel(); appExitMonitorJob = null }

    @Synchronized
    private fun performCleanup(reason: String) {
        if (cleanupCompleted) return
        cleanupCompleted = true
        Log.i(TAG, "performCleanup() -> Starting central resource recycling sequencer. Reason: $reason")
        MirrorDiagnostics.endSession(terminalReason.get()?.let { "terminal:${it.name}" } ?: reason)
        isCleanupInProgress = true

        if (screenOffPolicy.state in listOf(ScreenOffState.PANEL_OFF_ACTIVE, ScreenOffState.PANEL_OFF_PENDING)) {
            try { pipelines.values.firstOrNull()?.controller?.setPhysicalDisplayPower(true) } catch (_: Exception) {}
        }
        screenOffPolicy.reset()
        _panelOffStateFlow.value = ScreenOffState.ACTIVE

        val receiverToUnregister = screenOffReceiver
        if (receiverToUnregister != null) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                try {
                    unregisterReceiver(receiverToUnregister)
                    Log.i(TAG, "Synchronously unregistered screenOffReceiver using service context safely [Main Thread].")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister screenOffReceiver: ${e.message}")
                }
            } else {
                mainHandler.post {
                    try {
                        unregisterReceiver(receiverToUnregister)
                        Log.i(TAG, "Asynchronously unregistered screenOffReceiver on Main Looper context thread safely.")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to unregister screenOffReceiver on Main Looper: ${e.message}")
                    }
                }
            }
            screenOffReceiver = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { try { thermalThrottleManager.unregister() } catch (_: Exception) {} }
        powerLockManager.releaseWakeLocks()
        stopVdKeepAlive()
        audioOrchestrator?.stop()

        pipelines.values.forEach { try { it.resizeJob?.cancel() } catch (_: Exception) {} }
        adaptiveBitrateManager.stopAllLoops()
        pendingBrowserDisconnectJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null

        try { mirrorServer?.stop() } catch (_: Exception) {}
        mirrorServer = null



        kotlinx.coroutines.runBlocking {
            Log.i(TAG, "[Cleanup] Sequentially releasing virtual hardware display devices inside blocking coroutine.")
            pipelines.values.reversed().forEach { pipeline -> 
                try { kotlinx.coroutines.withTimeoutOrNull(1500L) { pipeline.release(forcePhysical = true) } } catch (e: Exception) { Log.e(TAG, "Error releasing pane (${pipeline.name})", e) } 
            }
            
            try { kotlinx.coroutines.withTimeoutOrNull(1000L) { pipelines.values.firstOrNull()?.controller?.getPrivilegedService()?.restoreStayAwakeMode() } } catch (_: Exception) {}
            pipelines.values.forEach { pipeline ->
                try { pipeline.touchInjector?.detachController("perform_cleanup") } catch (_: Exception) {}
                try { kotlinx.coroutines.withTimeoutOrNull(1000L) { pipeline.controller.release() } } catch (_: Exception) {}
            }
            
            try {
                val svc = shizukuSetup?.privilegedService
                if (svc != null) {
                    // Directly restore the IME synchronously during cleanup to bypass FSM locks and prevent deadlocks
                    com.castla.mirror.input.TextInputSettingsHelper.restorePreviousIme(this@MirrorForegroundService) { cmd ->
                        try { svc.execCommand(cmd) } catch (_: Exception) { null }
                    }
                    Log.i(TAG, "Directly restored previous IME during performCleanup.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed programmatically restoring previous IME during performCleanup", e)
            }

            try { kotlinx.coroutines.withTimeoutOrNull(1000L) { shizukuSetup?.release() } } catch (_: Exception) {}
            
            shizukuSetup = null
            try { serviceScope.cancel() } catch (_: Exception) {}
            try { compositionDispatcher.close() } catch (_: Exception) {}
            try { vdDispatcher.close() } catch (_: Exception) {}

            instance = null; isCleanupInProgress = false; isServiceRunning = false
            Log.i(TAG, "[Cleanup] Central resource recycling sequencer terminated successfully.")
        }
    }

    private fun startPipeline(audioEnabled: Boolean, relayPublishIp: String) {
        try {
            terminalReason.set(null)
            MirrorDiagnostics.onSessionStart()

            val metrics = resources.displayMetrics
            val rawWidth = metrics.widthPixels.coerceAtMost(1920)
            val rawHeight = metrics.heightPixels.coerceAtMost(1080)
            val effectiveMaxHeight = rawHeight.coerceAtMost(1080)

            var width = rawWidth
            var height = rawHeight
            if (height > effectiveMaxHeight) {
                val scale = effectiveMaxHeight.toFloat() / height
                height = effectiveMaxHeight
                width = (width * scale).toInt()
            }

            width = (width + 15) and 15.inv()
            height = (height + 15) and 15.inv()

            // Initialize target viewport dimensions with screen default landscape or portrait layout to 
            // prevent square (720x720) or uninitialized display sizing anomalies during cold start launches.
            pipelines.values.forEach {
                it.width = width
                it.height = height
                it.requestedWidth = width
                it.requestedHeight = height
                it.lastValidWidth = width
                it.lastValidHeight = height
            }
            
            pendingAudioEnabled = audioEnabled
            audioOrchestrator = AudioCaptureOrchestrator(object : AudioCaptureOrchestrator.Actions {
                override fun startCapture(codec: String?) {
                    audioCapture = AudioCapture(null, shizukuSetup?.privilegedService).also { audio ->
                        if (codec == "pcm") audio.startPcmOnly { mirrorServer?.broadcastAudio(it) }
                        else audio.start { mirrorServer?.broadcastAudio(it) }
                    }
                }
                override fun stopCapture() { try { audioCapture?.stop() } catch (_: Exception) {}; audioCapture = null }
                override fun grantAudioPermission() { tryGrantAudioCapturePermission() }
                override fun scheduleDeferredStart(delayMs: Long): Any = serviceScope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(delayMs); audioOrchestrator?.onDeferredTimerExpired() }
                override fun cancelDeferredStart(handle: Any?) { (handle as? Job)?.cancel(); if (deferredAudioStartJob == handle) deferredAudioStartJob = null }
            })

            pipelines.values.forEach { it.touchInjector = TouchInjector(width, height) }

            mirrorServer = MirrorServer(this).also { server ->
                server.setRelayPublishIp(relayPublishIp)
                server.setNetworkCongestionListener { adaptiveBitrateManager.onNetworkCongestion() }
                server.setTouchListener { event ->
                    val targetPipeline = pipelines[event.pane]
                    if (targetPipeline?.shouldDeferTouchForFocusGate(event) == true) {
                        appendRecentServerTouchTrace(
                            "gate pane=${event.pane} action=${event.action} id=${event.pointerId} app=${targetPipeline.currentApp}"
                        )
                        if (event.action != "move") {
                            broadcastWebDiagnostics("touch_gate:${event.pane}:${event.action}")
                        }
                        return@setTouchListener
                    }
                    targetPipeline?.noteTouchEvent(event.action)
                    recordInputDebugPacket(event, targetPipeline)
                    appendRecentServerTouchTrace(
                        "rx seq=$currentInputDebugLaunchSeq pane=${event.pane} action=${event.action} id=${event.pointerId} " +
                            "xy=${"%.3f".format(java.util.Locale.US, event.x)},${"%.3f".format(java.util.Locale.US, event.y)} " +
                            "clientTs=${event.clientTsMs} recv=${event.receivedAtElapsedMs}"
                    )
                    targetPipeline?.touchInjector?.onTouchEvent(event)
                    if (event.action == "up") { lastTouchPane = event.pane }
                    if (event.action != "move") {
                        broadcastWebDiagnostics("touch_${event.action}")
                    }
                }
                server.setTouchResetListener {
                    pipelines.values.forEach { pipeline ->
                        val shouldForceCancel =
                            pipeline.isTouchInteractionActive() ||
                                (pipeline.touchInjector?.hasTrackedPointers() == true)
                        try { pipeline.touchInjector?.release(forceFallbackCancel = shouldForceCancel, reason = "browser_touch_reset") 
                        } 
                        catch (_: Exception) {}
                    }
                    lastTouchPane = "primary"
                    logInputDebugSnapshot("touch_reset")
                }
                server.setCodecModeListener { onCodecModeRequest(it) }
                // Handle dynamic screen layout updates declaratively to update pane viewports.
                server.setLayoutUpdateListener { pipelinesArray ->
                    applyBrowserLayoutUpdate(pipelinesArray)
                }
                server.setTextInputListener { injectText(it) }
                server.setRemoteFocusHintListener { packageName, inputType, imeOptions, privateImeOptions ->
                    handleRemoteFocusHint(packageName, inputType, imeOptions, privateImeOptions)
                }
                server.setRemoteBlurHintListener {
                    handleRemoteBlurHint()
                }
                server.setKeyEventListener { injectKeyEvent(it) }
                server.setCompositionUpdateListener { bs, text -> injectCompositionUpdate(bs, text) }
                server.setAudioCodecListener { codec -> serviceScope.launch(Dispatchers.IO) { ensureAudioCaptureState(codec) } }
                server.setAudioSocketConnectedListener { audioOrchestrator?.onAudioSocketConnected() }
                server.setBrowserRearmListener {
                    serviceScope.launch {
                        onBrowserConnected()
                    }
                }
                server.setBrowserTeardownListener {
                    serviceScope.launch {
                        onBrowserDisconnected()
                    }
                }
                server.setGoHomeListener {
                    serviceScope.launch(Dispatchers.IO) {
                        Log.i(TAG, "[MirrorServer] GoHome received. Forcing home stack on all active displays.")
                        pipelines.values.forEach { pipeline ->
                            // Avoid calling binder launchHomeOnDisplay inside virtualDisplayHardwareMutex lock.
                            var hasToken = false
                            virtualDisplayHardwareMutex.withLock {
                                hasToken = (pipeline.currentVdToken() != null)
                            }
                            if (hasToken) {
                                pipeline.controller.launchHomeOnDisplay()
                            }
                            pipeline.currentApp = "HOME"; pipeline.currentWebUrl = null
                        }
                    }
                }
                server.setAppLaunchListener { pkg, cmp, pane, isVideoApp ->
                    serviceScope.launch {
                        try {
                            beginInputDebugLaunch(pane, pkg)
                            pipelines[pane]?.armTouchFocusGate(cmp ?: pkg)
                            // pipelines[pane]?.isVideoApp = isVideoApp
                            // pipelines[pane]?.launchAppFromWebLauncher(pkg, cmp)
                            // 💡 Proactively categorize the nature of the launch request here
                           val mode = if (pkg.startsWith("http")) {
                               LaunchMode.EXTERNAL_BROWSER_URL
                           } else {
                               LaunchMode.STANDARD_APP
                           }
                           val rawLaunchTarget = cmp ?: pkg
                           // Assemble the bus event envelope based on refined target details
                           val requestEvent = AppLaunchRequest(
                               packageName = pkg,
                               className = cmp,
                               pane = pane,
                               launchMode = mode, // Inject resolved launch mode
                               isVideoApp = isVideoApp
                           )

                           Log.i(TAG, "[Server Bridge] Routing request packed directly: pkg=$pkg, cmp=$cmp")

                           // Emit to the single event bus channel (Flow) to wake up observers
                           com.castla.mirror.utils.AppLaunchBus.requestLaunch(requestEvent)

                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse and emit inbound app launch packet", e)
                        }
                    }
                }
                server.setDisplayDensityListener { scale ->
                    dpiScale = scale
                    pipelines.values.forEach { pipeline ->
                        if (pipeline.controller.hasVirtualDisplay() && pipeline.width > 0 && pipeline.height > 0) {
                            serviceScope.launch {
                                pipeline.requestRebuild(
                                    reason = "display_density_change",
                                    priority = RebuildPriority.HIGH,
                                    newWidth = pipeline.width,
                                    newHeight = pipeline.height,
                                    force = true
                                )
                            }
                        }
                    }
                }
                server.setQualityReportListener { d, a, b ->
                    adaptiveBitrateManager.updateQualityMetrics(d, a, b)
                    // Active Self-Tuning Feedback loop for Content-Aware Quality Engine
                    pipelines.forEach { (name, pipeline) ->
                        if (pipeline.videoEncoder != null && pipeline.width > 0 && pipeline.height > 0) {
                            contentAwareQualityEngine.executeSelfTuningFeedback(name, pipeline, d, a)
                        }
                    }
                }
                server.setBrowserConnectionListener { connected ->
                    if (connected) {
                        cancelPendingBrowserDisconnect("browser_reconnected")
                        if (!browserConnected) { browserConnected = true; onBrowserConnected() }
                        browserConnectionListener?.invoke(true)
                    } else if (browserConnected) {
                        scheduleBrowserDisconnect()
                    } else {
                        browserConnectionListener?.invoke(false)
                    }
                }
                server.start(0)
            }
            MirrorWidgetProvider.updateAllWidgets(this)
        } catch (e: Exception) { Log.e(TAG, "Fatal error on startPipeline", e); stopSelf() }
    }

    private fun onBrowserConnected() {
        try {
            Log.i(TAG, "onBrowserConnected() - WebSocket link stabilized. Launching encoder engines.")
            powerLockManager.acquireWakeLocks()
            startVdKeepAlive()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalThrottleManager.broadcastThermalStatus(thermalThrottleManager.thermalStatus.value)

            adaptiveBitrateManager.startAllLoops()

            serviceScope.launch {
                kotlinx.coroutines.delay(200)
                isInitialRebuildTriggered = true
                val primary = pipelines["primary"] ?: return@launch
                primary.markFreshLaunchPreparation("browser_connected")
                val finalW = if (primary.width > 1) primary.width 
                             else if (primary.requestedWidth > 1) primary.requestedWidth
                             else primary.lastValidWidth.coerceAtLeast(720)
                val finalH = if (primary.height > 1) primary.height 
                             else if (primary.requestedHeight > 1) primary.requestedHeight
                             else primary.lastValidHeight.coerceAtLeast(720)
                paneVisibility["primary"] = true
                primary.setTier(DisplayTier.ACTIVE, "browser_connected")
                triggerPipelineRebuildWithPolicy(primary.name, finalW, finalH, force = true)

                pipelines["secondary"]?.let { secondary ->
                    secondary.markFreshLaunchPreparation("browser_connected_secondary")
                    if (paneVisibility["secondary"] != true) {
                        secondary.setTier(DisplayTier.SUSPENDED, "browser_connected_secondary_hidden")
                    }
                }
            }
        } catch (t: Throwable) { Log.e(TAG, "Failed onBrowserConnected", t); markTerminal(TerminalReason.BROWSER_ACTIVATION_FAILED) }

        mirrorServer?.broadcastDiagnostics()
        broadcastWebDiagnostics("browser_connected")
    }

    private var lastVisiblePaneCount = 1

    private fun applyBrowserLayoutUpdate(panes: JSONArray) {
        val paneStates = mutableListOf<Triple<String, android.util.Size, Boolean>>()
        val seen = mutableSetOf<String>()
        for (i in 0 until panes.length()) {
            val paneObj = panes.optJSONObject(i) ?: continue
            val paneId = paneObj.optString("id")
            if (paneId.isBlank()) continue
            val w = paneObj.optInt("width", 0)
            val h = paneObj.optInt("height", 0)
            val visible = paneObj.optBoolean("visible", w > 0 && h > 0)
            seen += paneId
            paneVisibility[paneId] = visible
            paneStates += Triple(paneId, android.util.Size(w, h), visible)
        }

        val visiblePanes = paneStates.filter { (_, size, visible) -> visible && size.width > 0 && size.height > 0 }
        val singleVisiblePane = if (visiblePanes.size == 1) visiblePanes.first().first else null
        val forceLayoutRealign = visiblePanes.size != lastVisiblePaneCount
        lastVisiblePaneCount = visiblePanes.size

        for ((paneId, size, visible) in paneStates) {
            val pipeline = pipelines[paneId] ?: continue
            if (visible && size.width > 0 && size.height > 0) {
                val targetTier = if (singleVisiblePane == paneId || (singleVisiblePane == null && paneId == "primary")) {
                    DisplayTier.ACTIVE
                } else {
                    DisplayTier.VISIBLE
                }
                serviceScope.launch { pipeline.setTier(targetTier, "browser_layout_visible") }
                pipeline.onViewportChange(size.width, size.height, forceLayoutRealign)
            } else {
                serviceScope.launch { pipeline.setTier(DisplayTier.SUSPENDED, "browser_layout_hidden") }
            }
        }

        pipelines.forEach { (paneId, pipeline) ->
            if (!seen.contains(paneId) && paneVisibility[paneId] == true) {
                paneVisibility[paneId] = false
                serviceScope.launch { pipeline.setTier(DisplayTier.SUSPENDED, "browser_layout_absent") }
            }
        }
    }

    private fun onBrowserDisconnected() {
        Log.w(TAG, "onBrowserDisconnected() - Target web panel dropped connection link.")
        pendingBrowserDisconnectJob = null; browserConnected = false; isInitialRebuildTriggered = false
        browserTeardownPhase = "begin"
        stopVdKeepAlive()
        
        val oldEncoders = pipelines.values.map {
            val vEnc = it.videoEncoder; val jEnc = it.jpegEncoder
            it.videoEncoder = null; it.jpegEncoder = null; it.currentEncoderSurface = null
            vEnc to jEnc
        }

        stopAppExitMonitor()
        pipelines.values.forEach { pipeline ->
            try { pipeline.touchInjector?.detachController("browser_disconnected") } catch (_: Exception) {}
            pipeline.markFreshLaunchPreparation("browser_disconnected")
            pipeline.invalidateVd("browser_disconnected")
            try { pipeline.controller.release() } catch (_: Exception) {}
        }
        
        audioOrchestrator?.stop()
        adaptiveBitrateManager.stopAllLoops()
        powerLockManager.releaseWakeLocks()
        broadcastWebDiagnostics("browser_disconnected_sync")

        serviceScope.launch(Dispatchers.IO) {
            browserTeardownPhase = "releasing"
            oldEncoders.forEach { (v, j) -> try { v?.release() } catch (_: Exception) {}; try { j?.release() } catch (_: Exception) {} }
            pipelines.values.forEach { try { it.release() } catch (_: Exception) {} }
            try { removeAllVdTasks() } catch (_: Exception) {}
            browserTeardownPhase = "released"
            broadcastWebDiagnostics("browser_disconnected_async_done")
        }

        mirrorServer?.broadcastDiagnostics()        
        broadcastWebDiagnostics("browser_disconnected")
    }

    private fun cancelPendingBrowserDisconnect(reason: String) {
        pendingBrowserDisconnectJob?.cancel()
        pendingBrowserDisconnectJob = null
        broadcastWebDiagnostics("cancel_pending_disconnect:$reason")
    }

    private fun scheduleBrowserDisconnect() {
        if (pendingBrowserDisconnectJob != null) return
        val screenOff = screenOffPolicy.isScreenOff
        broadcastWebDiagnostics("schedule_disconnect")
        pendingBrowserDisconnectJob = serviceScope.launch {
            kotlinx.coroutines.delay(DisconnectPolicy.graceMs(screenOff))
            pendingBrowserDisconnectJob = null
            broadcastWebDiagnostics("disconnect_grace_elapsed")
            if (mirrorServer?.isBrowserConnected() == true) return@launch
            if (!DisconnectPolicy.shouldTeardown(screenOff, isBrowserConnected = false)) return@launch
            if (browserConnected) { browserConnected = false; onBrowserDisconnected() }
            browserConnectionListener?.invoke(false)
        }
    }

    private fun ensureAudioCaptureState(codecOverride: String? = null) {
        audioOrchestrator?.apply { audioEnabled = pendingAudioEnabled && AudioCapture.isSupported(); browserConnected = this@MirrorForegroundService.browserConnected; ensure(codecOverride) }
    }

    private fun activeInputDisplayId(): Int {
        val targetPipeline = pipelines[lastTouchPane] ?: pipelines["primary"]
        return targetPipeline?.displayId ?: -1
    }

    private fun injectText(text: String) {
        resetImeTimeoutTimer()
        val displayId = activeInputDisplayId()
        val router = CastlaTextInputRouter.getInstance()
        val (isValid, _) = router.validateConnectionForTarget(displayId)
        if (isValid) {
            router.setRemoteTextDirty(true)
            val nextGenId = System.currentTimeMillis()
            remoteImeBridge?.dispatch(ImeCommand.CommitText(nextGenId, text))
        } else {
            serviceScope.launch(compositionDispatcher) {
                try {
                    shizukuSetup?.privilegedService?.injectText(text, displayId)
                } catch (_: Exception) {}
            }
        }
    }

    private var lastTouchPane = "primary"

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val compositionDispatcher = kotlinx.coroutines.newSingleThreadContext("composition")

    private fun injectCompositionUpdate(backspaces: Int, text: String) {
        resetImeTimeoutTimer()
        val displayId = activeInputDisplayId()
        val router = CastlaTextInputRouter.getInstance()
        val (isValid, _) = router.validateConnectionForTarget(displayId)
        if (isValid) {
            router.setRemoteTextDirty(true)
            if (text.isEmpty() && backspaces == 0) {
                remoteImeBridge?.dispatch(ImeCommand.FinishComposingText)
            } else {
                val nextGenId = System.currentTimeMillis()
                remoteImeBridge?.dispatch(
                    ImeCommand.SetComposingText(
                        compositionId = nextGenId,
                        text = text,
                        selectionStart = -1,
                        selectionEnd = -1
                    )
                )
            }
        } else {
            serviceScope.launch(compositionDispatcher) {
                try {
                    shizukuSetup?.privilegedService?.injectComposingText(backspaces, text, displayId)
                } catch (_: Exception) {}
            }
        }
    }

    private fun injectKeyEvent(keyCode: Int) {
        resetImeTimeoutTimer()
        val displayId = activeInputDisplayId()
        val router = CastlaTextInputRouter.getInstance()
        val (isValid, _) = router.validateConnectionForTarget(displayId)
        if (isValid && keyCode == 67) {
            router.setRemoteTextDirty(true)
            remoteImeBridge?.dispatch(ImeCommand.DeleteSurroundingText(beforeLength = 1, afterLength = 0))
        } else if (isValid && keyCode == 66) {
            router.setRemoteTextDirty(true)
            remoteImeBridge?.dispatch(ImeCommand.PerformEnter)
        } else {
            serviceScope.launch(compositionDispatcher) {
                try {
                    shizukuSetup?.privilegedService?.execCommand(
                        if (displayId > 0) "input -d $displayId keyevent $keyCode" else "input keyevent $keyCode"
                    )
                } catch (_: Exception) {}
            }
        }
    }
    
    private fun buildExternalBrowserCommand(displayId: Int, url: String, browserComponent: String): String =
        "am start --display $displayId -f 0x18000000 -a android.intent.action.VIEW -d ${escapeShellArg(url)} -n ${escapeShellArg(browserComponent)}".trim()

    private fun ensureShizukuSetup(): ShizukuSetup? {
        shizukuSetup?.let { return it }
        return ShizukuSetup().also { it.init(this, bindService = true); shizukuSetup = it; startReconnectObserver(it) }
    }

    private fun startReconnectObserver(setup: ShizukuSetup) {
        if (reconnectJob != null) return
        reconnectJob = serviceScope.launch {
            val tracker = BinderConnectionTracker()
            setup.serviceConnected.collect { connected ->
                if (connected) {
                    val svc = setup.privilegedService
                    if (svc != null) {
                        try {
                            // Self-healing: recover any pending crashes safely via FSM startup recovery
                            com.castla.mirror.input.ImeSwitchManager.sendEvent(
                                this@MirrorForegroundService,
                                com.castla.mirror.input.ImeEvent.AppStartupRecovery
                            ) { cmd ->
                                svc.execCommand(cmd)
                            }
                            // Silent prep: set Castla IME as the default keyboard programmatically on startup
                            com.castla.mirror.input.ImeSwitchManager.sendEvent(
                                this@MirrorForegroundService,
                                com.castla.mirror.input.ImeEvent.RemoteTextFocus
                            ) { cmd ->
                                svc.execCommand(cmd)
                            }
                            Log.i(TAG, "Programmatically prepared Castla IME silently on service connected.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed programmatically preparing IME settings", e)
                        }
                    }
                }
                when (if (connected) tracker.onConnected() else tracker.onDisconnected()) {
                    BinderConnectionTracker.Transition.Disconnect -> {
                        pipelines.values.forEach {
                            try { it.touchInjector?.detachController("binder_disconnect") } catch (_: Exception) {}
                            it.controller.attachPrivilegedService(null)
                        }
                    }
                    BinderConnectionTracker.Transition.Reconnect -> handleShizukuReconnect(setup)
                    else -> {}
                }
            }
        }
    }

    private fun handleShizukuReconnect(setup: ShizukuSetup) {
        if (!browserConnected) return
        val svc = setup.privilegedService ?: return
        
        // Re-register Binder Death Token upon reconnection to prevent virtual display leaks if the app is forcefully killed afterward
        try {
            svc.registerDeathToken(binder)
            Log.i(TAG, "[Shizuku Safeguard] Successfully re-registered Service Binder Death Token during reconnect.")
        } catch (e: Exception) {
            Log.e(TAG, "[Shizuku Safeguard] Failed to re-register Binder Death Token during reconnect", e)
        }
        
        Log.i(TAG, "[Shizuku] Privileged core reconnected. Restoring context mappings symmetrically.")
        pipelines.values.forEach { it.controller.attachPrivilegedService(svc) }
        
        pipelines.values.forEach { pipeline ->
            val surf = pipeline.currentEncoderSurface ?: return@forEach
            if (pipeline.width <= 0 || pipeline.height <= 0) return@forEach
            serviceScope.launch(Dispatchers.IO) {
                // Minimize lock scope to prevent blocking coroutine threads while restoring content via binder.
                var generation: Long = -1L
                var displayId = -1
                var hasVd = false
                virtualDisplayHardwareMutex.withLock {
                    pipeline.controller.createVirtualDisplay(pipeline.width, pipeline.height, computeVirtualDisplayDpi(pipeline.width, pipeline.height), surf)
                    if (pipeline.controller.hasVirtualDisplay()) {
                        displayId = pipeline.controller.getDisplayId()
                        generation = pipeline.markVdCreated(displayId, "shizuku_reconnect")
                        pipeline.touchInjector?.updateController { touchEvent, event ->
                            val accepted = pipeline.controller.injectMotionEventWithResult(event)
                            pipeline.recordInjectionResult(event, accepted)
                            if (!accepted) {
                                pipeline.handleInjectionRejected(event.actionMasked, event.pointerCount)
                            }
                            accepted
                        }
                        hasVd = true
                    }
                }
                if (hasVd && generation != -1L && displayId >= 0) {
                    try {
                        svc.wakeUpDisplay(displayId)
                        if (currentCodecMode != "mjpeg") {
                            pipeline.videoEncoder?.requestKeyFrame()
                        }
                        CastlaTextInputRouter.getInstance().triggerRecoveryFocusNudge()
                    } catch (e: Exception) {
                        Log.w(TAG, "[Shizuku] Soft recovery after reconnect failed for pane=${pipeline.name}", e)
                    }
                }
            }
        }
    }

    private suspend fun trySetupVirtualDisplay(width: Int, height: Int, surface: Surface): Boolean = withContext(vdDispatcher) {
        shizukuSetupMutex.withLock {
            val setup = ensureShizukuSetup() ?: return@withContext false
            if ((setup.privilegedService == null && !setup.isBindingInProgress) || !setup.isAvailable()) setup.forceResetBindingState()
            if (!setup.isAvailable() || !setup.hasPermission()) return@withContext false

            setup.bindPrivilegedService()
            var isStable = false; val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < BIND_WAIT_BUDGET_MS) {
                if (setup.serviceConnected.value && setup.privilegedService != null) {
                    val svc = setup.privilegedService
                    if (svc != null && (runBinderSafe(1000L) { svc.asBinder().isBinderAlive } == true)) {
                        kotlinx.coroutines.delay(250)
                        if (runBinderSafe(1000L) { svc.asBinder().isBinderAlive } == true) { isStable = true; break }
                    }
                }
                kotlinx.coroutines.delay(100)
            }

            if (!isStable) {
                Log.e(TAG, "[Shizuku Connect] Binding target failed to stabilize. Evicting active graphics buffers.")
                shizukuBindRetryCount++; setup.forceResetBindingState()
                
                try {
                    pipelines.values.forEach { pipeline ->
                        try { pipeline.touchInjector?.detachController("shizuku_bind_unstable") } catch (_: Exception) {}
                        runBinderSafe { pipeline.controller.releaseVirtualDisplay() }
                        pipeline.controller.attachPrivilegedService(null)
                    }
                } catch (_: Exception) {}
                
                if (shizukuBindRetryCount < SHIZUKU_MAX_RETRIES) {
                    kotlinx.coroutines.delay(2000)
                    if (browserConnected) {
                        val target = pipelines.values.firstOrNull()
                        target?.currentEncoderSurface?.let { return@withLock trySetupVirtualDisplay(target.width, target.height, it) }
                    }
                }
                return@withLock false
            }

            shizukuBindRetryCount = 0
            val svc = setup.privilegedService ?: return@withLock false
            try { runBinderSafe { svc.enableStayAwakeMode() } } catch (_: Exception) {}

            try {
                svc.registerDeathToken(binder)
                Log.i(TAG, "[Shizuku Safeguard] Successfully registered active Service Binder Death Token into remote root process.")
            } catch (e: Exception) {
                Log.e(TAG, "[Shizuku Safeguard] Failed to pass Binder Death Token to remote process context", e)
            }            

            pipelines.values.forEach { pipeline ->
                try { runBinderSafe { pipeline.controller.release() } } catch (_: Exception) {}
                pipeline.controller.attachPrivilegedService(svc)
            }
            
            var globalSuccess = true
            pipelines.values.forEach { pipeline ->
                val w = if (pipeline.width > 0) pipeline.width else width
                val h = if (pipeline.height > 0) pipeline.height else height
                val dpi = computeVirtualDisplayDpi(w, h)
                // Minimize lock scope to prevent blocking binder calls like restoreContentLocked within the mutex.
                var activeId = -1
                var generation = -1L
                var success = false
                virtualDisplayHardwareMutex.withLock {
                    success = runBinderSafe {
                        pipeline.controller.createVirtualDisplay(w, h, dpi, pipeline.currentEncoderSurface ?: surface)
                        pipeline.controller.hasVirtualDisplay()
                    } ?: false
                    if (success) {
                        activeId = pipeline.controller.getDisplayId()
                        generation = pipeline.markVdCreated(activeId, "try_setup")
                        pipeline.touchInjector?.updateController { touchEvent, event ->
                            val accepted = pipeline.controller.injectMotionEventWithResult(event)
                            pipeline.recordInjectionResult(event, accepted)
                            if (!accepted) {
                                pipeline.handleInjectionRejected(event.actionMasked, event.pointerCount)
                            }
                            accepted
                        }
                    }
                }
                if (success && activeId >= 0 && generation != -1L) {
                    try {
                        svc.wakeUpDisplay(activeId)
                        if (currentCodecMode != "mjpeg") {
                            pipeline.videoEncoder?.requestKeyFrame()
                        }
                        CastlaTextInputRouter.getInstance().triggerRecoveryFocusNudge()
                    } catch (e: Exception) {
                        Log.w(TAG, "[VDRebuild] Soft recovery failed for pane=${pipeline.name}", e)
                    }
                    Log.i(TAG, "[VDRebuild] Sub-session core mounted safely. Pane: (${pipeline.name}), Id: $activeId")
                } else if (!success) {
                    Log.e(TAG, "[VDRebuild] Failed to create virtual display for pane (${pipeline.name})")
                    globalSuccess = false
                }
            }
            if (globalSuccess) { startVdKeepAlive(); serviceScope.launch(Dispatchers.IO) { setup.ensureShizukuHardened() } }
            globalSuccess
        }
    }

    /**
     * [Orchestrator Layer] Business rule exception detection and full teardown execution control
     */
    fun triggerPipelineRebuildWithPolicy(name: String, w: Int, h: Int, force: Boolean = false, forceSingle: Boolean = false) {
        val pipeline = pipelines[name] ?: return
        serviceScope.launch {
            try {
                pipeline.requestRebuild(
                    reason = "orchestrator_policy",
                    priority = RebuildPriority.HIGH,
                    newWidth = w,
                    newHeight = h,
                    force = force,
                    forceSingle = forceSingle
                )
            } catch (t: Throwable) {
                Log.e(TAG, "[Orchestrator] Symmetrical system caught failure during rebuild from pane: $name", t)
                
                // Count the number of active VirtualDisplay hardware devices running globally
                val totalActiveVdCount = pipelines.values.count { it.displayId >= 0 && it.controller.hasVirtualDisplay() }

                if (totalActiveVdCount > 0) {
                    Log.w(TAG, "[Orchestrator] Active hardware count ($totalActiveVdCount) survives. Releasing failed loop: $name")
                    pipeline.release(forcePhysical = true)
                } else {
                    Log.e(TAG, "[Orchestrator] FATAL: Zero active VirtualDisplay frames exist in total map pool. Evicting service context.")
                    markTerminal(TerminalReason.VD_RECREATE_FAILED)
                }
            }
        }
    }

    private fun onCodecModeRequest(mode: String) {
        val anyJpegEncoderActive = pipelines.values.any { it.jpegEncoder != null }

        // Check for encoder profile mismatch between active VideoEncoder and cached preferredProfile.
        // We evaluate profile mismatches regardless of video socket existence since the profile
        // preference is now cached persistently via control channel messages.
        val mismatchedPipelines = pipelines.values.filter { pipeline ->
            val encoder = pipeline.videoEncoder
            if (encoder == null) {
                false
            } else {
                val preferred = mirrorServer?.getPreferredProfile(pipeline.name) ?: "High"
                val actual = encoder.preferredProfile
                !preferred.equals(actual, ignoreCase = true)
            }
        }

        val hasProfileMismatch = mismatchedPipelines.isNotEmpty()

        if (!CodecModeTransition.shouldApply(mode, currentCodecMode, anyJpegEncoderActive) && !hasProfileMismatch) {
            return
        }

        val isCodecSwitch = CodecModeTransition.shouldApply(mode, currentCodecMode, anyJpegEncoderActive)
        currentCodecMode = mode
        Log.i(TAG, "Codec transmission mode request processed. mode=$mode, isCodecSwitch=$isCodecSwitch, hasProfileMismatch=$hasProfileMismatch")

        val allDimensionsUnset = pipelines.values.all { it.width == 0 || it.height == 0 }
        if (allDimensionsUnset) {
            Log.i(TAG, "All canvas layout dimensions not yet set (0x0) — deferring pipeline build")
            return
        }

        Log.i(TAG, "Delegating to dynamic pipeline rebuild loop chain")
        serviceScope.launch {
            pipelines.values.forEach { pipeline ->
                // We rebuild a pipeline if:
                // 1. It is a global codec switch (which affects all pipelines)
                // 2. OR this specific pipeline has a profile mismatch
                val needsRebuild = isCodecSwitch || mismatchedPipelines.contains(pipeline)
                if (needsRebuild && pipeline.width > 0 && pipeline.height > 0) {
                    triggerPipelineRebuildWithPolicy(pipeline.name, pipeline.width, pipeline.height, force = true)
                }
            }
        }
    }

    private fun tryGrantAudioCapturePermission() {
        try {
            val setup = shizukuSetup
            val service = setup?.privilegedService
            if (setup != null && service != null && setup.isAvailable() && setup.hasPermission()) {
                val pkg = packageName
                val result = service.execCommand("appops set $pkg CAPTURE_AUDIO_OUTPUT allow")
                Log.i(TAG, "CAPTURE_AUDIO_OUTPUT grant via appops: $result")
                val result2 = service.execCommand("pm grant $pkg android.permission.CAPTURE_AUDIO_OUTPUT")
                Log.i(TAG, "CAPTURE_AUDIO_OUTPUT grant via pm: $result2")
            } else {
                Log.i(TAG, "Skipping audio capture permission grant: privileged service not connected")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to grant CAPTURE_AUDIO_OUTPUT via appops", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mirror Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.castla.mirror.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_STOP).apply { setPackage(packageName) },
            PendingIntent.FLAG_IMMUTABLE
        )
        val restoreImePending = PendingIntent.getService(
            this, 2,
            Intent(this, MirrorForegroundService::class.java).apply { action = ACTION_RESTORE_IME },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Castla")
            .setContentText("Streaming to Tesla")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop Mirroring", stopPending)
            .addAction(android.R.drawable.ic_menu_edit, "Restore Keyboard", restoreImePending)
            .build()
    }

    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == ACTION_STOP) context.startService(Intent(context, MirrorForegroundService::class.java).apply { action = ACTION_STOP })
        }
    }

    private fun computeVirtualDisplayDpi(width: Int, height: Int): Int = StreamMath.applyDensityScale(StreamMath.calculateDpi(minOf(width, height)), dpiScale)
    private suspend fun removeAllVdTasks() = withContext(Dispatchers.IO) { pipelines.values.forEach { cleanupDisplay(it.displayId) } }

    private suspend fun cleanupDisplay(displayId: Int) = withContext(Dispatchers.IO) {
        if (displayId < 0) return@withContext
        val service = pipelines.values.firstOrNull()?.controller?.getPrivilegedService() ?: return@withContext
        try {
            runBinderSafe { service.launchHomeOnDisplay(displayId) }
            val runningTasks = runBinderSafe { service.getRunningTasksOnDisplay(displayId) } ?: emptyList()
            val packagesToStop = mutableSetOf<String>()
            for (task in runningTasks) {
                val pkg = task.substringBefore('/').takeIf { it.contains('.') }
                if (pkg != null && pkg != packageName && !pkg.contains("com.castla.mirror") && !pkg.startsWith("com.android.launcher") && pkg != "com.android.settings") packagesToStop.add(pkg)
            }
            packagesToStop.forEach { pkg -> runBinderSafe { service.execCommand("am force-stop $pkg") } }
        } catch (_: Exception) {}
    }

    private suspend fun forceStopAppIfNeeded(packageName: String) {
        val pkg = packageName.substringBefore('/')
        if (pkg.isBlank() || pkg == "HOME" || pkg == "com.android.settings" || pkg == applicationContext.packageName) return
        try {
            val service = pipelines.values.firstOrNull()?.controller?.getPrivilegedService() ?: return
            val matchingTaskIds = try { runBinderSafe(1000L) { service.getTaskIdsForPackage(pkg).toList() } ?: emptyList() } catch (_: Exception) { emptyList() }
            matchingTaskIds.forEach { try { service.removeTask(it) } catch (_: Exception) {} }
            if (!BROWSER_PACKAGES.contains(pkg)) service.execCommand("am force-stop $pkg")
        } catch (_: Exception) {}
    }

    private val BROWSER_PACKAGES = setOf("com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.microsoft.emmx")
    private fun markTerminal(reason: TerminalReason) { if (terminalReason.compareAndSet(null, reason)) requestStopAsync("terminal_${reason.name.lowercase()}") }
    private fun escapeShellArg(value: String): String = "'" + value.replace("'", "'\''") + "'"
    private fun resolveLaunchComponent(packageOrComponent: String): String? {
        if (packageOrComponent.contains('/')) return packageOrComponent
        return try { packageManager.getLaunchIntentForPackage(packageOrComponent)?.component?.flattenToShortString() } catch (_: Exception) { null }
    }
    private fun normalizeLaunchTarget(packageOrComponent: String): String = resolveLaunchComponent(packageOrComponent) ?: packageOrComponent

    private fun buildShellLaunchCommand(
        displayId: Int,
        packageOrComponent: String,
        extraKey: String? = null,
        extraValue: String? = null,
        reorderToFront: Boolean = false
    ): String {
        val resolvedComponent = resolveLaunchComponent(packageOrComponent)
        val launchTarget = resolvedComponent ?: packageOrComponent
        val flags = if (reorderToFront) "0x10020000" else "0x10200000"
        return buildString {
            append("am start --display $displayId -f $flags ")
            if (resolvedComponent != null) {
                append("-n ${escapeShellArg(resolvedComponent)} ")
            } else {
                append("-a android.intent.action.MAIN -c android.intent.category.LAUNCHER ")
                append("-p ${escapeShellArg(launchTarget)} ")
            }
            if (!extraKey.isNullOrEmpty() && extraValue != null) {
                append("--es $extraKey ${escapeShellArg(extraValue)} ")
            }
        }.trim()
    }

    private fun verifySurfaceAndFallback(pipeline: MirroringPipeline, service: IPrivilegedService, displayId: Int, pkg: String, taskIds: List<Int>, packageOrComponent: String, extraKey: String?, extraValue: String?) {
        // ### 수정 시작 ###
        // Clean package check without hardcoded maps filter
        if (pkg.contains("com.castla.mirror") || pkg == "HOME" || pkg.isBlank()) return
        // ### 수정 끝 ###
        
        // Cancel the previous active fallback watchdog job to refresh the 5500ms grace period.
        // This prevents race condition and false positives where a subsequent fast layout rebuild
        // or concurrent launch request incorrectly triggers cold-start force stop.
        if (pipeline.activeFallbackJob?.isActive == true) {
            pipeline.debugFallbackCancels += 1
        }
        pipeline.activeFallbackJob?.cancel()
        
        pipeline.debugFallbackStarts += 1
        pipeline.activeFallbackJob = serviceScope.launch(Dispatchers.IO) {
            // Wait for activity manager to settle down task placement and allow the first graphic frame to render.
            // Increase stabilization grace period to 5.5s to comfortably accommodate heavy apps like Google Maps on cold start.
            kotlinx.coroutines.delay(5500L)
            try {
                if (pipeline.isTouchInteractionActive()) {
                    return@launch
                }
                val runningTasks = try { service.getRunningTasksOnDisplay(displayId) } catch (e: Exception) {
                    Log.w(TAG, "[Fallback] Failed to retrieve running tasks on Display $displayId: ${e.message}")
                    null
                }
                
                // Absent Detection: Verify if the target app has successfully entered the virtual display's task stack.
                // If it's completely missing from the running tasks on this display, it's a guaranteed launch failure.
                val isAbsent = runningTasks == null || runningTasks.none { it.contains(pkg) }
                
                // Stagnation Detection: Verify if the app has failed to render its very first graphic frame (lastFrameRenderedTime == 0L)
                // within the 5.5-second launch grace period due to graphics lockup or initialization freeze.
                // Already rendered static scenes (lastFrameRenderedTime > 0L) are excluded from recovery triggers.
                val isStagnated = !isAbsent && (pipeline.lastFrameRenderedTime == 0L)
                
                // ### 수정 시작 ###
                if (isStagnated && !isAbsent) {
                    Log.i(TAG, "[Fallback] Watchdog skipped: app ($pkg) exists, first frame delayed. displayId=$displayId")
                    return@launch
                }
                
                if (isAbsent) {
                    Log.w(TAG, "[Fallback] Watchdog detected missing task ($pkg) on Display $displayId; soft recovery only. Skipping force-stop / am start.")
                    try {
                        service.wakeUpDisplay(displayId)
                        if (currentCodecMode != "mjpeg") {
                            pipeline.videoEncoder?.requestKeyFrame()
                        }
                        val router = CastlaTextInputRouter.getInstance()
                        router.triggerRecoveryFocusNudge()
                    } catch (e: Exception) {
                        Log.e(TAG, "[Fallback] Soft recovery failed: ${e.message}")
                    }
                }
                // ### 수정 끝 ###
            } catch (e: Exception) {
                Log.e(TAG, "[Fallback] Critical error occurred inside surface verification coroutine: ${e.message}", e)
            } finally {
                // Safely clear the active fallback job reference if this job finished executing normally
                if (pipeline.activeFallbackJob == coroutineContext[kotlinx.coroutines.Job]) {
                    pipeline.activeFallbackJob = null
                }
            }
        }
    }

    // ==========================================
    // ENCAPSULATED VIRTUAL DISPLAY PIPELINE
    // ==========================================
    inner class MirroringPipeline(val name: String, val displayName: String) {
        val controller = VirtualDisplayController(displayName)
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        private val releasing = java.util.concurrent.atomic.AtomicBoolean(false)

        var width = 0; var height = 0; var displayId = -1
        val vdGeneration = java.util.concurrent.atomic.AtomicLong(0)
        
        // Timestamp of the last processed keyframe request to prevent coroutine and binder flood
        @Volatile var lastKeyframeRequestTime = 0L
        // Backup fields to remember the last valid viewport dimensions for self-healing recovery
        @Volatile var lastValidWidth: Int = 384
        @Volatile var lastValidHeight: Int = 672
        
        // State guards to prevent concurrent self-healing re-entry which triggers duplicate am start shell command floods
        @Volatile var isSelfHealingInProgress = false
        @Volatile var activeFallbackJob: kotlinx.coroutines.Job? = null
        @Volatile var lastFrameRenderedTime = 0L
        @Volatile var lastMoveRejectLoggedAt = 0L
        
        private val encoderSession = java.util.concurrent.atomic.AtomicLong(0)

        var videoEncoder: VideoEncoder? = null; var jpegEncoder: JpegEncoder? = null; var currentEncoderSurface: Surface? = null
        var pipelineState = PipelineState.IDLE; var pendingRebuildRequest: RebuildRequest? = null
        @Volatile var displayTier: DisplayTier = if (name == "primary") DisplayTier.ACTIVE else DisplayTier.SUSPENDED

        var currentBitrate = 0; var currentApp = ""; var currentWebUrl: String? = null
        @Volatile var requiresFreshLaunchPreparation = true
        @Volatile var lastPreparedTargetPackage = ""
        @Volatile var lastTouchFocusRecoveryAt = 0L
        @Volatile var debugLaunchSeq = 0
        @Volatile var debugTopTaskMisses = 0
        @Volatile var debugFocusRecoveryAttempts = 0
        @Volatile var debugFocusRecoveryEscalations = 0
        @Volatile var debugRebuildRequests = 0
        @Volatile var debugRebuildExecutions = 0
        @Volatile var debugResizeSchedules = 0
        @Volatile var debugResizeCancels = 0
        @Volatile var debugFallbackStarts = 0
        @Volatile var debugFallbackCancels = 0
        @Volatile var debugEncoderCreates = 0
        @Volatile var debugEncoderReleases = 0
        @Volatile var debugInjectionRejects = 0
        @Volatile var debugInjectionRecoveries = 0
        @Volatile var debugInjectAttempts = 0
        @Volatile var debugInjectAccepted = 0
        @Volatile var debugInjectRejected = 0
        @Volatile var debugMoveInjectAttempts = 0
        @Volatile var debugMoveInjectAccepted = 0
        @Volatile var debugMoveInjectRejected = 0
        @Volatile var debugFirstInjectFailureProbeLogged = false
        @Volatile var lastInjectionRecoveryAt = 0L
        @Volatile var lastServiceMutationAt = 0L
        @Volatile var lastServiceMutationReason = "init"
        @Volatile var activeTouchCount = 0
        @Volatile var lastTouchEventAt = 0L
        @Volatile var touchFocusGateArmedAt = 0L
        @Volatile var touchFocusGateTarget = ""
        @Volatile var touchFocusGateLastProbe = ""
        @Volatile var touchFocusGateEscalatedAt = 0L
        @Volatile var touchFocusGateNudgedAt = 0L
        @Volatile var touchFocusGateNudgeInFlight = false
        @Volatile var touchFocusGateNudgeJob: Job? = null
        @Volatile var consecutiveInjectionRejects = 0
        private val gatedPointerIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        var isVideoApp = false
        var autoResolution: Boolean = false
        var autoFps: Boolean = false
        var currentMaxHeight: Int = 720
        var targetFps: Int = 30

        var touchInjector: TouchInjector? = null; var resizeJob: Job? = null
        var requestedWidth: Int = 0; var requestedHeight: Int = 0

        private val pipelineMutex = Mutex()

        fun isEncoderRunning(): Boolean {
            return if (currentCodecMode == "mjpeg") jpegEncoder != null else videoEncoder != null
        }

        fun markInputDebugLaunch(launchSeq: Int) {
            debugLaunchSeq = launchSeq
            debugTopTaskMisses = 0
            debugFocusRecoveryAttempts = 0
            debugFocusRecoveryEscalations = 0
            debugRebuildRequests = 0
            debugRebuildExecutions = 0
            debugResizeSchedules = 0
            debugResizeCancels = 0
            debugFallbackStarts = 0
            debugFallbackCancels = 0
            debugEncoderCreates = 0
            debugEncoderReleases = 0
            debugInjectionRejects = 0
            debugInjectionRecoveries = 0
            debugInjectAttempts = 0
            debugInjectAccepted = 0
            debugInjectRejected = 0
            debugMoveInjectAttempts = 0
            debugMoveInjectAccepted = 0
            debugMoveInjectRejected = 0
            debugFirstInjectFailureProbeLogged = false
            consecutiveInjectionRejects = 0
//            touchInjector?.markDebugLaunch(launchSeq)
            Log.i(
                TAG,
                "[$name Pipeline] Input debug launch reset launchSeq=$launchSeq displayId=$displayId app=$currentApp"
            )
        }

        fun markFreshLaunchPreparation(reason: String) {
            requiresFreshLaunchPreparation = true
            lastPreparedTargetPackage = ""
            lastFrameRenderedTime = 0L
            lastKeyframeRequestTime = 0L
            activeFallbackJob?.cancel()
            activeFallbackJob = null
            mirrorServer?.clearCachedSpsPps(name)
            mirrorServer?.pauseStream(name, displayId, width, height)
            Log.i(
                TAG,
                "[$name Pipeline] Marked fresh launch preparation required. reason=$reason displayId=$displayId currentApp=$currentApp"
            )
        }

        fun recordInjectionResult(motionEvent: android.view.MotionEvent, accepted: Boolean) {
            val action = motionEvent.actionMasked
            debugInjectAttempts += 1
            if (accepted) debugInjectAccepted += 1 else debugInjectRejected += 1
            if (accepted && action != android.view.MotionEvent.ACTION_MOVE) {
                consecutiveInjectionRejects = 0
            }

            if (action == android.view.MotionEvent.ACTION_MOVE) {
                debugMoveInjectAttempts += 1
                if (accepted) debugMoveInjectAccepted += 1 else debugMoveInjectRejected += 1
            }
        }

        fun noteTouchEvent(action: String) {
            lastTouchEventAt = android.os.SystemClock.elapsedRealtime()
            when (action) {
                "down" -> {
                    activeTouchCount += 1
                    cancelFallbackDuringTouch("touch_down")
                }
                "up", "cancel" -> {
                    activeTouchCount = (activeTouchCount - 1).coerceAtLeast(0)
                }
            }
        }

        fun isTouchInteractionActive(): Boolean {
            if (activeTouchCount > 0) return true
            val lastAt = lastTouchEventAt
            if (lastAt <= 0L) return false
            return android.os.SystemClock.elapsedRealtime() - lastAt <= 250L
        }

        fun armTouchFocusGate(target: String) {
            touchFocusGateArmedAt = android.os.SystemClock.elapsedRealtime()
            touchFocusGateTarget = target
            touchFocusGateLastProbe = "disabled:$target"
            touchFocusGateEscalatedAt = 0L
            touchFocusGateNudgedAt = 0L
            touchFocusGateNudgeInFlight = false
            touchFocusGateNudgeJob?.cancel()
            touchFocusGateNudgeJob = null
            gatedPointerIds.clear()
            Log.i(TAG, "[FocusTrace] gate_disabled pane=$name target=$target displayId=$displayId")
        }

        private fun triggerInternalFocusNudge(
            activeId: Int,
            reason: String,
        ) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (touchFocusGateNudgeInFlight) return
            if (now - touchFocusGateNudgedAt < 250L) return

            val targetWidth = when {
                width > 0 -> width
                requestedWidth > 0 -> requestedWidth
                lastValidWidth > 0 -> lastValidWidth
                else -> 0
            }
            val targetHeight = when {
                height > 0 -> height
                requestedHeight > 0 -> requestedHeight
                lastValidHeight > 0 -> lastValidHeight
                else -> 0
            }
            if (targetWidth <= 0 || targetHeight <= 0) return

            touchFocusGateNudgeInFlight = true
            touchFocusGateNudgedAt = now
            val tapX = (targetWidth * 0.5f).coerceAtLeast(8f)
            val tapY = (targetHeight * 0.5f).coerceAtLeast(8f)
            val internalPointerId = 9001

            serviceScope.launch {
                var downAccepted = false
                var upAccepted = false
                try {
                    Log.i(
                        TAG,
                        "[FocusTrace] inject_focus_nudge pane=$name displayId=$activeId reason=$reason x=${"%.1f".format(java.util.Locale.US, tapX)} y=${"%.1f".format(java.util.Locale.US, tapY)}"
                    )
                    controller.getPrivilegedService()?.wakeUpDisplay(activeId)
                    val downTime = android.os.SystemClock.uptimeMillis()
                    val downEvent = android.view.MotionEvent.obtain(
                        downTime,
                        downTime,
                        android.view.MotionEvent.ACTION_DOWN,
                        tapX,
                        tapY,
                        0
                    )
                    val upEvent = android.view.MotionEvent.obtain(
                        downTime,
                        downTime + 24L,
                        android.view.MotionEvent.ACTION_UP,
                        tapX,
                        tapY,
                        0
                    )
                    try {
                        downAccepted = controller.injectMotionEventWithResult(downEvent)
                        delay(24L)
                        upAccepted = controller.injectMotionEventWithResult(upEvent)
                    } finally {
                        downEvent.recycle()
                        upEvent.recycle()
                    }
                    Log.i(
                        TAG,
                        "[FocusTrace] inject_focus_nudge_result pane=$name displayId=$activeId pointerId=$internalPointerId down=$downAccepted up=$upAccepted reason=$reason"
                    )
                    
                    // [FocusTrace] If regular binder injection fails or is rejected, 
                    // execute raw "input" shell command as the ultimate fallback to force display focus!
                    if (!downAccepted) {
                        Log.w(TAG, "[FocusTrace] Binder nudge failed on display $activeId. Invoking fallback raw shell input tap.")
                        try {
                            controller.getPrivilegedService()?.execCommand("input -d $activeId tap $tapX $tapY")
                        } catch (e: Exception) {
                            Log.w(TAG, "Raw shell input nudge fallback failed", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[FocusTrace] inject_focus_nudge_failed pane=$name displayId=$activeId reason=$reason", e)
                } finally {
                    touchFocusGateNudgeInFlight = false
                }
            }
        }

        fun shouldDeferTouchForFocusGate(event: TouchEvent): Boolean {
            touchFocusGateArmedAt = 0L
            touchFocusGateNudgedAt = 0L
            touchFocusGateNudgeInFlight = false
            touchFocusGateNudgeJob?.cancel()
            touchFocusGateNudgeJob = null
            gatedPointerIds.clear()
            touchFocusGateLastProbe = "disabled_passthrough"
            return false
        }

        fun focusGateSummary(): String {
            val armed = touchFocusGateArmedAt > 0L
            return "armed=$armed gatedPointers=${gatedPointerIds.size} probe=${touchFocusGateLastProbe}"
        }

        private fun cancelFallbackDuringTouch(reason: String) {
            val job = activeFallbackJob ?: return
            if (!job.isActive) return
            debugFallbackCancels += 1
            activeFallbackJob = null
            job.cancel()
        }

        fun markServiceMutation(reason: String) {
            lastServiceMutationAt = android.os.SystemClock.elapsedRealtime()
            lastServiceMutationReason = reason
        }

        fun recentServiceActionSummary(): String {
            val at = lastServiceMutationAt
            if (at <= 0L) return "none"
            val age = (android.os.SystemClock.elapsedRealtime() - at).coerceAtLeast(0L)
            return "$lastServiceMutationReason@${age}ms"
        }

        suspend fun setTier(next: DisplayTier, reason: String) {
            if (displayTier == next && (next == DisplayTier.ACTIVE || next == DisplayTier.VISIBLE)) return
            displayTier = next
            Log.i(TAG, "[$name Pipeline] Display tier -> $next ($reason)")
            when (next) {
                DisplayTier.ACTIVE, DisplayTier.VISIBLE -> {
                    val targetW = requestedWidth.takeIf { it > 1 } ?: lastValidWidth.coerceAtLeast(720)
                    val targetH = requestedHeight.takeIf { it > 1 } ?: lastValidHeight.coerceAtLeast(720)
                    if (!isEncoderRunning() && displayId >= 0 && browserConnected) {
                        requestRebuild("tier_resume", RebuildPriority.HIGH, targetW, targetH, force = true)
                    }
                }
                DisplayTier.SUSPENDED, DisplayTier.PARKED -> {
                    suspendEncoder(reason)
                    mirrorServer?.broadcastDiagnostics()
                }
            }
        }

        suspend fun suspendEncoder(reason: String) {
            Log.i(TAG, "[$name Pipeline] Suspending encoder and stream while preserving VD/app session. Reason=$reason")
            if (resizeJob?.isActive == true) debugResizeCancels += 1
            resizeJob?.cancel()
            if (videoEncoder != null || jpegEncoder != null) debugEncoderReleases += 1
            videoEncoder?.release(); videoEncoder = null
            jpegEncoder?.release(); jpegEncoder = null
            currentEncoderSurface = null
            lastFrameRenderedTime = 0L
            try { touchInjector?.detachController("suspend_encoder") } catch (_: Exception) {}
            if (displayId >= 0) {
                runBinderSafe { controller.setSurface(null) }
            }
            mirrorServer?.setKeyframeRequester(name) { _ -> }
            mirrorServer?.pauseStream(name, displayId, width, height)
            adaptiveBitrateManager.rebalanceBitrates()
        }

        fun onViewportChange(w: Int, h: Int, forceLayoutRealign: Boolean = false) {
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "[$name Pipeline] Viewport hidden or invalid -> suspending encoder without destroying VD.")
                resizeJob?.cancel(); serviceScope.launch { setTier(DisplayTier.SUSPENDED, "viewport_invalid") }; return
            }
            
            // Check if this is the initial setup phase. If so, bypass the 500ms debounce delay 
            // to instantly rebuild virtual display surface layout, preventing unaligned viewports during app startup.
            val isFirstSetup = requestedWidth <= 0 || displayId < 0
            
            // Cache the latest valid viewport sizes for runtime self-healing recovery
            lastValidWidth = w
            lastValidHeight = h
            
            requestedWidth = w; requestedHeight = h
            if (resizeJob?.isActive == true) debugResizeCancels += 1
            resizeJob?.cancel()
            debugResizeSchedules += 1
            resizeJob = serviceScope.launch { 
                if (!isFirstSetup) {
                    kotlinx.coroutines.delay(120L) 
                }
                val forceResume = !isEncoderRunning()
                requestRebuild(
                    reason = "viewport_change",
                    priority = if (isFirstSetup || forceResume) RebuildPriority.HIGH else RebuildPriority.NORMAL,
                    newWidth = w,
                    newHeight = h,
                    force = forceResume,
                    forceSingle = forceLayoutRealign
                )
            }
        }

        
        // Rebuild is non-blocking and always enqueues the latest request to the sequential
        // hardware worker. We intentionally do not collapse requests behind an active rebuild,
        // because split-ratio drags and fullscreen promotion depend on the final viewport size
        // being applied after any in-flight rebuild completes.
        suspend fun requestRebuild(
            reason: String,
            priority: RebuildPriority = RebuildPriority.NORMAL,
            newWidth: Int,
            newHeight: Int,
            force: Boolean = false,
            forceSingle: Boolean = false,
            onComplete: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        ) {
            this@MirrorForegroundService.requestRebuild(
                RebuildRequest(name, reason, priority, newWidth, newHeight, force, forceSingle, onComplete)
            )
        }

        suspend fun rebuild(
            newWidth: Int,
            newHeight: Int,
            force: Boolean = false,
            forceSingle: Boolean = false,
            onComplete: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        ) {
            requestRebuild("legacy_direct", RebuildPriority.NORMAL, newWidth, newHeight, force, forceSingle, onComplete)
        }

        suspend fun executeActualRebuild(targetWidth: Int, targetHeight: Int, force: Boolean = false, forceSingle: Boolean = false) {
            debugRebuildExecutions += 1
            markServiceMutation("rebuild_begin(force=$force,forceSingle=$forceSingle,target=${targetWidth}x${targetHeight})")
            val sessionId = encoderSession.incrementAndGet()
            val effectiveMaxHeight = targetHeight.coerceAtMost(currentMaxHeight)
            var targetW = targetWidth; var targetH = targetHeight
            if (targetH > effectiveMaxHeight) { val scale = effectiveMaxHeight.toFloat() / targetH; targetH = effectiveMaxHeight; targetW = (targetW * scale).toInt() }
            val alignedWidth = ((targetW + 15) and 15.inv()).coerceAtLeast(320); val alignedHeight = ((targetH + 15) and 15.inv()).coerceAtLeast(320)

            if (!force && alignedWidth == width && alignedHeight == height) return
            if (alignedWidth > 3840 || alignedHeight > 3840) return

            val w = alignedWidth; val h = alignedHeight; val dpi = computeVirtualDisplayDpi(w, h)
            val calculatedBitrate = adaptiveBitrateManager.getSharedBitrateForPipeline(this)

            // Reset frame indicator on viewport/encoder layout reconstruction to guarantee correct watchdog operation
            lastFrameRenderedTime = 0L
            mirrorServer?.clearCachedSpsPps(name)
            mirrorServer?.beginStreamGeneration(name, displayId, w, h)
            var firstFrameMetadataSent = false

            if (videoEncoder != null || jpegEncoder != null) debugEncoderReleases += 1
            videoEncoder?.release(); videoEncoder = null
            jpegEncoder?.release(); jpegEncoder = null
            delay(50)

            var startEncoderTask: (() -> Unit)? = null
            val surface = if (currentCodecMode == "mjpeg") {
                val jpeg = JpegEncoder(w, h, fps = 15, quality = 65); val inputSurface = jpeg.createInputSurface(); jpegEncoder = jpeg
                debugEncoderCreates += 1
                startEncoderTask = {
                    if (encoderSession.get() != sessionId || jpegEncoder !== jpeg || currentEncoderSurface !== inputSurface) {
                        Log.i(TAG, "[$name Pipeline] Skipping stale JPEG encoder start for session=$sessionId")
                    } else {
                        jpeg.start { data, key ->
                            lastFrameRenderedTime = System.currentTimeMillis()
                            if (!firstFrameMetadataSent) {
                                firstFrameMetadataSent = true
                                mirrorServer?.markFirstFrameReady(name, displayId, w, h)
                            }
                            mirrorServer?.broadcastFrame(data, key, name)
                        }
                    }
                }
                
                // Throttle keyframe requests to once per 1000ms, bypassing if force is true.
                mirrorServer?.setKeyframeRequester(name) { force ->
                    val now = System.currentTimeMillis()
                    if (!force && now - lastKeyframeRequestTime < 1000L) return@setKeyframeRequester
                    lastKeyframeRequestTime = now
                    serviceScope.launch {
                        try {
                            if (displayId >= 0) {
                                controller.getPrivilegedService()?.wakeUpDisplay(displayId)
                            }
                            // Bypassed restoreContent() on keyframe request to prevent relaunch loop
                        } catch (e: Exception) {
                            Log.w(TAG, "[$name Pipeline] Failed to force graphics wakeup on MJPEG keyframe request", e)
                        }
                    }
                }
                inputSurface
            } else {
                val preferredProfile = mirrorServer?.getPreferredProfile(name) ?: "High"
                val encoder = VideoEncoder(w, h, calculatedBitrate, thermalFpsOverride ?: targetFps, preferredProfile)
                val inputSurface = encoder.createInputSurface()
                videoEncoder = encoder
                debugEncoderCreates += 1
                encoder.onSpsPps = { mirrorServer?.broadcastSpsPps(it, name) }
                startEncoderTask = {
                    if (encoderSession.get() != sessionId || videoEncoder !== encoder || currentEncoderSurface !== inputSurface) {
                        Log.i(TAG, "[$name Pipeline] Skipping stale video encoder start for session=$sessionId")
                    } else {
                        encoder.start { data, key ->
                            lastFrameRenderedTime = System.currentTimeMillis()
                            if (!firstFrameMetadataSent) {
                                firstFrameMetadataSent = true
                                mirrorServer?.markFirstFrameReady(name, displayId, w, h)
                            }
                            mirrorServer?.broadcastFrame(data, key, name)
                        }
                    }
                }
                
                mirrorServer?.setKeyframeRequester(name) { force ->
                    val now = System.currentTimeMillis()
                    if (!force && now - lastKeyframeRequestTime < 1000L) return@setKeyframeRequester
                    lastKeyframeRequestTime = now
                    serviceScope.launch {
                        try {
                            if (displayId >= 0) {
                                controller.getPrivilegedService()?.wakeUpDisplay(displayId)
                            }
                            encoder.requestKeyFrame()
                        } catch (e: Exception) {
                            Log.w(TAG, "[$name Pipeline] Failed to force graphics wakeup on keyframe request", e)
                        }
                    }
                }
                
                inputSurface
            }

            currentEncoderSurface = surface; width = w; height = h; currentBitrate = calculatedBitrate
            delay(100)
            
            if (controller.isBound()) {
                var success = false
                var activeId = -1
                var isNewVd = false
                var gen = -1L

                // Minimize mutex scope to exclude binder activity launches and delay suspends, preventing deadlocks.
                vdOperationGlobalMutex.withLock {
                    virtualDisplayHardwareMutex.withLock {
                        val currentId = controller.getDisplayId()
                        if (currentId >= 0) {
                            runBinderSafe { controller.resizeDisplay(w, h, dpi) }
                            runBinderSafe { controller.setSurface(surface) }
                            displayId = currentId
                            activeId = currentId
                            gen = markVdCreated(currentId, "${name}_reuse")
                            isNewVd = false
                            success = true
                        } else {
                            runBinderSafe { controller.releaseVirtualDisplay() }
                            runBinderSafe { controller.createVirtualDisplay(w, h, dpi, surface) }
                            if (controller.hasVirtualDisplay()) {
                                val newActiveId = controller.getDisplayId()
                                displayId = newActiveId
                                activeId = newActiveId
                                gen = markVdCreated(newActiveId, "${name}_rebuild")
                                isNewVd = true
                                success = true
                            }
                        }
                    }
                }

                if (success && activeId >= 0) {
                    touchInjector = (touchInjector ?: TouchInjector(w, h)).also { injector ->
                        injector.updateDimensions(w, h)
                        injector.updateController { touchEvent, event ->
                            val accepted = controller.injectMotionEventWithResult(event)
                            recordInjectionResult(event, accepted)
                            if (!accepted) {
                                handleInjectionRejected(event.actionMasked, event.pointerCount)
                            }
                            accepted
                        }
                    }
                    startEncoderTask?.invoke()
                    
                    delay(100) // Small stabilization delay outside lock
                    runBinderSafe { controller.keepDisplayAwake() }

                    if (isNewVd) {
                        try {
                            controller.getPrivilegedService()?.wakeUpDisplay(activeId)
                        } catch (e: Exception) {
                            Log.w(TAG, "[$name Pipeline] Failed to trigger early wakeup guard", e)
                        }
                    }

                    if (currentApp.isBlank()) {
                        currentApp = "HOME"
                        markServiceMutation("launch_home_after_rebuild")
                        runBinderSafe { controller.launchHomeOnDisplay() }
                    } else if (isNewVd || forceSingle) {
                        markServiceMutation("soft_recovery_after_rebuild")
                        Log.i(TAG, "[$name Pipeline] Rebuild completed without automatic app relaunch. Waiting for explicit launch path.")
                    }
                    markServiceMutation("rebuild_end(newVd=$isNewVd,activeId=$activeId)")
                    Log.i(TAG, "[$name Pipeline] VirtualDisplay configured successfully. ID: $activeId (New VD: $isNewVd)")
                } else {
                    throw IllegalStateException("VirtualDisplay allocation completely failed via binder server.")
                }
            } else {
                if (trySetupVirtualDisplay(w, h, surface)) startEncoderTask?.invoke()
            }
            if (displayId >= 0) {
                try { mirrorServer?.broadcastControlMessage(org.json.JSONObject().apply { put("type", "resolutionChanged"); put("pane", name); put("width", w); put("height", h) }.toString()) } catch (_: Exception) {}
                // Wake the display and request a fresh frame without injecting synthetic touches
                // that can affect apps like maps during repeated split/expand cycles.
                serviceScope.launch {
                    try {
                        delay(150)
                        markServiceMutation("post_rebuild_wakeup")
                        controller.getPrivilegedService()?.wakeUpDisplay(displayId)
                        if (currentCodecMode != "mjpeg") {
                            markServiceMutation("post_rebuild_keyframe")
                            videoEncoder?.requestKeyFrame()
                        }
                        Log.i(TAG, "[$name Pipeline] Requested post-rebuild wakeup/keyframe (codec: $currentCodecMode)")
                    } catch (e: Exception) {
                        Log.w(TAG, "[$name Pipeline] Failed to force graphics wakeup post rebuild", e)
                    }
                }
            }

            mirrorServer?.broadcastDiagnostics()
        }

        fun invalidateVd(reason: String): Long { Log.w(TAG, "[$name Pipeline] Invalidating display channel cache token. Reason: $reason"); displayId = -1; return vdGeneration.incrementAndGet() }

        private fun summarizeProbeDump(raw: String, needles: List<String>): String {
            if (raw.isBlank()) return "none"
            val matched = raw
                .lineSequence()
                .map { it.trim() }
                .filter { line -> line.isNotEmpty() && needles.any { needle -> needle.isNotBlank() && line.contains(needle, ignoreCase = true) } }
                .take(12)
                .map { line -> line.replace(Regex("\\s+"), " ") }
                .toList()
            return if (matched.isEmpty()) "none" else matched.joinToString(" || ")
        }
        
        // Periodically monitors task residency on the virtual display to inject layout wakeup events adaptively as soon as the app mounts.
        private fun executeAdaptiveWakeup(targetDisplayId: Int, cleanPkg: String, service: IPrivilegedService) {
            if (targetDisplayId < 0) return
            serviceScope.launch {
                var appMounted = false
                // Poll task residency so touch can be released as soon as the app is actually mounted.
                for (attempt in 1..25) {
                    val runningTasks = try { service.getRunningTasksOnDisplay(targetDisplayId) } catch (_: Exception) { null }
                    val isPresent = runningTasks?.any { it.contains(cleanPkg) } ?: false
                    if (isPresent) {
                        appMounted = true
                        Log.i(TAG, "[$name Pipeline] Adaptive wakeup detected target app $cleanPkg in display $targetDisplayId on attempt $attempt")
                        break
                    }
                    delay(100)
                }

                if (!appMounted) {
                    Log.w(TAG, "[$name Pipeline] Adaptive wakeup timed out waiting for $cleanPkg on display $targetDisplayId. Proceeding with fallback wakeup.")
                }

                // Acquire the hardware mutex briefly to ensure any active rebuild has finalized before
                // we request a wakeup/keyframe refresh.
                try {
                    virtualDisplayHardwareMutex.withLock {
                        service.wakeUpDisplay(targetDisplayId)
                    }
                    delay(40)
                    if (currentCodecMode != "mjpeg") {
                        videoEncoder?.requestKeyFrame()
                    }
                    Log.i(TAG, "[$name Pipeline] Symmetrical adaptive wakeup successfully completed on display $targetDisplayId")
                } catch (e: Exception) {
                    Log.w(TAG, "[$name Pipeline] Failed to trigger adaptive wakeup sequence", e)
                }
            }
        }

        fun recoverTouchFocusIfNeeded(topTask: String?, trigger: String) {
            val activeId = displayId
            if (activeId < 0) return
            if (isTouchInteractionActive()) return
            val targetApp = currentApp
            
            // [FocusTrace] Skip focus recovery to prevent splash loop if the target app is a splash/launcher activity
            val normalizedTarget = targetApp.lowercase(java.util.Locale.US)
            if (normalizedTarget.contains("launchactivity") || 
                normalizedTarget.contains("introactivity") || 
                normalizedTarget.contains("splash")) {
                Log.i(TAG, "[FocusTrace] Skip touch focus recovery to prevent splash loop for $targetApp")
                return
            }

            val cleanPkg = targetApp.substringBefore('/').substringBefore('?').substringBefore(' ').trim()
            if (cleanPkg.isBlank() || cleanPkg == "HOME" || cleanPkg == "com.android.settings" || cleanPkg == packageName) return

            val hasExpectedTask = topTask?.contains(cleanPkg) == true
            if (hasExpectedTask) return
            debugTopTaskMisses += 1

            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastTouchFocusRecoveryAt < 2000L) return
            lastTouchFocusRecoveryAt = now
            debugFocusRecoveryAttempts += 1

            val token = currentVdToken()
            if (token == null) {
                Log.w(TAG, "[$name Pipeline] Touch focus recovery skipped ($trigger): no active VD token for app=$targetApp")
                return
            }

            serviceScope.launch(vdDispatcher) {
                if (!isCurrentVd(token.first, token.second)) {
                    Log.w(TAG, "[$name Pipeline] Touch focus recovery aborted ($trigger): VD token changed for app=$targetApp")
                    return@launch
                }

                val service = controller.getPrivilegedService()
                if (service == null) {
                    Log.w(TAG, "[$name Pipeline] Touch focus recovery skipped ($trigger): privileged service unavailable for app=$targetApp")
                    return@launch
                }

                Log.w(
                    TAG,
                    "[$name Pipeline] Touch focus recovery triggered ($trigger) displayId=$activeId app=$targetApp topTask=${topTask ?: "none"}"
                )
                markServiceMutation("touch_focus_recovery:$trigger")

                try { service.wakeUpDisplay(activeId) } catch (_: Exception) {}
                try { service.execCommand("wm dismiss-keyguard") } catch (_: Exception) {}

                val taskIds = try {
                    runBinderSafe(1000L) { service.getTaskIdsForPackage(cleanPkg).toList() } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                var moveAttempted = false
                for (taskId in taskIds) {
                    try {
                        runBinderSafe {
                            service.execCommand("cmd activity task move-to-display $taskId $activeId")
                            service.execCommand("cmd activity task move-to-front $taskId")
                        }
                        moveAttempted = true
                    } catch (_: Exception) {}
                }

                if (moveAttempted) {
                    executeAdaptiveWakeup(activeId, cleanPkg, service)
                    delay(120L)
                }

                val recoveredTopTask = try {
                    runBinderSafe(1000L) { service.getRunningTasksOnDisplay(activeId).firstOrNull() }
                } catch (_: Exception) {
                    null
                }

                if (recoveredTopTask?.contains(cleanPkg) == true) {
                    Log.i(
                        TAG,
                        "[$name Pipeline] Touch focus recovery restored app=$targetApp on displayId=$activeId topTask=$recoveredTopTask moveAttempted=$moveAttempted"
                    )
                    return@launch
                }

                Log.w(
                    TAG,
                    "[$name Pipeline] Touch focus recovery deferred displayId=$activeId app=$targetApp topTask=${recoveredTopTask ?: "none"} moveAttempted=$moveAttempted"
                )
                debugFocusRecoveryEscalations += 1
                Log.i(TAG, "[$name Pipeline] Touch-triggered recovery will not relaunch app. Waiting for explicit launch/rebuild path.")
            }
        }

        // Safely detach and re-attach the virtual display's Surface to break WindowManagerService transition locks.
        suspend fun resetSurfaceToBreakWmsLock() {
            val activeId = displayId
            val surface = currentEncoderSurface
            if (activeId >= 0 && surface != null) {
                Log.w(TAG, "[$name Pipeline] Initiating Surface reset sequence to clear WMS transition stagnation on display $activeId")
                try {
                    controller.setSurface(null)
                    delay(80L)
                    controller.setSurface(surface)
                    Log.i(TAG, "[$name Pipeline] Surface reset sequence successfully completed on display $activeId")
                } catch (e: Exception) {
                    Log.e(TAG, "[$name Pipeline] Failed to execute Surface reset recovery", e)
                }
            }
        }

        fun handleInjectionRejected(action: Int, pointerCount: Int) {
            debugInjectionRejects += 1
            val activeId = displayId
            if (activeId < 0) return
            
            // Allow ACTION_MOVE to accumulate reject counts with a 200ms throttle interval 
            // to detect stagnation during drag gestures without flooding recovery calls.
            val isMove = action == android.view.MotionEvent.ACTION_MOVE
            val now = android.os.SystemClock.elapsedRealtime()
            if (isMove) {
                if (now - lastMoveRejectLoggedAt < 200L) {
                    return
                }
                lastMoveRejectLoggedAt = now
            }
            
            consecutiveInjectionRejects += 1
            
            appendRecentServerTouchTrace(
                "reject pane=$name displayId=$activeId action=$action pointerCount=$pointerCount app=$currentApp"
            )
            broadcastWebDiagnostics("inject_reject:$name:$action")
            if (!debugFirstInjectFailureProbeLogged) {
                debugFirstInjectFailureProbeLogged = true
                logInjectionFailureProbe(activeId, action, pointerCount)
            }
            if (now - lastInjectionRecoveryAt >= 2000L) {
                lastInjectionRecoveryAt = now
                debugInjectionRecoveries += 1
                Log.w(
                    TAG,
                    "[FocusTrace] inject_reject pane=$name displayId=$activeId action=$action pointerCount=$pointerCount app=$currentApp"
                )
                val relaunchTarget = recoveryLaunchTarget().ifBlank { touchFocusGateTarget.ifBlank { currentApp } }
                serviceScope.launch {
                    try {
                        touchInjector?.release(forceFallbackCancel = false, reason = "inject_reject")
                        Log.w(
                            TAG,
                            "[FocusTrace] inject_input_session_reset pane=$name displayId=$activeId action=$action app=$currentApp consecutive=$consecutiveInjectionRejects"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "[$name Pipeline] inject reject input-session reset failed", e)
                    }
                    Log.w(
                        TAG,
                        "[FocusTrace] inject_realign pane=$name displayId=$activeId action=$action app=$currentApp target=$relaunchTarget consecutive=$consecutiveInjectionRejects"
                    )
                    Log.i(TAG, "[$name Pipeline] Injection recovery will not relaunch app automatically. Applying soft recovery only.")
                    try {
                        controller.getPrivilegedService()?.wakeUpDisplay(activeId)
                        controller.getPrivilegedService()?.execCommand("wm dismiss-keyguard")
                    } catch (_: Exception) {}
                    delay(60L)
                    triggerInternalFocusNudge(activeId, "inject_reject")
                    delay(120L)
                    try {
                        if (currentCodecMode != "mjpeg") {
                            videoEncoder?.requestKeyFrame()
                        } else {
                            // Bypassed restoreContent() on inject reject to prevent relaunch loop
                        }
                    } catch (_: Exception) {}
                    Log.w(
                        TAG,
                        "[FocusTrace] inject_recover pane=$name displayId=$activeId action=$action app=$currentApp target=$relaunchTarget"
                    )
                }
            }
        }

        private fun logInjectionFailureProbe(activeId: Int, action: Int, pointerCount: Int) {
            serviceScope.launch(vdDispatcher) {
                val service = controller.getPrivilegedService()
                if (service == null) {
                    Log.w(
                        TAG,
                        "[FocusTrace] inject_false_probe pane=$name displayId=$activeId action=$action pointerCount=$pointerCount app=$currentApp service=unavailable recentServiceAction=${recentServiceActionSummary()}"
                    )
                    return@launch
                }

                val targetApp = currentApp
                val cleanPkg = targetApp.substringBefore('/').substringBefore('?').substringBefore(' ').trim()
                val activitiesDump = try {
                    runBinderSafe(1500L) { service.execCommand("dumpsys activity activities") } ?: ""
                } catch (_: Exception) {
                    ""
                }
                val windowsDump = try {
                    runBinderSafe(1500L) { service.execCommand("dumpsys window windows") } ?: ""
                } catch (_: Exception) {
                    ""
                }

                val activitySummary = summarizeProbeDump(
                    activitiesDump,
                    listOf("Display #$activeId", "topResumedActivity", "mResumedActivity", "ResumedActivity", cleanPkg)
                )
                val windowSummary = summarizeProbeDump(
                    windowsDump,
                    listOf("mCurrentFocus", "mFocusedApp", "mTopFocusedDisplayId", "Display #$activeId", "mDisplayId=$activeId", cleanPkg)
                )
                lastRejectProbeSummary =
                    "pane=$name displayId=$activeId app=$targetApp activities=$activitySummary windows=$windowSummary recent=${recentServiceActionSummary()}"
                Log.w(
                    TAG,
                    "[FocusTrace] inject_false_probe pane=$name displayId=$activeId action=$action pointerCount=$pointerCount app=$targetApp activities=$activitySummary windows=$windowSummary recentServiceAction=${recentServiceActionSummary()}"
                )
                broadcastWebDiagnostics("inject_false_probe:$name:$action")
            }
        }

        fun markVdCreated(activeId: Int, reason: String): Long { displayId = activeId; return vdGeneration.incrementAndGet() }
        fun isCurrentVd(expectedGeneration: Long, expectedDisplayId: Int): Boolean = expectedDisplayId >= 0 && expectedGeneration == vdGeneration.get() && expectedDisplayId == displayId && controller.hasVirtualDisplay() && controller.getDisplayId() == expectedDisplayId
        fun currentVdToken(): Pair<Long, Int>? { val gen = vdGeneration.get(); val activeId = displayId; return if (isCurrentVd(gen, activeId)) gen to activeId else null }
        fun inputDebugSummary(): String =
            "injectAttempts=$debugInjectAttempts injectAccepted=$debugInjectAccepted injectRejected=$debugInjectRejected " +
                "moveInjectAttempts=$debugMoveInjectAttempts moveInjectAccepted=$debugMoveInjectAccepted moveInjectRejected=$debugMoveInjectRejected " +
            "launchSeq=$debugLaunchSeq topTaskMisses=$debugTopTaskMisses focusRecoveryAttempts=$debugFocusRecoveryAttempts " +
                "focusRecoveryEscalations=$debugFocusRecoveryEscalations rebuildRequests=$debugRebuildRequests rebuildExecutions=$debugRebuildExecutions " +
                "resizeSchedules=$debugResizeSchedules resizeCancels=$debugResizeCancels fallbackStarts=$debugFallbackStarts " +
                "fallbackCancels=$debugFallbackCancels encoderCreates=$debugEncoderCreates encoderReleases=$debugEncoderReleases " +
                "injectRejects=$debugInjectionRejects injectRecoveries=$debugInjectionRecoveries " +
                "resizeJobActive=${resizeJob?.isActive == true} fallbackJobActive=${activeFallbackJob?.isActive == true} " +
                "encoderActive=${videoEncoder != null || jpegEncoder != null} " +
                "focusGateArmed=${touchFocusGateArmedAt > 0L} gatedPointers=${gatedPointerIds.size} focusGateProbe=${touchFocusGateLastProbe}"

        private fun internalComponentName(activityClassName: String): String = if (activityClassName.contains('/')) activityClassName else "$packageName/$activityClassName"
        private fun recoveryLaunchTarget(): String {
            val active = currentApp.trim()
            if (active.isBlank() || active == "HOME" || active == "com.android.settings") return active
            if (active.startsWith("$packageName/") || active.contains("WebBrowserActivity")) return active
            return active.substringBefore('/').ifBlank { active }
        }

        fun launchOwnActivity(activityClassName: String, url: String) {
            val targetDisplayId = this.displayId
            if (targetDisplayId < 0) return
            Log.i(TAG, "[$name Pipeline] Spawning internal container panel component: $activityClassName")
            val options = android.app.ActivityOptions.makeBasic().apply { launchDisplayId = targetDisplayId }
            val intent = Intent().apply {
                setClassName(this@MirrorForegroundService, activityClassName)
                if (activityClassName.contains("WebBrowserActivity")) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra("url", url); putExtra("pane", name)
            }
            try { startActivity(intent, options.toBundle()) } catch (_: Exception) {
                serviceScope.launch { launchComponent(internalComponentName(activityClassName), "url", url, forceColdStart = false, forceDisplayId = true) }
            }
        }
        suspend fun launchComponent(
            packageOrComponent: String,
            extraKey: String? = null,
            extraValue: String? = null,
            forceColdStart: Boolean = false,
            forceDisplayId: Boolean = false,
            forceTaskRealign: Boolean = false
        ): Boolean = withContext(vdDispatcher) {
            markServiceMutation("launch_component_begin(target=$packageOrComponent,cold=$forceColdStart,realign=$forceTaskRealign)")
            // Ensure lastFrameRenderedTime is reset only when actually switching to a different application package
            // or when a clean cold start is explicitly requested. This preserves frame rendering timestamps for 
            // the active app, allowing the Command Equivalence Guard to accurately prevent duplicate launch floods.
            val cleanPkg = packageOrComponent.substringBefore('/').substringBefore('?').substringBefore(' ').trim()
            if (cleanPkg.isBlank() || cleanPkg == packageName || cleanPkg.contains("com.castla.mirror")) return@withContext false

            val previousPkg = currentApp.substringBefore('/').substringBefore('?').substringBefore(' ').trim()
            val isNewApp = currentApp.substringBefore('/') != cleanPkg
            val needsFreshLaunchPreparation = requiresFreshLaunchPreparation
            if (isNewApp || forceColdStart) {
                lastFrameRenderedTime = 0L
            }

            // Bypassed: Do not force stop the previous app on application switching to support warm start
            // Command Equivalence Guard: If target app is already active and rendering on this virtual display, skip redundant window displacement commands.
            // However, if the screen streaming has stagnated or has not yet rendered its first frame, bypass this safeguard to enforce visual recovery.
            val isAlreadyActive = currentApp == packageOrComponent || currentApp.substringBefore('/') == cleanPkg
            val isEncoderActive = if (currentCodecMode == "mjpeg") jpegEncoder != null else videoEncoder != null
            val now = System.currentTimeMillis()
            val isFrameStreamingNormal = lastFrameRenderedTime > 0L && (now - lastFrameRenderedTime < 3000L)
            
            if (isAlreadyActive && isEncoderActive && isFrameStreamingNormal && !needsFreshLaunchPreparation && !forceColdStart && !forceTaskRealign && !isSelfHealingInProgress) {
                Log.i(TAG, "[$name Pipeline] Command Equivalence Guard activated. $cleanPkg is already running and active on display $displayId. Bypassing redundant launch command.")
                // Keep-awake graphic trigger
                val correctedDisplayId = if (displayId >= 0) displayId else controller.getDisplayId()
                val service = controller.getPrivilegedService()
                if (correctedDisplayId >= 0 && service != null) {
                    executeAdaptiveWakeup(correctedDisplayId, cleanPkg, service)
                }
                return@withContext true
            }
            
            
            // Self-healing: restore released graphics pipelines and realign to requested viewport before shifting app focus
            // Account for active JpegEncoder in MJPEG mode to prevent redundant self-healing loops.
            // Also enforce isSelfHealingInProgress state lock to prevent recursive rebuild requests.
            val isEncoderReleased = if (currentCodecMode == "mjpeg") jpegEncoder == null else videoEncoder == null
            val targetW = if (requestedWidth > 0) requestedWidth else (if (lastValidWidth > 0) lastValidWidth else 384)
            val targetH = if (requestedHeight > 0) requestedHeight else (if (lastValidHeight > 0) lastValidHeight else 672)
            
            // Align dimensions to 16-pixel boundaries to check layout equivalence
            val alignedW = ((targetW + 15) and 15.inv()).coerceAtLeast(320)
            val alignedH = ((targetH + 15) and 15.inv()).coerceAtLeast(320)
            val needsViewportRealignment = width != alignedW || height != alignedH

            if ((isEncoderReleased || width <= 1 || needsViewportRealignment) && !isSelfHealingInProgress) {
                isSelfHealingInProgress = true
                try {
                    Log.i(TAG, "[$name Pipeline] Self-healing or Viewport Alignment activated on launchComponent. Restoring layout state to ${targetW}x${targetH}")
                    // Eliminate fragile hardcoded delays via event-driven coroutine completion tokens.
                    // Awaiting the CompletableDeferred guarantees the virtual display surfaces are fully bound 
                    // natively by the sequential worker before moving forward to launch components.
                    val rebuildDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                    requestRebuild(
                        reason = "launch_self_heal",
                        priority = RebuildPriority.HIGH,
                        newWidth = targetW,
                        newHeight = targetH,
                        onComplete = rebuildDeferred
                    )
                    try {
                        rebuildDeferred.await()
                    } catch (e: Exception) {
                        Log.w(TAG, "[$name Pipeline] Self-healing await deferred failed, fallback to grace period", e)
                        delay(300)
                    }
                } finally {
                    isSelfHealingInProgress = false
                }
            } else if (isSelfHealingInProgress) {
                Log.d(TAG, "[$name Pipeline] Self-healing is already in progress. Skipping redundant trigger.")
            }

            val correctedDisplayId = if (displayId >= 0) displayId else controller.getDisplayId()
            if (correctedDisplayId < 0) return@withContext false
            val service = controller.getPrivilegedService() ?: return@withContext false

            try {
                if (forceColdStart && cleanPkg != "HOME") { try { service.execCommand("am force-stop $cleanPkg") } catch (_: Exception) {} }
                val originalDisplayId = try { runBinderSafe { service.getDisplayIdForPackage(cleanPkg) } ?: -1 } catch (_: Exception) { -1 }
                val activeDisplayIds = pipelines.values.map { it.displayId }.filter { it >= 0 }
                val targetDisplayId = if (!needsFreshLaunchPreparation && !forceDisplayId && originalDisplayId >= 0 && activeDisplayIds.contains(originalDisplayId)) originalDisplayId else correctedDisplayId

                Log.i(TAG, "[$name Pipeline] Symmetric task processing initialized -> Routing $cleanPkg to Display token: $targetDisplayId freshLaunchPrep=$needsFreshLaunchPreparation previousPkg=$previousPkg lastPrepared=$lastPreparedTargetPackage")

                val matchingTaskIds = try { runBinderSafe(1000L) { service.getTaskIdsForPackage(cleanPkg).toList() } ?: emptyList() } catch (_: Exception) { emptyList() }
                val isWarmStart = matchingTaskIds.isNotEmpty()

                for (taskId in matchingTaskIds) {
                    try { runBinderSafe { service.execCommand("cmd activity task move-to-display $taskId $targetDisplayId"); service.execCommand("cmd activity task move-to-front $taskId") } } catch (_: Exception) {}
                }

                // Prevent redundant 'am start' shell command execution immediately following async task migration command.
                // Re-launching via 'am start' in parallel with active task displacement commands causes Android OS task stack conflict,
                // frequently forcing the primary Display 0 (MainActivity) to recede to the background Recents view.
                if (isWarmStart && !forceColdStart) {
                    markServiceMutation("launch_component_warm_start")
                    // Trigger adaptive task residency-aware wakeup asynchronously instead of waiting on hardcoded timings
                    executeAdaptiveWakeup(targetDisplayId, cleanPkg, service)
                    if (needsFreshLaunchPreparation) {
                        mirrorServer?.onKeyframeRequest(name, "fresh_launch_prepare")
                    }
                    
                    // Trigger the 4-second frame-based watchdog for graceful recovery on warm start layout transition
                    verifySurfaceAndFallback(
                        pipeline = this@MirroringPipeline,
                        service = service,
                        displayId = targetDisplayId,
                        pkg = cleanPkg,
                        taskIds = matchingTaskIds,
                        packageOrComponent = packageOrComponent,
                        extraKey = extraKey,
                        extraValue = extraValue
                    )

                    requiresFreshLaunchPreparation = false
                    lastPreparedTargetPackage = cleanPkg
                    currentApp = packageOrComponent
                    return@withContext true
                }

                // WMS Transition Lock Prevention Guard:
                // If this is a realign/recovery request (forceTaskRealign = true) for the ALREADY ACTIVE application
                // (i.e. cleanPkg is already currentApp and we are currently streaming/encoder is running),
                // we MUST NOT execute native launchAppOnDisplayV2 or buildShellLaunchCommand.
                // Doing so forces Android OS to initiate a new Window Manager transition state,
                // which locks display focus and causes a perpetual touch injection rejection loop.
                val isAlreadyActiveApp = cleanPkg == currentApp.substringBefore('/')
                val isEncoderActive = if (currentCodecMode == "mjpeg") jpegEncoder != null else videoEncoder != null
                if (forceTaskRealign && isAlreadyActiveApp && isEncoderActive && !needsFreshLaunchPreparation) {
                    Log.w(TAG, "[$name Pipeline] Realignment requested for active app $cleanPkg. Bypassing native cold start to prevent WMS focus transition lock.")
                    executeAdaptiveWakeup(targetDisplayId, cleanPkg, service)
                    currentApp = packageOrComponent
                    return@withContext true
                }

                // 1. Try native binder launchAppOnDisplayV2 first (only for Standard package without complex query strings)
                var nativeStarted = false
                val isStandardAppLaunch = extraKey.isNullOrEmpty() && extraValue == null && !packageOrComponent.contains("/")
                if (isStandardAppLaunch) {
                    try {
                        nativeStarted = runBinderSafe { controller.launchAppOnDisplayV2(cleanPkg, forceStop = false) } ?: false
                    } catch (e: Exception) {
                        Log.w(TAG, "[$name Pipeline] Native launchAppOnDisplayV2 failed, preparing shell fallback", e)
                    }
                }

                // 2. Fallback to buildShellLaunchCommand if native launch is inapplicable or failed
                if (!nativeStarted) {
                    markServiceMutation("launch_component_shell_start")
                    Log.i(TAG, "[$name Pipeline] Executing fallback shell launch command for $packageOrComponent")
                    // Introduce a 150ms delay for stabilization of window manager and focus subsystems.
                    delay(150L)
                    val command = buildShellLaunchCommand(targetDisplayId, packageOrComponent, extraKey, extraValue, reorderToFront = isWarmStart)
                    val result = runBinderSafe { service.execCommand(command) } ?: ""
                    if (result.contains("SecurityException") || result.contains("Permission Denial")) {
                        val retryTasks = try { runBinderSafe { service.getTaskIdsForPackage(cleanPkg) } ?: intArrayOf() } catch (_: Exception) { intArrayOf() }
                        for (taskId in retryTasks) {
                            try {
                                // val retryNativeMoved = runBinderSafe { controller.moveTaskToDisplayNative(taskId) } ?: false
                                val retryNativeMoved = false
                                if (!retryNativeMoved) {
                                    service.execCommand("cmd activity task move-to-display $taskId $targetDisplayId")
                                    service.execCommand("cmd activity task move-to-front $taskId")
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                
                // Force an immediate graphics wakeup sequence and request encoder keyframe for Cold-Start apps to prevent early stream corruption.
                if (!isWarmStart || forceColdStart) {
                    markServiceMutation("launch_component_cold_start")
                    executeAdaptiveWakeup(targetDisplayId, cleanPkg, service)
                    markServiceMutation("launch_component_keyframe")
                    mirrorServer?.onKeyframeRequest(name, if (needsFreshLaunchPreparation) "fresh_launch_prepare" else "launch_component")
                }
                
                // Trigger the 4-second frame-based watchdog for graceful recovery on cold start layout transition
                verifySurfaceAndFallback(
                    pipeline = this@MirroringPipeline,
                    service = service,
                    displayId = targetDisplayId,
                    pkg = cleanPkg,
                    taskIds = matchingTaskIds,
                    packageOrComponent = packageOrComponent,
                    extraKey = extraKey,
                    extraValue = extraValue
                )

                requiresFreshLaunchPreparation = false
                lastPreparedTargetPackage = cleanPkg
                currentApp = packageOrComponent; return@withContext true
            } catch (e: Exception) { Log.e(TAG, "[$name Pipeline] Component push crashed inside system shell launcher layer.", e); return@withContext false }
        }

        private fun buildExternalBrowserCommand(displayId: Int, url: String, browserComponent: String): String {
            return buildString {
                append("am start --display $displayId -f 0x18000000 ")
                append("-a android.intent.action.VIEW ")
                append("-d ${escapeShellArg(url)} ")
                append("-n ${escapeShellArg(browserComponent)} ")
            }.trim()
        }
        suspend fun launchBrowser(url: String, sourceAppPackage: String? = null, allowFallback: Boolean = true) {
            val browser = BrowserResolver.resolve(this@MirrorForegroundService, url)
            val targetComponent = browser?.componentFlat ?: internalComponentName("com.castla.mirror.ui.WebBrowserActivity")
            if (displayId < 0) {
                currentApp = targetComponent; currentWebUrl = url; isVideoApp = (browser != null)
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        // Dynamically fallback to the last valid system screen resolution to prevent layout squishing (720x720) during early startup.
                        val fallbackW = if (lastValidWidth > 0) lastValidWidth else 720
                        val fallbackH = if (lastValidHeight > 0) lastValidHeight else 720
                        requestRebuild(
                            reason = "browser_launch_missing_display",
                            priority = RebuildPriority.HIGH,
                            newWidth = if (requestedWidth > 0) requestedWidth else fallbackW,
                            newHeight = if (requestedHeight > 0) requestedHeight else fallbackH
                        )
                        if (displayId >= 0) {
                            if (browser != null) controller.getPrivilegedService()?.execCommand(buildExternalBrowserCommand(displayId, url, browser.componentFlat))
                            else launchOwnActivity("com.castla.mirror.ui.WebBrowserActivity", url)
                        }
                    } catch (_: Exception) {}
                }
                return
            }
            if (browser != null) {
                try {
                    controller.getPrivilegedService()?.execCommand(buildExternalBrowserCommand(displayId, url, browser.componentFlat))
                    // Bypassed: Do not force stop the previous app to support warm start
                    currentApp = browser.componentFlat; currentWebUrl = url; isVideoApp = true
                    adaptiveBitrateManager.rebalanceBitrates(); return
                } catch (_: Exception) {}
            }
            if (allowFallback) {
                launchOwnActivity("com.castla.mirror.ui.WebBrowserActivity", url)
                currentApp = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"); currentWebUrl = url; isVideoApp = false
                adaptiveBitrateManager.rebalanceBitrates()
            }
        }

        suspend fun launchStandard(launchTarget: String, forceDisplayId: Boolean = false) {
            val resolvedTarget = normalizeLaunchTarget(launchTarget)
            // ### 수정 시작 ###
            // Force task realignment on standard launching requests from web to bypass the Command Equivalence Guard
            val launched = if (displayId >= 0) launchComponent(resolvedTarget, forceDisplayId = forceDisplayId, forceTaskRealign = true) else false
            if (!launched) {
                currentApp = resolvedTarget; currentWebUrl = null; isVideoApp = false
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        // Dynamically fallback to the last valid system screen resolution to prevent layout squishing (720x720) during early startup.
                        val fallbackW = if (lastValidWidth > 0) lastValidWidth else 720
                        val fallbackH = if (lastValidHeight > 0) lastValidHeight else 720
                        requestRebuild(
                            reason = "standard_launch_missing_display",
                            priority = RebuildPriority.HIGH,
                            newWidth = if (requestedWidth > 0) requestedWidth else fallbackW,
                            newHeight = if (requestedHeight > 0) requestedHeight else fallbackH
                        )
                        if (displayId >= 0) launchComponent(resolvedTarget, forceDisplayId = forceDisplayId, forceTaskRealign = true)
                    } catch (_: Exception) {}
                }
            } else {
                currentApp = resolvedTarget; currentWebUrl = null; isVideoApp = false
                adaptiveBitrateManager.rebalanceBitrates()
            }
            // ### 수정 끝 ###
        }

        suspend fun launchWeb(activityClassName: String, url: String) {
            val targetComponent = internalComponentName(activityClassName)
            if (displayId < 0) {
                currentApp = targetComponent; currentWebUrl = url; isVideoApp = false
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        // Dynamically fallback to the last valid system screen resolution to prevent layout squishing (720x720) during early startup.
                        val fallbackW = if (lastValidWidth > 0) lastValidWidth else 720
                        val fallbackH = if (lastValidHeight > 0) lastValidHeight else 720
                        requestRebuild(
                            reason = "web_launch_missing_display",
                            priority = RebuildPriority.HIGH,
                            newWidth = if (requestedWidth > 0) requestedWidth else fallbackW,
                            newHeight = if (requestedHeight > 0) requestedHeight else fallbackH
                        )
                        if (displayId >= 0) launchOwnActivity(activityClassName, url)
                    } catch (_: Exception) {}
                }
                return
            }
            // Bypassed: Do not force stop the previous app to support warm start
            launchOwnActivity(activityClassName, url)
            currentApp = targetComponent; currentWebUrl = url; isVideoApp = false
            adaptiveBitrateManager.rebalanceBitrates()
        }

        suspend fun launchAppFromWebLauncher(pkgName: String, componentName: String? = null, forceDisplayId: Boolean = true) {
            if (pkgName.isBlank()) return
            val isAppInstalled = try {
                val pm = packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getApplicationInfo(pkgName, PackageManager.ApplicationInfoFlags.of(0)).enabled
                else @Suppress("DEPRECATION") pm.getApplicationInfo(pkgName, 0).enabled
            } catch (_: PackageManager.NameNotFoundException) { false }

            if (isAppInstalled) launchStandard(componentName ?: pkgName, forceDisplayId = forceDisplayId)
            else OttCatalog.webUrlFor(pkgName)?.let { launchBrowser(it, pkgName) }

            if (currentCodecMode == "mjpeg") {
                controller.getPrivilegedService()?.wakeUpDisplay(displayId)
            }
        }

        suspend fun restoreContentLocked(expectedGeneration: Long, expectedDisplayId: Int) {
            if (!isCurrentVd(expectedGeneration, expectedDisplayId)) return
            val activeId = if (displayId >= 0) displayId else controller.getDisplayId()
            markServiceMutation("restore_content_begin(activeId=$activeId)")

            when (currentApp) {
                "HOME", "", "com.android.settings" -> {
                    currentApp = "HOME"
                    markServiceMutation("restore_content_home")
                    controller.launchHomeOnDisplay()
                }
                else -> {
                    if (currentWebUrl != null && !currentApp.contains("WebBrowserActivity")) {
                        val browser = BrowserResolver.resolve(this@MirrorForegroundService, currentWebUrl!!)
                        val cmd = browser?.let { buildExternalBrowserCommand(activeId, currentWebUrl!!, it.componentFlat) }
                        val launched = try { if (cmd != null && isCurrentVd(vdGeneration.get(), activeId)) { controller.getPrivilegedService()?.execCommand(cmd); true } else false } catch (_: Exception) { false }
                        if (!launched) launchOwnActivity("com.castla.mirror.ui.WebBrowserActivity", currentWebUrl!!)
                    } else if (currentApp.contains("WebBrowserActivity")) {
                        markServiceMutation("restore_content_browser_activity")
                        launchOwnActivity(currentApp.substringAfter('/'), currentWebUrl ?: "https://m.youtube.com")
                    } else {
                        markServiceMutation("restore_content_launch_component")
                        launchComponent(currentApp, forceColdStart = false, forceTaskRealign = true)
                    }
                }
            }
        }

        fun restoreContent() {
            val token = currentVdToken() ?: return
            serviceScope.launch(Dispatchers.IO) { restoreContentLocked(token.first, token.second) }
        }

        suspend fun release(forcePhysical: Boolean = false) {
            if (forcePhysical) executeReleaseInternal(forcePhysical = true)
            else {
                withContext(vdDispatcher) {
                    val locked = withTimeoutOrNull(4000L) { pipelineMutex.withLock { executeReleaseInternal(forcePhysical = false) }; true }
                    if (locked == null) executeReleaseInternal(forcePhysical = true)
                }
            }
        }

        private suspend fun executeReleaseInternal(forcePhysical: Boolean) {
            if (released.get()) return
            if (!released.compareAndSet(false, true)) return

            try {
                withContext(Dispatchers.IO) {
                    Log.i(TAG, "[CLEANUP_START] [$name Pipeline] Initiating hardware display shutdown. ForcePhysical=$forcePhysical")
                    logInputDebugSnapshot("pipeline_release_begin:$name")
                    
                    Log.i(TAG, "[CLEANUP_STOP_LOOPS]")
                    videoEncoder?.stop()
                    jpegEncoder?.stop()
                    
                    Log.i(TAG, "[CLEANUP_CALLBACKS_UNREGISTERED]")
                    videoEncoder?.unregisterCallbacks()
                    jpegEncoder?.unregisterCallbacks()

                    Log.i(TAG, "[CLEANUP_VD_RELEASED]")
                    if (displayId >= 0) {
                        cleanupDisplay(displayId)
                        if (forcePhysical) { runBinderSafe { controller.releaseVirtualDisplay() }; displayId = -1 }
                        else { try { runBinderSafe { controller.resizeDisplay(1, 1, 160) }; width = 1; height = 1 } catch (_: Exception) {} }
                    }

                    
                    Log.i(TAG, "[CLEANUP_CODEC_STOPPED]")
                    videoEncoder?.stopCodecOnly()
                    
                    Log.i(TAG, "[CLEANUP_CODEC_RELEASED]")
                    videoEncoder?.releaseCodecOnly()
                    jpegEncoder?.releaseReaderOnly()

                    Log.i(TAG, "[CLEANUP_JOIN_ENCODERS]")
                    videoEncoder?.join()
                    jpegEncoder?.join()

                    videoEncoder = null                    
                    jpegEncoder = null                                        
                    
                    try {
                        resizeJob?.cancel()
                        resizeJob?.join()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to join resizeJob", e)
                    }
                    resizeJob = null
                    
                    Log.i(TAG, "[CLEANUP_SURFACE_RELEASED]")
                    currentEncoderSurface = null
                    
                    try { touchInjector?.detachController("pipeline_release") } catch (_: Exception) {}
                    touchInjector?.release()
                    isVideoApp = false
                    mirrorServer?.setKeyframeRequester(name) { _ -> }
                    width = 0; height = 0; requestedWidth = 0; requestedHeight = 0
                    currentApp = ""; currentWebUrl = null
                    adaptiveBitrateManager.rebalanceBitrates()
                    
                    released.set(true)
                    logInputDebugSnapshot("pipeline_release_end:$name")
                    Log.i(TAG, "[CLEANUP_DONE] [$name Pipeline] Display shutdown completed.")
                }
            } finally {
                releasing.set(false)
            }
        }
    }
}

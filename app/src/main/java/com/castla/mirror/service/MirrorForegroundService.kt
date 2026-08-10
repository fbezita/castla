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
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.castla.mirror.BuildConfig
import com.castla.mirror.R
import com.castla.mirror.notifications.CastlaNotificationListenerService
import com.castla.mirror.notifications.NotificationAccessSettingsHelper
import com.castla.mirror.widget.MirrorWidgetProvider
import com.castla.mirror.capture.AudioCapture
import com.castla.mirror.capture.JpegEncoder
import com.castla.mirror.capture.VideoEncoder
import com.castla.mirror.capture.VirtualDisplayController
import com.castla.mirror.compositor.DisplayTier
import com.castla.mirror.input.TouchInjector
import com.castla.mirror.input.CastlaTextInputRouter
import com.castla.mirror.server.MirrorServer
import com.castla.mirror.server.MirrorServerAvailability
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
import com.castla.mirror.policy.AppAudioTarget
import com.castla.mirror.policy.AudioCaptureRouteKey
import com.castla.mirror.policy.AudioTargetRegistry
import com.castla.mirror.policy.CodecModeTransition
import com.castla.mirror.policy.DisconnectPolicy
import com.castla.mirror.policy.ScreenOffLoopGuard
import com.castla.mirror.policy.ScreenOffRecoveryPlanner
import com.castla.mirror.policy.ScreenOffState
import com.castla.mirror.policy.ScreenOffEvent
import com.castla.mirror.ui.ScreenOffBlackoutActivity
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
import java.util.concurrent.ConcurrentHashMap

@OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class
)
class MirrorForegroundService : Service() {
    internal val vdDispatcher = kotlinx.coroutines.newSingleThreadContext("vd-operations")
    @Volatile private var useNativeVirtualDisplayIme = true
    private val castlaImeProxyEnabled: Boolean
        get() = !useNativeVirtualDisplayIme
    private val vdImeLogPrefix = "[VDIME]"
    @Volatile private var verboseDiagnosticsEnabled = false
    private val vdImeVerboseLogging: Boolean
        get() = verboseDiagnosticsEnabled
    private val verboseScreenOffLogging: Boolean
        get() = verboseDiagnosticsEnabled

    internal fun logScreenOffInfo(message: String) {
        if (verboseScreenOffLogging) {
            Log.i(TAG, message)
        }
    }

    internal fun logScreenOffWarn(message: String) {
        if (verboseScreenOffLogging) {
            Log.w(TAG, message)
        }
    }

    internal fun logLaunchRecoveryInfo(message: String) {
        if (verboseDiagnosticsEnabled) {
            FileLogger.i("LAUNCH_RECOVERY", message)
            Log.i(TAG, message)
        }
    }

    internal fun logStreamBootstrapInfo(message: String) {
        if (verboseDiagnosticsEnabled) {
            FileLogger.i("STREAM_BOOTSTRAP", message)
            Log.i(TAG, message)
        }
    }

    internal suspend fun <T> runBinderSafe(timeoutMs: Long = 3000L, block: suspend () -> T): T? {
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
        const val EXTRA_TESLA_BT_VIDEO_LATENCY_MS = "tesla_bt_video_latency_ms"
        const val EXTRA_STREAMED_AUDIO_VIDEO_LATENCY_MS = "streamed_audio_video_latency_ms"
        const val EXTRA_MIRRORING_MODE = "mirroring_mode"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_RELAY_PUBLISH_IP = "relay_publish_ip"

        private val _serviceRunningFlow = MutableStateFlow(false)
        val serviceRunningFlow: StateFlow<Boolean> = _serviceRunningFlow
        private val _serverAvailabilityFlow = MutableStateFlow(MirrorServerAvailability.IDLE)
        val serverAvailabilityFlow: StateFlow<MirrorServerAvailability> = _serverAvailabilityFlow

        private val _cleanupInProgressFlow = MutableStateFlow(false)
        val cleanupInProgressFlow: StateFlow<Boolean> = _cleanupInProgressFlow

        private val _panelOffStateFlow = MutableStateFlow(ScreenOffState.ACTIVE)
        val panelOffStateFlow: StateFlow<ScreenOffState> = _panelOffStateFlow

        @Volatile internal var isAppLaunchingContext = false

        var isServiceRunning: Boolean
            get() = _serviceRunningFlow.value
            set(value) { _serviceRunningFlow.value = value }

        var isCleanupInProgress: Boolean
            get() = _cleanupInProgressFlow.value
            set(value) { _cleanupInProgressFlow.value = value }

        @JvmStatic
        var instance: MirrorForegroundService? = null
            private set

        private const val RECOVERY_ACTION_MIN_INTERVAL_MS = 900L
    }

    inner class LocalBinder : Binder() {
        val service: MirrorForegroundService get() = this@MirrorForegroundService
    }

    private val binder = LocalBinder()
    @Volatile private var backFallbackLastTriggeredTime = 0L
    internal var mirrorServer: MirrorServer? = null
    private val recentRecoveryActionAtMs = ConcurrentHashMap<Int, Long>()
    fun getMirrorServer(): MirrorServer? = mirrorServer

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val remoteInputCoordinator = RemoteInputCoordinator(this) { castlaImeProxyEnabled }

    fun resetImeTimeoutTimer() = remoteInputCoordinator.resetTimeout()
    fun ensureCastlaImeActiveDynamically() = remoteInputCoordinator.ensureActive()
    fun restoreUserKeyboardSilently() = remoteInputCoordinator.restoreKeyboard()
    fun onRemoteFocusLost() = remoteInputCoordinator.onFocusLost()
    fun handleRemoteFocusHint(packageName: String?, inputType: Int, imeOptions: Int, privateImeOptions: String?) =
        remoteInputCoordinator.handleFocusHint(packageName, inputType, imeOptions, privateImeOptions)
    fun handleRemoteBlurHint() = remoteInputCoordinator.handleBlurHint()

    // Core map collection for symmetric pipeline extension
    val pipelines = java.util.concurrent.ConcurrentHashMap<String, MirroringPipeline>()

    internal lateinit var powerLockManager: PowerLockManager
    internal lateinit var thermalThrottleManager: ThermalThrottleManager
    internal lateinit var adaptiveBitrateManager: AdaptiveBitrateManager
    lateinit var contentAwareQualityEngine: ContentAwareQualityEngine

    val thermalStatus: kotlinx.coroutines.flow.StateFlow<Int>
        get() = thermalThrottleManager.thermalStatus

    internal var thermalFpsOverride: Int?
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
    internal var audioOrchestrator: AudioCaptureOrchestrator? = null
    private val audioTargetRegistry = AudioTargetRegistry()
    @Volatile private var activeAudioCaptureRouteKey: AudioCaptureRouteKey? = null
    private var shizukuSetup: ShizukuSetup? = null
    private var mirroringMode: String = "FULL_SCREEN"
    private var targetPackage: String = ""
    private var browserConnectionListener: ((Boolean) -> Unit)? = null
    @Volatile private var stopRequested = false
    @Volatile private var cleanupCompleted = false
    private val terminalReason = java.util.concurrent.atomic.AtomicReference<TerminalReason?>(null)
    internal var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    internal var browserConnected = false
    internal var isInitialRebuildTriggered = false
    @Volatile internal var currentCodecMode: String = "h264"
    internal val paneVisibility = java.util.concurrent.ConcurrentHashMap<String, Boolean>().apply {
        put("primary", true)
        put("secondary", false)
    }

    internal val virtualDisplayHardwareMutex = Mutex()
    internal val vdOperationGlobalMutex = Mutex()


    // Hardware request envelope to sequentialize all VirtualDisplay operations
    sealed class VdHardwareRequest {
        data class Rebuild(
            val requestId: Long,
            val reason: String,
            val pipelineName: String,
            val targetWidth: Int,
            val targetHeight: Int,
            val force: Boolean,
            val forceSingle: Boolean,
            val onComplete: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        ) : VdHardwareRequest()
    }



    enum class PipelineState { IDLE, REBUILDING }
    enum class RebuildPriority { LOW, NORMAL, HIGH, IMMEDIATE }
    data class RebuildRequest(
        val requestId: Long,
        val pipelineName: String,
        val reason: String,
        val priority: RebuildPriority,
        val width: Int,
        val height: Int,
        val force: Boolean,
        val forceSingle: Boolean,
        val onComplete: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    )
    internal val rebuildRequestIdGenerator = java.util.concurrent.atomic.AtomicLong(0)

    private var dpiScale: Float = 0.7f
    private val shizukuSetupMutex = Mutex()
    private var shizukuBindRetryCount = 0
    private val SHIZUKU_MAX_RETRIES = 2
    private val BIND_WAIT_BUDGET_MS = 8_000L

    private var reconnectJob: Job? = null
    private var pendingAudioEnabled = false
    private var audioSocketReady = false
    private var negotiatedAudioCodec: String? = null
    private var separateNavigationAudioToPhone = true
    private var audioCodecPreference = com.castla.mirror.policy.AudioCodecPreference.OPUS_FIRST
    private var systemSeparatedAudioPackages: Set<String>? = null
    private val audioStreamGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private var teslaBluetoothVideoLatencyMs = 0
    private var streamedAudioVideoLatencyMs = com.castla.mirror.policy.VideoLatencyPolicy.DEFAULT_STREAMED_AUDIO_LATENCY_MS
    private var bluetoothAudioConnected = false
    private lateinit var bluetoothAudioRouteMonitor: BluetoothAudioRouteMonitor
    private var deferredAudioStartJob: Job? = null

    private val browserSessionCoordinator = BrowserSessionCoordinator(this)
    internal val hasReceivedBrowserLayout: Boolean get() = browserSessionCoordinator.hasReceivedLayout
    private val vdRebuildCoordinator = VirtualDisplayRebuildCoordinator(this)
    private val displayRoutingDiagnostics = DisplayRoutingDiagnostics(this, { vdImeVerboseLogging }, vdImeLogPrefix)
    @Volatile internal var browserTeardownPhase: String = "idle"
    private val inputDebugLaunchSeq = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var currentInputDebugLaunchSeq = 0
    private val inputDebugPacketCounts = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
    private val inputDebugMovePacketCounts = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
    private val inputDebugLaunchStartElapsedMs = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val recentServerTouchTrace = java.util.ArrayDeque<String>()
    private val recentServerTouchTraceMutex = Any()
    @Volatile internal var lastRejectProbeSummary: String = ""

    @Volatile private var lastAppLaunchTime: Long = 0L
    private val paneLastLaunchTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val paneLastLaunchPackages = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val screenOffCoordinator = ScreenOffCoordinator(this)
    internal val isPhysicalScreenOff: Boolean get() = screenOffCoordinator.isPhysicalScreenOff
    internal val isLegacyScreenOffRecoveryActive: Boolean get() = screenOffCoordinator.isLegacyRecoveryActive
    internal fun updatePanelOffState(state: ScreenOffState) { _panelOffStateFlow.value = state }

    val isRunning: Boolean get() = mirrorServer != null
    val isPanelOffSupported: Boolean get() = screenOffCoordinator.isPanelOffSupported

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

    internal fun logInputDebugSnapshot(reason: String) {
        val server = mirrorServer
        val launchElapsedMs = (android.os.SystemClock.elapsedRealtime() - (inputDebugLaunchStartElapsedMs[currentInputDebugLaunchSeq] ?: android.os.SystemClock.elapsedRealtime())).coerceAtLeast(1L)
        val movePackets = inputDebugMovePacketCounts[currentInputDebugLaunchSeq]?.get() ?: 0
        val movePacketsPerSecond = (movePackets * 1000.0) / launchElapsedMs.toDouble()
//        val pipelineStates = pipelines.values.joinToString(" | ") { pipeline ->
//            val injectorState = try { pipeline.touchInjector?.debugState() ?: "injector=null" } catch (_: Exception) { "injector=error" }
//            "${pipeline.name}:displayId=${pipeline.displayId},app=${pipeline.currentApp},requested=${pipeline.requestedWidth}x${pipeline.requestedHeight},${pipeline.inputDebugSummary()},$injectorState"
//        }
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

    internal fun appendRecentServerTouchTrace(line: String) {
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

    internal fun broadcastWebDiagnostics(reason: String) {
        val server = mirrorServer ?: return
        try {
            val timestampMs = System.currentTimeMillis()
            server.broadcastControlMessage(
                JSONObject().apply {
                    put("type", "diagnostics")
                    put(
                        "server",
                        JSONObject().apply {
                            put("reason", reason)
                            put("browserConnected", browserConnected)
                            put("serverBrowserConnected", server.isBrowserConnected())
                            put("pendingDisconnect", browserSessionCoordinator.pendingDisconnectJob != null)
                            put("disconnectGraceMs", DisconnectPolicy.graceMs(isPhysicalScreenOff))
                            put("screenOff", isLegacyScreenOffRecoveryActive)
                            put("physicalScreenOff", isPhysicalScreenOff)
                            put("teardownPhase", browserTeardownPhase)
                            put("socketSummary", server.socketDebugSummary())
                            put("pipelineSnapshot", pipelineTouchSnapshot())
                            put("injectorSnapshot", injectorTouchSnapshot())
                            put("launchSeq", currentInputDebugLaunchSeq)
                            put("lastTouchPane", remoteInputCoordinator.lastTouchPane)
                    put("timestampMs", timestampMs)
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

    internal fun broadcastVideoFreeze(type: String, reason: String) {
        val server = mirrorServer ?: return
        try {
            server.setVideoFrozen(type == "freezeVideo", reason)
            val timestampMs = System.currentTimeMillis()
            server.broadcastControlMessage(
                JSONObject().apply {
                    put("type", type)
                    put("reason", reason)
                    put("timestampMs", timestampMs)
                }.toString()
            )
            Log.i(TAG, "[SCREEN_OFF] [WEB_VIDEO] control=$type reason=$reason ts=$timestampMs")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast video control type=$type reason=$reason", e)
        }
    }


    internal fun isAnyTouchInteractionActive(): Boolean {
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

    internal fun mostRecentTouchAgeMs(now: Long = android.os.SystemClock.elapsedRealtime()): Long? {
        val lastTouchAt = pipelines.values
            .map { it.lastTouchEventAt }
            .filter { it > 0L }
            .maxOrNull()
            ?: return null
        return (now - lastTouchAt).coerceAtLeast(0L)
    }

    fun recentViewportFocusAcquisitionAgeMs(): Long? = mostRecentTouchAgeMs()

    fun isRecentViewportFocusAcquisitionWindow(maxAgeMs: Long = 1200L): Boolean {
        val age = mostRecentTouchAgeMs() ?: return false
        return age in 0..maxAgeMs
    }

    internal suspend fun requestRebuild(request: RebuildRequest) =
        vdRebuildCoordinator.request(request)

    private fun startVdHardwareWorker() = vdRebuildCoordinator.start()

    fun turnPanelOffForMirroring(): Boolean =
        screenOffCoordinator.turnPanelOffForMirroring()

    fun restorePhysicalPanel() {
        screenOffCoordinator.restorePhysicalPanel()
    }
    fun setBrowserConnectionListener(listener: ((Boolean) -> Unit)?) {
        browserConnectionListener = listener
        mirrorServer?.setBrowserConnectionListener(listener)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate() - Initializing Symmetrical Pipeline Context Map Pool")
        val initialSettings = com.castla.mirror.ui.StreamSettings.load(this)
        useNativeVirtualDisplayIme = initialSettings.useNativeVirtualDisplayIme
        verboseDiagnosticsEnabled = initialSettings.verboseDiagnosticsEnabled
        logImeSelectionState("service_onCreate")
        val buildLine =
            "marker=ime_guard_v4 appId=${BuildConfig.APPLICATION_ID} versionName=${BuildConfig.VERSION_NAME} " +
                "versionCode=${BuildConfig.VERSION_CODE} buildTimestamp=${BuildConfig.BUILD_TIMESTAMP} debug=${BuildConfig.DEBUG}"
        Log.i(TAG, "[BUILD_MARKER] $buildLine")
        FileLogger.i("BUILD_MARKER", "MirrorForegroundService $buildLine")


        // Start the sequential hardware worker to handle rebuild tasks sequentially
        startVdHardwareWorker()


        pipelines["primary"] = MirroringPipeline(this, "primary", "Castla")
        pipelines["secondary"] = MirroringPipeline(this, "secondary", "Castla_Sec")
        bluetoothAudioRouteMonitor = BluetoothAudioRouteMonitor(this) { connected ->
            bluetoothAudioConnected = connected
            refreshVideoLatencies()
        }
        bluetoothAudioRouteMonitor.start()

        instance = this
        isServiceRunning = true
        val hasNotificationAccess = NotificationAccessSettingsHelper.isNotificationAccessEnabled(
            this,
            CastlaNotificationListenerService::class.java,
        )
        if (NotificationAccessSettingsHelper.shouldRequestRebind(hasNotificationAccess)) {
            NotificationListenerService.requestRebind(
                ComponentName(this, CastlaNotificationListenerService::class.java),
            )
            Log.i(TAG, "Requested notification listener rebind for active mirror session")
        }
        remoteInputCoordinator.initialize()
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

        screenOffCoordinator.start()
    }

    override fun onDestroy() {
        if (::bluetoothAudioRouteMonitor.isInitialized) {
            try { bluetoothAudioRouteMonitor.stop() } catch (_: Exception) {}
        }
        screenOffCoordinator.stop()

        Log.i(TAG, "onDestroy() - Service is being destroyed by stopService() or system.")


        // Terminate the sequential virtual display hardware worker loop
        vdRebuildCoordinator.stop()


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
                    remoteInputCoordinator.lastTouchPane = "primary"
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
                targetPipeline.audioTargetPackage = request.packageName.substringBefore('/')
                targetPipeline.currentAppUserId = request.userId
                targetPipeline.currentAppUid = try {
                    shizukuSetup?.privilegedService?.resolvePackageUidForUser(targetPipeline.audioTargetPackage, request.userId) ?: -1
                } catch (e: Exception) {
                    Log.w(TAG, "UID resolve failed package=${targetPipeline.audioTargetPackage} userId=${request.userId}", e)
                    -1
                }
                if (targetPipeline.currentAppUid >= 0) {
                    audioTargetRegistry.remember(
                        AppAudioTarget(
                            targetPipeline.audioTargetPackage,
                            targetPipeline.currentAppUserId,
                            targetPipeline.currentAppUid,
                        )
                    )
                }
                refreshVideoLatencies()
                refreshAudioCaptureRouting()

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
                val routingDecision = LaunchRouting.resolve(
                    request.packageName,
                    request.className,
                    request.launchMode,
                )
                when (routingDecision.kind) {
                    LaunchRoutingKind.STANDARD_APP -> {
                        targetPipeline.launchAppFromWebLauncher(
                            request.packageName,
                            request.className,
                            forceDisplayId = request.forceDisplayId
                        )
                    }
                    LaunchRoutingKind.WEB_URL -> {
                        targetPipeline.launchBrowser(
                            routingDecision.launchTarget,
                            sourceAppPackage = routingDecision.sourceAppPackage,
                            allowFallback = routingDecision.allowEmbeddedFallback,
                            forceEmbeddedBrowser = routingDecision.forceEmbeddedBrowser,
                        )
                    }
                }

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
        teslaBluetoothVideoLatencyMs = intent?.getIntExtra(EXTRA_TESLA_BT_VIDEO_LATENCY_MS, 0)?.coerceIn(com.castla.mirror.policy.VideoLatencyPolicy.MIN_LATENCY_MS, com.castla.mirror.policy.VideoLatencyPolicy.MAX_LATENCY_MS) ?: 0
        streamedAudioVideoLatencyMs = com.castla.mirror.policy.VideoLatencyPolicy.clampStreamedAvOffset(
            intent?.getIntExtra(EXTRA_STREAMED_AUDIO_VIDEO_LATENCY_MS, com.castla.mirror.policy.VideoLatencyPolicy.DEFAULT_STREAMED_AUDIO_LATENCY_MS)
                ?: com.castla.mirror.policy.VideoLatencyPolicy.DEFAULT_STREAMED_AUDIO_LATENCY_MS,
        )
        mirroringMode = intent?.getStringExtra(EXTRA_MIRRORING_MODE) ?: "FULL_SCREEN"
        targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""
        val runtimeSettings = com.castla.mirror.ui.StreamSettings.load(this)
        useNativeVirtualDisplayIme = runtimeSettings.useNativeVirtualDisplayIme
        verboseDiagnosticsEnabled = runtimeSettings.verboseDiagnosticsEnabled
        separateNavigationAudioToPhone = runtimeSettings.separateNavigationAudioToPhone
        audioCodecPreference = runtimeSettings.audioCodecPreference
        systemSeparatedAudioPackages = readSamsungSeparateSoundPackages()

        Log.i(TAG, "onStartCommand() - Frame profiling parameters input. HeightHint=$rawMaxHeight, FpsHint=$rawFps, Audio=$pendingAudioEnabled, audioCodecPreference=$audioCodecPreference, separateNavigationAudioToPhone=$separateNavigationAudioToPhone, systemSeparatedAudioPackages=$systemSeparatedAudioPackages, hostIp=$hostIp, relayPublishIp=$relayPublishIp")
        Log.i(
            TAG,
            "$vdImeLogPrefix [IME_ROUTING] mode=${if (useNativeVirtualDisplayIme) "native_vd_ime" else "castla_proxy_fallback"} " +
                "targetDisplayId=${activeInputDisplayId()} localIme=$useNativeVirtualDisplayIme proxyEnabled=$castlaImeProxyEnabled"
        )
        Log.i(
            TAG,
            "$vdImeLogPrefix [VD] mode=${if (useNativeVirtualDisplayIme) "native_vd_ime_primary" else "ime_proxy_primary"} " +
                "mirroringMode=$mirroringMode primaryTier=${pipelines["primary"]?.displayTier} secondaryTier=${pipelines["secondary"]?.displayTier}"
        )
        FileLogger.i(
            "IME_ROUTING",
            "$vdImeLogPrefix mode=${if (useNativeVirtualDisplayIme) "native_vd_ime" else "castla_proxy_fallback"} " +
                "targetDisplayId=${activeInputDisplayId()} localIme=$useNativeVirtualDisplayIme proxyEnabled=$castlaImeProxyEnabled"
        )

        pipelines.values.forEach { pipeline ->
            pipeline.autoResolution = (rawMaxHeight == 0)
            pipeline.currentMaxHeight = if (pipeline.autoResolution) 720 else rawMaxHeight
            pipeline.autoFps = (rawFps == 0)
            pipeline.targetFps = if (pipeline.autoFps) 30 else rawFps
        }
        refreshVideoLatencies()

        serviceScope.launch(Dispatchers.IO) {
            startPipeline(
                audioEnabled = pendingAudioEnabled,
                relayPublishIp = relayPublishIp
        )
        }
        return START_NOT_STICKY
    }

    fun updateVideoLatencySettings(teslaBluetoothMs: Int, streamedAudioMs: Int) {
        teslaBluetoothVideoLatencyMs = teslaBluetoothMs.coerceIn(com.castla.mirror.policy.VideoLatencyPolicy.MIN_LATENCY_MS, com.castla.mirror.policy.VideoLatencyPolicy.MAX_LATENCY_MS)
        streamedAudioVideoLatencyMs = com.castla.mirror.policy.VideoLatencyPolicy.clampStreamedAvOffset(streamedAudioMs)
        Log.i(TAG, "updateVideoLatencySettings bt=${teslaBluetoothVideoLatencyMs}ms streamedAudio=${streamedAudioVideoLatencyMs}ms audioEnabled=$pendingAudioEnabled")
        refreshVideoLatencies()
        mirrorServer?.broadcastAudioDelay(
            com.castla.mirror.policy.VideoLatencyPolicy.resolveStreamedAudioDelay(streamedAudioVideoLatencyMs),
        )
    }

    private fun refreshVideoLatencies() {
        pipelines.forEach { (pane, pipeline) ->
            val latencyMs = com.castla.mirror.policy.VideoLatencyPolicy.resolve(
                audioEnabled = pendingAudioEnabled,
                bluetoothAudioConnected = bluetoothAudioConnected,
                bluetoothRoutedApp = pipeline.isVideoApp,
                bluetoothLatencyMs = teslaBluetoothVideoLatencyMs,
                streamedAudioLatencyMs = streamedAudioVideoLatencyMs,
            )
            pipeline.videoLatencyMs = latencyMs
            mirrorServer?.setVideoLatency(pane, latencyMs)
        }
    }

    private fun logImeSelectionState(event: String) {
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
        val castlaImeId = "${packageName}/com.castla.mirror.input.CastlaImeService"
        val line =
            "event=$event castlaImeId=$castlaImeId defaultInputMethod=${defaultIme ?: ""} " +
                "enabledInputMethods=${enabledImes ?: ""} " +
                "mode=${if (useNativeVirtualDisplayIme) "native_vd_ime" else "castla_proxy_fallback"}"
        Log.i(TAG, "$vdImeLogPrefix [IME_SERVICE_STATE] $line")
        FileLogger.i("IME_SERVICE_STATE", "$vdImeLogPrefix $line")
    }

    internal fun scheduleDisplayRoutingDiagnostics(
        pane: String,
        service: IPrivilegedService?,
        targetPkg: String,
        targetDisplayId: Int,
        phase: String,
        launchMode: String,
        vdDisplayId: Int,
    ) = displayRoutingDiagnostics.schedule(
        pane, service, targetPkg, targetDisplayId, phase, launchMode, vdDisplayId,
    )

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

    fun onBlackoutActivityReady() {
        screenOffCoordinator.onBlackoutActivityReady()
    }

    fun onUserRequestRestoreFromBlackout() {
        screenOffCoordinator.onUserRequestRestoreFromBlackout()
    }

    internal fun startVdKeepAlive() {
        screenOffCoordinator.startVdKeepAlive()
    }

    internal fun stopVdKeepAlive() {
        screenOffCoordinator.stopVdKeepAlive()
    }

    internal suspend fun requestScreenOffRebuild(pipeline: MirroringPipeline, reason: String) {
        screenOffCoordinator.requestScreenOffRebuild(pipeline, reason)
    }
    internal fun wakeDisplayForRecovery(
        service: IPrivilegedService?,
        displayId: Int,
        reason: String,
    ) {
        if (service == null || displayId < 0) {
            logLaunchRecoveryInfo(
                "recovery_wake_skipped displayId=$displayId reason=$reason serviceAvailable=${service != null}"
            )
            return
        }
        logLaunchRecoveryInfo(
            "recovery_wake_begin displayId=$displayId reason=$reason physicalScreenOff=$isPhysicalScreenOff legacyRecovery=$isLegacyScreenOffRecoveryActive"
        )
        if (shouldThrottleRecoveryAction(displayId, reason)) {
            logLaunchRecoveryInfo(
                "recovery_wake_throttled displayId=$displayId reason=$reason"
            )
            return
        }
        try {
            if (isLegacyScreenOffRecoveryActive) {
                screenOffCoordinator.markKeepAlive()
                service.keepVirtualDisplayAlive(displayId)
                logScreenOffInfo("[SCREEN_OFF] [VD_KEEPALIVE] reason=$reason displayId=$displayId source=recovery")
                logLaunchRecoveryInfo(
                    "recovery_wake_done displayId=$displayId reason=$reason action=keepVirtualDisplayAlive"
                )
            } else if (isPhysicalScreenOff) {
                logLaunchRecoveryInfo(
                    "recovery_wake_done displayId=$displayId reason=$reason action=isolated_display_already_awake"
                )
            } else {
                service.wakeUpDisplay(displayId)
                logLaunchRecoveryInfo(
                    "recovery_wake_done displayId=$displayId reason=$reason action=wakeUpDisplay"
                )
            }
        } catch (e: Exception) {
            logLaunchRecoveryInfo(
                "recovery_wake_error displayId=$displayId reason=$reason error=${e.message ?: e::class.java.simpleName}"
            )
            Log.w(TAG, "wakeDisplayForRecovery failed reason=$reason displayId=$displayId", e)
        }
    }

    internal fun requestKeyFrameForRecovery(
        pipeline: MirroringPipeline,
        reason: String,
    ) {
        if (currentCodecMode == "mjpeg") return
        val encoderAvailable = pipeline.videoEncoder != null
        logLaunchRecoveryInfo(
            "recovery_keyframe_request pane=${pipeline.name} displayId=${pipeline.displayId} reason=$reason " +
                "encoderAvailable=$encoderAvailable firstFramePublished=${pipeline.firstFrameMetadataSent} " +
                "lastFrameRenderedTime=${pipeline.lastFrameRenderedTime}"
        )
        try {
            pipeline.videoEncoder?.requestKeyFrame()
            logLaunchRecoveryInfo(
                "recovery_keyframe_done pane=${pipeline.name} displayId=${pipeline.displayId} reason=$reason"
            )
        } catch (e: Exception) {
            logLaunchRecoveryInfo(
                "recovery_keyframe_error pane=${pipeline.name} displayId=${pipeline.displayId} reason=$reason " +
                    "error=${e.message ?: e::class.java.simpleName}"
            )
        }
    }

    internal fun shouldThrottleRecoveryAction(displayId: Int, reason: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = recentRecoveryActionAtMs[displayId] ?: 0L
        if (last > 0L && now - last < RECOVERY_ACTION_MIN_INTERVAL_MS) {
            logScreenOffInfo("[SCREEN_OFF] [RECOVERY_THROTTLE] reason=$reason displayId=$displayId elapsed=${now - last}ms")
            return true
        }
        recentRecoveryActionAtMs[displayId] = now
        return false
    }

    internal fun dismissKeyguardForRecovery(
        service: IPrivilegedService?,
        reason: String,
    ) {
        if (service == null) return
        if (isPhysicalScreenOff) {
            logScreenOffInfo("[SCREEN_OFF] [PHYSICAL_WAKE_BLOCKED] command=dismiss-keyguard reason=$reason")
            return
        }
        try {
            service.execCommand("wm dismiss-keyguard")
        } catch (e: Exception) {
            Log.w(TAG, "dismissKeyguardForRecovery failed reason=$reason", e)
        }
    }

    @Synchronized
    private fun performCleanup(reason: String) {
        if (cleanupCompleted) return
        cleanupCompleted = true
        Log.i("MirrorServiceCleanup", "begin")
        Log.i(TAG, "performCleanup() -> Starting central resource recycling sequencer. Reason: $reason")
        MirrorDiagnostics.endSession(terminalReason.get()?.let { "terminal:${it.name}" } ?: reason)
        isCleanupInProgress = true

        screenOffCoordinator.cleanup()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { try { thermalThrottleManager.unregister() } catch (_: Exception) {} }
        powerLockManager.releaseWakeLocks()
        audioOrchestrator?.stop()
        audioTargetRegistry.clear()

        pipelines.values.forEach { try { it.resizeJob?.cancel() } catch (_: Exception) {} }
        adaptiveBitrateManager.stopAllLoops()
        browserSessionCoordinator.cleanup()
        reconnectJob?.cancel()
        reconnectJob = null

        try { mirrorServer?.stopBlocking() } catch (_: Exception) {}
        mirrorServer = null



        kotlinx.coroutines.runBlocking {
            Log.i(TAG, "[Cleanup] Sequentially releasing virtual hardware display devices inside blocking coroutine.")
            pipelines.values.reversed().forEach { pipeline ->
                try { kotlinx.coroutines.withTimeoutOrNull(1500L) { pipeline.release(forcePhysical = true) } } catch (e: Exception) { Log.e(TAG, "Error releasing pane (${pipeline.name})", e) }
            }
            Log.i("MirrorServiceCleanup", "encoderReleased=true")

            try { kotlinx.coroutines.withTimeoutOrNull(1000L) { pipelines.values.firstOrNull()?.controller?.getPrivilegedService()?.restoreStayAwakeMode() } } catch (_: Exception) {}
            pipelines.values.forEach { pipeline ->
                try { pipeline.touchInjector?.detachController("perform_cleanup") } catch (_: Exception) {}
                try { kotlinx.coroutines.withTimeoutOrNull(1000L) { pipeline.controller.release() } } catch (_: Exception) {}
            }
            Log.i("MirrorServiceCleanup", "virtualDisplayReleased=true")

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
            Log.i("MirrorServiceCleanup", "projectionStopped=true")

            shizukuSetup = null
            try { serviceScope.cancel() } catch (_: Exception) {}
            try { remoteInputCoordinator.cleanup() } catch (_: Exception) {}
            try { vdDispatcher.close() } catch (_: Exception) {}
            Log.i("MirrorServiceCleanup", "threadsStopped=true")

            instance = null; isCleanupInProgress = false; isServiceRunning = false
            _serverAvailabilityFlow.value = MirrorServerAvailability.IDLE
            Log.i(TAG, "[Cleanup] Central resource recycling sequencer terminated successfully.")
            Log.i("MirrorServiceCleanup", "done")
        }
    }

    private fun startPipeline(audioEnabled: Boolean, relayPublishIp: String) {
        try {
            terminalReason.set(null)
            MirrorDiagnostics.onSessionStart()
            _serverAvailabilityFlow.value = MirrorServerAvailability.STARTING

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
                override fun startCapture(codec: String?): Boolean {
                    val selection = currentAudioCaptureSelection()
                    if (selection.includedUids.isEmpty()) {
                        Log.i(TAG, "Audio capture start deferred: no browser-routed VD app")
                        return false
                    }
                    val requested = com.castla.mirror.policy.AudioCodec.fromWireName(codec)
                    val decision = com.castla.mirror.policy.AudioCodecPolicy.select(
                        audioEnabled = pendingAudioEnabled,
                        requestedCodec = requested,
                        capabilities = com.castla.mirror.policy.AudioCodecCapabilities(
                            androidOpusEncoderSupported = com.castla.mirror.capture.RemoteSubmixOpusTranscoder.isSupported(),
                            browserOpusDecoderSupported = requested == com.castla.mirror.policy.AudioCodec.OPUS,
                        ),
                    )
                    val selectedCodec = (decision as? com.castla.mirror.policy.AudioCaptureDecision.Enabled)?.codec ?: return false
                    val nextStreamId = audioStreamGeneration.incrementAndGet()
                    val capture = AudioCapture(
                        null,
                        shizukuSetup?.privilegedService,
                        selection,
                        selectedCodec,
                        nextStreamId,
                        com.castla.mirror.policy.VideoLatencyPolicy.resolveStreamedAudioDelay(streamedAudioVideoLatencyMs),
                    ) { reason ->
                        serviceScope.launch(Dispatchers.IO) {
                            Log.w(TAG, "Audio codec fallback requested streamId=$nextStreamId reason=$reason")
                            negotiatedAudioCodec = "pcm"
                            ensureAudioCaptureState("pcm")
                        }
                    }
                    val started = capture.start { mirrorServer?.broadcastAudio(it) }
                    audioCapture = capture.takeIf { started }
                    activeAudioCaptureRouteKey = AudioCaptureRouteKey.from(selection).takeIf { started }
                    if (!started) Log.e(TAG, "Audio capture failed to start codec=$selectedCodec selection=$selection")
                    return started
                }
                override fun stopCapture() {
                    try { audioCapture?.stop() } catch (_: Exception) {}
                    audioCapture = null
                    activeAudioCaptureRouteKey = null
                }
                override fun grantAudioPermission() { tryGrantAudioCapturePermission() }
                override fun scheduleDeferredStart(delayMs: Long): Any = serviceScope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(delayMs); audioOrchestrator?.onDeferredTimerExpired() }
                override fun cancelDeferredStart(handle: Any?) { (handle as? Job)?.cancel(); if (deferredAudioStartJob == handle) deferredAudioStartJob = null }
            })

            pipelines.values.forEach { it.touchInjector = TouchInjector(width, height) }

            mirrorServer = MirrorServer(this).also { server ->
                server.setAvailabilityListener { availability ->
                    _serverAvailabilityFlow.value = availability
                }
                server.setVerboseDiagnosticsEnabled(verboseDiagnosticsEnabled)
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
                    if (event.action == "up") { remoteInputCoordinator.lastTouchPane = event.pane }
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
                    remoteInputCoordinator.lastTouchPane = "primary"
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
                server.setAudioCodecListener { codec ->
                    serviceScope.launch(Dispatchers.IO) {
                        val effectiveCodec = resolvePreferredAudioCodec(codec)
                        negotiatedAudioCodec = effectiveCodec
                        ensureAudioCaptureState(effectiveCodec)
                    }
                }
                server.setAudioSocketConnectedListener {
                    serviceScope.launch(Dispatchers.IO) {
                        audioSocketReady = true
                        val hasTarget = currentAudioCaptureSelection().includedUids.isNotEmpty()
                        val result = audioOrchestrator?.onAudioSocketConnected(
                            audioEnabled = pendingAudioEnabled && hasTarget && AudioCapture.isSupported(),
                            browserConnected = true,
                        )
                        Log.i(TAG, "Audio socket connected; captureResult=$result audioEnabled=$pendingAudioEnabled hasBrowserTarget=$hasTarget")
                    }
                }
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
                server.setAppLaunchListener { pkg, cmp, pane, isVideoApp, userId ->
                    serviceScope.launch {
                        try {
                            beginInputDebugLaunch(pane, pkg)
                            pipelines[pane]?.armTouchFocusGate(cmp ?: pkg)
                            // pipelines[pane]?.isVideoApp = isVideoApp
                            // pipelines[pane]?.launchAppFromWebLauncher(pkg, cmp)
                            // 💡 Proactively categorize the nature of the launch request here
                           val mode = if (pkg.startsWith("http") || OttCatalog.isOtt(pkg)) {
                               LaunchMode.EXTERNAL_BROWSER_URL
                           } else {
                               LaunchMode.STANDARD_APP
                           }
                           // Assemble the bus event envelope based on refined target details
                           val requestEvent = AppLaunchRequest(
                               packageName = pkg,
                               className = cmp,
                               pane = pane,
                               launchMode = mode, // Inject resolved launch mode
                               isVideoApp = isVideoApp,
                               userId = userId,
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
            refreshVideoLatencies()
            MirrorWidgetProvider.updateAllWidgets(this)
        } catch (e: Exception) { Log.e(TAG, "Fatal error on startPipeline", e); stopSelf() }
    }

    private fun onBrowserConnected() = browserSessionCoordinator.onConnected()

    private fun applyBrowserLayoutUpdate(panes: JSONArray) = browserSessionCoordinator.applyLayout(panes)

    private fun onBrowserDisconnected() {
        audioSocketReady = false
        negotiatedAudioCodec = null
        browserSessionCoordinator.onDisconnected()
    }

    internal fun cancelPendingBrowserDisconnect(reason: String) =
        browserSessionCoordinator.cancelPendingDisconnect(reason)

    private fun scheduleBrowserDisconnect() = browserSessionCoordinator.scheduleDisconnect()

    internal fun notifyBrowserConnection(connected: Boolean) { browserConnectionListener?.invoke(connected) }

    private fun currentAudioCaptureSelection(): com.castla.mirror.policy.AudioCaptureSelection {
        val routes = audioTargetRegistry.snapshot().map { target ->
            val packageName = target.packageName
            val output = com.castla.mirror.policy.AudioAppRoutePreference.outputFor(
                packageName = packageName,
                separateNavigationToPhone = separateNavigationAudioToPhone,
                systemSeparatedPackages = systemSeparatedAudioPackages,
            )
            com.castla.mirror.policy.AppAudioRoute(target, output)
        }
        return com.castla.mirror.policy.AudioRoutePolicy.select(routes)
    }

    private fun refreshAudioCaptureRouting() {
        if (!pendingAudioEnabled || !audioSocketReady) return
        val selection = currentAudioCaptureSelection()
        if (selection.includedUids.isEmpty()) {
            audioOrchestrator?.stop()
            return
        }
        val desiredRouteKey = AudioCaptureRouteKey.from(selection)
        if (audioOrchestrator?.captureActive == true && activeAudioCaptureRouteKey == desiredRouteKey) {
            Log.i(TAG, "Audio route kept without restart included=${selection.includedApps} excluded=${selection.excludedApps} route=${selection.routeMode}")
            return
        }
        audioOrchestrator?.stop()
        audioOrchestrator?.onAudioSocketConnected(audioEnabled = true, browserConnected = true)
        negotiatedAudioCodec?.let { ensureAudioCaptureState(it) }
        Log.i(TAG, "Audio route refreshed included=${selection.includedApps} excluded=${selection.excludedApps} route=${selection.routeMode}")
    }

    private fun ensureAudioCaptureState(codecOverride: String? = null) {
        val selection = currentAudioCaptureSelection()
        if (selection.includedUids.isEmpty()) return
        audioOrchestrator?.apply {
            audioEnabled = pendingAudioEnabled && AudioCapture.isSupported()
            browserConnected = audioSocketReady
            ensure(codecOverride)
        }
    }

    private fun resolvePreferredAudioCodec(browserCodec: String): String {
        val browserSupported = com.castla.mirror.policy.AudioCodec.fromWireName(browserCodec)
            ?: com.castla.mirror.policy.AudioCodec.PCM_S16LE
        return when (audioCodecPreference.resolve(browserSupported)) {
            com.castla.mirror.policy.AudioCodec.OPUS -> "opus"
            com.castla.mirror.policy.AudioCodec.PCM_S16LE -> "pcm"
        }
    }

    private fun readSamsungSeparateSoundPackages(): Set<String>? = try {
        val state = Settings.Global.getString(contentResolver, "multisound_state")
        val packages = Settings.System.getString(contentResolver, "multisound_app")
        com.castla.mirror.policy.SamsungSeparateSoundPolicy.parse(state, packages).also {
            Log.i(TAG, "Samsung separate sound state=$state packages=$packages resolved=$it")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Samsung separate sound settings unavailable; using Castla navigation fallback", e)
        null
    }

    private fun activeInputDisplayId(): Int = remoteInputCoordinator.activeInputDisplayId()
    private fun injectText(text: String) = remoteInputCoordinator.injectText(text)
    private fun injectCompositionUpdate(backspaces: Int, text: String) = remoteInputCoordinator.injectComposition(backspaces, text)
    private fun injectKeyEvent(keyCode: Int) = remoteInputCoordinator.injectKeyEvent(keyCode)

    internal fun currentPrivilegedService(): IPrivilegedService? = shizukuSetup?.privilegedService

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
                            if (castlaImeProxyEnabled) {
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
                            } else {
                                FileLogger.i("IME_ROUTING", "imeSwitchFsm skipped on reconnect reason=system_ime_mode")
                            }
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
            if (!pipeline.shouldMaterializeVirtualDisplay()) {
                Log.i(TAG, "[VDIME] skip reconnect VD materialization pane=${pipeline.name} tier=${pipeline.displayTier}")
                return@forEach
            }
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
                        wakeDisplayForRecovery(svc, displayId, "shizuku_reconnect")
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

    internal suspend fun trySetupVirtualDisplay(width: Int, height: Int, surface: Surface): Boolean = withContext(vdDispatcher) {
        shizukuSetupMutex.withLock {
            val trySetupStartedAt = android.os.SystemClock.elapsedRealtime()
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
                logLaunchRecoveryInfo(
                    "fallback_vd_bind_unstable elapsedMs=${android.os.SystemClock.elapsedRealtime() - trySetupStartedAt} " +
                        "browserConnected=$browserConnected retryCount=$shizukuBindRetryCount"
                )
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
            logLaunchRecoveryInfo(
                "fallback_vd_bind_ready elapsedMs=${android.os.SystemClock.elapsedRealtime() - trySetupStartedAt} " +
                    "serviceConnected=${setup.serviceConnected.value}"
            )
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
                if (!pipeline.shouldMaterializeVirtualDisplay()) {
                    Log.i(TAG, "[VDIME] skip initial VD materialization pane=${pipeline.name} tier=${pipeline.displayTier}")
                    return@forEach
                }
                val paneVisible = paneVisibility[pipeline.name] == true
                if (LaunchRecoveryPolicy.shouldDeferFallbackMaterialization(
                        paneName = pipeline.name,
                        paneVisible = paneVisible,
                        browserConnected = browserConnected,
                    )
                ) {
                    logLaunchRecoveryInfo(
                        "fallback_vd_deferred pane=${pipeline.name} visible=$paneVisible tier=${pipeline.displayTier} " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - trySetupStartedAt}"
                    )
                    return@forEach
                }
                val w = if (pipeline.width > 0) pipeline.width else width
                val h = if (pipeline.height > 0) pipeline.height else height
                val dpi = computeVirtualDisplayDpi(w, h)
                val pipelineSetupStartedAt = android.os.SystemClock.elapsedRealtime()
                // Minimize lock scope to prevent blocking binder calls like restoreContentLocked within the mutex.
                var activeId = -1
                var generation = -1L
                var success = false
                virtualDisplayHardwareMutex.withLock {
                    logLaunchRecoveryInfo(
                        "fallback_vd_create_begin pane=${pipeline.name} target=${w}x${h} dpi=$dpi " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - trySetupStartedAt}"
                    )
                    success = runBinderSafe {
                        pipeline.controller.createVirtualDisplay(w, h, dpi, pipeline.currentEncoderSurface ?: surface)
                        pipeline.controller.hasVirtualDisplay()
                    } ?: false
                    logLaunchRecoveryInfo(
                        "fallback_vd_create_result pane=${pipeline.name} success=$success hasVd=${pipeline.controller.hasVirtualDisplay()} " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - trySetupStartedAt}"
                    )
                    if (success) {
                        activeId = pipeline.controller.getDisplayId()
                        generation = pipeline.markVdCreated(activeId, "try_setup")
                        logLaunchRecoveryInfo(
                            "fallback_vd_create_ready pane=${pipeline.name} displayId=$activeId generation=$generation " +
                                "elapsedMs=${android.os.SystemClock.elapsedRealtime() - trySetupStartedAt} " +
                                "pipelineElapsedMs=${android.os.SystemClock.elapsedRealtime() - pipelineSetupStartedAt}"
                        )
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
                        wakeDisplayForRecovery(svc, activeId, "vd_rebuild")
                        if (currentCodecMode != "mjpeg") {
                            pipeline.videoEncoder?.requestKeyFrame()
                        }
                        CastlaTextInputRouter.getInstance().triggerRecoveryFocusNudge()
                    } catch (e: Exception) {
                        Log.w(TAG, "[VDRebuild] Soft recovery failed for pane=${pipeline.name}", e)
                    }
                    logLaunchRecoveryInfo(
                        "fallback_vd_post_ready pane=${pipeline.name} displayId=$activeId generation=$generation " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - trySetupStartedAt} " +
                            "pipelineElapsedMs=${android.os.SystemClock.elapsedRealtime() - pipelineSetupStartedAt}"
                    )
                    Log.i(TAG, "[VDRebuild] Sub-session core mounted safely. Pane: (${pipeline.name}), Id: $activeId")
                } else if (!success) {
                    logLaunchRecoveryInfo(
                        "fallback_vd_create_failed pane=${pipeline.name} elapsedMs=${android.os.SystemClock.elapsedRealtime() - trySetupStartedAt}"
                    )
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

    internal fun computeVirtualDisplayDpi(width: Int, height: Int): Int = StreamMath.applyDensityScale(StreamMath.calculateDpi(minOf(width, height)), dpiScale)
    internal suspend fun cleanupDisplay(displayId: Int) = withContext(Dispatchers.IO) {
        if (displayId < 0) return@withContext
        val service = pipelines.values.firstNotNullOfOrNull { it.controller.getPrivilegedService() } ?: return@withContext
        val removedTaskIds = VirtualDisplayTaskCleaner.cleanup(
            displayId = displayId,
            getTaskIdsOnDisplay = { targetDisplayId ->
                runBinderSafe { service.getTaskIdsOnDisplay(targetDisplayId) } ?: intArrayOf()
            },
            removeTask = { taskId -> runBinderSafe { service.removeTask(taskId) }; Unit },
            launchHome = { targetDisplayId -> runBinderSafe { service.launchHomeOnDisplay(targetDisplayId) }; Unit },
        )
        Log.i(TAG, "VD task cleanup completed displayId=$displayId taskIds=$removedTaskIds")
    }

    private val BROWSER_PACKAGES = setOf("com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.microsoft.emmx")
    internal fun markTerminal(reason: TerminalReason) { if (terminalReason.compareAndSet(null, reason)) requestStopAsync("terminal_${reason.name.lowercase()}") }
    private fun resolveLaunchComponent(packageOrComponent: String): String? {
        if (packageOrComponent.contains('/')) return packageOrComponent
        return try { packageManager.getLaunchIntentForPackage(packageOrComponent)?.component?.flattenToShortString() } catch (_: Exception) { null }
    }
    internal fun normalizeLaunchTarget(packageOrComponent: String): String = resolveLaunchComponent(packageOrComponent) ?: packageOrComponent

    internal fun buildShellLaunchCommand(
        displayId: Int,
        packageOrComponent: String,
        extraKey: String? = null,
        extraValue: String? = null,
        reorderToFront: Boolean = false
    ): String {
        val resolvedComponent = resolveLaunchComponent(packageOrComponent)
        return ShellLaunchCommandBuilder.buildAppLaunchCommand(
            displayId = displayId,
            packageOrComponent = packageOrComponent,
            resolvedComponent = resolvedComponent,
            flags = MultiDisplayLaunchPolicy.shellFlags(reorderToFront),
            extraKey = extraKey,
            extraValue = extraValue,
        )
    }

    internal fun verifySurfaceAndFallback(pipeline: MirroringPipeline, service: IPrivilegedService, displayId: Int, pkg: String, taskIds: List<Int>, packageOrComponent: String, extraKey: String?, extraValue: String?) {
        // Clean package check without hardcoded maps filter
        if (pkg.contains("com.castla.mirror") || pkg == "HOME" || pkg.isBlank()) return

        // Cancel the previous active fallback watchdog job to refresh the 5500ms grace period.
        // This prevents race condition and false positives where a subsequent fast layout rebuild
        // or concurrent launch request incorrectly triggers cold-start force stop.
        if (pipeline.activeFallbackJob?.isActive == true) {
            pipeline.debugFallbackCancels += 1
        }
        pipeline.activeFallbackJob?.cancel()

        pipeline.debugFallbackStarts += 1
        val scheduledAtMs = android.os.SystemClock.elapsedRealtime()
        val watchdogDelayMs = LaunchRecoveryPolicy.fallbackWatchdogDelayMs(isLegacyScreenOffRecoveryActive)
        logLaunchRecoveryInfo(
            "watchdog_scheduled pane=${pipeline.name} pkg=$pkg displayId=$displayId delayMs=$watchdogDelayMs " +
                "physicalScreenOff=$isPhysicalScreenOff legacyRecovery=$isLegacyScreenOffRecoveryActive taskIds=${taskIds.joinToString(prefix = "[", postfix = "]")}"
        )
        pipeline.activeFallbackJob = serviceScope.launch(Dispatchers.IO) {
            // Wait for activity manager to settle down task placement and allow the first graphic frame to render.
            // Screen-off mirroring gets a longer grace period to avoid expensive rebuild churn while the
            // panel-off recovery path is still stabilizing.
            kotlinx.coroutines.delay(watchdogDelayMs)
            try {
                if (pipeline.isTouchInteractionActive()) {
                    logLaunchRecoveryInfo(
                        "watchdog_skipped_touch pane=${pipeline.name} pkg=$pkg displayId=$displayId " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - scheduledAtMs}"
                    )
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
                logLaunchRecoveryInfo(
                    "watchdog_evaluated pane=${pipeline.name} pkg=$pkg displayId=$displayId " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime() - scheduledAtMs} " +
                        "isAbsent=$isAbsent isStagnated=$isStagnated lastFrameRenderedTime=${pipeline.lastFrameRenderedTime} " +
                        "runningTasks=${runningTasks?.joinToString(prefix = "[", postfix = "]") ?: "<null>"}"
                )

                if (isStagnated && !isAbsent) {
                    if (isLegacyScreenOffRecoveryActive) {
                        logLaunchRecoveryInfo(
                            "watchdog_action pane=${pipeline.name} pkg=$pkg displayId=$displayId action=screenoff_rebuild_and_keyframe reason=first_frame_delayed"
                        )
                        logScreenOffWarn("[SCREEN_OFF] [REVIVE_REBUILD] watchdog pane=${pipeline.name} pkg=$pkg displayId=$displayId firstFrameDelayed=true")
                        requestScreenOffRebuild(pipeline, "fallback_watchdog")
                        wakeDisplayForRecovery(service, displayId, "fallback_watchdog_rebuild")
                        requestKeyFrameForRecovery(pipeline, "fallback_watchdog_rebuild")
                    } else {
                        logLaunchRecoveryInfo(
                            "watchdog_action pane=${pipeline.name} pkg=$pkg displayId=$displayId action=await_frontend_recovery_only reason=first_frame_delayed"
                        )
                        Log.i(TAG, "[Fallback] Watchdog skipped: app ($pkg) exists, first frame delayed. displayId=$displayId")
                    }
                    return@launch
                }

                if (isAbsent) {
                    logLaunchRecoveryInfo(
                        "watchdog_action pane=${pipeline.name} pkg=$pkg displayId=$displayId action=soft_recovery_keyframe reason=task_absent"
                    )
                    Log.w(TAG, "[Fallback] Watchdog detected missing task ($pkg) on Display $displayId; soft recovery only. Skipping force-stop / am start.")
                    try {
                        if (isLegacyScreenOffRecoveryActive) {
                            logScreenOffWarn("[SCREEN_OFF] [REVIVE_REBUILD] watchdog pane=${pipeline.name} pkg=$pkg displayId=$displayId taskAbsent=true")
                            requestScreenOffRebuild(pipeline, "fallback_absent")
                        }
                        wakeDisplayForRecovery(service, displayId, "fallback_watchdog")
                        requestKeyFrameForRecovery(pipeline, "fallback_watchdog")
                        val router = CastlaTextInputRouter.getInstance()
                        router.triggerRecoveryFocusNudge()
                    } catch (e: Exception) {
                        Log.e(TAG, "[Fallback] Soft recovery failed: ${e.message}")
                    }
                    return@launch
                }
                logLaunchRecoveryInfo(
                    "watchdog_noop pane=${pipeline.name} pkg=$pkg displayId=$displayId reason=frame_or_task_healthy"
                )
            } catch (e: Exception) {
                logLaunchRecoveryInfo(
                    "watchdog_error pane=${pipeline.name} pkg=$pkg displayId=$displayId error=${e.message ?: e::class.java.simpleName}"
                )
                Log.e(TAG, "[Fallback] Critical error occurred inside surface verification coroutine: ${e.message}", e)
            } finally {
                // Safely clear the active fallback job reference if this job finished executing normally
                if (pipeline.activeFallbackJob == coroutineContext[kotlinx.coroutines.Job]) {
                    pipeline.activeFallbackJob = null
                }
                logLaunchRecoveryInfo(
                    "watchdog_finished pane=${pipeline.name} pkg=$pkg displayId=$displayId " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime() - scheduledAtMs}"
                )
            }
        }
    }

    // ==========================================
    // ENCAPSULATED VIRTUAL DISPLAY PIPELINE
    // ==========================================

}

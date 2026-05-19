package com.castla.mirror.service

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
import com.castla.mirror.capture.VirtualDisplayManager
import com.castla.mirror.input.TouchInjector
import com.castla.mirror.server.MirrorServer
import com.castla.mirror.shizuku.BinderConnectionTracker
import com.castla.mirror.shizuku.IPrivilegedService
import com.castla.mirror.shizuku.ShizukuSetup
import com.castla.mirror.ott.BrowserResolver
import com.castla.mirror.ott.OttCatalog
import com.castla.mirror.utils.ImeState
import com.castla.mirror.utils.ImeVisibilityPolicy
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
import com.castla.mirror.utils.StreamMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class MirrorForegroundService : Service() {

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "castla_mirror"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.castla.mirror.ACTION_STOP"
        const val EXTRA_MAX_RESOLUTION = "max_resolution"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUDIO = "audio_enabled"
        const val EXTRA_MIRRORING_MODE = "mirroring_mode"
        const val EXTRA_TARGET_PACKAGE = "target_package"

        /** Observable service running state ??UI can collect this to stay in sync. */
        private val _serviceRunningFlow = MutableStateFlow(false)
        val serviceRunningFlow: StateFlow<Boolean> = _serviceRunningFlow

        /** True while the service is actively tearing down the previous session. */
        private val _cleanupInProgressFlow = MutableStateFlow(false)
        val cleanupInProgressFlow: StateFlow<Boolean> = _cleanupInProgressFlow

        /** Current panel-off state ??UI observes this for button state. */
        private val _panelOffStateFlow = MutableStateFlow(ScreenOffState.ACTIVE)
        val panelOffStateFlow: StateFlow<ScreenOffState> = _panelOffStateFlow


        var isServiceRunning: Boolean
            get() = _serviceRunningFlow.value
            set(value) { _serviceRunningFlow.value = value }

        var isCleanupInProgress: Boolean
            get() = _cleanupInProgressFlow.value
            set(value) { _cleanupInProgressFlow.value = value }

        @JvmStatic
        var instance: MirrorForegroundService? = null
            private set

        /** Resolution/FPS tiers for auto mode, ordered from most conservative to highest. */
        // Expanded to map explicit target bitrates per tier for hardware-driven runtime scaling
        data class AutoTier(val maxHeight: Int, val fps: Int, val bitrate: Int, val label: String)
        val AUTO_TIERS = listOf(
            AutoTier(720, 15, 1_200_000, "720p15"),  // Critical network congestion recovery tier
            AutoTier(720, 30, 2_500_000, "720p30"),  // Baseline mid-quality tier
            AutoTier(720, 60, 4_000_000, "720p60"),  // High-performance smooth fluid tier

            // 1080p Profiling Group (Static resolution, stepping up frame performance)
            AutoTier(1080, 15, 2_500_000, "1080p15"), // Critical bandwidth saving tier for full HD
            AutoTier(1080, 30, 4_500_000, "1080p30"), // Standard crisp operational tier
            AutoTier(1080, 60, 7_500_000, "1080p60")  // Ultra-smooth full-fidelity premium tier
        )
        /** Check interval for auto-scale loop */
        private const val AUTO_SCALE_INTERVAL_MS = 10_000L
        /** Initial delay before first auto-scale evaluation */
        private const val AUTO_SCALE_INITIAL_DELAY_MS = 5_000L
        // Grace period constants are now in DisconnectPolicy
        /** Interval for poking the VD awake while the physical screen is off. */
        private const val VD_KEEP_ALIVE_INTERVAL_MS = 3_000L

    }

    /** Binder for local (same-process) binding */
    inner class LocalBinder : Binder() {
        val service: MirrorForegroundService get() = this@MirrorForegroundService
    }

    private val binder = LocalBinder()

    private var mirrorServer: MirrorServer? = null
    lateinit var primaryPipeline: VirtualDisplayPipeline
        lateinit var secondaryPipeline: VirtualDisplayPipeline

    private lateinit var powerLockManager: PowerLockManager
    private lateinit var thermalThrottleManager: ThermalThrottleManager
    private lateinit var adaptiveBitrateManager: AdaptiveBitrateManager

    val thermalStatus: kotlinx.coroutines.flow.StateFlow<Int>
        get() = thermalThrottleManager.thermalStatus

    private var preThermalTargetBitrate: Int
        get() = thermalThrottleManager.preThermalTargetBitrate
        set(value) { thermalThrottleManager.preThermalTargetBitrate = value }
    private var thermalFpsOverride: Int?
        get() = thermalThrottleManager.thermalFpsOverride
        set(value) { thermalThrottleManager.thermalFpsOverride = value }
    private var thermalMaxHeight: Int?
        get() = thermalThrottleManager.thermalMaxHeight
        set(value) { thermalThrottleManager.thermalMaxHeight = value }

    private var targetBitrate: Int
        get() = adaptiveBitrateManager.targetBitrate
        set(value) { adaptiveBitrateManager.targetBitrate = value }
    private var lastCongestionTimeMs: Long
        get() = adaptiveBitrateManager.lastCongestionTimeMs
        set(value) { adaptiveBitrateManager.lastCongestionTimeMs = value }
    private var autoTierIndex: Int
        get() = adaptiveBitrateManager.autoTierIndex
        set(value) { adaptiveBitrateManager.autoTierIndex = value }
    private var autoStableCount: Int
        get() = adaptiveBitrateManager.autoStableCount
        set(value) { adaptiveBitrateManager.autoStableCount = value }
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
    private var virtualDisplayManager: VirtualDisplayManager? = null
    private var shizukuSetup: ShizukuSetup? = null
    private var currentFps: Int = 30
    private var currentMaxHeight: Int = 720
    private var mirroringMode: String = "FULL_SCREEN"
    private var targetPackage: String = ""
    private var browserConnectionListener: ((Boolean) -> Unit)? = null
    @Volatile private var stopRequested = false
    @Volatile private var cleanupCompleted = false
    private var isWakingUpFromPowerButton = false
    private val terminalReason = java.util.concurrent.atomic.AtomicReference<TerminalReason?>(null)
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var resizeJob: Job? = null
    private var secondaryResizeJob: Job? = null
    private var pendingBrowserDisconnectJob: Job? = null
    private var browserConnected = false
    private var secondaryRequestedWidth: Int = 0
    private var secondaryRequestedHeight: Int = 0
    @Volatile private var currentCodecMode: String = "h264"
    private val pipelineMutex = Mutex()
    private val secondaryPipelineMutex = Mutex()
    private val primaryVdOperationMutex = Mutex()

    enum class PipelineState {
        IDLE,
        REBUILDING
    }

    data class RebuildRequest(
        val width: Int,
        val height: Int,
        val force: Boolean,
        val forceSingle: Boolean
    )

    data class SecondaryRebuildRequest(
        val width: Int,
        val height: Int
    )




    private val mainHandler = Handler(Looper.getMainLooper())

    // ABR (Adaptive Bitrate) state
   // Thermal throttling: stores original bitrate before thermal reduction for restoration
   // Thermal fps/resolution overrides ??applied by rebuildPipeline when non-null
   // Display density scale (0.7 = default small, lower = more compact UI / more content)
    private var dpiScale: Float = 0.7f
    // Guard against concurrent Shizuku binding attempts
    @Volatile private var shizukuSetupInProgress = false
    private val shizukuSetupCallbacks = java.util.Collections.synchronizedList(mutableListOf<(Boolean) -> Unit>())
    private var shizukuBindRetryCount = 0
    private val SHIZUKU_MAX_RETRIES = 2
    private val BIND_WAIT_BUDGET_MS = 8_000L

    /**
     * Singleton coroutine that observes [ShizukuSetup.serviceConnected] for the
     * service lifetime. Translates flow emissions into discrete transitions via
     * [BinderConnectionTracker] so duplicate connects (a frequent symptom on
     * Samsung when the user-service is briefly killed and respawned) do not
     * spawn a new VirtualDisplay per spurious callback.
     */
    private var reconnectJob: Job? = null

    // Auto mode: dynamically adjusts resolution/fps based on conditions.
    private var autoResolution: Boolean = false
    private var autoFps: Boolean = false
    // Stability counter: number of consecutive healthy check intervals
   // Browser quality report ??updated asynchronously from control socket
   // WakeLocks to keep streaming alive when screen is off
   // Deferred pipeline state: heavy capture/encoding starts only when browser connects
    private var pendingAudioEnabled = false
    private var deferredAudioStartJob: Job? = null
   private var screenOffReceiver: BroadcastReceiver? = null
    private var vdKeepAliveJob: Job? = null
    private var appExitMonitorJob: Job? = null
    @Volatile private var lastAppLaunchTime: Long = 0L
    private val screenOffPolicy = ScreenOffPolicy()
    private val keyguardManager by lazy {
        getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
    }

    val isRunning: Boolean
        get() = mirrorServer != null

    /** Whether this device supports panel-off (false after first failure). */
    val isPanelOffSupported: Boolean
        get() = screenOffPolicy.isPanelOffSupported

    /**
     * Turn off the physical display panel while keeping mirroring alive.
     */
    fun turnPanelOffForMirroring(): Boolean {
        if (!isRunning) {
            Log.w(TAG, "turnPanelOffForMirroring: service not running")
            return false
        }
        if (!browserConnected) {
            Log.w(TAG, "turnPanelOffForMirroring: browser not connected")
            return false
        }
        if (virtualDisplayManager?.hasVirtualDisplay() != true) {
            Log.w(TAG, "turnPanelOffForMirroring: no active virtual display")
            return false
        }
        if (screenOffPolicy.isScreenOff) {
            Log.d(TAG, "turnPanelOffForMirroring: already off")
            return true
        }

        powerLockManager.acquireWakeLocks()

        val action = screenOffPolicy.onScreenOff(panelOffSupported = true)
        logScreenState("Panel OFF requested via button (action=$action)")
        executeScreenOffAction(action)
        _panelOffStateFlow.value = screenOffPolicy.state
        return screenOffPolicy.state == ScreenOffState.PANEL_OFF_ACTIVE
    }

    /**
     * Restore the physical display panel.
     */
    fun restorePhysicalPanel() {
        if (!screenOffPolicy.isScreenOff) return
        val action = screenOffPolicy.onScreenOn()
        logScreenState("Panel ON requested via button (action=$action)")
        executeScreenOnAction(action)
        _panelOffStateFlow.value = screenOffPolicy.state
    }

    /** Current thermal throttle level exposed to the UI. 0 = normal, higher = hotter. */
        fun setBrowserConnectionListener(listener: ((Boolean) -> Unit)?) {
        browserConnectionListener = listener
        mirrorServer?.setBrowserConnectionListener(listener)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        primaryPipeline = VirtualDisplayPipeline("primary")
        secondaryPipeline = VirtualDisplayPipeline("secondary")
        instance = this
        isServiceRunning = true
        isCleanupInProgress = false
        createNotificationChannel()
        observeAppLaunchRequests()

        // Initialize peripheral managers with functional bindings
        powerLockManager = PowerLockManager(this)
        thermalThrottleManager = ThermalThrottleManager(
            context = this,
            serviceScope = serviceScope,
            mainExecutor = mainExecutor,
            primaryPipeline = primaryPipeline,
            getTargetBitrate = { targetBitrate },
            setTargetBitrate = { targetBitrate = it },
            getAudioOrchestrator = { audioOrchestrator },
            getBrowserConnected = { browserConnected },
            getMirrorServer = { mirrorServer },
            rebuildPipeline = { w, h, f -> rebuildPipeline(w, h, f) },
            onThermalThrottled = {
                autoTierIndex = 0
                autoStableCount = 0
            }
        )
        adaptiveBitrateManager = AdaptiveBitrateManager(
            context = this,
            serviceScope = serviceScope,
            primaryPipeline = primaryPipeline,
            secondaryPipeline = secondaryPipeline,
            getBrowserConnected = { browserConnected },
            getIsServiceRunning = { isServiceRunning },
            getIsCurrentAppVideo = { isCurrentAppVideo },
            getIsSecondaryAppVideo = { isSecondaryAppVideo },
            getHasSplit = { secondaryPipeline.displayId >= 0 && secondaryPipeline.width > 0 },
            getThermalActive = { thermalThrottleManager.thermalStatus.value >= PowerManager.THERMAL_STATUS_LIGHT },
            getThermalFpsOverride = { thermalFpsOverride },
            getThermalMaxHeight = { thermalMaxHeight },
            getMirrorServer = { mirrorServer },
            getCurrentFps = { currentFps },
            setCurrentFps = { currentFps = it },
            getCurrentMaxHeight = { currentMaxHeight },
            setCurrentMaxHeight = { currentMaxHeight = it },
            rebuildPipeline = { w, h, f, fs -> rebuildPipeline(w, h, f, fs) },
            rebuildSecondaryPipeline = { w, h -> rebuildSecondaryPipeline(w, h) }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalThrottleManager.register()
        }

        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> {
                        Log.i(TAG, "Screen OFF detected ??using scrcpy approach")
                        onPhoneScreenOff()
                        mainHandler.postDelayed({
                            if (keyguardManager.isKeyguardLocked) {
                                MirrorDiagnostics.log(DiagnosticEvent.KEYGUARD_LOCKED)
                            }
                        }, 500)
                    }
                    android.content.Intent.ACTION_SCREEN_ON -> {
                        Log.i(TAG, "Screen ON detected")
                        onPhoneScreenOn()
                    }
                    android.content.Intent.ACTION_USER_PRESENT -> {
                        MirrorDiagnostics.log(DiagnosticEvent.KEYGUARD_UNLOCKED)
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenOffReceiver, filter)
    }
    
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    
    





    private var isCurrentAppVideo = false
    private var isSecondaryAppVideo = false
    private var lastBitrateChangeMs = 0L

    private fun observeAppLaunchRequests() {
        serviceScope.launch {
            com.castla.mirror.utils.AppLaunchBus.events.collect { request ->
                val component = if (request.className != null) {
                    "${request.packageName}/${request.className}"
                } else {
                    request.packageName
                }
                val pane = request.pane ?: "primary"
                Log.i(TAG, "VD launch request: $component (video=${request.isVideoApp}, mode=${request.launchMode}, pane=$pane)")

                when (request.launchMode) {
                    LaunchMode.EXTERNAL_BROWSER_URL -> {
                        val url = request.url ?: return@collect
                        val preserveSecondary = pane == "primary" && secondaryPipeline.displayId >= 0
                        launchBrowserTarget(
                            pane = pane,
                            url = url,
                            sourceAppPackage = request.sourceAppPackage,
                            allowFallback = request.allowEmbeddedFallback,
                            preserveSecondary = preserveSecondary
                        )
                    }
                    LaunchMode.INTERNAL_WEBVIEW -> {
                        val activityClassName = component.substringAfter('/', "com.castla.mirror.ui.WebBrowserActivity")
                        val url = request.url ?: request.intentExtra ?: return@collect
                        launchWebTarget(
                            pane = pane,
                            activityClassName = activityClassName,
                            url = url,
                            preserveSecondary = pane == "primary" && secondaryPipeline.displayId >= 0
                        )
                    }
                    LaunchMode.STANDARD_APP -> {
                        if (request.intentExtra != null) {
                            val activityClassName = component.substringAfter('/', "com.castla.mirror.ui.WebBrowserActivity")
                            launchWebTarget(
                                pane = pane,
                                activityClassName = activityClassName,
                                url = request.intentExtra,
                                preserveSecondary = pane == "primary" && secondaryPipeline.displayId >= 0
                            )
                        } else {
                            launchStandardTarget(
                                pane = pane,
                                launchTarget = component,
                                preserveSecondary = pane == "primary" && secondaryPipeline.displayId >= 0
                            )
                        }
                    }
                }
                val now = android.os.SystemClock.elapsedRealtime()
                val isSecondary = (pane == "secondary")
                val videoChanged = if (isSecondary) {
                    val changed = request.isVideoApp != isSecondaryAppVideo
                    if (changed) isSecondaryAppVideo = request.isVideoApp
                    changed
                } else {
                    val changed = request.isVideoApp != isCurrentAppVideo
                    if (changed) isCurrentAppVideo = request.isVideoApp
                    changed
                }

                if (videoChanged && now - lastBitrateChangeMs > 500) {
                    lastBitrateChangeMs = now
                    rebalanceDualDisplayBitrates()
                }

                if (autoResolution || autoFps) {
                    val activeTiers = AdaptiveBitrateManager.AUTO_TIERS.filter { it.maxHeight == currentMaxHeight }
                    val boostTier = AutoScalePolicy.ottMinTier(
                        currentTierIndex = autoTierIndex,
                        isVideoApp = isCurrentAppVideo,
                        thermalStatus = thermalThrottleManager.thermalStatus.value,
                        tierCount = activeTiers.size
                    )
                    if (boostTier != null && boostTier < activeTiers.size) {
                        autoTierIndex = boostTier
                        autoStableCount = 0
                        adaptiveBitrateManager.applyAutoTier(autoResolution, autoFps)
                        adaptiveBitrateManager.notifyAutoTierChange("ott_boost")
                        Log.i(TAG, "OTT tier boost ??jumped to ${activeTiers[autoTierIndex].label}")
                    }
                }

                val profileMsg = JSONObject().apply {
                    put("type", "ottProfileHint")
                    put("active", isCurrentAppVideo)
                }.toString()
                mirrorServer?.broadcastControlMessage(profileMsg)
                Log.i(TAG, "OTT profile hint: active=$isCurrentAppVideo")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStopAsync("notification_action")
            return START_NOT_STICKY
        }

        val notification = createNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        Log.i(TAG, "onStartCommand: Shizuku virtual-display mode")

        val rawMaxHeight = intent!!.getIntExtra(EXTRA_MAX_RESOLUTION, 0)
        autoResolution = rawMaxHeight == 0
        currentMaxHeight = if (autoResolution) 720 else rawMaxHeight

        val rawFps = intent.getIntExtra(EXTRA_FPS, 0)
        autoFps = rawFps == 0
        val settingsFps = if (autoFps) 30 else rawFps

        Log.i(TAG, "Mode: autoRes=$autoResolution autoFps=$autoFps initialMaxHeight=$currentMaxHeight initialFps=$settingsFps")
        val audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO, false)
        mirroringMode = intent.getStringExtra(EXTRA_MIRRORING_MODE) ?: "FULL_SCREEN"
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""

        startPipeline(settingsFps, audioEnabled)

        return START_NOT_STICKY
    }



    private fun requestStopAsync(reason: String) {
        if (stopRequested) {
            Log.i(TAG, "Stop already requested, ignoring duplicate: $reason")
            return
        }
        stopRequested = true
        Log.i(TAG, "Async stop requested: $reason")

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop foreground notification cleanly", e)
        }

        Thread {
            performCleanup(reason)
            mainHandler.post {
                MirrorWidgetProvider.updateAllWidgets(this)
                stopSelf()
            }
        }.start()
    }

    private fun logScreenState(event: String) {
        val keyguardLocked = keyguardManager.isKeyguardLocked
        val deviceLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            keyguardManager.isDeviceLocked else keyguardLocked
        val vdId = virtualDisplayManager?.getDisplayId() ?: -1
        Log.i(TAG, "[BUILD:screen-off-v3] $event ??" +
                "state=${screenOffPolicy.state}, keyguardLocked=$keyguardLocked, deviceLocked=$deviceLocked, " +
                "browserConnected=$browserConnected, serverConnected=${mirrorServer?.isBrowserConnected()}, " +
                "wakeLockHeld=${powerLockManager.isHeld}, vdId=$vdId, panelOffSupported=${screenOffPolicy.isPanelOffSupported}")
    }

    private fun onPhoneScreenOff() {
        MirrorDiagnostics.log(DiagnosticEvent.SCREEN_OFF)
        logScreenState("onPhoneScreenOff() called. state=${screenOffPolicy.state}")
        
        if (screenOffPolicy.state == ScreenOffState.ACTIVE) {
            isWakingUpFromPowerButton = true
            
            val action = screenOffPolicy.onScreenOff(panelOffSupported = screenOffPolicy.isPanelOffSupported)
            logScreenState("Screen OFF (action=$action)")
            executeScreenOffAction(action)
            _panelOffStateFlow.value = screenOffPolicy.state
        } else {
            Log.i(TAG, "Power button pressed while panel was OFF ??restoring physical panel")
            isWakingUpFromPowerButton = false
            
            val action = screenOffPolicy.onScreenOn()
            logScreenState("Screen ON (action=$action)")
            executeScreenOnAction(action)
            _panelOffStateFlow.value = screenOffPolicy.state
        }
    }

    private fun onPhoneScreenOn() {
        MirrorDiagnostics.log(DiagnosticEvent.SCREEN_ON)
        logScreenState("onPhoneScreenOn() called. isWakingUpFromPowerButton=$isWakingUpFromPowerButton")
        
        if (isWakingUpFromPowerButton) {
            isWakingUpFromPowerButton = false
            Log.i(TAG, "Screen ON broadcast received from our own WAKEUP injection ??keeping physical panel OFF")
            return
        }
        
        val action = screenOffPolicy.onScreenOn()
        logScreenState("Screen ON (action=$action)")
        executeScreenOnAction(action)
        _panelOffStateFlow.value = screenOffPolicy.state

        cancelPendingBrowserDisconnect("screen_on")

        val stillConnected = mirrorServer?.isBrowserConnected() == true
        if (!stillConnected && browserConnected && !isCleanupInProgress) {
            Log.i(TAG, "Screen ON ??browser gone while screen was off, executing deferred teardown")
            serviceScope.launch {
                onBrowserDisconnected()
                browserConnectionListener?.invoke(false)
            }
        }
    }

    private fun executeScreenOffAction(action: ScreenOffAction) {
        when (action) {
            ScreenOffAction.TURN_PANEL_OFF -> {
                val vdm = virtualDisplayManager
                if (vdm == null) {
                    Log.w(TAG, "Panel-off requested but no VirtualDisplayManager ??falling back")
                    val fallback = screenOffPolicy.onPanelOffResult(success = false)
                    executeScreenOffAction(fallback)
                    return
                }
                
                try {
                    vdm.getPrivilegedService()?.execCommand("input keyevent 224")
                    vdm.getPrivilegedService()?.execCommand("wm dismiss-keyguard")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to inject WAKEUP/dismiss-keyguard keyevents", e)
                }

                serviceScope.launch {
                    var success = false
                    for (i in 1..10) {
                        try {
                            success = vdm.setPhysicalDisplayPower(false)
                        } catch (_: Exception) {}
                        kotlinx.coroutines.delay(100)
                    }
                    Log.i(TAG, "[BUILD:screen-off-v3] Physical panel OFF burst complete: final_success=$success")
                    
                    serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        val fallback = screenOffPolicy.onPanelOffResult(success)
                        if (fallback != ScreenOffAction.NONE) {
                            executeScreenOffAction(fallback)
                        }
                    }
                }
            }
            ScreenOffAction.START_KEEP_ALIVE -> {
                startVdKeepAlive()
            }
            ScreenOffAction.NONE -> {}
            else -> Log.w(TAG, "Unexpected screen-off action: $action")
        }
    }

    private fun executeScreenOnAction(action: ScreenOffAction) {
        when (action) {
            ScreenOffAction.RESTORE_PANEL -> {
                stopVdKeepAlive()
                val restored = virtualDisplayManager?.setPhysicalDisplayPower(true) ?: false
                Log.i(TAG, "Physical panel restored: success=$restored")
            }
            ScreenOffAction.STOP_KEEP_ALIVE -> {
                stopVdKeepAlive()
            }
            ScreenOffAction.NONE -> {}
            else -> Log.w(TAG, "Unexpected screen-on action: $action")
        }
    }

    private fun startVdKeepAlive() {
        stopVdKeepAlive()
        val vdm = virtualDisplayManager ?: run {
            Log.w(TAG, "VD keep-alive skipped ??no VirtualDisplayManager")
            return
        }
        vdKeepAliveJob = serviceScope.launch {
            Log.i(TAG, "[BUILD:screen-off-v3] VD keep-alive starting (interval=${VD_KEEP_ALIVE_INTERVAL_MS}ms, vdId=${vdm.getDisplayId()})")
            vdm.keepDisplayAwake()
            while (true) {
                kotlinx.coroutines.delay(VD_KEEP_ALIVE_INTERVAL_MS)
                vdm.keepDisplayAwake()
            }
        }
        startAppExitMonitor()
    }

    private fun stopVdKeepAlive() {
        vdKeepAliveJob?.cancel()
        vdKeepAliveJob = null
        stopAppExitMonitor()
    }

    private fun startAppExitMonitor() {
        stopAppExitMonitor()
        val vdm = virtualDisplayManager ?: return
        val displayId = vdm.getDisplayId()
        if (displayId < 0) return
 
        appExitMonitorJob = serviceScope.launch {
            Log.i(TAG, "VD app-exit monitor starting for display $displayId")
            while (true) {
                kotlinx.coroutines.delay(2000L)
                if (secondaryPipeline.displayId >= 0) {
                    // Bypass exit monitor entirely while both independent VD panes are active.
                    // to prevent annoying spontaneous exits due to system focus/display adjustments.
                    continue
                }
                val currentApp = primaryPipeline.currentApp
                if (currentApp.isNotBlank() && currentApp != "HOME" && currentApp != "com.android.settings") {
                    val timeSinceLaunch = System.currentTimeMillis() - lastAppLaunchTime
                    val service = vdm.getPrivilegedService()
                    if (service != null) {
                        try {
                            val activeTasks = service.getRunningTasksOnDisplay(displayId) ?: emptyList()
                            
                            // If our custom Home activity is detected at the top of the virtual display,
                            // it means the user manually exited or went home.
                            val isHomeAtTop = activeTasks.firstOrNull()?.contains("VirtualDisplayHomeActivity") == true

                            if (isHomeAtTop) {
                                Log.i(TAG, "Home activity detected at the top of VD $displayId. Sending stream stopped notification.")
                                primaryPipeline.currentApp = "HOME"
                                mirrorServer?.broadcastControlMessage("{\"type\":\"APP_STREAM_STOPPED\"}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to query active tasks in VD", e)
                        }
                    }
                }
            }
        }
    }

    private fun stopAppExitMonitor() {
        appExitMonitorJob?.cancel()
        appExitMonitorJob = null
    }

    @Synchronized
    private fun performCleanup(reason: String) {
        if (cleanupCompleted) {
            Log.i(TAG, "Cleanup already completed, skipping: $reason")
            return
        }
        cleanupCompleted = true 
        val effectiveReason = terminalReason.get()?.let { "terminal:${it.name}" } ?: reason
        Log.i(TAG, "Performing cleanup: $effectiveReason")
        FileLogger.i(TAG, "Performing cleanup: $effectiveReason")
        MirrorDiagnostics.endSession(effectiveReason)
        isCleanupInProgress = true

        if (screenOffPolicy.state == ScreenOffState.PANEL_OFF_ACTIVE ||
            screenOffPolicy.state == ScreenOffState.PANEL_OFF_PENDING) {
            try {
                virtualDisplayManager?.setPhysicalDisplayPower(true)
                Log.i(TAG, "Physical display restored to ON during cleanup")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore physical display", e)
            }
        }
        screenOffPolicy.reset()
        _panelOffStateFlow.value = ScreenOffState.ACTIVE

        try { screenOffReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        screenOffReceiver = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                thermalThrottleManager.unregister()
            } catch (_: Exception) {}
        }
        powerLockManager.releaseWakeLocks()
        stopVdKeepAlive()

        audioOrchestrator?.stop()

        try { resizeJob?.cancel() } catch (_: Exception) {}
        adaptiveBitrateManager.stopAbrLoop()
        adaptiveBitrateManager.stopAutoScaleLoop()
        try { serviceScope.cancel() } catch (_: Exception) {}
        try { compositionDispatcher.close() } catch (_: Exception) {}

        releaseSecondaryPipeline(clearState = true)
        try { removeAllVdTasks() } catch (e: Exception) { Log.w(TAG, "Failed to remove VD tasks", e) }
        try { pendingBrowserDisconnectJob?.cancel() } catch (_: Exception) {}
        pendingBrowserDisconnectJob = null
        try { reconnectJob?.cancel() } catch (_: Exception) {}
        reconnectJob = null
        try {
            virtualDisplayManager?.getPrivilegedService()?.restoreStayAwakeMode()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore stay-awake mode", e)
        }
        try { virtualDisplayManager?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release virtual display manager", e) }
        try { shizukuSetup?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release shizuku setup", e) }
        try { primaryPipeline.videoEncoder?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release video encoder", e) }
        try { primaryPipeline.jpegEncoder?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release jpeg encoder", e) }
        try { primaryPipeline.touchInjector?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release touch injector", e) }
        try { mirrorServer?.stop() } catch (e: Exception) { Log.w(TAG, "Failed to stop mirror server", e) }

        virtualDisplayManager = null
        shizukuSetup = null
        primaryPipeline.videoEncoder = null
        primaryPipeline.jpegEncoder = null
        primaryPipeline.touchInjector = null
        mirrorServer = null

        instance = null
        isCleanupInProgress = false
        isServiceRunning = false
        Log.i(TAG, "Cleanup completed: $reason")
    }



    
    
    
    private fun startPipeline(
        fps: Int,
        audioEnabled: Boolean
    ) {
        try {
            terminalReason.set(null)
            MirrorDiagnostics.onSessionStart()

            val metrics = resources.displayMetrics
            val rawWidth = metrics.widthPixels.coerceAtMost(1920)
            val rawHeight = metrics.heightPixels.coerceAtMost(1080)
            val effectiveMaxHeight = effectiveMaxHeightForRequest(rawHeight)

            var width = rawWidth
            var height = rawHeight

            if (height > effectiveMaxHeight) {
                val scale = effectiveMaxHeight.toFloat() / height
                height = effectiveMaxHeight
                width = (width * scale).toInt()
            }

            width = (width + 15) and 15.inv()
            height = (height + 15) and 15.inv()

            primaryPipeline.width = width
            primaryPipeline.height = height
            currentFps = fps
            pendingAudioEnabled = audioEnabled

            audioOrchestrator = AudioCaptureOrchestrator(object : AudioCaptureOrchestrator.Actions {
                override fun startCapture(codec: String?) {
                    audioCapture = AudioCapture(null, shizukuSetup?.privilegedService).also { audio ->
                        if (codec == "pcm") {
                            audio.startPcmOnly { audioData -> mirrorServer?.broadcastAudio(audioData) }
                        } else {
                            audio.start { audioData -> mirrorServer?.broadcastAudio(audioData) }
                        }
                    }
                    Log.i(TAG, "Audio capture started (codec=${codec ?: "default"})")
                }
                override fun stopCapture() {
                    try { audioCapture?.stop() } catch (_: Exception) {}
                    audioCapture = null
                }
                override fun grantAudioPermission() {
                    tryGrantAudioCapturePermission()
                }
                override fun scheduleDeferredStart(delayMs: Long): Any? {
                    val job = serviceScope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(delayMs)
                        audioOrchestrator?.onDeferredTimerExpired()
                    }
                    deferredAudioStartJob = job
                    return job
                }
                override fun cancelDeferredStart(handle: Any?) {
                    (handle as? Job)?.cancel()
                    if (deferredAudioStartJob == handle) deferredAudioStartJob = null
                }
            })

            primaryPipeline.touchInjector = TouchInjector(width, height)

            mirrorServer = MirrorServer(this).also { server ->
                server.setNetworkCongestionListener { adaptiveBitrateManager.onNetworkCongestion() }
                server.setTouchListener { event ->
                    if (event.pane == "secondary") {
                        secondaryPipeline.touchInjector?.onTouchEvent(event)
                    } else {
                        primaryPipeline.touchInjector?.onTouchEvent(event)
                    }
                    if (event.action == "down") {
                        bubbleClosedByUser = false
                    }
                    if (event.action == "up") {
                        lastTouchPane = event.pane
                        checkImeAndNotifyBrowser()
                    }
                }
                server.setCodecModeListener { mode -> onCodecModeRequest(mode) }
                server.setViewportChangeListener { pane, w, h, layoutMode ->
                    if (pane == "secondary") {
                        onSecondaryViewportChange(w, h)
                    } else {
                        onViewportChange(w, h, layoutMode)
                    }
                }
                server.setTextInputListener { text -> injectText(text) }
                server.setKeyEventListener { keyCode -> injectKeyEvent(keyCode) }
                server.setCompositionUpdateListener { bs, text -> injectCompositionUpdate(bs, text) }
                server.setBubbleClosedListener {
                    Log.d(TAG, "Browser reported bubbleClosed ??resetting IME state")
                    bubbleClosedByUser = true
                    lastImeState = false
                    lastBroadcastPane = null
                    haveSeenRealImeShow = false
                    cancelImeHideWatchdog()
                }
                server.setAudioCodecListener { codec -> onAudioCodecRequest(codec) }
                server.setAudioSocketConnectedListener { audioOrchestrator?.onAudioSocketConnected() }
                server.setGoHomeListener {
                    Log.i(TAG, "Navigating to home requested by Web Launcher")
                    releaseSecondaryPipeline(clearState = true)
                    serviceScope.launch(Dispatchers.IO) {
                        primaryVdOperationMutex.withLock {
                            val token = currentPrimaryVdToken()
                            if (token != null) {
                                virtualDisplayManager?.launchHomeOnDisplay()
                            } else {
                                Log.w(TAG, "Skipping HOME launch: primary virtual display is not current")
                            }
                        }
                    }
                    primaryPipeline.currentApp = "HOME"
                    primaryPipeline.currentWebUrl = null
                }
                server.setAppLaunchListener { pkgName, componentName, pane ->
                    launchAppFromWebLauncher(pkgName, componentName, pane)
                }

                server.setCloseSplitListener {
                    Log.i(TAG, "Close split requested ??releasing secondary display and restoring primary fullscreen")
                    releaseSecondaryPipeline(clearState = true)
                }

                server.setDisplayDensityListener { scale ->
                    Log.i(TAG, "Display density scale changed to $scale")
                    dpiScale = scale
                    val vdm = virtualDisplayManager
                    if (vdm != null && vdm.hasVirtualDisplay() && primaryPipeline.width > 0 && primaryPipeline.height > 0) {
                        val dpi = computeVirtualDisplayDpi(primaryPipeline.width, primaryPipeline.height)
                        vdm.resizeDisplay(vdm.getDisplayId(), primaryPipeline.width, primaryPipeline.height, dpi)
                        Log.i(TAG, "Updated VD DPI to $dpi (scale=$scale, size=${primaryPipeline.width}x${primaryPipeline.height})")
                    }
                }

                server.setQualityReportListener { dropped, avgDelay, backlogDrops ->
                    adaptiveBitrateManager.lastQualityDroppedFrames = dropped
                    adaptiveBitrateManager.lastQualityAvgDelayMs = avgDelay
                    adaptiveBitrateManager.lastQualityBacklogDrops = backlogDrops
                }


                server.setBrowserConnectionListener { connected ->
                    if (connected) {
                        cancelPendingBrowserDisconnect("browser_reconnected")
                        if (!browserConnected) {
                            browserConnected = true
                            onBrowserConnected()
                        }
                        browserConnectionListener?.invoke(true)
                    } else if (browserConnected) {
                        scheduleBrowserDisconnect()
                    } else {
                        browserConnectionListener?.invoke(false)
                    }
                }

                server.start(0)
                Log.i(TAG, "Server started on port ${MirrorServer.DEFAULT_PORT} ??waiting for browser")
            }

            Log.i(TAG, "Pipeline initialized (idle): ${width}x${height}, audio=$audioEnabled")
            MirrorWidgetProvider.updateAllWidgets(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pipeline", e)
            stopSelf()
        }
    }
    
    private enum class SessionMode { STANDARD_APP, EXTERNAL_BROWSER, INTERNAL_WEBVIEW }
    private data class ActiveLaunchSession(
        val mode: SessionMode,
        val launchTarget: String,
        val url: String? = null,
        val sourceAppPackage: String? = null,
        val browserPackage: String? = null
    )
    private var activeSession: ActiveLaunchSession? = null

    private fun internalComponentName(activityClassName: String): String {
        return if (activityClassName.contains('/')) activityClassName else "$packageName/$activityClassName"
    }

    private fun clearSecondaryState() {
        secondaryPipeline.currentApp = ""
        secondaryPipeline.currentWebUrl = null
        secondaryPipeline.width = 0
        secondaryPipeline.height = 0
        secondaryRequestedWidth = 0
        secondaryRequestedHeight = 0
    }

    private fun invalidatePrimaryVd(reason: String): Long {
        primaryPipeline.displayId = -1
        val generation = primaryPipeline.vdGeneration.incrementAndGet()
        Log.i(TAG, "Primary VD invalidated generation=$generation reason=$reason")
        return generation
    }

    private fun markPrimaryVdCreated(displayId: Int, reason: String): Long {
        primaryPipeline.displayId = displayId
        val generation = primaryPipeline.vdGeneration.incrementAndGet()
        Log.i(TAG, "Primary VD active generation=$generation displayId=$displayId reason=$reason")
        return generation
    }

    private fun isCurrentPrimaryVd(expectedGeneration: Long, expectedDisplayId: Int): Boolean {
        val vdm = virtualDisplayManager ?: return false
        return expectedDisplayId >= 0 &&
            expectedGeneration == primaryPipeline.vdGeneration.get() &&
            expectedDisplayId == primaryPipeline.displayId &&
            vdm.hasVirtualDisplay() &&
            vdm.getDisplayId() == expectedDisplayId
    }

    private fun currentPrimaryVdToken(): Pair<Long, Int>? {
        val generation = primaryPipeline.vdGeneration.get()
        val displayId = primaryPipeline.displayId
        return if (isCurrentPrimaryVd(generation, displayId)) generation to displayId else null
    }

    private fun secondaryBitrate(width: Int, height: Int): Int {
        return com.castla.mirror.utils.StreamMath.calculateSecondaryBitrate(width, height)
    }

    private fun rebalanceDualDisplayBitrates() {
        val thermalActive = thermalThrottleManager.thermalStatus.value >= PowerManager.THERMAL_STATUS_LIGHT
        val hasSplit = secondaryPipeline.displayId >= 0 && secondaryPipeline.width > 0
        val now = android.os.SystemClock.elapsedRealtime()
        val canApply = now - lastCongestionTimeMs > 2000

        if (hasSplit && (isCurrentAppVideo || isSecondaryAppVideo) && !thermalActive) {
            val primaryBps = if (isCurrentAppVideo)
                StreamMath.calculateSplitVideoBitrate(primaryPipeline.width, primaryPipeline.height)
            else
                StreamMath.calculateSplitCompanionBitrate(primaryPipeline.width, primaryPipeline.height)

            val secondaryBps = if (isSecondaryAppVideo)
                StreamMath.calculateSplitVideoBitrate(secondaryPipeline.width, secondaryPipeline.height)
            else
                StreamMath.calculateSplitCompanionBitrate(secondaryPipeline.width, secondaryPipeline.height)

            targetBitrate = primaryBps
            if (canApply || primaryPipeline.currentBitrate > primaryBps) {
                primaryPipeline.currentBitrate = primaryBps
                primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)
            }
            secondaryPipeline.videoEncoder?.setBitrate(secondaryBps)
            Log.i(TAG, "Dual display rebalance: primary=${primaryBps / 1000}kbps(video=${isCurrentAppVideo}) secondary=${secondaryBps / 1000}kbps(video=${isSecondaryAppVideo})")
        } else {
            val baseBitrate = StreamMath.calculateBaseBitrate(primaryPipeline.width, primaryPipeline.height)
            targetBitrate = if (isCurrentAppVideo && !thermalActive)
                StreamMath.calculateOttBitrate(baseBitrate)
            else
                baseBitrate
            if (canApply || primaryPipeline.currentBitrate > targetBitrate) {
                primaryPipeline.currentBitrate = targetBitrate
                primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)
            }
            if (hasSplit) {
                val secBitrate = StreamMath.calculateSecondaryBitrate(secondaryPipeline.width, secondaryPipeline.height)
                secondaryPipeline.videoEncoder?.setBitrate(secBitrate)
            }
            Log.i(TAG, "Bitrate set: primary=${targetBitrate / 1000}kbps (video=${isCurrentAppVideo}, split=$hasSplit)")
        }
    }

    private fun computeVirtualDisplayDpi(width: Int, height: Int): Int {
        val baseDpi = StreamMath.calculateDpi(minOf(width, height))
        return StreamMath.applyDensityScale(baseDpi, dpiScale)
    }


    private fun releaseSecondaryPipeline(clearState: Boolean = false) {
        if (secondaryPipeline.displayId >= 0) {
            cleanupDisplay(secondaryPipeline.displayId)
            virtualDisplayManager?.releaseSecondaryVirtualDisplay(secondaryPipeline.displayId)
            secondaryPipeline.displayId = -1
        }
        secondaryPipeline.videoEncoder?.release()
        secondaryPipeline.videoEncoder = null
        secondaryPipeline.jpegEncoder?.release()
        secondaryPipeline.jpegEncoder = null
        mirrorServer?.setKeyframeRequester("secondary") {}
        secondaryPipeline.touchInjector?.release()
        secondaryPipeline.touchInjector = null
        if (isSecondaryAppVideo) {
            isSecondaryAppVideo = false
            rebalanceDualDisplayBitrates()
        }
        if (clearState) {
            clearSecondaryState()
            Log.i(TAG, "Secondary pipeline released ??primary will resize to fullscreen on next viewport")
            serviceScope.launch {
                rebuildPipeline(primaryPipeline.width, primaryPipeline.height, force = true, forceSingle = true)
            }
        }
    }

    private suspend fun rebuildSecondaryPipeline(targetWidth: Int, targetHeight: Int) {
        secondaryPipeline.rebuild(targetWidth, targetHeight)
    }



    private fun onSecondaryViewportChange(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        secondaryRequestedWidth = width
        secondaryRequestedHeight = height
        secondaryResizeJob?.cancel()
        secondaryResizeJob = serviceScope.launch(Dispatchers.IO) {
            rebuildSecondaryPipeline(width, height)
        }
    }

    private fun restoreSecondaryVdContent() {
        val displayId = secondaryPipeline.displayId
        Log.i(TAG, "restoreSecondaryVdContent: secondaryPipeline.displayId=$displayId, secondaryPipeline.currentApp=$secondaryPipeline.currentApp, secondaryPipeline.currentWebUrl=$secondaryPipeline.currentWebUrl")
        FileLogger.i(TAG, "restoreSecondaryVdContent start: id=$displayId app=$secondaryPipeline.currentApp")
        if (displayId < 0 || secondaryPipeline.currentApp.isBlank()) return
        when (secondaryPipeline.currentApp) {
            "HOME", "", "com.android.settings" -> {
                secondaryPipeline.currentApp = "HOME"
                try {
                    val service = virtualDisplayManager?.getPrivilegedService()
                    service?.launchHomeOnDisplay(displayId)
                    Log.i(TAG, "Launched custom HOME on secondary display $displayId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch home on secondary display $displayId", e)
                }
            }
            else -> {
                if (secondaryPipeline.currentWebUrl != null && !secondaryPipeline.currentApp.contains("WebBrowserActivity")) {
                    val browser = BrowserResolver.resolve(this, secondaryPipeline.currentWebUrl!!)
                    if (browser != null) {
                        val cmd = buildExternalBrowserCommand(displayId, secondaryPipeline.currentWebUrl!!, browser.componentFlat)
                        val launched = try {
                            val svc = virtualDisplayManager?.getPrivilegedService()
                            if (svc != null) { svc.execCommand(cmd); true } else false
                        } catch (_: Exception) { false }
                        if (!launched) {
                            launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, secondaryPipeline.currentWebUrl!!)
                        }
                    } else {
                        launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, secondaryPipeline.currentWebUrl!!)
                    }
                } else if (secondaryPipeline.currentApp.contains("WebBrowserActivity")) {
                    val activityClassName = secondaryPipeline.currentApp.substringAfter('/')
                    launchInternalActivity(activityClassName, displayId, secondaryPipeline.currentWebUrl ?: "https://m.youtube.com")
                } else {
                    launchTargetOnDisplay(displayId, secondaryPipeline.currentApp, forceColdStart = false, forceDisplayId = true)
                }
            }
        }
    }





    private fun removeAllVdTasks() {
        cleanupDisplay(virtualDisplayManager?.getDisplayId() ?: -1)
        cleanupDisplay(secondaryPipeline.displayId)
    }

    private fun cleanupDisplay(displayId: Int) {
        if (displayId < 0) return
        val service = virtualDisplayManager?.getPrivilegedService() ?: return
        val myPackage = packageName

        try {
            service.launchHomeOnDisplay(displayId)

            val runningTasks = service.getRunningTasksOnDisplay(displayId)
            val packagesToStop = mutableSetOf<String>()

            for (task in runningTasks) {
                val pkg = task.substringBefore('/').takeIf { it.contains('.') }
                if (pkg != null && pkg != myPackage
                    && !pkg.startsWith("com.android.launcher")
                    && !pkg.startsWith("com.sec.android.app.launcher")
                    && pkg != "com.android.settings"
                ) {
                    packagesToStop.add(pkg)
                }
            }

            for (pkg in packagesToStop) {
                service.execCommand("am force-stop $pkg")
                Log.i(TAG, "Force-stopped $pkg from display $displayId")
            }

            val removedTaskIds = mutableSetOf<Int>()
            for (pkg in packagesToStop) {
                for (taskId in service.getTaskIdsForPackage(pkg)) {
                    if (removedTaskIds.add(taskId)) {
                        service.removeTask(taskId)
                        Log.i(TAG, "Removed task $taskId for $pkg while cleaning display $displayId")
                    }
                }
            }

            Log.i(TAG, "Cleaned up display $displayId: ${packagesToStop.size} force-stopped, ${removedTaskIds.size} tasks removed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up display $displayId", e)
        }
    }

    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx"
    )

    private fun forceStopAppIfNeeded(packageName: String) {
        val pkg = packageName.substringBefore('/')
        if (pkg.isBlank()
            || pkg == "HOME"
            || pkg == "com.android.settings"
            || pkg.startsWith("com.android.launcher")
            || pkg.startsWith("com.sec.android.app.launcher")
            || pkg == applicationContext.packageName
        ) return

        try {
            val service = virtualDisplayManager?.getPrivilegedService() ?: return
            val dumpsys = service.execCommand("dumpsys activity activities")
            val matchingTaskIds = findAllTaskIds(dumpsys, pkg)
            for (taskId in matchingTaskIds) {
                try {
                    service.removeTask(taskId)
                    Log.i(TAG, "Synchronously removed zombie task $taskId for package $pkg to guarantee fresh launch on new virtual display")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove task $taskId", e)
                }
            }

            if (BROWSER_PACKAGES.contains(pkg)) {
                return
            }
            service.execCommand("am force-stop $pkg")
            Log.i(TAG, "Force-stopped previous app: $pkg")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force-stop $pkg", e)
        }
    }



    private fun markTerminal(reason: TerminalReason) {
        if (!terminalReason.compareAndSet(null, reason)) return
        FileLogger.e(TAG, "Terminal failure: ${reason.name}")
        Log.e(TAG, "Terminal failure: ${reason.name}")
        try {
            requestStopAsync("terminal_${reason.name.lowercase()}")
        } catch (e: Exception) {
            Log.w(TAG, "requestStopAsync failed after markTerminal", e)
        }
    }



    private fun escapeShellArg(value: String): String = "'" + value.replace("'", "'\''") + "'"

    private fun resolveLaunchComponent(packageOrComponent: String): String? {
        if (packageOrComponent.contains('/')) return packageOrComponent
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageOrComponent)
            val component = launchIntent?.component ?: run {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    `package` = packageOrComponent
                }
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    .firstOrNull()
                    ?.activityInfo
                    ?.let { ComponentName(it.packageName, it.name) }
            }
            component?.flattenToShortString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve launcher component for $packageOrComponent", e)
            null
        }
    }

    private fun normalizeLaunchTarget(packageOrComponent: String): String {
        return resolveLaunchComponent(packageOrComponent) ?: packageOrComponent
    }

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

    private fun launchTargetOnDisplay(
        displayId: Int,
        packageOrComponent: String,
        extraKey: String? = null,
        extraValue: String? = null,
        forceColdStart: Boolean = false,
        forceDisplayId: Boolean = false
    ): Boolean {
        Log.i(TAG, "launchTargetOnDisplay start: displayId=$displayId, packageOrComponent=$packageOrComponent, forceDisplayId=$forceDisplayId, forceColdStart=$forceColdStart")
        FileLogger.i(TAG, "launchTargetOnDisplay start: display=$displayId pkg=$packageOrComponent forceDisplayId=$forceDisplayId")
        if (displayId < 0) return false

        // Automatically correct to the active primary/secondary displayId if the input displayId is stale.
        var correctedDisplayId = displayId
        val activePrimaryId = virtualDisplayManager?.getDisplayId() ?: -1
        val activeSecondaryId = secondaryPipeline.displayId

        if (displayId != activeSecondaryId && !isCurrentPrimaryVd(primaryPipeline.vdGeneration.get(), displayId)) {
            if (activePrimaryId >= 0 && activePrimaryId != displayId) {
                Log.i(TAG, "Auto-correcting launch displayId from stale $displayId to active primary $activePrimaryId")
                correctedDisplayId = activePrimaryId
            } else {
                Log.w(TAG, "Skipping launch on stale primary display $displayId: primaryPipeline.displayId=$primaryPipeline.displayId generation=${primaryPipeline.vdGeneration.get()}")
                return false
            }
        }

        val service = virtualDisplayManager?.getPrivilegedService() ?: return false
        return try {
            val pkg = packageOrComponent.substringBefore('/')

            if (forceColdStart && pkg.isNotBlank() && pkg != "HOME" && !pkg.contains("com.castla.mirror")) {
                try {
                    val stopResult = service.execCommand("am force-stop $pkg")
                    Log.i(TAG, "Forced cold start: Successfully force-stopped $pkg before launching. Result: $stopResult")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to force stop $pkg for cold start", e)
                }
            }

            // 1. Precise Display ID tracking for original task to ensure symmetric control
            val originalDisplayId = try { service.getDisplayIdForPackage(pkg) } catch (e: Exception) { -1 }
            val primaryVdId = virtualDisplayManager?.getDisplayId() ?: -1
            val secondaryVdId = secondaryPipeline.displayId
            Log.i(TAG, "launchTargetOnDisplay tracking: originalDisplayId=$originalDisplayId, primaryVdId=$primaryVdId, secondaryVdId=$secondaryVdId")
            
            val targetDisplayId = if (!forceDisplayId && originalDisplayId >= 0 && (originalDisplayId == primaryVdId || originalDisplayId == secondaryVdId)) {
                Log.i(TAG, "Symmetric Task Routing: Redirecting launch of $pkg from display $correctedDisplayId to original display $originalDisplayId")
                originalDisplayId
            } else {
                Log.i(TAG, "Using target displayId $correctedDisplayId (forceDisplayId=$forceDisplayId or originalDisplayId=$originalDisplayId)")
                correctedDisplayId
            }

            val dumpsys = service.execCommand("dumpsys activity activities")
            val matchingTaskIds = findAllTaskIds(dumpsys, pkg)
            val isWarmStart = matchingTaskIds.isNotEmpty()
            Log.i(TAG, "launchTargetOnDisplay warm start check: matchingTaskIds=$matchingTaskIds, isWarmStart=$isWarmStart")

            for (taskId in matchingTaskIds) {
                try {
                    // Move the task to the correct target display
                    val moveResult = service.execCommand("cmd activity task move-to-display $taskId $targetDisplayId")
                    Log.i(TAG, "Migrated existing task $taskId ($pkg) to display $targetDisplayId. Result: $moveResult")
                    
                    // Force bring task to the front of target display to restore focus and resume rendering
                    val frontResult = service.execCommand("cmd activity task move-to-front $taskId")
                    Log.i(TAG, "Forced task $taskId to front of display $targetDisplayId. Result: $frontResult")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to migrate/bring-to-front task $taskId for display $targetDisplayId", e)
                }
            }

            val command = buildShellLaunchCommand(targetDisplayId, packageOrComponent, extraKey, extraValue, reorderToFront = isWarmStart)
            Log.i(TAG, "Executing Shell Launch: $command")
            val result = service.execCommand(command)
            Log.i(TAG, "Launch result for $packageOrComponent: $result")

            // 3. Surface Re-bind Verification and Safe Fallback mechanism
            if (isWarmStart) {
                verifySurfaceAndFallback(service, targetDisplayId, pkg, matchingTaskIds, packageOrComponent, extraKey, extraValue)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageOrComponent on display $correctedDisplayId (original=$displayId)", e)
            FileLogger.e(TAG, "launchTargetOnDisplay failed pkg=$packageOrComponent display=$correctedDisplayId", e)
            false
        }
    }

    private fun verifySurfaceAndFallback(
        service: IPrivilegedService,
        displayId: Int,
        pkg: String,
        taskIds: List<Int>,
        packageOrComponent: String,
        extraKey: String?,
        extraValue: String?
    ) {
        serviceScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1000L) // Wait 1 second for OS window manager transitions to settle
            try {
                val runningTasks = service.getRunningTasksOnDisplay(displayId)
                Log.i(TAG, "verifySurfaceAndFallback for $pkg on display $displayId: runningTasks=$runningTasks")
                // Check if the target package has successfully resumed as topActivity or active on this display
                val isResumedSuccessfully = runningTasks.any { it.contains(pkg) }
                if (!isResumedSuccessfully) {
                    Log.w(TAG, "Surface binding verification FAILED for $pkg on display $displayId. Black screen or focus loss suspected.")
                    FileLogger.w(TAG, "Verification failed for $pkg on display $displayId, initiating Clean Launch fallback.")
                    
                    // Fallback Safety Mechanism: completely remove stale tasks and execute clean launch
                    for (taskId in taskIds) {
                        try {
                            service.removeTask(taskId)
                            Log.i(TAG, "Fallback: Removed stale task $taskId")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to remove stale task $taskId during fallback", e)
                        }
                    }
                    val stopResult = service.execCommand("am force-stop $pkg")
                    Log.i(TAG, "Fallback: Force-stopped package $pkg. Result: $stopResult")
                    
                    // Re-launch with a clean slate
                    val command = buildShellLaunchCommand(displayId, packageOrComponent, extraKey, extraValue, reorderToFront = false)
                    Log.i(TAG, "Fallback Clean Launch: Executing: $command")
                    val result = service.execCommand(command)
                    Log.i(TAG, "Fallback Clean Launch result: $result")
                } else {
                    Log.i(TAG, "Surface binding verified successfully for $pkg on display $displayId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed during surface binding verification for $pkg", e)
            }
        }
    }

    private fun launchBrowserTarget(
        pane: String,
        url: String,
        sourceAppPackage: String? = null,
        allowFallback: Boolean = true,
        preserveSecondary: Boolean = false
    ) {
        if (pane == "primary" && secondaryPipeline.displayId >= 0 && !preserveSecondary) {
            Log.i(TAG, "Switching primary to fullscreen: releasing secondary pipeline prior to launching browser: $url")
            releaseSecondaryPipeline(clearState = false)
            serviceScope.launch {
                try {
                    rebuildPipeline(primaryPipeline.width, primaryPipeline.height, force = true, forceSingle = true)
                    launchBrowserTarget("primary", url, sourceAppPackage, allowFallback, preserveSecondary = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rebuild and launch browser target: $url", e)
                }
            }
            return
        }

        val browser = BrowserResolver.resolve(this, url)
        val targetComponent = browser?.componentFlat ?: internalComponentName("com.castla.mirror.ui.WebBrowserActivity")
        val isSecondary = (pane == "secondary")
        val displayId = if (isSecondary) secondaryPipeline.displayId else (virtualDisplayManager?.getDisplayId() ?: -1)

        if (displayId < 0) {
            Log.i(TAG, "Display not active for $pane. Deferring external browser launch ($url)")
            if (isSecondary) {
                secondaryPipeline.currentApp = targetComponent
                secondaryPipeline.currentWebUrl = url
                isSecondaryAppVideo = (browser != null)
                
                val targetW = if (secondaryRequestedWidth > 0) secondaryRequestedWidth else (primaryPipeline.width / 2).coerceAtLeast(320)
                val targetH = if (secondaryRequestedHeight > 0) secondaryRequestedHeight else primaryPipeline.height
                Log.i(TAG, "Secondary display not active ($displayId) ??triggering dynamic auto-provisioning: ${targetW}x${targetH}")
                serviceScope.launch(Dispatchers.IO) {
                    rebuildSecondaryPipeline(targetW, targetH)
                }
            } else {
                primaryPipeline.currentApp = targetComponent
                primaryPipeline.currentWebUrl = url
                isCurrentAppVideo = (browser != null)
                activeSession = ActiveLaunchSession(
                    mode = if (browser != null) SessionMode.EXTERNAL_BROWSER else SessionMode.INTERNAL_WEBVIEW,
                    launchTarget = targetComponent,
                    url = url,
                    sourceAppPackage = sourceAppPackage
                )
            }
            return
        }

        if (browser != null) {
            val command = buildExternalBrowserCommand(displayId, url, browser.componentFlat)
            val service = virtualDisplayManager?.getPrivilegedService()
            if (service != null) {
                val launched = try {
                    Log.i(TAG, "External browser launch on display $displayId ($pane): $command")
                    service.execCommand(command)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "External browser launch failed on $pane", e)
                    false
                }

                if (launched) {
                    if (isSecondary) {
                        val previousApp = secondaryPipeline.currentApp
                        val previousPkg = previousApp.substringBefore('/')
                        if (previousPkg != browser.packageName) {
                            forceStopAppIfNeeded(previousApp)
                        }
                        secondaryPipeline.currentApp = browser.componentFlat
                        secondaryPipeline.currentWebUrl = url
                        isSecondaryAppVideo = true
                    } else {
                        val previousApp = primaryPipeline.currentApp
                        val previousPkg = previousApp.substringBefore('/')
                        if (previousPkg != browser.packageName) {
                            forceStopAppIfNeeded(previousApp)
                        }
                        primaryPipeline.currentApp = browser.componentFlat
                        primaryPipeline.currentWebUrl = url
                        isCurrentAppVideo = true
                        activeSession = ActiveLaunchSession(
                            mode = SessionMode.EXTERNAL_BROWSER,
                            launchTarget = browser.componentFlat,
                            url = url,
                            sourceAppPackage = sourceAppPackage,
                            browserPackage = browser.packageName
                        )
                    }
                    Log.i(TAG, "External browser launched successfully on $pane: ${browser.componentFlat} -> $url")
                    rebalanceDualDisplayBitrates()
                    return
                }
            }
        }

        if (allowFallback) {
            Log.w(TAG, "Falling back to internal WebBrowserActivity for $url on $pane")
            val webActivity = "com.castla.mirror.ui.WebBrowserActivity"
            launchInternalActivity(webActivity, displayId, url)
            if (isSecondary) {
                secondaryPipeline.currentApp = internalComponentName(webActivity)
                secondaryPipeline.currentWebUrl = url
                isSecondaryAppVideo = false
            } else {
                primaryPipeline.currentApp = internalComponentName(webActivity)
                primaryPipeline.currentWebUrl = url
                isCurrentAppVideo = false
                activeSession = ActiveLaunchSession(
                    mode = SessionMode.INTERNAL_WEBVIEW,
                    launchTarget = internalComponentName(webActivity),
                    url = url,
                    sourceAppPackage = sourceAppPackage
                )
            }
            rebalanceDualDisplayBitrates()
        }
    }

    private fun buildExternalBrowserCommand(displayId: Int, url: String, browserComponent: String): String {
        return buildString {
            append("am start --display $displayId -f 0x18000000 ")
            append("-a android.intent.action.VIEW ")
            append("-d ${escapeShellArg(url)} ")
            append("-n ${escapeShellArg(browserComponent)} ")
        }.trim()
    }

    private fun launchStandardTarget(pane: String, launchTarget: String, preserveSecondary: Boolean = false) {
        val resolvedTarget = normalizeLaunchTarget(launchTarget)
        if (pane == "primary" && secondaryPipeline.displayId >= 0 && !preserveSecondary) {
            Log.i(TAG, "Switching primary to fullscreen: releasing secondary pipeline prior to launching: $resolvedTarget")
            releaseSecondaryPipeline(clearState = false)
            serviceScope.launch {
                try {
                    rebuildPipeline(primaryPipeline.width, primaryPipeline.height, force = true, forceSingle = true)
                    launchStandardTarget("primary", resolvedTarget, preserveSecondary = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rebuild and launch standard target: $resolvedTarget", e)
                }
            }
            return
        }

        val isSecondary = (pane == "secondary")
        val displayId = if (isSecondary) secondaryPipeline.displayId else (virtualDisplayManager?.getDisplayId() ?: -1)

        val launched = if (displayId >= 0) launchTargetOnDisplay(displayId, resolvedTarget) else false
        if (!launched) {
            if (isSecondary) {
                Log.i(TAG, "Secondary display not active ($displayId). Deferring launch for $resolvedTarget until secondary is ready.")
                secondaryPipeline.currentApp = resolvedTarget
                secondaryPipeline.currentWebUrl = null
                isSecondaryAppVideo = false
                
                val targetW = if (secondaryRequestedWidth > 0) secondaryRequestedWidth else (primaryPipeline.width / 2).coerceAtLeast(320)
                val targetH = if (secondaryRequestedHeight > 0) secondaryRequestedHeight else primaryPipeline.height
                Log.i(TAG, "Secondary display not active ($displayId) ??triggering dynamic auto-provisioning: ${targetW}x${targetH}")
                serviceScope.launch(Dispatchers.IO) {
                    rebuildSecondaryPipeline(targetW, targetH)
                }
            } else {
                val activeDisplayId = virtualDisplayManager?.getDisplayId() ?: -1
                if (virtualDisplayManager?.hasVirtualDisplay() == false || displayId != activeDisplayId) {
                    Log.i(TAG, "Primary VD not active or stale (displayId=$displayId active=$activeDisplayId). Deferring launch for $resolvedTarget until bind completion.")
                    primaryPipeline.currentApp = resolvedTarget
                    primaryPipeline.currentWebUrl = null
                    isCurrentAppVideo = false
                    return
                }
                Log.w(TAG, "Failed to launch $resolvedTarget on active display $displayId ??triggering single rebuild fallback")
                serviceScope.launch {
                    try {
                        rebuildPipeline(primaryPipeline.width, primaryPipeline.height, force = true)
                        val retryId = virtualDisplayManager?.getDisplayId() ?: -1
                        val retried = launchTargetOnDisplay(retryId, resolvedTarget)
                        if (retried) {
                            Log.i(TAG, "Retry launch succeeded for $resolvedTarget after pipeline rebuild")
                        } else {
                            Log.e(TAG, "Retry launch failed for $resolvedTarget after pipeline rebuild")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to rebuild and retry launch for $resolvedTarget", e)
                    }
                }
            }
        } else {
            if (isSecondary) {
                secondaryPipeline.currentApp = resolvedTarget
                secondaryPipeline.currentWebUrl = null
                isSecondaryAppVideo = false
            } else {
                primaryPipeline.currentApp = resolvedTarget
                primaryPipeline.currentWebUrl = null
                isCurrentAppVideo = false
                activeSession = ActiveLaunchSession(mode = SessionMode.STANDARD_APP, launchTarget = resolvedTarget)
            }
            rebalanceDualDisplayBitrates()
        }
    }

    private fun launchWebTarget(
        pane: String,
        activityClassName: String,
        url: String,
        preserveSecondary: Boolean = false
    ) {
        if (pane == "primary" && secondaryPipeline.displayId >= 0 && !preserveSecondary) {
            Log.i(TAG, "Switching primary to fullscreen: releasing secondary pipeline prior to launching WebView: $url")
            releaseSecondaryPipeline(clearState = false)
            serviceScope.launch {
                try {
                    rebuildPipeline(primaryPipeline.width, primaryPipeline.height, force = true, forceSingle = true)
                    launchWebTarget("primary", activityClassName, url, preserveSecondary = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rebuild and launch WebView: $url", e)
                }
            }
            return
        }

        val isSecondary = (pane == "secondary")
        val displayId = if (isSecondary) secondaryPipeline.displayId else (virtualDisplayManager?.getDisplayId() ?: -1)
        val targetComponent = internalComponentName(activityClassName)

        if (displayId < 0) {
            Log.i(TAG, "Display not active for $pane. Deferring fullscreen web launch ($url)")
            if (isSecondary) {
                secondaryPipeline.currentApp = targetComponent
                secondaryPipeline.currentWebUrl = url
                isSecondaryAppVideo = false
                
                val targetW = if (secondaryRequestedWidth > 0) secondaryRequestedWidth else (primaryPipeline.width / 2).coerceAtLeast(320)
                val targetH = if (secondaryRequestedHeight > 0) secondaryRequestedHeight else primaryPipeline.height
                Log.i(TAG, "Secondary display not active ($displayId) ??triggering dynamic auto-provisioning: ${targetW}x${targetH}")
                serviceScope.launch(Dispatchers.IO) {
                    rebuildSecondaryPipeline(targetW, targetH)
                }
            } else {
                primaryPipeline.currentApp = targetComponent
                primaryPipeline.currentWebUrl = url
                isCurrentAppVideo = false
                activeSession = ActiveLaunchSession(
                    mode = SessionMode.INTERNAL_WEBVIEW,
                    launchTarget = targetComponent,
                    url = url
                )
            }
            return
        }

        val previousApp = if (isSecondary) secondaryPipeline.currentApp else primaryPipeline.currentApp
        if (previousApp != targetComponent) {
            forceStopAppIfNeeded(previousApp)
        }
        launchInternalActivity(activityClassName, displayId, url)

        if (isSecondary) {
            secondaryPipeline.currentApp = targetComponent
            secondaryPipeline.currentWebUrl = url
            isSecondaryAppVideo = false
        } else {
            primaryPipeline.currentApp = targetComponent
            primaryPipeline.currentWebUrl = url
            isCurrentAppVideo = false
            activeSession = ActiveLaunchSession(
                mode = SessionMode.INTERNAL_WEBVIEW,
                launchTarget = targetComponent,
                url = url
            )
        }
        rebalanceDualDisplayBitrates()
    }

    private fun launchInternalActivity(activityClassName: String, displayId: Int, url: String) {
        launchOwnActivityOnDisplay(activityClassName, displayId, url)
    }

    private fun launchOwnActivityOnDisplay(
        activityClassName: String,
        displayId: Int,
        url: String
    ) {
        if (displayId < 0) return

        val options = android.app.ActivityOptions.makeBasic()
        options.launchDisplayId = displayId
        val intent = Intent().apply {
            setClassName(this@MirrorForegroundService, activityClassName)
            if (activityClassName.contains("WebBrowserActivity")) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            } else {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            putExtra("url", url)
            putExtra("pane", if (displayId == secondaryPipeline.displayId) "secondary" else "primary")
        }
        try {
            startActivity(intent, options.toBundle())
            Log.i(TAG, "Launched $activityClassName on display $displayId via ActivityOptions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $activityClassName on display $displayId via ActivityOptions", e)
            launchTargetOnDisplay(
                displayId,
                internalComponentName(activityClassName),
                "url",
                url,
                forceColdStart = false,
                forceDisplayId = true
            )
        }
    }

    private fun findAllTaskIds(dumpsys: String?, pkg: String): List<Int> {
        val service = virtualDisplayManager?.getPrivilegedService()
        if (service != null) {
            try {
                return service.getTaskIdsForPackage(pkg).toList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get task IDs natively, falling back to regex", e)
            }
        }

        if (dumpsys.isNullOrBlank()) return emptyList()
        val taskIds = mutableListOf<Int>()

        val blocks = dumpsys.split(Regex("\\* Task"))
        for (i in 1 until blocks.size) {
            val block = blocks[i]
            val taskId = Regex("^\\s*(?:#|Record\\{\\s*|\\{\\s*)(\\d+)").find(block)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue

            val hasActivity = block.contains("realActivity=$pkg/") ||
                              block.contains("origActivity=$pkg/") ||
                              block.contains("ComponentInfo{$pkg/")
            if (hasActivity) {
                taskIds.add(taskId)
            }
        }
        return taskIds
    }



    private fun launchAppFromWebLauncher(pkgName: String, componentName: String? = null, pane: String = "primary") {
        Log.i(TAG, "launchAppFromWebLauncher: pkg=$pkgName pane=$pane")
        if (pane != "secondary") {
            lastAppLaunchTime = System.currentTimeMillis()
        }
        if (pane == "secondary" && pkgName.isBlank()) {
            releaseSecondaryPipeline(clearState = true)
            return
        }

        val webUrl = OttCatalog.webUrlFor(pkgName)
        if (webUrl != null) {
            val preserveSecondary = pane == "primary" && secondaryPipeline.displayId >= 0
            Log.i(TAG, "Web Launcher: Launching OTT app via external browser: $pkgName -> $webUrl (pane=$pane preserveSecondary=$preserveSecondary)")
            launchBrowserTarget(pane, webUrl, pkgName, preserveSecondary = preserveSecondary)
        } else {
            val launchTarget = componentName ?: pkgName
            val preserveSecondary = pane == "primary" && secondaryPipeline.displayId >= 0
            Log.i(TAG, "Web Launcher: Launching standard app: $pkgName (target=$launchTarget pane=$pane preserveSecondary=$preserveSecondary)")
            launchStandardTarget(pane, launchTarget, preserveSecondary = preserveSecondary)
        }

        if (currentCodecMode == "mjpeg") {
            primaryPipeline.touchInjector?.onTouchEvent(com.castla.mirror.server.TouchEvent("down", 0.5f, 0.5f, 99))
            serviceScope.launch {
                kotlinx.coroutines.delay(50)
                primaryPipeline.touchInjector?.onTouchEvent(com.castla.mirror.server.TouchEvent("up", 0.5f, 0.5f, 99))
            }
        }
    }

    private fun ensureShizukuSetup(): ShizukuSetup? {
        shizukuSetup?.let {
            Log.i(TAG, "ensureShizukuSetup: reusing existing instance")
            return it
        }
        val setup = ShizukuSetup()
        setup.init(this, bindService = true)
        Log.i(TAG, "ensureShizukuSetup: created new instance lazily on browser-connect path")
        shizukuSetup = setup
        startReconnectObserver(setup)
        return setup
    }

    private fun startReconnectObserver(setup: ShizukuSetup) {
        if (reconnectJob != null) return
        reconnectJob = serviceScope.launch {
            val tracker = BinderConnectionTracker()
            setup.serviceConnected.collect { connected ->
                val transition = if (connected) tracker.onConnected() else tracker.onDisconnected()
                Log.i(TAG, "Shizuku connection transition=$transition connected=$connected")
                when (transition) {
                    BinderConnectionTracker.Transition.FirstConnect,
                    BinderConnectionTracker.Transition.Idempotent -> {}
                    BinderConnectionTracker.Transition.Disconnect -> handleShizukuDisconnect()
                    BinderConnectionTracker.Transition.Reconnect -> handleShizukuReconnect(setup)
                }
            }
        }
    }

    private fun handleShizukuDisconnect() {
        val vdm = virtualDisplayManager ?: return
        vdm.attachPrivilegedService(null)
    }

    private fun handleShizukuReconnect(setup: ShizukuSetup) {
        if (!browserConnected) {
            Log.w(TAG, "Ignoring stale Shizuku reconnect after browser disconnect")
            return
        }
        val vdm = virtualDisplayManager ?: VirtualDisplayManager().also { virtualDisplayManager = it }
        val surf = primaryPipeline.currentEncoderSurface ?: return
        if (primaryPipeline.width <= 0 || primaryPipeline.height <= 0) {
            Log.w(TAG, "Reconnect skipped: invalid dims ${primaryPipeline.width}x${primaryPipeline.height}")
            return
        }
        val svc = setup.privilegedService ?: return
        vdm.attachPrivilegedService(svc)
        serviceScope.launch(Dispatchers.IO) {
            primaryVdOperationMutex.withLock {
                vdm.createVirtualDisplay(primaryPipeline.width, primaryPipeline.height, computeVirtualDisplayDpi(primaryPipeline.width, primaryPipeline.height), surf)
                if (vdm.hasVirtualDisplay()) {
                    val displayId = vdm.getDisplayId()
                    val generation = markPrimaryVdCreated(displayId, "shizuku_reconnect")
                    primaryPipeline.touchInjector?.setVirtualDisplayInjector { motionEvent ->
                        vdm.injectMotionEvent(motionEvent)
                    }
                    restoreCurrentVdContentLocked(generation, displayId)
                }
            }
        }
    }

    private fun trySetupVirtualDisplay(
        width: Int,
        height: Int,
        surface: android.view.Surface,
        onResult: (Boolean) -> Unit
    ) {
        if (shizukuSetupInProgress) {
            Log.i(TAG, "trySetupVirtualDisplay queued: setup already in progress")
            shizukuSetupCallbacks.add(onResult)
            return
        }
        shizukuSetupInProgress = true
        shizukuSetupCallbacks.clear()
        shizukuSetupCallbacks.add(onResult)
        
        var resultDelivered = false
        val safeResult = { success: Boolean ->
            if (!resultDelivered) {
                resultDelivered = true
                shizukuSetupInProgress = false
                if (!success) {
                    tearDownVdSession("virtual_display_setup_failed")
                }
                
                val callbacks = synchronized(shizukuSetupCallbacks) {
                    val list = shizukuSetupCallbacks.toList()
                    shizukuSetupCallbacks.clear()
                    list
                }
                callbacks.forEach { cb ->
                    try {
                        cb(success)
                    } catch (e: Exception) {
                        Log.w(TAG, "Callback invocation failed during setup completion", e)
                    }
                }
            }
        }

        val setup = ensureShizukuSetup() ?: run {
            Log.w(TAG, "trySetupVirtualDisplay: ensureShizukuSetup returned null")
            safeResult(false)
            return
        }
        if (!setup.isAvailable() || !setup.hasPermission()) {
            Log.i(TAG, "Shizuku not available/permitted")
            safeResult(false)
            return
        }

        setup.bindPrivilegedService()

        serviceScope.launch {
            val connected = withTimeoutOrNull(BIND_WAIT_BUDGET_MS) {
                setup.serviceConnected.first { it }
            } != null

            if (!connected) {
                shizukuBindRetryCount++
                FileLogger.w(TAG, "trySetupVirtualDisplay: serviceConnected timeout (attempt $shizukuBindRetryCount/$SHIZUKU_MAX_RETRIES)")
                setup.forceResetBindingState()
                if (shizukuBindRetryCount < SHIZUKU_MAX_RETRIES) {
                    Log.w(TAG, "Shizuku binding timed out (attempt $shizukuBindRetryCount/$SHIZUKU_MAX_RETRIES) ??retrying")
                    shizukuSetupInProgress = false
                    tearDownVdSession("binding_timeout")
                    kotlinx.coroutines.delay(2_000)
                    if (browserConnected) {
                        val surf = primaryPipeline.currentEncoderSurface
                        if (surf != null) {
                            Log.i(TAG, "Retrying Shizuku setup after timeout (attempt ${shizukuBindRetryCount + 1})")
                            trySetupVirtualDisplay(primaryPipeline.width, primaryPipeline.height, surf, onResult)
                            return@launch
                        }
                    }
                    safeResult(false)
                } else {
                    Log.e(TAG, "Shizuku binding failed after $SHIZUKU_MAX_RETRIES retries ??Shizuku server may need restart")
                    safeResult(false)
                }
                return@launch
            }

            shizukuBindRetryCount = 0

            if (!browserConnected) {
                Log.w(TAG, "trySetupVirtualDisplay: browser disconnected during bind wait ??abort")
                safeResult(false)
                return@launch
            }

            val svc = setup.privilegedService
            if (svc == null) {
                Log.w(TAG, "trySetupVirtualDisplay: serviceConnected=true but privilegedService null")
                safeResult(false)
                return@launch
            }

            try {
                svc.enableStayAwakeMode()
                Log.i(TAG, "Enabled stay-awake mode")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable stay-awake mode (non-fatal)", e)
            }

            val vdm = VirtualDisplayManager().also { virtualDisplayManager = it }
            vdm.attachPrivilegedService(svc)

            val actualWidth = if (primaryPipeline.width > 0) primaryPipeline.width else width
            val actualHeight = if (primaryPipeline.height > 0) primaryPipeline.height else height
            val actualSurface = primaryPipeline.currentEncoderSurface ?: surface
            val actualDpi = computeVirtualDisplayDpi(actualWidth, actualHeight)
            primaryVdOperationMutex.withLock {
                Log.i(TAG, "trySetupVirtualDisplay [DIAGNOSTIC]: creating VirtualDisplay: size=${actualWidth}x${actualHeight}, dpi=$actualDpi, surface=$actualSurface")
                vdm.createVirtualDisplay(actualWidth, actualHeight, actualDpi, actualSurface)

                if (vdm.hasVirtualDisplay()) {
                    val displayId = vdm.getDisplayId()
                    val generation = markPrimaryVdCreated(displayId, "try_setup")
                    Log.i(TAG, "trySetupVirtualDisplay [DIAGNOSTIC]: VirtualDisplay created successfully! ID=$displayId generation=$generation")
                    primaryPipeline.touchInjector?.setVirtualDisplayInjector { motionEvent ->
                        vdm.injectMotionEvent(motionEvent)
                    }
                    startAppExitMonitor() // Start the exit monitor for standard mirroring sessions
                    restoreCurrentVdContentLocked(generation, displayId)
                    serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val ok = setup.ensureShizukuHardened()
                        Log.i(TAG, "ensureShizukuHardened (service): $ok")
                    }
                    safeResult(true)
                } else {
                    Log.e(TAG, "trySetupVirtualDisplay [DIAGNOSTIC]: VirtualDisplay creation returned null/failed!")
                    safeResult(false)
                }
            }
        }
    }

    private fun cancelPendingBrowserDisconnect(reason: String) {
        val job = pendingBrowserDisconnectJob ?: return
        Log.i(TAG, "Cancelling pending browser disconnect: $reason")
        job.cancel()
        pendingBrowserDisconnectJob = null
    }

    private fun scheduleBrowserDisconnect() {
        if (pendingBrowserDisconnectJob != null) {
            Log.d(TAG, "Browser disconnect already pending")
            return
        }
        val screenOff = screenOffPolicy.isScreenOff
        val graceMs = DisconnectPolicy.graceMs(screenOff)
        pendingBrowserDisconnectJob = serviceScope.launch {
            Log.i(TAG, "Scheduling browser disconnect grace window: ${graceMs}ms (screenOff=$screenOff)")
            kotlinx.coroutines.delay(graceMs)
            pendingBrowserDisconnectJob = null
            val stillConnected = mirrorServer?.isBrowserConnected() == true
            if (stillConnected) {
                Log.i(TAG, "Browser reconnected during grace window; keeping pipeline alive")
                return@launch
            }
            if (!DisconnectPolicy.shouldTeardown(screenOffPolicy.isScreenOff, isBrowserConnected = false)) {
                Log.i(TAG, "Screen is off ??deferring teardown until screen turns on")
                return@launch
            }
            if (browserConnected) {
                browserConnected = false
                onBrowserDisconnected()
            }
            browserConnectionListener?.invoke(false)
        }
    }

    private fun onBrowserConnected() {
        try {
            powerLockManager.acquireWakeLocks()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalThrottleManager.broadcastThermalStatus(thermalThrottleManager.thermalStatus.value)
            }

            val isPipelineActive = (primaryPipeline.videoEncoder != null || primaryPipeline.jpegEncoder != null)
            if (isPipelineActive) {
                Log.i(TAG, "Browser reconnected ??rebuilding pipeline")
                serviceScope.launch {
                    rebuildPipeline(primaryPipeline.width, primaryPipeline.height, force = true)
                }
                ensureAudioCaptureState()
                return
            }

            Log.i(TAG, "Browser connected ??starting active pipeline")
            val width = if (primaryPipeline.width > 0) primaryPipeline.width else 720
            val height = if (primaryPipeline.height > 0) primaryPipeline.height else 720

            val baseTargetBitrate = com.castla.mirror.utils.StreamMath.calculateBaseBitrate(width, height)
            targetBitrate = baseTargetBitrate
            primaryPipeline.currentBitrate = targetBitrate
            preThermalTargetBitrate = targetBitrate

            adaptiveBitrateManager.startAbrLoop()
            adaptiveBitrateManager.startAutoScaleLoop(autoResolution, autoFps)

            serviceScope.launch {
                rebuildPipeline(width, height, force = true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Browser connection activation failed", t)
            FileLogger.e(TAG, "Browser connection activation failed", t)
            markTerminal(TerminalReason.BROWSER_ACTIVATION_FAILED)
        }
    }

    private fun tearDownVdSession(reason: String) {
        Log.i(TAG, "Tearing down VD session: $reason")
        stopAppExitMonitor() // Safely stop task monitor when virtual display is released
        try { primaryPipeline.touchInjector?.setVirtualDisplayInjector(null) } catch (_: Exception) {}
        invalidatePrimaryVd("teardown_$reason")
        try { virtualDisplayManager?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release virtual display manager", e) }
        virtualDisplayManager = null
    }

    private fun ensureAudioCaptureState(codecOverride: String? = null) {
        val orch = audioOrchestrator ?: return
        orch.audioEnabled = pendingAudioEnabled && AudioCapture.isSupported()
        orch.browserConnected = browserConnected
        orch.ensure(codecOverride)
    }

    private fun restoreCurrentVdContent() {
        val token = currentPrimaryVdToken()
        if (token == null) {
            Log.d(TAG, "restoreCurrentVdContent skipped: no current primary VD token")
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            primaryVdOperationMutex.withLock {
                restoreCurrentVdContentLocked(token.first, token.second)
            }
        }
    }

    private fun restoreCurrentVdContentLocked(expectedGeneration: Long, expectedDisplayId: Int) {
        if (!isCurrentPrimaryVd(expectedGeneration, expectedDisplayId)) {
            Log.i(
                TAG,
                "Skipping stale primary restore: expectedGeneration=$expectedGeneration expectedDisplayId=$expectedDisplayId currentGeneration=${primaryPipeline.vdGeneration.get()} activeDisplayId=$primaryPipeline.displayId"
            )
            return
        }
        val vdm = virtualDisplayManager ?: return
        startAppExitMonitor() // Keep monitor active during display rebuilds/reconnects
        when (primaryPipeline.currentApp) {
            "HOME", "", "com.android.settings" -> {
                primaryPipeline.currentApp = "HOME"
                if (isCurrentPrimaryVd(expectedGeneration, expectedDisplayId)) {
                    vdm.launchHomeOnDisplay()
                }
            }
            else -> {
                if (activeSession?.mode == SessionMode.EXTERNAL_BROWSER && primaryPipeline.currentWebUrl != null) {
                    val displayId = expectedDisplayId
                    val browser = BrowserResolver.resolve(this, primaryPipeline.currentWebUrl!!)
                    if (browser != null) {
                        val cmd = buildExternalBrowserCommand(displayId, primaryPipeline.currentWebUrl!!, browser.componentFlat)
                        val launched = try {
                            val svc = virtualDisplayManager?.getPrivilegedService()
                            if (svc != null && isCurrentPrimaryVd(expectedGeneration, expectedDisplayId)) { svc.execCommand(cmd); true } else false
                        } catch (_: Exception) { false }
                        if (!launched) {
                            launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, primaryPipeline.currentWebUrl!!)
                        }
                    } else {
                        launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, primaryPipeline.currentWebUrl!!)
                    }
                } else if (primaryPipeline.currentApp.contains("WebBrowserActivity")) {
                    val activityClassName = primaryPipeline.currentApp.substringAfter('/')
                    launchInternalActivity(activityClassName, expectedDisplayId, primaryPipeline.currentWebUrl ?: "https://m.youtube.com")
                } else {
                    launchTargetOnDisplay(expectedDisplayId, primaryPipeline.currentApp, forceColdStart = false)
                }
            }
        }
    }

    private fun onAudioCodecRequest(codec: String) {
        serviceScope.launch(Dispatchers.IO) {
            ensureAudioCaptureState(codecOverride = codec)
        }
    }

    private fun onBrowserDisconnected() {
        Log.i(TAG, "Browser disconnected ??suspending pipeline")
        pendingBrowserDisconnectJob = null
        browserConnected = false
        lastImeState = false
        lastBroadcastPane = null
        haveSeenRealImeShow = false
        bubbleClosedByUser = false
        cancelImeHideWatchdog()
        releaseSecondaryPipeline(clearState = false)
        try { removeAllVdTasks() } catch (e: Exception) { Log.w(TAG, "Failed to remove VD tasks on disconnect", e) }
        tearDownVdSession("browser_disconnected")
        
        primaryPipeline.videoEncoder?.release()
        primaryPipeline.videoEncoder = null
        primaryPipeline.jpegEncoder?.release()
        primaryPipeline.jpegEncoder = null
        primaryPipeline.currentEncoderSurface = null

        audioOrchestrator?.stop()

        adaptiveBitrateManager.stopAbrLoop()

        powerLockManager.releaseWakeLocks()
    }

    private fun activeInputDisplayId(): Int {
        return if (lastTouchPane == "secondary" && secondaryPipeline.displayId >= 0) {
            secondaryPipeline.displayId
        } else {
            virtualDisplayManager?.getDisplayId() ?: -1
        }
    }

    private fun injectText(text: String) {
        serviceScope.launch(compositionDispatcher) {
            try {
                val displayId = activeInputDisplayId()
                val service = shizukuSetup?.privilegedService
                if (service != null) {
                    service.injectText(text, displayId)
                }
            } catch (e: Exception) {}
        }
    }

    private var lastTouchPane = "primary"
    private var lastImeState = false
    private var lastBroadcastPane: String? = null
    private var lastImeCheckTime = 0L
    private var imeCheckSuspendUntil = 0L
    private var lastImeVisibleTime = 0L
    private var imeHiddenSince = 0L
    private var haveSeenRealImeShow = false
    private var bubbleClosedByUser = false
    private var imeHideWatchdogJob: Job? = null

    private suspend fun imeCheckTick(activePane: String, activeDisplayId: Int, source: String): Boolean? {
        val service = virtualDisplayManager?.getPrivilegedService() ?: return null

        var imeState = try {
            service.getImeState(activeDisplayId)
        } catch (e: android.os.DeadObjectException) {
            imeCheckSuspendUntil = System.currentTimeMillis() + 10_000
            return null
        }
        if (imeState == 0) {
            imeState = legacyImeState(service, activeDisplayId)
        }

        var imeVisible = !bubbleClosedByUser && (imeState and ImeState.VISIBLE) != 0
        if (!imeVisible && !bubbleClosedByUser && activeDisplayId > 0) {
            imeVisible = (imeState and ImeState.SERVED_INPUT) != 0
        }
        if (imeVisible) {
            haveSeenRealImeShow = true
        }

        val hasTargetOnActive = if (!imeVisible && activeDisplayId > 0) {
            ImeVisibilityPolicy.shouldUseInputTargetFallback(
                activeDisplayId = activeDisplayId,
                hasInputTargetOnActiveDisplay = (imeState and ImeState.INPUT_TARGET_ON_DISPLAY) != 0,
                haveSeenRealImeShow = haveSeenRealImeShow,
                bubbleClosedByUser = bubbleClosedByUser
            )
        } else false

        val combinedVisible = imeVisible || hasTargetOnActive
        val now = System.currentTimeMillis()
        if (combinedVisible) {
            lastImeVisibleTime = now
            imeHiddenSince = 0L
        } else if (lastImeState) {
            if (imeHiddenSince == 0L) {
                imeHiddenSince = now
            }
            val hideStableMs = now - imeHiddenSince
            val sinceVisibleMs = now - lastImeVisibleTime
            if (hideStableMs < 700 || sinceVisibleMs < 1_200) {
                Log.d(TAG, "IME $source: suppress transient hide pane=$activePane display=$activeDisplayId hideStableMs=$hideStableMs sinceVisibleMs=$sinceVisibleMs imeVisible=$imeVisible hasTarget=$hasTargetOnActive")
                return true
            }
        }
        val stateChanged = combinedVisible != lastImeState
        val paneChangedWhileVisible = combinedVisible && lastImeState &&
            lastBroadcastPane != null && lastBroadcastPane != activePane
        val shouldRefreshVisibleBubble = combinedVisible && source == "check"
        if (stateChanged || paneChangedWhileVisible || shouldRefreshVisibleBubble) {
            lastImeState = combinedVisible
            lastBroadcastPane = if (combinedVisible) activePane else null
            if (!combinedVisible || paneChangedWhileVisible) {
                haveSeenRealImeShow = false
            }
            val msg = if (combinedVisible)
                """{"type":"showKeyboard","pane":"$activePane"}"""
            else
                """{"type":"hideKeyboard"}"""
            Log.d(TAG, "IME broadcast: $msg state=$imeState source=$source (sockets=${mirrorServer?.controlSocketCount()})")
            mirrorServer?.broadcastControlMessage(msg)
        }
        return combinedVisible
    }

    private fun legacyImeState(service: IPrivilegedService, activeDisplayId: Int): Int {
        val inputState = try {
            ImeState.parseInputMethodDump(
                service.execCommand("dumpsys input_method | grep -E 'mInputShown|mImeWindowVis|mDecorViewVisible|mWindowVisible|mServedView|mServedInputConnection|mShowRequested|mShowInputRequested|mIsInputViewShown|isInputViewShown|mInputViewStarted|mCurClient'")
            )
        } catch (e: android.os.DeadObjectException) {
            imeCheckSuspendUntil = System.currentTimeMillis() + 10_000
            return 0
        } catch (_: Exception) {
            0
        }
        if ((inputState and (ImeState.VISIBLE or ImeState.SERVED_INPUT)) != 0 || activeDisplayId <= 0) {
            return inputState
        }
        val targetState = try {
            ImeState.parseWindowDump(
                service.execCommand("dumpsys window | grep 'imeInputTarget in display'"),
                activeDisplayId
            )
        } catch (e: android.os.DeadObjectException) {
            imeCheckSuspendUntil = System.currentTimeMillis() + 10_000
            0
        } catch (_: Exception) {
            0
        }
        return inputState or targetState
    }

    private fun startImeHideWatchdog() {
        if (imeHideWatchdogJob?.isActive == true) return
        imeHideWatchdogJob = serviceScope.launch(Dispatchers.IO) {
            try {
                while (isActive && lastImeState) {
                    kotlinx.coroutines.delay(200)
                    if (!lastImeState) break
                    val activePane = lastTouchPane
                    val activeDisplayId = if (activePane == "secondary" && secondaryPipeline.displayId >= 0) {
                        secondaryPipeline.displayId
                    } else {
                        virtualDisplayManager?.getDisplayId() ?: -1
                    }
                    val combined = imeCheckTick(activePane, activeDisplayId, "watchdog") ?: continue
                    if (!combined) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "IME hide watchdog error", e)
            } finally {
                imeHideWatchdogJob = null
            }
        }
    }

    private fun cancelImeHideWatchdog() {
        imeHideWatchdogJob?.cancel()
        imeHideWatchdogJob = null
    }

    private fun checkImeAndNotifyBrowser() {
        val now = System.currentTimeMillis()
        if (now - lastImeCheckTime < 500) return
        if (now < imeCheckSuspendUntil) return
        lastImeCheckTime = now

        val activePane = lastTouchPane
        val activeDisplayId = if (activePane == "secondary" && secondaryPipeline.displayId >= 0) {
            secondaryPipeline.displayId
        } else {
            virtualDisplayManager?.getDisplayId() ?: -1
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val maxRetries = 4
                val retryDelays = longArrayOf(300, 400, 500, 600)
                for (attempt in 0 until maxRetries) {
                    kotlinx.coroutines.delay(retryDelays[attempt])
                    imeCheckTick(activePane, activeDisplayId, "check") ?: return@launch
                    if (lastImeState) {
                        startImeHideWatchdog()
                        break
                    }
                    if (attempt == maxRetries - 1) break
                }
            } catch (e: Exception) {
                imeCheckSuspendUntil = System.currentTimeMillis() + 10_000
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val compositionDispatcher = kotlinx.coroutines.newSingleThreadContext("composition")

    private fun injectCompositionUpdate(backspaces: Int, text: String) {
        serviceScope.launch(compositionDispatcher) {
            try {
                val displayId = activeInputDisplayId()
                shizukuSetup?.privilegedService?.injectComposingText(backspaces, text, displayId)
            } catch (e: Exception) {}
        }
    }

    private fun injectKeyEvent(keyCode: Int) {
        serviceScope.launch(compositionDispatcher) {
            try {
                val displayId = activeInputDisplayId()
                val cmd = if (displayId > 0) "input -d $displayId keyevent $keyCode" else "input keyevent $keyCode"
                shizukuSetup?.privilegedService?.execCommand(cmd)
            } catch (e: Exception) {}
        }
    }

    private fun onViewportChange(width: Int, height: Int, layoutMode: String = "") {
        resizeJob?.cancel()
        resizeJob = serviceScope.launch {
            kotlinx.coroutines.delay(200L) // Wait 200ms to allow layout settling and avoid rapid rebuild avalanche
            val forceSingle = (layoutMode == "single")
            rebuildPipeline(width, height, forceSingle = forceSingle)
        }
    }

    private fun hasActiveSecondaryViewportRequest(): Boolean {
        return secondaryRequestedWidth > 0 && secondaryRequestedHeight > 0
    }

    private fun shouldUseRequestedHeightForDualMode(isSecondaryPane: Boolean = false): Boolean {
        return isSecondaryPane ||
            hasActiveSecondaryViewportRequest() ||
            secondaryPipeline.displayId >= 0 ||
            secondaryPipeline.currentApp.isNotBlank()
    }

    private fun effectiveMaxHeightForRequest(
        requestedHeight: Int,
        isSecondaryPane: Boolean = false,
        forceSingle: Boolean = false
    ): Int {
        return requestedHeight.coerceAtMost(1080)
    }

    private fun shouldUseRequestedHeightForSplit(): Boolean = false

    private suspend fun rebuildPipeline(
        newWidth: Int,
        newHeight: Int,
        force: Boolean = false,
        forceSingle: Boolean = false
    ) {
        primaryPipeline.rebuild(newWidth, newHeight, force, forceSingle)
    }

    

    
    private fun onCodecModeRequest(mode: String) {
        if (!CodecModeTransition.shouldApply(mode, currentCodecMode, primaryPipeline.jpegEncoder != null)) return
        currentCodecMode = CodecModeTransition.MODE_MJPEG
        Log.i(TAG, "Codec mode request: mjpeg")
        if (primaryPipeline.width == 0 || primaryPipeline.height == 0) {
            Log.i(TAG, "Viewport dimensions not yet set (0x0) ??deferring pipeline build")
            return
        }
        Log.i(TAG, "Delegating to rebuildPipeline")
        serviceScope.launch {
            try {
                rebuildPipeline(primaryPipeline.width, primaryPipeline.height, force = true)
                if (secondaryPipeline.width > 0 && secondaryPipeline.height > 0) {
                    rebuildSecondaryPipeline(secondaryPipeline.width, secondaryPipeline.height)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch codec to mjpeg", e)
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy called")
        performCleanup("onDestroy") 
        MirrorWidgetProvider.updateAllWidgets(this)
        super.onDestroy()
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
            Log.w(TAG, "Failed to grant CAPTURE_AUDIO_OUTPUT", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Mirror Service", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, com.castla.mirror.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(ACTION_STOP).apply { setPackage(packageName) }
        val stopPending = PendingIntent.getBroadcast(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Castla")
            .setContentText("Streaming to Tesla")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "??Stop Mirroring", stopPending)
            .build()
    }

    inner class VirtualDisplayPipeline(val name: String) {
        val isPrimary = (name == "primary")

        var width = 0
        var height = 0
        var displayId = -1
        val vdGeneration = java.util.concurrent.atomic.AtomicLong(0)

        var videoEncoder: VideoEncoder? = null
        var jpegEncoder: JpegEncoder? = null
        var currentEncoderSurface: Surface? = null

        var pipelineState = PipelineState.IDLE
        var pendingRebuildRequest: RebuildRequest? = null

        var targetBitrate = 0
        var currentBitrate = 0

        var currentApp = ""
        var currentWebUrl: String? = null

        var touchInjector: TouchInjector? = null

        suspend fun rebuild(
            newWidth: Int,
            newHeight: Int,
            force: Boolean = false,
            forceSingle: Boolean = false
        ) {
            if (newWidth <= 0 || newHeight <= 0) return
            val lock = if (isPrimary) pipelineMutex else secondaryPipelineMutex
            lock.withLock {
                if (pipelineState == PipelineState.REBUILDING) {
                    pendingRebuildRequest = RebuildRequest(newWidth, newHeight, force, forceSingle)
                    Log.i(TAG, "$name pipeline is REBUILDING. Storing pending request: ${newWidth}x${newHeight}")
                    return@withLock
                }

                pipelineState = PipelineState.REBUILDING

                try {
                    executeActualRebuild(newWidth, newHeight, force, forceSingle)
                } finally {
                    val nextRequest = pendingRebuildRequest
                    if (nextRequest != null) {
                        pendingRebuildRequest = null
                        Log.i(TAG, "Consuming pending $name rebuild request: ${nextRequest.width}x${nextRequest.height}")
                        pipelineState = PipelineState.IDLE
                        serviceScope.launch {
                            rebuild(nextRequest.width, nextRequest.height, nextRequest.force, nextRequest.forceSingle)
                        }
                    } else {
                        pipelineState = PipelineState.IDLE
                        Log.i(TAG, "$name pipeline rebuild finished. Transitioning to IDLE")
                    }
                }
            }
        }

        private suspend fun executeActualRebuild(
            targetWidth: Int,
            targetHeight: Int,
            force: Boolean = false,
            forceSingle: Boolean = false
        ) {
            val effectiveMaxHeight = effectiveMaxHeightForRequest(targetHeight, isSecondaryPane = !isPrimary, forceSingle = forceSingle)
            Log.i(TAG, "Rebuilding $name pipeline requested=${targetWidth}x${targetHeight} effectiveMaxHeight=$effectiveMaxHeight")

            var targetW = targetWidth
            var targetH = targetHeight
            if (targetH > effectiveMaxHeight) {
                val scale = effectiveMaxHeight.toFloat() / targetH
                targetH = effectiveMaxHeight
                targetW = (targetW * scale).toInt()
            }
            val alignedWidth = ((targetW + 15) and 15.inv()).coerceAtLeast(320)
            val alignedHeight = ((targetH + 15) and 15.inv()).coerceAtLeast(320)

            if (!force && isPrimary && alignedWidth == width && alignedHeight == height) {
                Log.d(TAG, "rebuild $name Pipeline skipped: dimensions unchanged ${alignedWidth}x${alignedHeight}")
                return
            }

            if (isPrimary && (alignedWidth > 3840 || alignedHeight > 3840)) {
                Log.w(TAG, "rebuild $name Pipeline skipped: dimensions out of range ${alignedWidth}x${alignedHeight}")
                return
            }

            val w = alignedWidth
            val h = alignedHeight
            val dpi = computeVirtualDisplayDpi(w, h)

            if (isPrimary) {
                val newTargetBitrate = com.castla.mirror.utils.StreamMath.calculateBaseBitrate(w, h)
                targetBitrate = if (isCurrentAppVideo) com.castla.mirror.utils.StreamMath.calculateOttBitrate(newTargetBitrate) else newTargetBitrate
                currentBitrate = targetBitrate
            } else {
                if (virtualDisplayManager?.isBound() != true) return
                val hasMatchingPipeline = displayId >= 0 && width == w && height == h &&
                    ((currentCodecMode == "mjpeg" && jpegEncoder != null) || (currentCodecMode != "mjpeg" && videoEncoder != null))
                if (hasMatchingPipeline) {
                    Log.d(TAG, "$name pipeline already matches ${w}x${h}, skipping rebuild")
                    return
                }
            }

            videoEncoder?.release()
            videoEncoder = null
            jpegEncoder?.release()
            jpegEncoder = null

            val surface = if (currentCodecMode == "mjpeg") {
                val jpeg = JpegEncoder(w, h, fps = 15, quality = 65)
                val inputSurface = jpeg.createInputSurface()
                jpeg.start { frameData, isKeyFrame -> mirrorServer?.broadcastFrame(frameData, isKeyFrame, name) }
                jpegEncoder = jpeg

                if (isPrimary) {
                    mirrorServer?.setKeyframeRequester("primary") {
                        serviceScope.launch {
                            try {
                                val vdId = virtualDisplayManager?.getDisplayId()
                                if (vdId != null && vdId >= 0) {
                                    virtualDisplayManager?.getPrivilegedService()?.wakeUpDisplay(vdId)
                                }
                                restoreCurrentVdContent()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to handle MJPEG keyframe request", e)
                            }
                        }
                    }
                } else {
                    mirrorServer?.setKeyframeRequester("secondary") {}
                }
                inputSurface
            } else {
                val baseBitrate = if (isPrimary) currentBitrate else secondaryBitrate(w, h)
                val encoder = VideoEncoder(w, h, baseBitrate, thermalFpsOverride ?: currentFps)
                val inputSurface = encoder.createInputSurface()
                videoEncoder = encoder

                encoder.onSpsPps = { spsPps -> mirrorServer?.broadcastSpsPps(spsPps, name) }
                encoder.start { frameData, isKeyFrame -> mirrorServer?.broadcastFrame(frameData, isKeyFrame, name) }
                mirrorServer?.setKeyframeRequester(name) { encoder.requestKeyFrame() }
                inputSurface
            }

            currentEncoderSurface = surface
            width = w
            height = h

            if (isPrimary) {
                touchInjector?.updateDimensions(w, h)
                if (virtualDisplayManager?.isBound() == true) {
                    primaryVdOperationMutex.withLock {
                        Log.i(TAG, "Recreating primary virtual display during pipeline rebuild")
                        virtualDisplayManager?.createVirtualDisplay(w, h, dpi, surface)
                        if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                            val activeId = virtualDisplayManager?.getDisplayId() ?: -1
                            displayId = activeId
                            val generation = markPrimaryVdCreated(activeId, "primary_rebuild")
                            touchInjector?.setVirtualDisplayInjector { motionEvent ->
                                virtualDisplayManager?.injectMotionEvent(motionEvent)
                            }
                            restoreCurrentVdContentLocked(generation, activeId)
                        } else {
                            virtualDisplayManager?.createVirtualDisplay(w, h, dpi, surface)
                            if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                                val activeId = virtualDisplayManager?.getDisplayId() ?: -1
                                displayId = activeId
                                val generation = markPrimaryVdCreated(activeId, "primary_rebuild_retry")
                                touchInjector?.setVirtualDisplayInjector { motionEvent ->
                                    virtualDisplayManager?.injectMotionEvent(motionEvent)
                                }
                                restoreCurrentVdContentLocked(generation, activeId)
                            } else {
                                Log.e(TAG, "Primary VD creation failed during rebuild")
                                markTerminal(TerminalReason.VD_RECREATE_FAILED)
                            }
                        }
                    }
                } else if (!shizukuSetupInProgress) {
                    Log.w(TAG, "Shizuku not bound during rebuild ??attempting rebind")
                    trySetupVirtualDisplay(w, h, surface) { success ->
                        if (!success) {
                            Log.e(TAG, "Shizuku rebind failed ??keeping service alive for recovery")
                        }
                    }
                }
            } else {
                if (displayId >= 0) {
                    virtualDisplayManager?.getPrivilegedService()?.setSurface(displayId, surface)
                    virtualDisplayManager?.resizeDisplay(displayId, w, h, dpi)
                    touchInjector = (touchInjector ?: TouchInjector(w, h)).also { injector ->
                        injector.updateDimensions(w, h)
                        injector.setVirtualDisplayInjector { motionEvent ->
                            try {
                                shizukuSetup?.privilegedService?.injectMotionEvent(displayId, motionEvent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to inject secondary input on display $displayId", e)
                            }
                        }
                    }
                    Log.i(TAG, "Gradually resized secondary VD $displayId to ${w}x${h} without restarting")
                } else {
                    val newDisplayId = virtualDisplayManager?.createSecondaryVirtualDisplay(w, h, dpi, surface) ?: -1
                    if (newDisplayId < 0) {
                        releaseSecondaryPipeline(clearState = false)
                        return
                    }
                    displayId = newDisplayId
                    try {
                        virtualDisplayManager?.getPrivilegedService()?.launchHomeOnDisplay(newDisplayId)
                        Log.i(TAG, "Successfully bound launcher properties to VD_2 display $newDisplayId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch secondary home on VD_2", e)
                    }
                    touchInjector = (touchInjector ?: TouchInjector(w, h)).also { injector ->
                        injector.updateDimensions(w, h)
                        injector.setVirtualDisplayInjector { motionEvent ->
                            try {
                                shizukuSetup?.privilegedService?.injectMotionEvent(displayId, motionEvent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to inject secondary input on display $displayId", e)
                            }
                        }
                    }
                }
            }
        }

        fun invalidateVd(reason: String) {
            displayId = -1
            vdGeneration.incrementAndGet()
            Log.i(TAG, "[$reason] Invalidated $name virtual display state")
        }

        fun release() {
            videoEncoder?.release()
            videoEncoder = null
            jpegEncoder?.release()
            jpegEncoder = null
            currentEncoderSurface = null
            touchInjector = null
            if (!isPrimary) {
                displayId = -1
                width = 0
                height = 0
            }
        }
    }

    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == ACTION_STOP) {
                val stopIntent = Intent(context, MirrorForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(stopIntent)
            }
        }
    }
}
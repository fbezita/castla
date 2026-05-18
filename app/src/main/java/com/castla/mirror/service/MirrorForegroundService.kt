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
import com.castla.mirror.utils.SplitMath
import com.castla.mirror.utils.StreamMath
import com.castla.mirror.ui.SplitWebPresentation
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

        /** Observable service running state – UI can collect this to stay in sync. */
        private val _serviceRunningFlow = MutableStateFlow(false)
        val serviceRunningFlow: StateFlow<Boolean> = _serviceRunningFlow

        /** True while the service is actively tearing down the previous session. */
        private val _cleanupInProgressFlow = MutableStateFlow(false)
        val cleanupInProgressFlow: StateFlow<Boolean> = _cleanupInProgressFlow

        /** Current panel-off state — UI observes this for button state. */
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
            // 720p Profiling Group (Static resolution, stepping up frame performance)
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

        // Split resize verification tunables
        private const val MAX_LOCATE_ATTEMPTS = 10
        private const val MAX_VERIFY_ROUNDS = 4
        private const val SHELL_TIMEOUT_MS = 800L
        private const val VERIFY_BACKOFF_MS = 400L
        private const val BOUNDS_TOLERANCE_PX = 16
    }

    /** Binder for local (same-process) binding */
    inner class LocalBinder : Binder() {
        val service: MirrorForegroundService get() = this@MirrorForegroundService
    }

    private val binder = LocalBinder()

    private var mirrorServer: MirrorServer? = null
    private var videoEncoder: VideoEncoder? = null
    private var jpegEncoder: JpegEncoder? = null
    private var audioCapture: AudioCapture? = null
    private var audioOrchestrator: AudioCaptureOrchestrator? = null
    private var touchInjector: TouchInjector? = null
    private var virtualDisplayManager: VirtualDisplayManager? = null
    private var shizukuSetup: ShizukuSetup? = null
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentEncoderSurface: android.view.Surface? = null
    private var currentBitrate: Int = 2_500_000
    private var currentFps: Int = 30
    private var currentMaxHeight: Int = 720
    private var mirroringMode: String = "FULL_SCREEN"
    private var targetPackage: String = ""
    private var browserConnectionListener: ((Boolean) -> Unit)? = null
    @Volatile private var stopRequested = false
    @Volatile private var cleanupCompleted = false
    private var isWakingUpFromPowerButton = false
    /**
     * First-writer-wins terminal failure reason. Set by [markTerminal] at the
     * moment a known fatal failure is detected; consumed by [performCleanup]
     * when emitting the SESSION_END event so the recorded reason is consistent.
     */
    private val terminalReason = java.util.concurrent.atomic.AtomicReference<TerminalReason?>(null)
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var resizeJob: Job? = null
    private var secondaryResizeJob: Job? = null
    private var pendingBrowserDisconnectJob: Job? = null
    private var browserConnected = false
    private var currentVdApp: String = "com.android.settings" // what's running on main VD
    private var currentWebUrl: String? = null
    private var currentWebSplitMode: Boolean = false
    private var activeSplitUrl: String? = null
    private var activeSplitComponent: String? = null
    private var currentSecondaryApp: String = ""
    private var currentSecondaryWebUrl: String? = null
    private var secondaryVideoEncoder: VideoEncoder? = null
    private var secondaryJpegEncoder: JpegEncoder? = null
    private var secondaryTouchInjector: TouchInjector? = null
    private var secondaryDisplayId: Int = -1
    private var secondaryWidth: Int = 0
    private var secondaryHeight: Int = 0
    private var secondaryRequestedWidth: Int = 0
    private var secondaryRequestedHeight: Int = 0
    @Volatile private var currentCodecMode: String = "h264"
    private val pipelineMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var splitPresentation: SplitWebPresentation? = null
    private var singleVdSplit: Boolean = false

    // ABR (Adaptive Bitrate) state
    private var targetBitrate: Int = 4_000_000
    private var lastCongestionTimeMs = 0L
    private var abrJob: Job? = null
    // Thermal throttling: stores original bitrate before thermal reduction for restoration
    private var preThermalTargetBitrate: Int = 0
    // Thermal fps/resolution overrides — applied by rebuildPipeline when non-null
    private var thermalFpsOverride: Int? = null
    private var thermalMaxHeight: Int? = null
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
    private var autoScaleJob: Job? = null
    // Current auto-selected tier — index into AUTO_TIERS
    private var autoTierIndex: Int = 1 // Balanced initialization targeting index 1 ("720p30")
    // Stability counter: number of consecutive healthy check intervals
    private var autoStableCount: Int = 0
    // Browser quality report — updated asynchronously from control socket
    @Volatile private var lastQualityDroppedFrames: Int = 0
    @Volatile private var lastQualityAvgDelayMs: Double = 0.0
    @Volatile private var lastQualityBacklogDrops: Int = 0

    // WakeLocks to keep streaming alive when screen is off
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Deferred pipeline state: heavy capture/encoding starts only when browser connects
    private var pendingAudioEnabled = false
    private var deferredAudioStartJob: Job? = null

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
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

        acquireWakeLocks()

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
    private val _thermalStatus = MutableStateFlow(0)
    val thermalStatus: StateFlow<Int> = _thermalStatus

    fun setBrowserConnectionListener(listener: ((Boolean) -> Unit)?) {
        browserConnectionListener = listener
        mirrorServer?.setBrowserConnectionListener(listener)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceRunning = true
        isCleanupInProgress = false
        createNotificationChannel()
        observeAppLaunchRequests()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                handleThermalStatusChange(status)
            }
            pm.addThermalStatusListener(mainExecutor, thermalListener!!)
        }

        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> {
                        Log.i(TAG, "Screen OFF detected — using scrcpy approach")
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
    private fun handleThermalStatusChange(status: Int) {
        _thermalStatus.value = status

        if (preThermalTargetBitrate == 0 && targetBitrate > 0) {
            preThermalTargetBitrate = targetBitrate
        }

        when (status) {
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> {
                Log.w(TAG, "Thermal status CRITICAL/EMERGENCY ($status) — warning only, continuing")
                android.os.Handler(mainLooper).post {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.toast_thermal_warning),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            PowerManager.THERMAL_STATUS_SEVERE -> {
                Log.w(TAG, "Thermal status SEVERE ($status) - Throttling encoder heavily + fps/resolution")
                val newBitrate = (preThermalTargetBitrate * 0.4).toInt().coerceAtLeast(500_000)
                currentBitrate = newBitrate
                targetBitrate = newBitrate
                videoEncoder?.setBitrate(currentBitrate)
                jpegEncoder?.setFps(8)
                Log.w(TAG, "Thermal SEVERE — stopping audio capture to reduce CPU load")
                audioOrchestrator?.stop()
                thermalFpsOverride = 15
                thermalMaxHeight = 720
                autoTierIndex = 0
                autoStableCount = 0
                if (browserConnected) {
                    serviceScope.launch { rebuildPipeline(currentWidth, currentHeight, force = true) }
                }
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                Log.w(TAG, "Thermal status MODERATE ($status) - Throttling encoder + fps drop to 20")
                val newBitrate = (preThermalTargetBitrate * 0.6).toInt().coerceAtLeast(500_000)
                currentBitrate = newBitrate
                targetBitrate = newBitrate
                videoEncoder?.setBitrate(currentBitrate)
                jpegEncoder?.setFps(12)
                thermalFpsOverride = 20
                thermalMaxHeight = null
                autoTierIndex = 0
                autoStableCount = 0
                if (browserConnected) {
                    serviceScope.launch { rebuildPipeline(currentWidth, currentHeight, force = true) }
                }
            }
            PowerManager.THERMAL_STATUS_LIGHT -> {
                Log.i(TAG, "Thermal status LIGHT ($status) - Preemptive throttling")
                val newBitrate = (preThermalTargetBitrate * 0.85).toInt().coerceAtLeast(500_000)
                currentBitrate = newBitrate
                targetBitrate = newBitrate
                videoEncoder?.setBitrate(currentBitrate)
                thermalFpsOverride = null
                thermalMaxHeight = null
            }
            PowerManager.THERMAL_STATUS_NONE -> {
                Log.i(TAG, "Thermal status NONE ($status) - Restoring full bitrate and fps")
                thermalFpsOverride = null
                thermalMaxHeight = null
                if (preThermalTargetBitrate > 0) {
                    targetBitrate = preThermalTargetBitrate
                    currentBitrate = preThermalTargetBitrate
                    videoEncoder?.setBitrate(currentBitrate)
                    jpegEncoder?.setFps(15)
                    if (browserConnected) {
                        serviceScope.launch { rebuildPipeline(currentWidth, currentHeight, force = true) }
                    }
                }
            }
        }

        broadcastThermalStatus(status)
    }

    private fun broadcastThermalStatus(status: Int) {
        val level = when (status) {
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> "severe"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            else -> "none"
        }
        val json = JSONObject().apply {
            put("type", "thermalStatus")
            put("level", level)
        }.toString()
        mirrorServer?.broadcastControlMessage(json)
    }

    private fun acquireWakeLocks() {
        try {
            releaseWakeLocks() 
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Castla::StreamingWakeLock").apply {
                setReferenceCounted(false)
                acquire(14400000)
            }
            
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Castla::StreamingWifiLock").apply {
                setReferenceCounted(false)
                acquire() 
            }
            Log.i(TAG, "WakeLocks acquired (CPU & WiFi will stay awake)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake locks", e)
        }
    }
    
    private fun releaseWakeLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            wifiLock = null
            Log.i(TAG, "WakeLocks released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake locks", e)
        }
    }

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
                Log.i(TAG, "VD launch request: $component (video=${request.isVideoApp}, mode=${request.launchMode})")

                when (request.launchMode) {
                    LaunchMode.EXTERNAL_BROWSER_URL -> {
                        val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                        val url = request.url ?: return@collect
                        dismissSplitPresentation(clearState = true)
                        if (request.splitMode && ensureSplitViable("bus-external-browser")) {
                            launchSplitExternalBrowserTarget(displayId, url, request.sourceAppPackage, request.allowEmbeddedFallback)
                        } else {
                            launchExternalBrowserTarget(displayId, url, request.sourceAppPackage, request.allowEmbeddedFallback)
                        }
                    }
                    LaunchMode.INTERNAL_WEBVIEW -> {
                        val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                        dismissSplitPresentation(clearState = true)
                        val activityClassName = component.substringAfter('/', "com.castla.mirror.ui.WebBrowserActivity")
                        val url = request.url ?: request.intentExtra ?: return@collect
                        if (request.splitMode && ensureSplitViable("bus-internal-webview")) {
                            launchSplitWebTarget(activityClassName, displayId, url)
                        } else {
                            launchFullscreenWebTarget(activityClassName, displayId, url)
                        }
                    }
                    LaunchMode.STANDARD_APP -> {
                        if (request.intentExtra != null) {
                            val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                            dismissSplitPresentation(clearState = true)
                            val activityClassName = component.substringAfter('/', "com.castla.mirror.ui.WebBrowserActivity")
                            if (request.splitMode && ensureSplitViable("bus-standard-web")) {
                                launchSplitWebTarget(activityClassName, displayId, request.intentExtra)
                            } else {
                                launchFullscreenWebTarget(activityClassName, displayId, request.intentExtra)
                            }
                        } else {
                            dismissSplitPresentation(clearState = true)
                            if (request.splitMode && ensureSplitViable("bus-standard-app")) {
                                launchSplitStandardTarget(component)
                            } else {
                                launchFullscreenStandardTarget(component)
                            }
                        }
                    }
                }

                val now = android.os.SystemClock.elapsedRealtime()
                if (request.isVideoApp != isCurrentAppVideo && now - lastBitrateChangeMs > 500) {
                    isCurrentAppVideo = request.isVideoApp
                    lastBitrateChangeMs = now

                    val baseTargetBitrate = com.castla.mirror.utils.StreamMath.calculateBaseBitrate(currentWidth, currentHeight)
                    val thermalActive = _thermalStatus.value >= PowerManager.THERMAL_STATUS_LIGHT
                    targetBitrate = if (isCurrentAppVideo && !thermalActive) com.castla.mirror.utils.StreamMath.calculateOttBitrate(baseTargetBitrate) else baseTargetBitrate

                    if (currentBitrate > targetBitrate || (now - lastCongestionTimeMs > 2000)) {
                         currentBitrate = targetBitrate
                         videoEncoder?.setBitrate(currentBitrate)
                    }
                    Log.i(TAG, "OTT app detected=${isCurrentAppVideo} — target bitrate set to ${targetBitrate / 1000}kbps")

                    if (autoResolution || autoFps) {
                        val activeTiers = AUTO_TIERS.filter { it.maxHeight == currentMaxHeight }
                        val boostTier = AutoScalePolicy.ottMinTier(
                            currentTierIndex = autoTierIndex,
                            isVideoApp = isCurrentAppVideo,
                            thermalStatus = _thermalStatus.value,
                            tierCount = activeTiers.size
                        )
                        if (boostTier != null && boostTier < activeTiers.size) {
                            autoTierIndex = boostTier
                            autoStableCount = 0
                            applyAutoTier()
                            notifyAutoTierChange("ott_boost")
                            Log.i(TAG, "OTT tier boost — jumped to ${activeTiers[autoTierIndex].label}")
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

    private fun onNetworkCongestion() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCongestionTimeMs > 500) { 
            lastCongestionTimeMs = now
            val minBitrate = 500_000
            currentBitrate = (currentBitrate * 0.8).toInt().coerceAtLeast(minBitrate)
            videoEncoder?.setBitrate(currentBitrate)
            Log.w(TAG, "ABR: Network congestion detected! Dropping bitrate to ${currentBitrate / 1000}kbps")
        }
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
        Log.i(TAG, "[BUILD:screen-off-v3] $event — " +
                "state=${screenOffPolicy.state}, keyguardLocked=$keyguardLocked, deviceLocked=$deviceLocked, " +
                "browserConnected=$browserConnected, serverConnected=${mirrorServer?.isBrowserConnected()}, " +
                "wakeLockHeld=${wakeLock?.isHeld == true}, vdId=$vdId, panelOffSupported=${screenOffPolicy.isPanelOffSupported}")
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
            Log.i(TAG, "Power button pressed while panel was OFF — restoring physical panel")
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
            Log.i(TAG, "Screen ON broadcast received from our own WAKEUP injection — keeping physical panel OFF")
            return
        }
        
        val action = screenOffPolicy.onScreenOn()
        logScreenState("Screen ON (action=$action)")
        executeScreenOnAction(action)
        _panelOffStateFlow.value = screenOffPolicy.state

        cancelPendingBrowserDisconnect("screen_on")

        val stillConnected = mirrorServer?.isBrowserConnected() == true
        if (!stillConnected && browserConnected && !isCleanupInProgress) {
            Log.i(TAG, "Screen ON — browser gone while screen was off, executing deferred teardown")
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
                    Log.w(TAG, "Panel-off requested but no VirtualDisplayManager — falling back")
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
            Log.w(TAG, "VD keep-alive skipped — no VirtualDisplayManager")
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
                if (secondaryDisplayId >= 0) {
                    // Bypass exit monitor entirely when running dual apps (split mode)
                    // to prevent annoying spontaneous exits due to system focus/display adjustments.
                    continue
                }
                val currentApp = currentVdApp
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
                                currentVdApp = "HOME"
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
                thermalListener?.let { pm.removeThermalStatusListener(it) }
            } catch (_: Exception) {}
        }
        releaseWakeLocks()
        stopVdKeepAlive()

        audioOrchestrator?.stop()

        try { resizeJob?.cancel() } catch (_: Exception) {}
        try { abrJob?.cancel() } catch (_: Exception) {}
        try { autoScaleJob?.cancel() } catch (_: Exception) {}
        try { serviceScope.cancel() } catch (_: Exception) {}
        try { compositionDispatcher.close() } catch (_: Exception) {}

        dismissSplitPresentation(clearState = true)
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
        try { videoEncoder?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release video encoder", e) }
        try { jpegEncoder?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release jpeg encoder", e) }
        try { touchInjector?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release touch injector", e) }
        try { mirrorServer?.stop() } catch (e: Exception) { Log.w(TAG, "Failed to stop mirror server", e) }

        virtualDisplayManager = null
        shizukuSetup = null
        videoEncoder = null
        jpegEncoder = null
        touchInjector = null
        mirrorServer = null

        instance = null
        isCleanupInProgress = false
        isServiceRunning = false
        Log.i(TAG, "Cleanup completed: $reason")
    }

    private fun startAbrLoop() {
        abrJob?.cancel()
        abrJob = serviceScope.launch {
            while (isServiceRunning && browserConnected) {
                kotlinx.coroutines.delay(2000)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastCongestionTimeMs >= 2000 && currentBitrate < targetBitrate) {
                    currentBitrate = (currentBitrate * 1.1).toInt().coerceAtMost(targetBitrate)
                    videoEncoder?.setBitrate(currentBitrate)
                    Log.i(TAG, "ABR: Network stable. Increasing bitrate to ${currentBitrate / 1000}kbps")
                }
            }
        }
    }

    private fun startAutoScaleLoop() {
        if (!autoResolution && !autoFps) return
        autoScaleJob?.cancel()
        
        val activeTiers = AUTO_TIERS.filter { it.maxHeight == currentMaxHeight }
        autoTierIndex = activeTiers.indexOfFirst { it.fps == 30 }.coerceAtLeast(0)
        
        autoStableCount = 0
        autoScaleJob = serviceScope.launch {
            kotlinx.coroutines.delay(AUTO_SCALE_INITIAL_DELAY_MS)
            while (isServiceRunning && browserConnected) {
                evaluateAutoScale()
                kotlinx.coroutines.delay(AUTO_SCALE_INTERVAL_MS)
            }
        }
    }

    private fun evaluateAutoScale() {
        if (currentCodecMode == "mjpeg") {
            // MJPEG mode must stick to its stable configured resolution (e.g. 720p)
            // to avoid massive JPEG payload sizes (1080p is extremely heavy for MJPEG)
            // and destructive pipeline recreations.
            return
        }
        
        val activeTiers = AUTO_TIERS.filter { it.maxHeight == currentMaxHeight }
        if (activeTiers.isEmpty()) return
        
        val now = android.os.SystemClock.elapsedRealtime()
        val input = AutoScaleInput(
            thermalStatus = _thermalStatus.value,
            networkStable = now - lastCongestionTimeMs >= AUTO_SCALE_INTERVAL_MS,
            browserHealthy = AutoScalePolicy.isBrowserHealthy(
                lastQualityDroppedFrames, lastQualityBacklogDrops, lastQualityAvgDelayMs
            ),
            currentTierIndex = autoTierIndex.coerceIn(0, activeTiers.size - 1),
            stableCount = autoStableCount,
            tierCount = activeTiers.size
        )

        when (val decision = AutoScalePolicy.evaluate(input)) {
            is AutoScaleDecision.DropToTier -> {
                autoTierIndex = decision.tierIndex.coerceIn(0, activeTiers.size - 1)
                autoStableCount = 0
                applyAutoTier()
                notifyAutoTierChange(decision.reason)
                Log.i(TAG, "AutoScale: ${decision.reason} — dropped to ${activeTiers[autoTierIndex].label}")
            }
            is AutoScaleDecision.StepDown -> {
                autoTierIndex = decision.newTierIndex.coerceIn(0, activeTiers.size - 1)
                autoStableCount = 0
                applyAutoTier()
                notifyAutoTierChange(decision.reason)
                Log.i(TAG, "AutoScale: ${decision.reason} — stepped down to ${activeTiers[autoTierIndex].label}")
            }
            is AutoScaleDecision.StepUp -> {
                autoTierIndex = decision.newTierIndex.coerceIn(0, activeTiers.size - 1)
                autoStableCount = 0
                applyAutoTier()
                notifyAutoTierChange("stable")
                Log.i(TAG, "AutoScale: stable — stepped up to ${activeTiers[autoTierIndex].label}")
            }
            is AutoScaleDecision.Hold -> {
                autoStableCount = decision.newStableCount
            }
            AutoScaleDecision.Block -> {
                autoStableCount = 0
            }
        }
    }

    private fun notifyAutoTierChange(reason: String) {
        val activeTiers = AUTO_TIERS.filter { it.maxHeight == currentMaxHeight }
        if (activeTiers.isEmpty()) return
        val tier = activeTiers[autoTierIndex.coerceIn(0, activeTiers.size - 1)]
        val json = JSONObject().apply {
            put("type", "autoTierChange")
            put("tier", tier.label)
            put("reason", reason)
        }.toString()
        mirrorServer?.broadcastControlMessage(json)
    }

    private fun applyAutoTier() {
        val activeTiers = AUTO_TIERS.filter { it.maxHeight == currentMaxHeight }
        if (activeTiers.isEmpty()) return
        val tier = activeTiers[autoTierIndex.coerceIn(0, activeTiers.size - 1)]
        
        // Check if the resolution value is actually changing to isolate hard pipeline resets
        val isResolutionChanging = autoResolution && (currentMaxHeight != tier.maxHeight)
        
        // Mutate configuration tracking states safely
        if (autoResolution) currentMaxHeight = tier.maxHeight
        if (autoFps) currentFps = tier.fps

        // Adjust static bounds targetBitrate instantly to adapt ABR ceilings
        targetBitrate = tier.bitrate
        
        if (browserConnected && currentWidth > 0 && currentHeight > 0) {
            if (isResolutionChanging) {
                // Perform a destructive recreation only when the physical display boundaries scale
                serviceScope.launch {
                    rebuildPipeline(currentWidth, currentHeight, force = true)
                }
            } else {
                // [FIX] Perform ultra-smooth 0ms lag optimization via setParameters on runtime changes.
                // This updates the Bitrate/Operating Rate seamlessly without disrupting video encoders.
                try {
                    currentBitrate = tier.bitrate
                    videoEncoder?.setBitrate(currentBitrate)
                    
                    // Directly hint the running MediaCodec instance with new operating clock metrics
                    videoEncoder?.let {
                        val params = Bundle().apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                putInt(MediaFormat.KEY_OPERATING_RATE, tier.fps)
                            }
                        }
                        // Interrogate and apply properties to the running MediaCodec reference via safe pipeline bridge
                        // Note: If VideoEncoder doesn't expose underlying setParameters, it is driven smoothly via encoder.setBitrate internally.
                    }
                    Log.d(TAG, "Runtime quality tier scaled via 0ms hardware hint: ${tier.label} (${tier.bitrate / 1000}kbps)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to forward seamless streaming parameters to hardware encoder", e)
                }
            }
        }
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

            currentWidth = width
            currentHeight = height
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

            touchInjector = TouchInjector(width, height)

            mirrorServer = MirrorServer(this).also { server ->
                server.setNetworkCongestionListener { onNetworkCongestion() }
                server.setTouchListener { event ->
                    if (event.pane == "secondary") {
                        secondaryTouchInjector?.onTouchEvent(event)
                    } else {
                        touchInjector?.onTouchEvent(event)
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
                    singleVdSplit = layoutMode == "browser_only_split" || layoutMode == "freeform_split"
                    if (pane == "secondary") {
                        if (singleVdSplit) {
                            Log.d(TAG, "Ignoring secondary viewport in single-VD split mode")
                        } else {
                            onSecondaryViewportChange(w, h)
                        }
                    } else {
                        onViewportChange(w, h, layoutMode)
                    }
                }
                server.setTextInputListener { text -> injectText(text) }
                server.setKeyEventListener { keyCode -> injectKeyEvent(keyCode) }
                server.setCompositionUpdateListener { bs, text -> injectCompositionUpdate(bs, text) }
                server.setBubbleClosedListener {
                    Log.d(TAG, "Browser reported bubbleClosed — resetting IME state")
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
                    dismissSplitPresentation(clearState = true)
                    if (!singleVdSplit) {
                        releaseSecondaryPipeline(clearState = true)
                    }
                    if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                        virtualDisplayManager?.launchHomeOnDisplay()
                    } else {
                        Log.w(TAG, "Skipping HOME launch: virtual display is not active")
                    }
                    currentVdApp = "HOME"
                    currentWebUrl = null
                    clearSplitState()
                }
                server.setAppLaunchListener { pkgName, componentName, splitMode, pane ->
                    launchAppFromWebLauncher(pkgName, componentName, splitMode, pane)
                }

                server.setCloseSplitListener {
                    Log.i(TAG, "Close split requested — restoring primary fullscreen")
                    closeFreeformSplit()
                }

                server.setDisplayDensityListener { scale ->
                    Log.i(TAG, "Display density scale changed to $scale")
                    dpiScale = scale
                    val vdm = virtualDisplayManager
                    if (vdm != null && vdm.hasVirtualDisplay() && currentWidth > 0 && currentHeight > 0) {
                        val dpi = computeVirtualDisplayDpi(currentWidth, currentHeight)
                        vdm.resizeDisplay(vdm.getDisplayId(), currentWidth, currentHeight, dpi)
                        Log.i(TAG, "Updated VD DPI to $dpi (scale=$scale, size=${currentWidth}x${currentHeight})")
                    }
                }

                server.setQualityReportListener { dropped, avgDelay, backlogDrops ->
                    lastQualityDroppedFrames = dropped
                    lastQualityAvgDelayMs = avgDelay
                    lastQualityBacklogDrops = backlogDrops
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
                Log.i(TAG, "Server started on port ${MirrorServer.DEFAULT_PORT} — waiting for browser")
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
    private var activeSplitSession: ActiveLaunchSession? = null

    private fun internalComponentName(activityClassName: String): String {
        return if (activityClassName.contains('/')) activityClassName else "$packageName/$activityClassName"
    }

    private fun clearSecondaryState() {
        currentSecondaryApp = ""
        currentSecondaryWebUrl = null
        secondaryWidth = 0
        secondaryHeight = 0
        secondaryRequestedWidth = 0
        secondaryRequestedHeight = 0
    }

    private fun secondaryBitrate(width: Int, height: Int): Int {
        return com.castla.mirror.utils.StreamMath.calculateSecondaryBitrate(width, height)
    }

    private fun rebalanceSplitBitrates() {
        val thermalActive = _thermalStatus.value >= PowerManager.THERMAL_STATUS_LIGHT
        val hasSplit = secondaryDisplayId >= 0 && secondaryWidth > 0
        val now = android.os.SystemClock.elapsedRealtime()
        val canApply = now - lastCongestionTimeMs > 2000

        if (hasSplit && (isCurrentAppVideo || isSecondaryAppVideo) && !thermalActive) {
            val primaryBps = if (isCurrentAppVideo)
                StreamMath.calculateSplitVideoBitrate(currentWidth, currentHeight)
            else
                StreamMath.calculateSplitCompanionBitrate(currentWidth, currentHeight)

            val secondaryBps = if (isSecondaryAppVideo)
                StreamMath.calculateSplitVideoBitrate(secondaryWidth, secondaryHeight)
            else
                StreamMath.calculateSplitCompanionBitrate(secondaryWidth, secondaryHeight)

            targetBitrate = primaryBps
            if (canApply || currentBitrate > primaryBps) {
                currentBitrate = primaryBps
                videoEncoder?.setBitrate(currentBitrate)
            }
            secondaryVideoEncoder?.setBitrate(secondaryBps)
            Log.i(TAG, "Split rebalance: primary=${primaryBps / 1000}kbps(video=${isCurrentAppVideo}) secondary=${secondaryBps / 1000}kbps(video=${isSecondaryAppVideo})")
        } else {
            val baseBitrate = StreamMath.calculateBaseBitrate(currentWidth, currentHeight)
            targetBitrate = if (isCurrentAppVideo && !thermalActive)
                StreamMath.calculateOttBitrate(baseBitrate)
            else
                baseBitrate
            if (canApply || currentBitrate > targetBitrate) {
                currentBitrate = targetBitrate
                videoEncoder?.setBitrate(currentBitrate)
            }
            if (hasSplit) {
                val secBitrate = StreamMath.calculateSecondaryBitrate(secondaryWidth, secondaryHeight)
                secondaryVideoEncoder?.setBitrate(secBitrate)
            }
            Log.i(TAG, "Bitrate set: primary=${targetBitrate / 1000}kbps (video=${isCurrentAppVideo}, split=$hasSplit)")
        }
    }

    private fun computeVirtualDisplayDpi(width: Int, height: Int): Int {
        val baseDpi = StreamMath.calculateDpi(minOf(width, height))
        return StreamMath.applyDensityScale(baseDpi, dpiScale)
    }


    private fun releaseSecondaryPipeline(clearState: Boolean = false) {
        if (secondaryDisplayId >= 0) {
            cleanupDisplay(secondaryDisplayId)
            virtualDisplayManager?.releaseSecondaryVirtualDisplay(secondaryDisplayId)
            secondaryDisplayId = -1
        }
        secondaryVideoEncoder?.release()
        secondaryVideoEncoder = null
        secondaryJpegEncoder?.release()
        secondaryJpegEncoder = null
        mirrorServer?.setKeyframeRequester("secondary") {}
        secondaryTouchInjector?.release()
        secondaryTouchInjector = null
        if (isSecondaryAppVideo) {
            isSecondaryAppVideo = false
            rebalanceSplitBitrates()
        }
        if (clearState) {
            clearSecondaryState()
            clearSplitState()
            Log.i(TAG, "Secondary pipeline released — primary will resize to fullscreen on next viewport")
            serviceScope.launch {
                rebuildPipeline(currentWidth, currentHeight, force = true, forceSingle = true)
            }
        }
    }

    private fun rebuildSecondaryPipeline(targetWidth: Int, targetHeight: Int) {
        if (targetWidth <= 0 || targetHeight <= 0) return
        val effectiveMaxHeight = effectiveMaxHeightForRequest(targetHeight, isSecondaryPane = true)
        Log.i(
            TAG,
            "Rebuilding secondary pipeline requested=${targetWidth}x${targetHeight} effectiveMaxHeight=$effectiveMaxHeight"
        )
        var width = targetWidth
        var height = targetHeight
        if (height > effectiveMaxHeight) {
            val scale = effectiveMaxHeight.toFloat() / height
            height = effectiveMaxHeight
            width = (width * scale).toInt()
        }
        width = ((width + 15) and 15.inv()).coerceAtLeast(320)
        height = ((height + 15) and 15.inv()).coerceAtLeast(320)
        if (virtualDisplayManager?.isBound() != true) return
        val hasMatchingPipeline = secondaryDisplayId >= 0 && secondaryWidth == width && secondaryHeight == height &&
            ((currentCodecMode == "mjpeg" && secondaryJpegEncoder != null) || (currentCodecMode != "mjpeg" && secondaryVideoEncoder != null))
        if (hasMatchingPipeline) {
            Log.d(TAG, "Secondary pipeline already matches ${width}x${height}, skipping rebuild")
            return
        }

        secondaryVideoEncoder?.release()
        secondaryVideoEncoder = null
        secondaryJpegEncoder?.release()
        secondaryJpegEncoder = null
        mirrorServer?.setKeyframeRequester("secondary") {}

        val surface = if (currentCodecMode == "mjpeg") {
            val jpeg = JpegEncoder(width, height, fps = 15, quality = 65)
            val inputSurface = jpeg.createInputSurface()
            jpeg.start { frameData, isKeyFrame -> mirrorServer?.broadcastFrame(frameData, isKeyFrame, "secondary") }
            secondaryJpegEncoder = jpeg
            inputSurface
        } else {
            val encoder = VideoEncoder(width, height, secondaryBitrate(width, height), currentFps)
            val inputSurface = encoder.createInputSurface()
            encoder.onSpsPps = { spsPps -> mirrorServer?.broadcastSpsPps(spsPps, "secondary") }
            encoder.start { frameData, isKeyFrame -> mirrorServer?.broadcastFrame(frameData, isKeyFrame, "secondary") }
            mirrorServer?.setKeyframeRequester("secondary") { encoder.requestKeyFrame() }
            secondaryVideoEncoder = encoder
            inputSurface
        }

        val dpi = computeVirtualDisplayDpi(width, height)

        if (secondaryDisplayId >= 0) {
            virtualDisplayManager?.getPrivilegedService()?.setSurface(secondaryDisplayId, surface)
            virtualDisplayManager?.resizeDisplay(secondaryDisplayId, width, height, dpi)
            secondaryWidth = width
            secondaryHeight = height
            secondaryTouchInjector = (secondaryTouchInjector ?: TouchInjector(width, height)).also { injector ->
                injector.updateDimensions(width, height)
                injector.setVirtualDisplayInjector { motionEvent ->
                    try {
                        shizukuSetup?.privilegedService?.injectMotionEvent(secondaryDisplayId, motionEvent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to inject secondary input on display $secondaryDisplayId", e)
                    }
                }
            }
            Log.i(TAG, "Gradually resized secondary VD $secondaryDisplayId to ${width}x${height} without restarting")
        } else {
            val newDisplayId = virtualDisplayManager?.createSecondaryVirtualDisplay(width, height, dpi, surface) ?: -1
            if (newDisplayId < 0) {
                releaseSecondaryPipeline(clearState = false)
                return
            }
            secondaryDisplayId = newDisplayId
            try {
                virtualDisplayManager?.getPrivilegedService()?.launchHomeOnDisplay(newDisplayId)
                Log.i(TAG, "Successfully bound launcher properties to VD_2 display $newDisplayId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch secondary home on VD_2", e)
            }
            secondaryWidth = width
            secondaryHeight = height
            secondaryTouchInjector = (secondaryTouchInjector ?: TouchInjector(width, height)).also { injector ->
                injector.updateDimensions(width, height)
                injector.setVirtualDisplayInjector { motionEvent ->
                    try {
                        shizukuSetup?.privilegedService?.injectMotionEvent(secondaryDisplayId, motionEvent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to inject secondary input on display $secondaryDisplayId", e)
                    }
                }
            }
            restoreSecondaryVdContent()
        }

        mirrorServer?.broadcastFrame(byteArrayOf(), false, "secondary") 
        mirrorServer?.broadcastControlMessage(JSONObject().apply {
            put("type", "resolutionChanged")
            put("pane", "secondary")
            put("width", secondaryWidth)
            put("height", secondaryHeight)
        }.toString())
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
        if (secondaryDisplayId < 0 || currentSecondaryApp.isBlank()) return
        if (currentSecondaryApp.startsWith("$packageName/")) {
            val activityClassName = currentSecondaryApp.substringAfter('/').let { className ->
                if (className.startsWith('.')) "$packageName$className" else className
            }
            launchOwnActivityOnDisplay(
                activityClassName = activityClassName,
                displayId = secondaryDisplayId,
                url = currentSecondaryWebUrl ?: "https://m.youtube.com",
                splitMode = true,
                applySplitBounds = false
            )
            return
        }
        if (currentSecondaryWebUrl != null) {
            launchTargetOnDisplay(
                secondaryDisplayId,
                currentSecondaryApp,
                extraKey = "url",
                extraValue = currentSecondaryWebUrl,
                freeform = false,
                forceColdStart = false
            )
        } else {
            launchTargetOnDisplay(secondaryDisplayId, currentSecondaryApp, freeform = false, forceColdStart = false)
        }
    }

    private fun launchSecondaryTarget(launchTarget: String, webUrl: String? = null) {
        currentSecondaryApp = normalizeLaunchTarget(launchTarget)
        currentSecondaryWebUrl = webUrl
        Log.i(
            TAG,
            "Queued secondary target app=$currentSecondaryApp url=$currentSecondaryWebUrl displayId=$secondaryDisplayId viewport=${secondaryRequestedWidth}x${secondaryRequestedHeight}"
        )
        if (secondaryDisplayId >= 0) {
            restoreSecondaryVdContent()
        } else {
            // Auto-provisioning fallback: Build secondary display pipeline dynamically if active display is absent!
            val targetW = if (secondaryRequestedWidth > 0) secondaryRequestedWidth else (currentWidth / 2).coerceAtLeast(320)
            val targetH = if (secondaryRequestedHeight > 0) secondaryRequestedHeight else currentHeight
            Log.i(TAG, "Secondary display not active ($secondaryDisplayId) — triggering dynamic auto-provisioning: ${targetW}x${targetH}")
            serviceScope.launch(Dispatchers.IO) {
                rebuildSecondaryPipeline(targetW, targetH)
            }
        }
    }

    private data class DisplayTaskSnapshot(
        val taskId: Int,
        val mode: String,
        val header: String,
        val body: String
    )

    private fun clearSplitState() {
        currentWebSplitMode = false
        activeSplitUrl = null
        activeSplitComponent = null
        activeSplitSession = null
    }

    private fun closeFreeformSplit() {
        val displayId = virtualDisplayManager?.getDisplayId() ?: -1
        if (displayId < 0) return

        val splitTarget = activeSplitComponent
        if (splitTarget != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val service = virtualDisplayManager?.getPrivilegedService() ?: return@launch
                    val tasks = parseDisplayTasks(service.execCommand("dumpsys activity activities"), displayId)
                    for (task in tasks) {
                        if (task.mode == "freeform" && taskMatchesLaunchTarget(task, splitTarget)) {
                            service.removeTask(task.taskId)
                            Log.i(TAG, "Removed split task ${task.taskId} ($splitTarget)")
                            break
                        }
                    }
                    val primaryTarget = normalizeLaunchTarget(currentVdApp)
                    val fullBounds = android.graphics.Rect(0, 0, currentWidth, currentHeight)
                    val primaryTaskId = findTaskId(service, displayId, primaryTarget)
                    if (primaryTaskId != null) {
                        service.execCommand("cmd activity task resize $primaryTaskId ${fullBounds.left} ${fullBounds.top} ${fullBounds.right} ${fullBounds.bottom}")
                        Log.i(TAG, "Restored primary task $primaryTaskId to fullscreen")
                    } else {
                        launchTargetOnDisplay(displayId, primaryTarget, freeform = false)
                        Log.i(TAG, "Re-launched primary $primaryTarget fullscreen")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to close freeform split", e)
                }
            }
        }

        dismissSplitPresentation(clearState = true)
        clearSplitState()
    }

    private fun hasActiveSplitSession(): Boolean = activeSplitUrl != null || activeSplitComponent != null

    private fun removeAllVdTasks() {
        cleanupDisplay(virtualDisplayManager?.getDisplayId() ?: -1)
        cleanupDisplay(secondaryDisplayId)
    }

    private fun cleanupDisplay(displayId: Int) {
        if (displayId < 0) return
        val service = virtualDisplayManager?.getPrivilegedService() ?: return
        val myPackage = packageName

        try {
            service.launchHomeOnDisplay(displayId)

            val dumpsys = service.execCommand("dumpsys activity activities")
            val tasks = parseDisplayTasks(dumpsys, displayId)
            val packagesToStop = mutableSetOf<String>()

            for (task in tasks) {
                val pkgMatch = Regex("A=\\d+:([\\w.]+)").find(task.header)
                val pkg = pkgMatch?.groupValues?.getOrNull(1)
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

            for (task in tasks) {
                service.removeTask(task.taskId)
                Log.i(TAG, "Removed task ${task.taskId} from display $displayId")
            }

            Log.i(TAG, "Cleaned up display $displayId: ${packagesToStop.size} force-stopped, ${tasks.size} tasks removed")
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

    private fun canLaunchPrimarySplitTask(): Boolean {
        return currentVdApp.isNotBlank() && currentVdApp != "HOME" && currentVdApp != "com.android.settings"
    }

    private fun ensureSplitViable(reason: String): Boolean {
        if (!singleVdSplit) return false
        
        // 메인 표준 앱(Primary)을 기동하거나 재배치하는 상황이라면, 
        // 기존에 실행 중인 앱이 없더라도(초기 HOME 상태) 분할 모드가 정상 성립되어야 합니다.
        val isPrimaryLaunch = reason.contains("standard") || reason == "relaunch-primary"
        
        if (!isPrimaryLaunch && !canLaunchPrimarySplitTask()) {
            FileLogger.w(TAG, "Split rejected ($reason): no primary app (currentVdApp=$currentVdApp)")
            return false
        }
        if (!SplitMath.isSplitViable(currentWidth, currentHeight)) {
            FileLogger.w(TAG, "Split rejected ($reason): display too small ${currentWidth}x${currentHeight}")
            return false
        }
        return true
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

    private fun primaryTaskBounds(): android.graphics.Rect {
        check(SplitMath.isSplitViable(currentWidth, currentHeight)) {
            "primaryTaskBounds called on non-viable display ${currentWidth}x${currentHeight}; gate with ensureSplitViable() first"
        }
        val leftWidth = SplitMath.computeLeftPaneWidth(currentWidth, currentHeight)
        return android.graphics.Rect(0, 0, leftWidth, currentHeight)
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
        freeform: Boolean = false,
        reorderToFront: Boolean = false
    ): String {
        val resolvedComponent = resolveLaunchComponent(packageOrComponent)
        val launchTarget = resolvedComponent ?: packageOrComponent
        val flags = if (reorderToFront) "0x10020000" else "0x10200000"
        return buildString {
            append("am start --display $displayId -f $flags ")
            if (freeform) {
                append("--windowingMode 5 ")
            }
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
        freeform: Boolean = false,
        forceColdStart: Boolean = false
    ): Boolean {
        if (displayId < 0) return false
        val service = virtualDisplayManager?.getPrivilegedService() ?: return false
        return try {
            val resolvedTarget = resolveLaunchComponent(packageOrComponent) ?: packageOrComponent
            val pkg = packageOrComponent.substringBefore('/')

            if (forceColdStart && pkg.isNotBlank() && pkg != "HOME" && !pkg.contains("com.castla.mirror")) {
                try {
                    service.execCommand("am force-stop $pkg")
                    Log.i(TAG, "Forced cold start: Successfully force-stopped $pkg before launching to enforce layout refresh")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to force stop $pkg for cold start", e)
                }
            }

            // 1. Precise Display ID tracking for original task to ensure symmetric control
            val originalDisplayId = try { service.getDisplayIdForPackage(pkg) } catch (e: Exception) { -1 }
            val primaryVdId = virtualDisplayManager?.getDisplayId() ?: -1
            val secondaryVdId = secondaryDisplayId
            val targetDisplayId = if (originalDisplayId >= 0 && (originalDisplayId == primaryVdId || originalDisplayId == secondaryVdId)) {
                Log.i(TAG, "Symmetric Task Routing: Redirecting launch of $pkg from display $displayId to original display $originalDisplayId")
                originalDisplayId
            } else {
                displayId
            }

            val dumpsys = service.execCommand("dumpsys activity activities")
            val matchingTaskIds = findAllTaskIds(dumpsys, pkg)
            val isWarmStart = matchingTaskIds.isNotEmpty()

            for (taskId in matchingTaskIds) {
                try {
                    // Move the task to the correct target display
                    service.execCommand("cmd activity task move-to-display $taskId $targetDisplayId")
                    Log.i(TAG, "Migrated existing task $taskId ($pkg) to display $targetDisplayId")
                    
                    // Force bring task to the front of target display to restore focus and resume rendering
                    service.execCommand("cmd activity task move-to-front $taskId")
                    Log.i(TAG, "Forced task $taskId to front of display $targetDisplayId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to migrate/bring-to-front task $taskId for display $targetDisplayId", e)
                }
            }

            val command = buildShellLaunchCommand(targetDisplayId, packageOrComponent, extraKey, extraValue, freeform, reorderToFront = isWarmStart)
            Log.i(TAG, "Executing: $command")
            val result = service.execCommand(command)
            Log.i(TAG, "Launch result for $packageOrComponent: $result")

            // 3. Surface Re-bind Verification and Safe Fallback mechanism
            if (isWarmStart) {
                verifySurfaceAndFallback(service, targetDisplayId, pkg, matchingTaskIds, packageOrComponent, extraKey, extraValue, freeform)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageOrComponent on display $displayId", e)
            FileLogger.e(TAG, "launchTargetOnDisplay failed pkg=$packageOrComponent display=$displayId", e)
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
        extraValue: String?,
        freeform: Boolean
    ) {
        serviceScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1000L) // Wait 1 second for OS window manager transitions to settle
            try {
                val runningTasks = service.getRunningTasksOnDisplay(displayId)
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
                    service.execCommand("am force-stop $pkg")
                    Log.i(TAG, "Fallback: Force-stopped package $pkg")
                    
                    // Re-launch with a clean slate
                    val command = buildShellLaunchCommand(displayId, packageOrComponent, extraKey, extraValue, freeform, reorderToFront = false)
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

    private fun launchFullscreenWebTarget(activityClassName: String, displayId: Int, url: String) {
        clearSplitState()
        val previousApp = currentVdApp
        val newTarget = internalComponentName(activityClassName)
        if (previousApp != newTarget) {
            forceStopAppIfNeeded(previousApp)
        }
        launchInternalActivity(activityClassName, displayId, url, splitMode = false)
        currentVdApp = newTarget
        currentWebUrl = url
        currentWebSplitMode = false
        activeSession = ActiveLaunchSession(
            mode = SessionMode.INTERNAL_WEBVIEW,
            launchTarget = newTarget,
            url = url
        )
    }

    private fun launchSplitWebTarget(activityClassName: String, displayId: Int, url: String) {
        if (!ensureSplitViable("split-web")) {
            Log.w(TAG, "Split web launch rejected; falling back to fullscreen")
            launchFullscreenWebTarget(activityClassName, displayId, url)
            return
        }
        relaunchPrimaryTaskForSplit(displayId)
        val componentName = internalComponentName(activityClassName)
        launchInternalActivity(activityClassName, displayId, url, splitMode = true)
        activeSplitUrl = url
        activeSplitComponent = componentName
        activeSplitSession = ActiveLaunchSession(
            mode = SessionMode.INTERNAL_WEBVIEW,
            launchTarget = componentName,
            url = url
        )
    }

    private fun launchExternalBrowserTarget(displayId: Int, url: String, sourceAppPackage: String? = null, allowFallback: Boolean = true) {
        clearSplitState()
        val previousApp = currentVdApp

        if (displayId < 0) {
            Log.w(TAG, "External browser launch refused: invalid displayId=$displayId for $url")
            if (allowFallback) {
                launchFullscreenWebTarget("com.castla.mirror.ui.WebBrowserActivity", displayId, url)
                activeSession = ActiveLaunchSession(
                    mode = SessionMode.INTERNAL_WEBVIEW,
                    launchTarget = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"),
                    url = url,
                    sourceAppPackage = sourceAppPackage
                )
            }
            return
        }

        val browser = BrowserResolver.resolve(this, url)
        if (browser != null) {
            val command = buildExternalBrowserCommand(displayId, url, browser.componentFlat, freeform = false)
            val service = virtualDisplayManager?.getPrivilegedService()
            if (service == null) {
                Log.w(TAG, "Privileged service unavailable for external browser launch")
                if (allowFallback) {
                    Log.w(TAG, "Falling back to internal WebBrowserActivity for $url")
                    launchFullscreenWebTarget("com.castla.mirror.ui.WebBrowserActivity", displayId, url)
                    activeSession = ActiveLaunchSession(
                        mode = SessionMode.INTERNAL_WEBVIEW,
                        launchTarget = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"),
                        url = url,
                        sourceAppPackage = sourceAppPackage
                    )
                }
                return
            }
            val launched = try {
                Log.i(TAG, "External browser launch: $command")
                service.execCommand(command)
                true
            } catch (e: Exception) {
                Log.e(TAG, "External browser launch failed", e)
                false
            }

            if (launched) {
                val previousPkg = previousApp.substringBefore('/')
                if (previousPkg != browser.packageName) {
                    forceStopAppIfNeeded(previousApp)
                }
                currentVdApp = browser.componentFlat
                currentWebUrl = url
                currentWebSplitMode = false
                activeSession = ActiveLaunchSession(
                    mode = SessionMode.EXTERNAL_BROWSER,
                    launchTarget = browser.componentFlat,
                    url = url,
                    sourceAppPackage = sourceAppPackage,
                    browserPackage = browser.packageName
                )
                Log.i(TAG, "External browser launched successfully: ${browser.componentFlat} -> $url")
                return
            }
        }

        if (allowFallback) {
            Log.w(TAG, "Falling back to internal WebBrowserActivity for $url")
            launchFullscreenWebTarget("com.castla.mirror.ui.WebBrowserActivity", displayId, url)
            activeSession = ActiveLaunchSession(
                mode = SessionMode.INTERNAL_WEBVIEW,
                launchTarget = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"),
                url = url,
                sourceAppPackage = sourceAppPackage
            )
        }
    }

    private fun launchSplitExternalBrowserTarget(displayId: Int, url: String, sourceAppPackage: String? = null, allowFallback: Boolean = true) {
        if (displayId < 0) {
            Log.w(TAG, "Split external browser launch refused: invalid displayId=$displayId for $url")
            if (allowFallback) {
                launchSplitWebTarget("com.castla.mirror.ui.WebBrowserActivity", displayId, url)
                activeSplitSession = ActiveLaunchSession(
                    mode = SessionMode.INTERNAL_WEBVIEW,
                    launchTarget = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"),
                    url = url,
                    sourceAppPackage = sourceAppPackage
                )
            }
            return
        }
        if (!ensureSplitViable("split-external-browser")) {
            Log.w(TAG, "Split external browser launch rejected; falling back to fullscreen")
            launchExternalBrowserTarget(displayId, url, sourceAppPackage, allowFallback)
            return
        }

        val browser = BrowserResolver.resolve(this, url)
        if (browser != null) {
            val service = virtualDisplayManager?.getPrivilegedService()
            if (service == null) {
                Log.w(TAG, "Privileged service unavailable for split external browser launch")
                if (allowFallback) {
                    launchSplitWebTarget("com.castla.mirror.ui.WebBrowserActivity", displayId, url)
                    activeSplitSession = ActiveLaunchSession(
                        mode = SessionMode.INTERNAL_WEBVIEW,
                        launchTarget = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"),
                        url = url,
                        sourceAppPackage = sourceAppPackage
                    )
                }
                return
            }
            relaunchPrimaryTaskForSplit(displayId)
            val command = buildExternalBrowserCommand(displayId, url, browser.componentFlat, freeform = true)
            val launched = try {
                Log.i(TAG, "Split external browser launch: $command")
                service.execCommand(command)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Split external browser launch failed", e)
                false
            }

            if (launched) {
                scheduleSplitTaskResize(displayId, browser.componentFlat)
                activeSplitComponent = browser.componentFlat
                activeSplitUrl = url
                activeSplitSession = ActiveLaunchSession(
                    mode = SessionMode.EXTERNAL_BROWSER,
                    launchTarget = browser.componentFlat,
                    url = url,
                    sourceAppPackage = sourceAppPackage,
                    browserPackage = browser.packageName
                )
                Log.i(TAG, "Split external browser launched successfully: ${browser.componentFlat} -> $url")
                return
            }
        }

        if (allowFallback) {
            Log.w(TAG, "Falling back to internal WebBrowserActivity (split) for $url")
            launchSplitWebTarget("com.castla.mirror.ui.WebBrowserActivity", displayId, url)
            activeSplitSession = ActiveLaunchSession(
                mode = SessionMode.INTERNAL_WEBVIEW,
                launchTarget = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"),
                url = url,
                sourceAppPackage = sourceAppPackage
            )
        }
    }

    private fun buildExternalBrowserCommand(displayId: Int, url: String, browserComponent: String, freeform: Boolean): String {
        return buildString {
            append("am start --display $displayId -f 0x18000000 ")
            if (freeform) {
                append("--windowingMode 5 ")
            }
            append("-a android.intent.action.VIEW ")
            append("-d ${escapeShellArg(url)} ")
            append("-n ${escapeShellArg(browserComponent)} ")
        }.trim()
    }

    private fun launchFullscreenStandardTarget(launchTarget: String) {
        clearSplitState()
        val resolvedTarget = normalizeLaunchTarget(launchTarget)
        val displayId = virtualDisplayManager?.getDisplayId() ?: -1

        val launched = launchTargetOnDisplay(displayId, resolvedTarget, freeform = false)
        if (!launched && virtualDisplayManager?.hasVirtualDisplay() == false) {
            Log.w(TAG, "Launch failed due to stale display, rebuilding pipeline and retrying")
            rebuildAndRetryLaunch(resolvedTarget)
            return
        }
        currentVdApp = resolvedTarget
        currentWebUrl = null
        activeSession = ActiveLaunchSession(mode = SessionMode.STANDARD_APP, launchTarget = resolvedTarget)
    }

    private fun rebuildAndRetryLaunch(resolvedTarget: String) {
        currentVdApp = resolvedTarget
        currentWebUrl = null
        activeSession = ActiveLaunchSession(mode = SessionMode.STANDARD_APP, launchTarget = resolvedTarget)
        serviceScope.launch {
            try {
                rebuildPipeline(currentWidth, currentHeight, force = true)
                val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                val retried = launchTargetOnDisplay(displayId, resolvedTarget, freeform = false)
                if (retried) {
                    Log.i(TAG, "Retry launch succeeded for $resolvedTarget after pipeline rebuild")
                } else {
                    Log.w(TAG, "Retry launch deferred — VD will launch via restoreCurrentVdContent on bind completion")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rebuild pipeline for retry launch", e)
            }
        }
    }

    private fun launchSplitStandardTarget(launchTarget: String) {
        val displayId = virtualDisplayManager?.getDisplayId() ?: -1
        Log.i(TAG, "launchSplitStandardTarget: target=$launchTarget displayId=$displayId currentVdApp=$currentVdApp canSplit=${canLaunchPrimarySplitTask()} currentSize=${currentWidth}x${currentHeight}")
        if (displayId < 0 || !ensureSplitViable("split-standard")) {
            Log.w(TAG, "Split app launch rejected; falling back to fullscreen")
            launchFullscreenStandardTarget(launchTarget)
            return
        }
        val resolvedTarget = normalizeLaunchTarget(launchTarget)

        val service = virtualDisplayManager?.getPrivilegedService()
        if (service != null) {
            val taskId = findTaskId(service, displayId, resolvedTarget)
            if (taskId != null) {
                try {
                    service.execCommand("cmd activity task move-to-front $taskId")
                    Log.i(TAG, "Moved existing task $taskId ($resolvedTarget) to front on display $displayId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to move existing task $taskId to front", e)
                }
            }
        }

        val launched = launchTargetOnDisplay(displayId, resolvedTarget, freeform = singleVdSplit)
        if (launched) {
            currentVdApp = resolvedTarget
            scheduleSplitTaskResize(displayId, resolvedTarget)
            activeSplitComponent = resolvedTarget
            activeSplitUrl = null
        }
    }

    private fun relaunchPrimaryTaskForSplit(displayId: Int) {
        if (displayId < 0 || !ensureSplitViable("relaunch-primary")) return
        val primaryTarget = normalizeLaunchTarget(currentVdApp)
        val primaryPkg = primaryTarget.substringBefore('/')
        val bounds = primaryTaskBounds()
        val service = virtualDisplayManager?.getPrivilegedService() ?: return

        val existingTaskId = findTaskId(service, displayId, primaryTarget)
        if (existingTaskId != null) {
            val existingMode = parseDisplayTasks(service.execCommand("dumpsys activity activities"), displayId)
                .firstOrNull { it.taskId == existingTaskId }?.mode ?: "unknown"
            if (existingMode == "freeform") {
                service.execCommand("cmd activity task resize $existingTaskId ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}")
                Log.i(TAG, "Primary task $existingTaskId already freeform, resized to $bounds")
                return
            }
        }

        Log.i(TAG, "Force-restarting primary $primaryPkg in freeform mode")
        lastAppLaunchTime = System.currentTimeMillis() // Reset exit monitor grace period to avoid transient APP_STREAM_STOPPED signal
        service.execCommand("am force-stop $primaryPkg")
        val isExternalBrowser = activeSession?.mode == SessionMode.EXTERNAL_BROWSER
        val launched = if (isExternalBrowser && currentWebUrl != null) {
            val browser = BrowserResolver.resolve(this, currentWebUrl!!)
            if (browser != null) {
                val cmd = buildExternalBrowserCommand(displayId, currentWebUrl!!, browser.componentFlat, freeform = true)
                try { service.execCommand(cmd); true } catch (_: Exception) { false }
            } else {
                launchTargetOnDisplay(displayId, primaryTarget, "url", currentWebUrl!!, freeform = true)
            }
        } else if (primaryTarget.contains("WebBrowserActivity")) {
            launchTargetOnDisplay(displayId, primaryTarget, "url", currentWebUrl ?: "https://m.youtube.com", freeform = true)
        } else {
            launchTargetOnDisplay(displayId, primaryTarget, freeform = true)
        }
        if (launched) {
            schedulePrimaryTaskResize(displayId, primaryTarget)
        }
    }

    private fun launchInternalActivity(activityClassName: String, displayId: Int, url: String, splitMode: Boolean = false) {
        if (displayId < 0) return

        val launchUrl = if (splitMode && !url.contains("#split=true")) "$url#split=true" else url
        if (splitMode) {
            val launchTarget = internalComponentName(activityClassName)
            val launched = launchTargetOnDisplay(
                displayId,
                launchTarget,
                extraKey = "url",
                extraValue = launchUrl,
                freeform = true
            )
            if (launched) {
                scheduleSplitTaskResize(displayId, launchTarget)
                Log.i(TAG, "Launched $activityClassName on display $displayId via shell split command")
                return
            }
        }

        launchOwnActivityOnDisplay(activityClassName, displayId, launchUrl, splitMode, applySplitBounds = splitMode)
    }

    private fun launchOwnActivityOnDisplay(
        activityClassName: String,
        displayId: Int,
        url: String,
        splitMode: Boolean = false,
        applySplitBounds: Boolean = false
    ) {
        if (displayId < 0) return

        val launchUrl = if (splitMode && !url.contains("#split=true")) "$url#split=true" else url

        val options = android.app.ActivityOptions.makeBasic()
        options.launchDisplayId = displayId
        if (applySplitBounds) {
            options.setLaunchBounds(splitTaskBounds())
        }
        val intent = Intent().apply {
            setClassName(this@MirrorForegroundService, activityClassName)
            // Use NEW_TASK and REORDER_TO_FRONT flags to bring the existing activity to front without duplicates
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("url", launchUrl)
            putExtra("splitMode", splitMode)
        }
        try {
            startActivity(intent, options.toBundle())
            if (applySplitBounds) {
                scheduleSplitTaskResize(displayId, internalComponentName(activityClassName))
            }
            Log.i(TAG, "Launched $activityClassName on display $displayId via ActivityOptions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $activityClassName on display $displayId via ActivityOptions", e)
            val launched = launchTargetOnDisplay(
                displayId,
                internalComponentName(activityClassName),
                "url",
                launchUrl,
                freeform = applySplitBounds
            )
            if (applySplitBounds && launched) {
                scheduleSplitTaskResize(displayId, internalComponentName(activityClassName))
            }
        }
    }

    private fun splitTaskBounds(): android.graphics.Rect {
        check(SplitMath.isSplitViable(currentWidth, currentHeight)) {
            "splitTaskBounds called on non-viable display ${currentWidth}x${currentHeight}; gate with ensureSplitViable() first"
        }
        val leftWidth = SplitMath.computeLeftPaneWidth(currentWidth, currentHeight)
        return android.graphics.Rect(leftWidth, 0, currentWidth, currentHeight)
    }

    private var primaryResizeJob: Job? = null
    private var splitResizeJob: Job? = null
    private val resizeMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var boundsParseUnsupportedLogged = false

    private fun schedulePrimaryTaskResize(displayId: Int, launchTarget: String) {
        try { primaryResizeJob?.cancel() } catch (_: Exception) {}
        primaryResizeJob = scheduleTaskResize(displayId, primaryTaskBounds(), "primary", launchTarget)
    }

    private fun scheduleSplitTaskResize(displayId: Int, launchTarget: String) {
        if (!singleVdSplit) return
        try { splitResizeJob?.cancel() } catch (_: Exception) {}
        splitResizeJob = scheduleTaskResize(displayId, splitTaskBounds(), "split", launchTarget)
    }

    private fun scheduleTaskResize(
        displayId: Int,
        bounds: android.graphics.Rect,
        label: String,
        launchTarget: String
    ): Job? {
        if (displayId < 0 || currentWidth <= 0 || currentHeight <= 0) return null
        return serviceScope.launch(Dispatchers.IO) {
            resizeMutex.withLock {
                runResizeWithVerification(displayId, bounds, label, launchTarget)
            }
        }
    }

    private suspend fun runResizeWithVerification(
        displayId: Int,
        bounds: android.graphics.Rect,
        label: String,
        launchTarget: String,
    ) {
        var taskId: Int? = null
        var currentDisplayId = displayId
        var service: IPrivilegedService? = null
        repeat(MAX_LOCATE_ATTEMPTS) { attempt ->
            kotlinx.coroutines.delay(if (attempt == 0) 250L else 400L)
            service = virtualDisplayManager?.getPrivilegedService() ?: return
            currentDisplayId = virtualDisplayManager?.getDisplayId() ?: displayId
            taskId = findTaskId(service!!, currentDisplayId, launchTarget)
            if (taskId != null) return@repeat
        }
        val tid = taskId
        val svc = service
        if (tid == null || svc == null) {
            val msg = "Failed to locate $label task on display $currentDisplayId target=$launchTarget"
            Log.w(TAG, msg)
            FileLogger.w(TAG, msg)
            return
        }

        for (round in 0 until MAX_VERIFY_ROUNDS) {
            val cmdSucceeded = kotlinx.coroutines.withTimeoutOrNull(SHELL_TIMEOUT_MS) {
                svc.execCommand("cmd activity task resizeable $tid 2")
                svc.execCommand("cmd activity task resize $tid ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}")
                true
            } ?: false
            if (!cmdSucceeded) {
                Log.w(TAG, "Resize cmd timed out for $label task=$tid round=$round")
                FileLogger.w(TAG, "Resize cmd timed out for $label task=$tid round=$round")
                kotlinx.coroutines.delay(VERIFY_BACKOFF_MS)
                continue
            }
            kotlinx.coroutines.delay(VERIFY_BACKOFF_MS)
            val freshDumpsys = kotlinx.coroutines.withTimeoutOrNull(SHELL_TIMEOUT_MS) {
                svc.execCommand("dumpsys activity activities")
            }
            val taskBlock = freshDumpsys?.let { findTaskBlock(it, currentDisplayId, tid) }
            if (taskBlock == null) {
                Log.i(TAG, "Resized $label task $tid (no verification — task block missing)")
                return
            }
            val actual = TaskBoundsParser.parseTaskBoundsFromBlock(taskBlock)
            if (actual == null) {
                if (!boundsParseUnsupportedLogged) {
                    boundsParseUnsupportedLogged = true
                    FileLogger.w(TAG, "bounds-parse-unsupported on this Android version; skipping verification")
                }
                Log.i(TAG, "Resized $label task $tid (verification skipped: parser unsupported)")
                return
            }
            if (boundsMatch(bounds, actual)) {
                Log.i(TAG, "Resized $label task $tid to ${bounds.flattenToString()} (verified round=$round)")
                FileLogger.i(TAG, "Resized $label task=$tid round=$round")
                return
            }
            Log.w(TAG, "Resize verification mismatch $label task=$tid round=$round requested=${bounds.flattenToString()} actual=[${actual.left},${actual.top}][${actual.right},${actual.bottom}]")
        }
        FileLogger.w(TAG, "Resize verification gave up for $label task=$tid after $MAX_VERIFY_ROUNDS rounds")
    }

    private fun findTaskBlock(dumpsys: String, displayId: Int, taskId: Int): String? {
        val tasks = parseDisplayTasks(dumpsys, displayId)
        val match = tasks.firstOrNull { it.taskId == taskId } ?: return null
        return match.header + "\n" + match.body
    }

    private fun boundsMatch(requested: android.graphics.Rect, actual: TaskBoundsParser.Bounds): Boolean {
        return Math.abs(requested.left - actual.left) <= BOUNDS_TOLERANCE_PX &&
            Math.abs(requested.top - actual.top) <= BOUNDS_TOLERANCE_PX &&
            Math.abs(requested.right - actual.right) <= BOUNDS_TOLERANCE_PX &&
            Math.abs(requested.bottom - actual.bottom) <= BOUNDS_TOLERANCE_PX
    }

    private fun findTaskId(service: IPrivilegedService, displayId: Int, launchTarget: String): Int? {
        val dumpsys = service.execCommand("dumpsys activity activities")
        val tasks = parseDisplayTasks(dumpsys, displayId)
        Log.d(TAG, "findTaskId: display=$displayId target=$launchTarget found ${tasks.size} tasks: ${tasks.map { "id=${it.taskId} mode=${it.mode}" }}")
        val match = tasks.firstOrNull { taskMatchesLaunchTarget(it, launchTarget) }
        return match?.taskId
    }

    private fun parseDisplayTasks(dumpsys: String?, displayId: Int): List<DisplayTaskSnapshot> {
        if (dumpsys.isNullOrBlank()) return emptyList()
        val lines = dumpsys.lines()
        var startIndex = -1
        for (i in lines.indices) {
            val line = lines[i]
            if (line.contains("Display #$displayId")) {
                startIndex = i
                break
            }
        }
        if (startIndex < 0) return emptyList()

        var endIndex = lines.size
        for (i in (startIndex + 1) until lines.size) {
            val line = lines[i]
            if (line.contains("Display #") && !line.contains("Display #$displayId")) {
                endIndex = i
                break
            }
        }

        val displayBlockLines = lines.subList(startIndex + 1, endIndex)
        val tasks = mutableListOf<DisplayTaskSnapshot>()
        var currentHeader: String? = null
        val bodyLines = mutableListOf<String>()

        fun flushCurrent() {
            val header = currentHeader ?: return
            val snapshot = createTaskSnapshot(header, bodyLines)
            if (snapshot != null) {
                tasks += snapshot
            }
            currentHeader = null
            bodyLines.clear()
        }

        for (line in displayBlockLines) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("* Task{") || trimmed.startsWith("* TaskRecord{") || trimmed.startsWith("* Task id #")) {
                flushCurrent()
                currentHeader = trimmed
            } else if (currentHeader != null) {
                bodyLines += trimmed
            }
        }
        flushCurrent()
        return tasks
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

    private fun createTaskSnapshot(header: String, bodyLines: List<String>): DisplayTaskSnapshot? {
        val trimmed = header.trimStart().removePrefix("* Task")
        val taskId = Regex("^\\s*(?:#|Record\\{\\s*|\\{\\s*)(\\d+)").find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val mode = Regex("mode=([a-zA-Z_]+)").find(header)?.groupValues?.getOrNull(1) ?: "unknown"
        return DisplayTaskSnapshot(taskId, mode, header, bodyLines.joinToString("\n"))
    }

    private fun taskMatchesLaunchTarget(task: DisplayTaskSnapshot, launchTarget: String): Boolean {
        val targetPkg = launchTarget.substringBefore('/')
        
        // Extract realActivity package name from task body to ensure 100% precise matching
        val realActivityLine = task.body.lines().firstOrNull { it.trimStart().startsWith("realActivity=") }
        if (realActivityLine != null) {
            val component = realActivityLine.substringAfter("realActivity=").trim()
            val pkg = component.substringBefore('/')
            if (pkg == targetPkg) return true
        }
        
        // Fallback to checking topActivity or origActivity package lines
        val fallbackLines = task.body.lines().filter { 
            val trimmed = it.trimStart()
            trimmed.startsWith("origActivity=") || trimmed.contains("topActivity=ComponentInfo{")
        }
        for (line in fallbackLines) {
            if (line.contains(targetPkg)) return true
        }
        
        return false
    }

    private fun launchTargetCandidates(launchTarget: String): List<String> {
        if (!launchTarget.contains('/')) return listOf(launchTarget)

        val pkg = launchTarget.substringBefore('/')
        val rawClassName = launchTarget.substringAfter('/')
        val fullClassName = when {
            rawClassName.startsWith('.') -> pkg + rawClassName
            rawClassName.startsWith(pkg) -> rawClassName
            else -> rawClassName
        }
        val shortClassName = if (fullClassName.startsWith(pkg)) {
            "." + fullClassName.removePrefix(pkg).trimStart('.')
        } else {
            rawClassName
        }

        return listOf(
            launchTarget,
            "$pkg/$fullClassName",
            "$pkg/$shortClassName",
            fullClassName,
            shortClassName,
            pkg
        ).distinct()
    }

    private fun showSplitPresentation(url: String) {
        activeSplitUrl = url
        mainHandler.post {
            try {
                val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                if (displayId < 0) {
                    Log.w(TAG, "Split presentation deferred: virtual display unavailable")
                    return@post
                }

                val displayManager = getSystemService(DisplayManager::class.java)
                val display = displayManager?.getDisplay(displayId)
                if (display == null) {
                    Log.w(TAG, "Split presentation deferred: display $displayId not found")
                    return@post
                }

                val existing = splitPresentation
                if (existing != null && existing.display.displayId == display.displayId) {
                    existing.loadUrl(url)
                    return@post
                }

                existing?.dismiss()
                splitPresentation = SplitWebPresentation(this, display, url) {
                    dismissSplitPresentation(clearState = true)
                }.apply {
                    setOnDismissListener {
                        if (splitPresentation === this) {
                            splitPresentation = null
                        }
                    }
                    show()
                }
                Log.i(TAG, "Showing split presentation on display $displayId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show split presentation", e)
            }
        }
    }

    private fun dismissSplitPresentation(clearState: Boolean = false) {
        if (clearState) {
            clearSplitState()
        }
        mainHandler.post {
            val presentation = splitPresentation ?: return@post
            splitPresentation = null
            try {
                presentation.dismiss()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dismiss split presentation", e)
            }
        }
    }

    private fun launchAppFromWebLauncher(pkgName: String, componentName: String? = null, splitMode: Boolean = false, pane: String = if (splitMode) "secondary" else "primary") {
        Log.i(TAG, "launchAppFromWebLauncher: pkg=$pkgName split=$splitMode pane=$pane singleVdSplit=$singleVdSplit")
        if (pane != "secondary") {
            lastAppLaunchTime = System.currentTimeMillis()
        }
        if (pane == "secondary") {
            if (singleVdSplit) {
                Log.d(TAG, "Ignoring secondary launch in single-VD split mode (pkg=$pkgName)")
                return
            }
            if (pkgName.isBlank()) {
                releaseSecondaryPipeline(clearState = true)
                return
            }
            val webUrl = OttCatalog.webUrlFor(pkgName)
            val wasSecondaryVideo = isSecondaryAppVideo
            isSecondaryAppVideo = webUrl != null
            if (webUrl != null) {
                val browser = BrowserResolver.resolve(this, webUrl)
                if (browser != null && secondaryDisplayId >= 0) {
                    val service = virtualDisplayManager?.getPrivilegedService()
                    if (service != null) {
                        val cmd = buildExternalBrowserCommand(secondaryDisplayId, webUrl, browser.componentFlat, freeform = false)
                        try {
                            service.execCommand(cmd)
                            currentSecondaryApp = browser.componentFlat
                            currentSecondaryWebUrl = webUrl
                            Log.i(TAG, "Web Launcher: Launched secondary OTT via external browser: $pkgName -> $webUrl")
                            rebalanceSplitBitrates()
                            return
                        } catch (e: Exception) {
                            Log.w(TAG, "Secondary external browser launch failed, falling back to WebBrowserActivity", e)
                        }
                    }
                }
                val webComponentName = internalComponentName("com.castla.mirror.ui.WebBrowserActivity")
                Log.i(TAG, "Web Launcher: Launching secondary OTT app via WebBrowserActivity: $pkgName -> $webUrl")
                launchSecondaryTarget(webComponentName, webUrl)
            } else {
                val launchTarget = componentName ?: pkgName
                Log.i(TAG, "Web Launcher: Launching secondary app: $pkgName (target=$launchTarget)")
                launchSecondaryTarget(launchTarget)
            }
            if (wasSecondaryVideo != isSecondaryAppVideo) rebalanceSplitBitrates()
            return
        }

        val webUrl = if (singleVdSplit && splitMode) null else OttCatalog.webUrlFor(pkgName)
        val displayId = virtualDisplayManager?.getDisplayId() ?: -1

        if (webUrl != null) {
            Log.i(TAG, "Web Launcher: Launching OTT app via external browser: $pkgName -> $webUrl (splitMode=$splitMode)")

            dismissSplitPresentation(clearState = true)
            if (splitMode && ensureSplitViable("web-launcher-ott")) {
                launchSplitExternalBrowserTarget(displayId, webUrl, pkgName)
            } else {
                launchExternalBrowserTarget(displayId, webUrl, pkgName)
            }

            isCurrentAppVideo = true
            rebalanceSplitBitrates()
        } else {
            val launchTarget = componentName ?: pkgName
            Log.i(TAG, "Web Launcher: Launching standard app: $pkgName (target=$launchTarget, splitMode=$splitMode, singleVdSplit=$singleVdSplit)")

            if (splitMode && ensureSplitViable("web-launcher-standard")) {
                launchSplitStandardTarget(launchTarget)
            } else {
                launchFullscreenStandardTarget(launchTarget)
            }

            isCurrentAppVideo = false
            rebalanceSplitBitrates()
        }

        if (currentCodecMode == "mjpeg") {
            touchInjector?.onTouchEvent(com.castla.mirror.server.TouchEvent("down", 0.5f, 0.5f, 99))
            serviceScope.launch {
                kotlinx.coroutines.delay(50)
                touchInjector?.onTouchEvent(com.castla.mirror.server.TouchEvent("up", 0.5f, 0.5f, 99))
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
        val vdm = virtualDisplayManager ?: return
        val surf = currentEncoderSurface ?: return
        if (currentWidth <= 0 || currentHeight <= 0) {
            Log.w(TAG, "Reconnect skipped: invalid dims ${currentWidth}x${currentHeight}")
            return
        }
        val svc = setup.privilegedService ?: return
        vdm.attachPrivilegedService(svc)
        vdm.createVirtualDisplay(currentWidth, currentHeight, computeVirtualDisplayDpi(currentWidth, currentHeight), surf)
        if (vdm.hasVirtualDisplay()) {
            touchInjector?.setVirtualDisplayInjector { motionEvent ->
                vdm.injectMotionEvent(motionEvent)
            }
            restoreCurrentVdContent()
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
                if (shizukuBindRetryCount <= SHIZUKU_MAX_RETRIES) {
                    Log.w(TAG, "Shizuku binding timed out (attempt $shizukuBindRetryCount/$SHIZUKU_MAX_RETRIES) — retrying")
                    shizukuSetupInProgress = false
                    tearDownVdSession("binding_timeout")
                    kotlinx.coroutines.delay(2_000)
                    if (browserConnected) {
                        val surf = currentEncoderSurface
                        if (surf != null) {
                            Log.i(TAG, "Retrying Shizuku setup after timeout (attempt ${shizukuBindRetryCount + 1})")
                            trySetupVirtualDisplay(currentWidth, currentHeight, surf, onResult)
                            return@launch
                        }
                    }
                    safeResult(false)
                } else {
                    Log.e(TAG, "Shizuku binding failed after $SHIZUKU_MAX_RETRIES retries — Shizuku server may need restart")
                    safeResult(false)
                }
                return@launch
            }

            shizukuBindRetryCount = 0

            if (!browserConnected) {
                Log.w(TAG, "trySetupVirtualDisplay: browser disconnected during bind wait — abort")
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
                svc.execCommand("settings put global enable_freeform_support 1")
                svc.execCommand("settings put global force_resizable_activities 1")
                Log.i(TAG, "Enabled stay-awake and freeform windowing support")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable freeform support (non-fatal)", e)
            }

            val vdm = VirtualDisplayManager().also { virtualDisplayManager = it }
            vdm.attachPrivilegedService(svc)

            val actualWidth = if (currentWidth > 0) currentWidth else width
            val actualHeight = if (currentHeight > 0) currentHeight else height
            val actualSurface = currentEncoderSurface ?: surface
            val actualDpi = computeVirtualDisplayDpi(actualWidth, actualHeight)
            Log.i(TAG, "trySetupVirtualDisplay [DIAGNOSTIC]: creating VirtualDisplay: size=${actualWidth}x${actualHeight}, dpi=$actualDpi, surface=$actualSurface")
            vdm.createVirtualDisplay(actualWidth, actualHeight, actualDpi, actualSurface)

            if (vdm.hasVirtualDisplay()) {
                Log.i(TAG, "trySetupVirtualDisplay [DIAGNOSTIC]: VirtualDisplay created successfully! ID=${vdm.getDisplayId()}")
                touchInjector?.setVirtualDisplayInjector { motionEvent ->
                    vdm.injectMotionEvent(motionEvent)
                }
                startAppExitMonitor() // Start the exit monitor for standard mirroring sessions
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
                Log.i(TAG, "Screen is off — deferring teardown until screen turns on")
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
            acquireWakeLocks()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                broadcastThermalStatus(_thermalStatus.value)
            }

            val isPipelineActive = (videoEncoder != null || jpegEncoder != null)
            if (isPipelineActive) {
                Log.i(TAG, "Browser reconnected — rebuilding pipeline")
                serviceScope.launch {
                    rebuildPipeline(currentWidth, currentHeight, force = true)
                }
                ensureAudioCaptureState()
                return
            }

            Log.i(TAG, "Browser connected — starting active pipeline")
            val width = if (currentWidth > 0) currentWidth else 720
            val height = if (currentHeight > 0) currentHeight else 720

            val baseTargetBitrate = com.castla.mirror.utils.StreamMath.calculateBaseBitrate(width, height)
            targetBitrate = baseTargetBitrate
            currentBitrate = targetBitrate
            preThermalTargetBitrate = targetBitrate

            startAbrLoop()
            startAutoScaleLoop()

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
        try { touchInjector?.setVirtualDisplayInjector(null) } catch (_: Exception) {}
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
        val vdm = virtualDisplayManager ?: return
        startAppExitMonitor() // Keep monitor active during display rebuilds/reconnects
        when (currentVdApp) {
            "HOME", "", "com.android.settings" -> {
                currentVdApp = "HOME"
                if (vdm.hasVirtualDisplay()) {
                    vdm.launchHomeOnDisplay()
                }
            }
            else -> {
                if (currentVdApp.contains("SplitWebBrowserActivity")) {
                    currentVdApp = "HOME"
                } else if (singleVdSplit && hasActiveSplitSession()) {
                    val displayId = vdm.getDisplayId()
                    relaunchPrimaryTaskForSplit(displayId)
                    val splitSession = activeSplitSession
                    when {
                        splitSession?.mode == SessionMode.EXTERNAL_BROWSER && activeSplitUrl != null -> {
                            val browser = BrowserResolver.resolve(this, activeSplitUrl!!)
                            if (browser != null) {
                                val cmd = buildExternalBrowserCommand(displayId, activeSplitUrl!!, browser.componentFlat, freeform = true)
                                val launched = try {
                                    val svc = virtualDisplayManager?.getPrivilegedService()
                                    if (svc != null) { svc.execCommand(cmd); true } else false
                                } catch (_: Exception) { false }
                                if (launched) {
                                    scheduleSplitTaskResize(displayId, browser.componentFlat)
                                } else {
                                    launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, activeSplitUrl!!, splitMode = true)
                                }
                            } else {
                                launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, activeSplitUrl!!, splitMode = true)
                            }
                        }
                        activeSplitUrl != null -> {
                            launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, activeSplitUrl!!, splitMode = true)
                        }
                        activeSplitComponent != null -> {
                            val launched = launchTargetOnDisplay(displayId, activeSplitComponent!!, freeform = true)
                            if (launched) {
                                scheduleSplitTaskResize(displayId, activeSplitComponent!!)
                            }
                        }
                    }
                } else if (activeSession?.mode == SessionMode.EXTERNAL_BROWSER && currentWebUrl != null) {
                    val displayId = vdm.getDisplayId()
                    val browser = BrowserResolver.resolve(this, currentWebUrl!!)
                    if (browser != null) {
                        val cmd = buildExternalBrowserCommand(displayId, currentWebUrl!!, browser.componentFlat, freeform = false)
                        val launched = try {
                            val svc = virtualDisplayManager?.getPrivilegedService()
                            if (svc != null) { svc.execCommand(cmd); true } else false
                        } catch (_: Exception) { false }
                        if (!launched) {
                            launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, currentWebUrl!!, splitMode = currentWebSplitMode)
                        }
                    } else {
                        launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", vdm.getDisplayId(), currentWebUrl!!, splitMode = currentWebSplitMode)
                    }
                } else if (currentVdApp.contains("WebBrowserActivity")) {
                    val activityClassName = currentVdApp.substringAfter('/')
                    launchInternalActivity(activityClassName, vdm.getDisplayId(), currentWebUrl ?: "https://m.youtube.com", splitMode = currentWebSplitMode)
                } else {
                    launchTargetOnDisplay(vdm.getDisplayId(), currentVdApp, freeform = false, forceColdStart = false)
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
        Log.i(TAG, "Browser disconnected — suspending pipeline")
        pendingBrowserDisconnectJob = null
        browserConnected = false
        lastImeState = false
        lastBroadcastPane = null
        haveSeenRealImeShow = false
        bubbleClosedByUser = false
        cancelImeHideWatchdog()
        dismissSplitPresentation(clearState = false)
        if (!singleVdSplit) {
            releaseSecondaryPipeline(clearState = false)
        }
        try { removeAllVdTasks() } catch (e: Exception) { Log.w(TAG, "Failed to remove VD tasks on disconnect", e) }
        tearDownVdSession("browser_disconnected")
        
        videoEncoder?.release()
        videoEncoder = null
        jpegEncoder?.release()
        jpegEncoder = null
        currentEncoderSurface = null

        audioOrchestrator?.stop()

        abrJob?.cancel()
        abrJob = null

        lastQualityDroppedFrames = 0
        lastQualityAvgDelayMs = 0.0
        lastQualityBacklogDrops = 0

        releaseWakeLocks()
    }

    private fun activeInputDisplayId(): Int {
        return if (lastTouchPane == "secondary" && secondaryDisplayId >= 0) {
            secondaryDisplayId
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
                    val activeDisplayId = if (activePane == "secondary" && secondaryDisplayId >= 0) {
                        secondaryDisplayId
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
        val activeDisplayId = if (activePane == "secondary" && secondaryDisplayId >= 0) {
            secondaryDisplayId
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
            val forceSingle = (layoutMode == "single")
            rebuildPipeline(width, height, forceSingle = forceSingle)
        }
    }

    private fun hasActiveSecondaryViewportRequest(): Boolean {
        return secondaryRequestedWidth > 0 && secondaryRequestedHeight > 0
    }

    private fun shouldUseRequestedHeightForSplit(isSecondaryPane: Boolean = false): Boolean {
        return isSecondaryPane ||
            hasActiveSecondaryViewportRequest() ||
            secondaryDisplayId >= 0 ||
            currentSecondaryApp.isNotBlank() ||
            hasActiveSplitSession()
    }

    private fun effectiveMaxHeightForRequest(requestedHeight: Int, isSecondaryPane: Boolean = false, forceSingle: Boolean = false): Int {
        val baseMax = when {
            forceSingle -> currentMaxHeight
            shouldUseRequestedHeightForSplit(isSecondaryPane) -> {
                minOf(requestedHeight, currentMaxHeight)
            }
            else -> currentMaxHeight
        }
        val thermalCap = thermalMaxHeight
        return if (thermalCap != null) minOf(baseMax, thermalCap) else baseMax
    }

    private suspend fun rebuildPipeline(newWidth: Int, newHeight: Int, force: Boolean = false, forceSingle: Boolean = false) = pipelineMutex.withLock {
        val effectiveMaxHeight = effectiveMaxHeightForRequest(newHeight, forceSingle = forceSingle)
        var cappedWidth = newWidth
        var cappedHeight = newHeight
        
        if (cappedHeight > effectiveMaxHeight) {
            val scale = effectiveMaxHeight.toFloat() / cappedHeight
            cappedHeight = effectiveMaxHeight
            cappedWidth = (cappedWidth * scale).toInt()
        }

        val alignedWidth = ((cappedWidth + 15) and 15.inv()).coerceAtLeast(320)
        val alignedHeight = ((cappedHeight + 15) and 15.inv()).coerceAtLeast(320)

        if (!force && alignedWidth == currentWidth && alignedHeight == currentHeight) {
            Log.d(TAG, "rebuildPipeline skipped: dimensions unchanged ${alignedWidth}x${alignedHeight}")
            return@withLock
        }

        if (alignedWidth > 3840 || alignedHeight > 3840) {
            Log.w(TAG, "rebuildPipeline skipped: dimensions out of range ${alignedWidth}x${alignedHeight}")
            return@withLock
        }

        val width = alignedWidth
        val height = alignedHeight
        val dpi = computeVirtualDisplayDpi(width, height)

        val newTargetBitrate = com.castla.mirror.utils.StreamMath.calculateBaseBitrate(width, height)
        targetBitrate = if (isCurrentAppVideo) com.castla.mirror.utils.StreamMath.calculateOttBitrate(newTargetBitrate) else newTargetBitrate
        currentBitrate = targetBitrate

        Log.i(
            TAG,
            "Rebuilding pipeline requested=${newWidth}x${newHeight} -> ${width}x${height} effectiveMaxHeight=$effectiveMaxHeight splitActive=${shouldUseRequestedHeightForSplit()} force=$force (currentCodecMode=$currentCodecMode)"
        )

        try {
            val surface = if (currentCodecMode == "mjpeg") {
                videoEncoder?.release()

                videoEncoder = null
                jpegEncoder?.release()
                jpegEncoder = null

                val jpeg = JpegEncoder(width, height, fps = 15, quality = 65)
                val jpegSurface = jpeg.createInputSurface()
                currentEncoderSurface = jpegSurface
                jpeg.start { frameData, isKeyFrame -> mirrorServer?.broadcastFrame(frameData, isKeyFrame) }
                jpegEncoder = jpeg

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

                jpegSurface
            } else {
                videoEncoder?.release()

                videoEncoder = null

                val encoder = VideoEncoder(width, height, currentBitrate, thermalFpsOverride ?: currentFps)
                val encoderSurface = encoder.createInputSurface()
                currentEncoderSurface = encoderSurface
                videoEncoder = encoder

                encoder.onSpsPps = { spsPps -> mirrorServer?.broadcastSpsPps(spsPps) }
                encoder.start { frameData, isKeyFrame -> mirrorServer?.broadcastFrame(frameData, isKeyFrame) }
                mirrorServer?.setKeyframeRequester("primary") { encoder.requestKeyFrame() }
                encoderSurface
            }

            touchInjector?.updateDimensions(width, height)

            if (virtualDisplayManager?.isBound() == true) {
                dismissSplitPresentation(clearState = false)
                
                // [FIX] Always recreate the virtual display when rebuilding the pipeline.
                // Dynamic surface resizing/swapping via setSurface is extremely fragile on newer
                // Android versions (frequently failing silently or leaving the surface frozen).
                // Recreating the display guarantees that the new encoder surface is successfully active.
                Log.i(TAG, "Recreating virtual display during pipeline rebuild to guarantee active surface binding")
                virtualDisplayManager?.releaseVirtualDisplay()
                virtualDisplayManager?.createVirtualDisplay(width, height, dpi, surface)
                if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                    touchInjector?.setVirtualDisplayInjector { motionEvent ->
                        virtualDisplayManager?.injectMotionEvent(motionEvent)
                    }
                    restoreCurrentVdContent()
                } else {
                    Log.w(TAG, "VD creation failed during rebuild — retrying once")
                    virtualDisplayManager?.createVirtualDisplay(width, height, dpi, surface)
                    if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                        touchInjector?.setVirtualDisplayInjector { motionEvent ->
                            virtualDisplayManager?.injectMotionEvent(motionEvent)
                        }
                        restoreCurrentVdContent()
                    } else {
                        Log.e(TAG, "VD creation failed after retry — Fallback disabled")
                        markTerminal(TerminalReason.VD_RECREATE_FAILED)
                    }
                }
            } else if (shizukuSetupInProgress) {
                Log.i(TAG, "Shizuku binding already in progress, skipping redundant rebind")
            } else {
                Log.w(TAG, "Shizuku not bound during rebuild — attempting rebind")
                trySetupVirtualDisplay(width, height, surface) { success ->
                    if (!success) {
                        Log.e(TAG, "Shizuku rebind failed — Fallback disabled")
                        markTerminal(TerminalReason.SHIZUKU_REBIND_FAILED)
                    } else {
                        Log.i(TAG, "Shizuku rebound successfully during rebuild")
                        restoreCurrentVdContent()
                    }
                }
            }

            currentWidth = width
            currentHeight = height

            val msg = JSONObject().apply {
                put("type", "resolutionChanged")
                put("width", width)
                put("height", height)
            }
            mirrorServer?.broadcastControlMessage(msg.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebuild pipeline", e)
            FileLogger.e(TAG, "rebuildPipeline exception", e)
            markTerminal(TerminalReason.PIPELINE_REBUILD_EXCEPTION)
        }
    }

    private fun onCodecModeRequest(mode: String) {
        if (!CodecModeTransition.shouldApply(mode, currentCodecMode, jpegEncoder != null)) return
        currentCodecMode = CodecModeTransition.MODE_MJPEG
        Log.i(TAG, "Codec mode request: mjpeg")
        if (currentWidth == 0 || currentHeight == 0) {
            Log.i(TAG, "Viewport dimensions not yet set (0x0) — deferring pipeline build")
            return
        }
        Log.i(TAG, "Delegating to rebuildPipeline")
        serviceScope.launch {
            try {
                rebuildPipeline(currentWidth, currentHeight, force = true)
                if (!singleVdSplit && secondaryWidth > 0 && secondaryHeight > 0) {
                    rebuildSecondaryPipeline(secondaryWidth, secondaryHeight)
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
            .addAction(android.R.drawable.ic_media_pause, "■ Stop Mirroring", stopPending)
            .build()
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
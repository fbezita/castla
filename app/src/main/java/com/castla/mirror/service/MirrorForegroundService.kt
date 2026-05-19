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

        data class AutoTier(val maxHeight: Int, val fps: Int, val bitrate: Int, val label: String)
        val AUTO_TIERS = listOf(
            AutoTier(720, 15, 1_200_000, "720p15"),
            AutoTier(720, 30, 2_500_000, "720p30"),
            AutoTier(720, 60, 4_000_000, "720p60"),
            AutoTier(1080, 15, 2_500_000, "1080p15"),
            AutoTier(1080, 30, 4_500_000, "1080p30"),
            AutoTier(1080, 60, 7_500_000, "1080p60")
        )
        private const val AUTO_SCALE_INTERVAL_MS = 10_000L
        private const val AUTO_SCALE_INITIAL_DELAY_MS = 5_000L
        private const val VD_KEEP_ALIVE_INTERVAL_MS = 3_000L
    }

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
    private var thermalTransformationOverride: Int? = null
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
    
    private var browserConnected = false
    @Volatile private var currentCodecMode: String = "h264"
    
    private val pipelineMutex = Mutex()
    private val secondaryPipelineMutex = Mutex()
    private val primaryVdOperationMutex = Mutex()

    enum class PipelineState { IDLE, REBUILDING }
    data class RebuildRequest(val width: Int, val height: Int, val force: Boolean, val forceSingle: Boolean)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dpiScale: Float = 0.7f
    @Volatile private var shizukuSetupInProgress = false
    private val shizukuSetupCallbacks = java.util.Collections.synchronizedList(mutableListOf<(Boolean) -> Unit>())
    private var shizukuBindRetryCount = 0
    private val SHIZUKU_MAX_RETRIES = 2
    private val BIND_WAIT_BUDGET_MS = 8_000L

    private var reconnectJob: Job? = null
    private var autoResolution: Boolean = false
    private var autoFps: Boolean = false
    private var pendingAudioEnabled = false
    private var deferredAudioStartJob: Job? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var vdKeepAliveJob: Job? = null
    private var appExitMonitorJob: Job? = null

    private var pendingBrowserDisconnectJob: Job? = null

    @Volatile private var lastAppLaunchTime: Long = 0L
    private val screenOffPolicy = ScreenOffPolicy()
    private val keyguardManager by lazy { getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager }

    val isRunning: Boolean get() = mirrorServer != null
    val isPanelOffSupported: Boolean get() = screenOffPolicy.isPanelOffSupported

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
        primaryPipeline = VirtualDisplayPipeline("primary")
        secondaryPipeline = VirtualDisplayPipeline("secondary")
        instance = this
        isServiceRunning = true
        isCleanupInProgress = false
        createNotificationChannel()
        observeAppLaunchRequests()

        powerLockManager = PowerLockManager(this@MirrorForegroundService)
        thermalThrottleManager = ThermalThrottleManager(
            context = this@MirrorForegroundService,
            serviceScope = serviceScope,
            mainExecutor = androidx.core.content.ContextCompat.getMainExecutor(this@MirrorForegroundService),
            primaryPipeline = primaryPipeline,
            getTargetBitrate = { targetBitrate },
            setTargetBitrate = { targetBitrate = it },
            getAudioOrchestrator = { audioOrchestrator },
            getBrowserConnected = { browserConnected },
            getMirrorServer = { mirrorServer },
            rebuildPipeline = { w, h, f ->
                serviceScope.launch {
                    primaryPipeline.rebuild(
                        w,
                        h,
                        f
                    )
                }
            },
            onThermalThrottled = { autoTierIndex = 0; autoStableCount = 0 }
        )
        adaptiveBitrateManager = AdaptiveBitrateManager(
            context = this@MirrorForegroundService,
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
            rebuildPipeline = { w, h, f, fs ->
                serviceScope.launch {
                    primaryPipeline.rebuild(
                        w,
                        h,
                        f,
                        fs
                    )
                }
            },
            rebuildSecondaryPipeline = { w, h ->
                serviceScope.launch {
                    secondaryPipeline.rebuild(
                        w,
                        h
                    )
                }
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalThrottleManager.register()
        }


        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        onPhoneScreenOff()
                        mainHandler.postDelayed({
                            if (keyguardManager.isKeyguardLocked) MirrorDiagnostics.log(
                                DiagnosticEvent.KEYGUARD_LOCKED
                            )
                        }, 500)
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

        if (!cleanupCompleted) {
            // 안드로이드 시스템이 onDestroy() 블록을 너무 빨리 탈출해서
            // 프로세스를 죽여버리는 것을 방지하기 위해, 안전하게 스레드를 분리하여 순차 집행합니다.
            val cleanupThread = Thread {
                performCleanup("service_ondestroy")
            }
            cleanupThread.start()
            try {
                // 클린업 작업이 최대 1초 내에는 끝나도록 대기 시간을 주어 안전하게 프로세스 종료를 방어합니다.
                cleanupThread.join(1000)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private var isCurrentAppVideo: Boolean
        get() = primaryPipeline.isVideoApp
        set(value) { primaryPipeline.isVideoApp = value }

    private var isSecondaryAppVideo: Boolean
        get() = secondaryPipeline.isVideoApp
        set(value) { secondaryPipeline.isVideoApp = value }
        
    private var lastBitrateChangeMs = 0L

    private fun observeAppLaunchRequests() {
        serviceScope.launch {
            com.castla.mirror.utils.AppLaunchBus.events.collect { request ->
                val component = if (request.className != null) "${request.packageName}/${request.className}" else request.packageName
                val pane = request.pane ?: "primary"
                val targetPipeline = if (pane == "secondary") secondaryPipeline else primaryPipeline

                when (request.launchMode) {
                    LaunchMode.EXTERNAL_BROWSER_URL -> request.url?.let { targetPipeline.launchBrowser(it, request.sourceAppPackage, request.allowEmbeddedFallback) }
                    LaunchMode.INTERNAL_WEBVIEW -> {
                        val url = request.url ?: request.intentExtra ?: return@collect
                        targetPipeline.launchWeb(component.substringAfter('/', "com.castla.mirror.ui.WebBrowserActivity"), url)
                    }
                    LaunchMode.STANDARD_APP -> {
                        if (request.intentExtra != null) {
                            targetPipeline.launchWeb(component.substringAfter('/', "com.castla.mirror.ui.WebBrowserActivity"), request.intentExtra)
                        } else {
                            targetPipeline.launchStandard(component)
                        }
                    }
                }

                val now = android.os.SystemClock.elapsedRealtime()
                val videoChanged = request.isVideoApp != targetPipeline.isVideoApp
                if (videoChanged) { targetPipeline.isVideoApp = request.isVideoApp }

                if (videoChanged && now - lastBitrateChangeMs > 500) {
                    lastBitrateChangeMs = now
                    rebalanceDualDisplayBitrates()
                }

                if (autoResolution || autoFps) {
                    val activeTiers = AUTO_TIERS.filter { it.maxHeight == currentMaxHeight }
                    val boostTier = AutoScalePolicy.ottMinTier(autoTierIndex, isCurrentAppVideo, thermalThrottleManager.thermalStatus.value, activeTiers.size)
                    if (boostTier != null && boostTier < activeTiers.size) {
                        autoTierIndex = boostTier
                        autoStableCount = 0
                        adaptiveBitrateManager.applyAutoTier(autoResolution, autoFps)
                        adaptiveBitrateManager.notifyAutoTierChange("ott_boost")
                    }
                }

                mirrorServer?.broadcastControlMessage(JSONObject().apply { put("type", "ottProfileHint"); put("active", isCurrentAppVideo) }.toString())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStopAsync("notification_action")
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        
        val rawMaxHeight = intent!!.getIntExtra(EXTRA_MAX_RESOLUTION, 0)
        autoResolution = rawMaxHeight == 0
        currentMaxHeight = if (autoResolution) 720 else rawMaxHeight

        val rawFps = intent.getIntExtra(EXTRA_FPS, 0)
        autoFps = rawFps == 0
        val settingsFps = if (autoFps) 30 else rawFps

        pendingAudioEnabled = intent.getBooleanExtra(EXTRA_AUDIO, false)
        mirroringMode = intent.getStringExtra(EXTRA_MIRRORING_MODE) ?: "FULL_SCREEN"
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""

        serviceScope.launch(Dispatchers.Default) {
            startPipeline(settingsFps, pendingAudioEnabled)
        }
        return START_NOT_STICKY
    }

    private fun requestStopAsync(reason: String) {
        if (stopRequested) return
        stopRequested = true

        // 포그라운드 알림 제거
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}

        // 위젯 업데이트 및 서비스 종료 트리거 (이 stopSelf()가 결국 onDestroy()를 깨웁니다)
        mainHandler.post {
            MirrorWidgetProvider.updateAllWidgets(this)
            stopSelf()
        }
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
                    continue
                }
                val currentApp = primaryPipeline.currentApp
                if (currentApp.isNotBlank() && currentApp != "HOME" && currentApp != "com.android.settings") {
                    val service = vdm.getPrivilegedService()
                    if (service != null) {
                        try {
                            val activeTasks = service.getRunningTasksOnDisplay(displayId) ?: emptyList()
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
        if (cleanupCompleted) return
        cleanupCompleted = true
        val effectiveReason = terminalReason.get()?.let { "terminal:${it.name}" } ?: reason
        MirrorDiagnostics.endSession(effectiveReason)
        isCleanupInProgress = true

        if (screenOffPolicy.state in listOf(ScreenOffState.PANEL_OFF_ACTIVE, ScreenOffState.PANEL_OFF_PENDING)) {
            try { virtualDisplayManager?.setPhysicalDisplayPower(true) } catch (_: Exception) {}
        }
        screenOffPolicy.reset()
        _panelOffStateFlow.value = ScreenOffState.ACTIVE

        // 🔴 [리시버 누수 원천 차단] 
        // Android 시스템 커널 장부에서 리시버를 확실하게 지우기 위해 메인 룹(UI 스레드)으로 포스팅하여 해제합니다.
        val receiverToUnregister = screenOffReceiver
        if (receiverToUnregister != null) {
            // Context를 서비스 본체가 아닌 시스템의 Application Context 레벨에서 안전하게 해제 요청을 보냅니다.
            val appContext = applicationContext

            // 메인 스레드(Looper)에서 돌고 있는지 확인 후 즉시 동기식 집행
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                try {
                    appContext.unregisterReceiver(receiverToUnregister)
                    Log.i(TAG, "[Cleanup Dynamic Fix] Synchronously unregistered screenOffReceiver via AppContext.")
                } catch (e: Exception) {
                    try { unregisterReceiver(receiverToUnregister) } catch (_: Exception) {}
                }
            } else {
                // 백그라운드 스레드에서 넘어온 경우 메인 룹에 즉시 처리를 때리고 커널 장부가 마운트될 때까지 50ms 물리 락을 걸어 강제 동기화합니다.
                val latch = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    try {
                        appContext.unregisterReceiver(receiverToUnregister)
                        Log.i(TAG, "[Cleanup Dynamic Fix] Intercepted Main Looper: Unregistered via AppContext successfully.")
                    } catch (e: Exception) {
                        try { unregisterReceiver(receiverToUnregister) } catch (_: Exception) {}
                    } finally {
                        latch.countDown()
                    }
                }
                try { latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            }
            screenOffReceiver = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { try { thermalThrottleManager.unregister() } catch (_: Exception) {} }
        powerLockManager.releaseWakeLocks()
        stopVdKeepAlive()
        audioOrchestrator?.stop()

        try { primaryPipeline.resizeJob?.cancel() } catch (_: Exception) {}
        try { secondaryPipeline.resizeJob?.cancel() } catch (_: Exception) {}
        adaptiveBitrateManager.stopAbrLoop()
        adaptiveBitrateManager.stopAutoScaleLoop()
        try { serviceScope.cancel() } catch (_: Exception) {}
        try { compositionDispatcher.close() } catch (_: Exception) {}

        secondaryPipeline.release()
        try { removeAllVdTasks() } catch (_: Exception) {}
        pendingBrowserDisconnectJob?.cancel()
        pendingBrowserDisconnectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        
        try { virtualDisplayManager?.getPrivilegedService()?.restoreStayAwakeMode() } catch (_: Exception) {}
        try { virtualDisplayManager?.release() } catch (_: Exception) {}
        try { shizukuSetup?.release() } catch (_: Exception) {}
        
        primaryPipeline.release(forcePhysical = true)
        try { mirrorServer?.stop() } catch (_: Exception) {}

        virtualDisplayManager = null
        shizukuSetup = null
        mirrorServer = null
        instance = null
        isCleanupInProgress = false
        isServiceRunning = false
    }

    private fun startPipeline(fps: Int, audioEnabled: Boolean) {
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

            primaryPipeline.width = width
            primaryPipeline.height = height
            currentFps = fps
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
                override fun scheduleDeferredStart(delayMs: Long): Any {
                    return serviceScope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(delayMs); audioOrchestrator?.onDeferredTimerExpired() }
                }
                override fun cancelDeferredStart(handle: Any?) { (handle as? Job)?.cancel(); if (deferredAudioStartJob == handle) deferredAudioStartJob = null }
            })

            primaryPipeline.touchInjector = TouchInjector(width, height)

            mirrorServer = MirrorServer(this).also { server ->
                server.setNetworkCongestionListener { adaptiveBitrateManager.onNetworkCongestion() }
                server.setTouchListener { event ->
                    if (event.pane == "secondary") secondaryPipeline.touchInjector?.onTouchEvent(event)
                    else primaryPipeline.touchInjector?.onTouchEvent(event)
                    if (event.action == "down") bubbleClosedByUser = false
                    if (event.action == "up") { lastTouchPane = event.pane; checkImeAndNotifyBrowser() }
                }
                server.setCodecModeListener { onCodecModeRequest(it) }
                server.setViewportChangeListener { pane, w, h, layoutMode ->
                    if (pane == "secondary") secondaryPipeline.onViewportChange(w, h, layoutMode)
                    else primaryPipeline.onViewportChange(w, h, layoutMode)
                }
                server.setTextInputListener { injectText(it) }
                server.setKeyEventListener { injectKeyEvent(it) }
                server.setCompositionUpdateListener { bs, text -> injectCompositionUpdate(bs, text) }
                server.setBubbleClosedListener { bubbleClosedByUser = true; lastImeState = false; lastBroadcastPane = null; haveSeenRealImeShow = false; cancelImeHideWatchdog() }
                server.setAudioCodecListener { codec -> serviceScope.launch(Dispatchers.IO) { ensureAudioCaptureState(codec) } }
                server.setAudioSocketConnectedListener { audioOrchestrator?.onAudioSocketConnected() }
                server.setGoHomeListener {
                    secondaryPipeline.release()
                    serviceScope.launch(Dispatchers.IO) { primaryVdOperationMutex.withLock { if (primaryPipeline.currentVdToken() != null) virtualDisplayManager?.launchHomeOnDisplay() } }
                    primaryPipeline.currentApp = "HOME"; primaryPipeline.currentWebUrl = null
                }
                server.setAppLaunchListener { pkg, cmp, pane -> (if (pane == "secondary") secondaryPipeline else primaryPipeline).launchAppFromWebLauncher(pkg, cmp) }
                server.setDisplayDensityListener { scale ->
                    dpiScale = scale
                    val vdm = virtualDisplayManager
                    if (vdm?.hasVirtualDisplay() == true && primaryPipeline.width > 0 && primaryPipeline.height > 0) {
                        serviceScope.launch {
                            primaryPipeline.rebuild(primaryPipeline.width, primaryPipeline.height, force = true)
                        }
                    }
                }
                server.setQualityReportListener { d, a, b -> adaptiveBitrateManager.apply { lastQualityDroppedFrames = d; lastQualityAvgDelayMs = a; lastQualityBacklogDrops = b } }
                server.setBrowserConnectionListener { connected ->
                    if (connected) {
                        cancelPendingBrowserDisconnect("browser_reconnected")
                        if (!browserConnected) { browserConnected = true; onBrowserConnected() }
                        browserConnectionListener?.invoke(true)
                    } else if (browserConnected) { scheduleBrowserDisconnect() } else { browserConnectionListener?.invoke(false) }
                }
                server.start(0)
            }
            MirrorWidgetProvider.updateAllWidgets(this)
        } catch (e: Exception) { stopSelf() }
    }
    
    private enum class SessionMode { STANDARD_APP, EXTERNAL_BROWSER, INTERNAL_WEBVIEW }
    private data class ActiveLaunchSession(val mode: SessionMode, val launchTarget: String, val url: String? = null, val sourceAppPackage: String? = null, val browserPackage: String? = null)
    private var activeSession: ActiveLaunchSession? = null

    private fun internalComponentName(activityClassName: String): String = if (activityClassName.contains('/')) activityClassName else "$packageName/$activityClassName"

    private fun rebalanceDualDisplayBitrates() {
        val thermalActive = thermalThrottleManager.thermalStatus.value >= PowerManager.THERMAL_STATUS_LIGHT
        val hasSplit = secondaryPipeline.displayId >= 0 && secondaryPipeline.width > 0
        val now = android.os.SystemClock.elapsedRealtime()
        val canApply = now - lastCongestionTimeMs > 2000

        if (hasSplit && (isCurrentAppVideo || isSecondaryAppVideo) && !thermalActive) {
            val primaryBps = if (isCurrentAppVideo) StreamMath.calculateSplitVideoBitrate(primaryPipeline.width, primaryPipeline.height) else StreamMath.calculateSplitCompanionBitrate(primaryPipeline.width, primaryPipeline.height)
            val secondaryBps = if (isSecondaryAppVideo) StreamMath.calculateSplitVideoBitrate(secondaryPipeline.width, secondaryPipeline.height) else StreamMath.calculateSplitCompanionBitrate(secondaryPipeline.width, secondaryPipeline.height)

            targetBitrate = primaryBps
            if (canApply || primaryPipeline.currentBitrate > primaryBps) {
                primaryPipeline.currentBitrate = primaryBps
                primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)
            }
            secondaryPipeline.videoEncoder?.setBitrate(secondaryBps)
        } else {
            val baseBitrate = StreamMath.calculateBaseBitrate(primaryPipeline.width, primaryPipeline.height)
            targetBitrate = if (isCurrentAppVideo && !thermalActive) StreamMath.calculateOttBitrate(baseBitrate) else baseBitrate
            if (canApply || primaryPipeline.currentBitrate > targetBitrate) {
                primaryPipeline.currentBitrate = targetBitrate
                primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)
            }
            if (hasSplit) {
                secondaryPipeline.videoEncoder?.setBitrate(StreamMath.calculateSecondaryBitrate(secondaryPipeline.width, secondaryPipeline.height))
            }
        }
    }

    private fun computeVirtualDisplayDpi(width: Int, height: Int): Int = StreamMath.applyDensityScale(StreamMath.calculateDpi(minOf(width, height)), dpiScale)

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
                if (pkg != null && pkg != myPackage && !pkg.startsWith("com.android.launcher") && !pkg.startsWith("com.sec.android.app.launcher") && pkg != "com.android.settings") {
                    packagesToStop.add(pkg)
                }
            }

            for (pkg in packagesToStop) { service.execCommand("am force-stop $pkg") }
            val removedTaskIds = mutableSetOf<Int>()
            for (pkg in packagesToStop) {
                for (taskId in service.getTaskIdsForPackage(pkg)) {
                    if (removedTaskIds.add(taskId)) service.removeTask(taskId)
                }
            }
        } catch (_: Exception) {}
    }

    private val BROWSER_PACKAGES = setOf("com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.microsoft.emmx")

    private fun forceStopAppIfNeeded(packageName: String) {
        val pkg = packageName.substringBefore('/')
        if (pkg.isBlank() || pkg == "HOME" || pkg == "com.android.settings" || pkg.startsWith("com.android.launcher") || pkg.startsWith("com.sec.android.app.launcher") || pkg == applicationContext.packageName) return

        try {
            val service = virtualDisplayManager?.getPrivilegedService() ?: return
            val matchingTaskIds = findAllTaskIds(service.execCommand("dumpsys activity activities"), pkg)
            for (taskId in matchingTaskIds) { try { service.removeTask(taskId) } catch (_: Exception) {} }
            if (BROWSER_PACKAGES.contains(pkg)) return
            service.execCommand("am force-stop $pkg")
        } catch (_: Exception) {}
    }

    private fun markTerminal(reason: TerminalReason) {
        if (!terminalReason.compareAndSet(null, reason)) return
        try { requestStopAsync("terminal_${reason.name.lowercase()}") } catch (_: Exception) {}
    }

    private fun escapeShellArg(value: String): String = "'" + value.replace("'", "'\''") + "'"

    private fun resolveLaunchComponent(packageOrComponent: String): String? {
        if (packageOrComponent.contains('/')) return packageOrComponent
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageOrComponent)
            val component = launchIntent?.component ?: run {
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER); `package` = packageOrComponent }, PackageManager.MATCH_ALL)
                    .firstOrNull()?.activityInfo?.let { ComponentName(it.packageName, it.name) }
            }
            component?.flattenToShortString()
        } catch (_: Exception) { null }
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


    private fun verifySurfaceAndFallback(pipeline: VirtualDisplayPipeline, service: IPrivilegedService, displayId: Int, pkg: String, taskIds: List<Int>, packageOrComponent: String, extraKey: String?, extraValue: String?) {
        serviceScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1000L)
            try {
                if (service.getRunningTasksOnDisplay(displayId).none { it.contains(pkg) }) {
                    for (taskId in taskIds) { try { service.removeTask(taskId) } catch (_: Exception) {} }
                    service.execCommand("am force-stop $pkg")
                    service.execCommand(buildShellLaunchCommand(displayId, packageOrComponent, extraKey, extraValue, reorderToFront = false))
                }
            } catch (_: Exception) {}
        }
    }

    private fun findAllTaskIds(dumpsys: String?, pkg: String): List<Int> {
        virtualDisplayManager?.getPrivilegedService()?.let { try { return it.getTaskIdsForPackage(pkg).toList() } catch (_: Exception) {} }
        if (dumpsys.isNullOrBlank()) return emptyList()
        val taskIds = mutableListOf<Int>()
        val blocks = dumpsys.split(Regex("\\* Task"))
        for (i in 1 until blocks.size) {
            val block = blocks[i]
            val taskId = Regex("^\\s*(?:#|Record\\{\\s*|\\{\\s*)(\\d+)").find(block)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (block.contains("realActivity=$pkg/") || block.contains("origActivity=$pkg/") || block.contains("ComponentInfo{$pkg/")) taskIds.add(taskId)
        }
        return taskIds
    }

    private fun ensureShizukuSetup(): ShizukuSetup? {
        shizukuSetup?.let { return it }
        return ShizukuSetup().also { it.init(this, bindService = true); shizukuSetup = it; startReconnectObserver(it) }
    }

    private fun startReconnectObserver(setup: ShizukuSetup) {
        if (reconnectJob != null) return
        reconnectJob = serviceScope.launch {
            val tracker = BinderConnectionTracker()
            setup.serviceConnected.collect { connected ->
                when (if (connected) tracker.onConnected() else tracker.onDisconnected()) {
                    BinderConnectionTracker.Transition.Disconnect -> virtualDisplayManager?.attachPrivilegedService(null)
                    BinderConnectionTracker.Transition.Reconnect -> handleShizukuReconnect(setup)
                    else -> {}
                }
            }
        }
    }

    private fun handleShizukuReconnect(setup: ShizukuSetup) {
        if (!browserConnected) return
        val vdm = virtualDisplayManager ?: VirtualDisplayManager().also { virtualDisplayManager = it }
        val surf = primaryPipeline.currentEncoderSurface ?: return
        if (primaryPipeline.width <= 0 || primaryPipeline.height <= 0) return
        vdm.attachPrivilegedService(setup.privilegedService ?: return)
        serviceScope.launch(Dispatchers.IO) {
            primaryVdOperationMutex.withLock {
                vdm.createVirtualDisplay(primaryPipeline.width, primaryPipeline.height, computeVirtualDisplayDpi(primaryPipeline.width, primaryPipeline.height), surf)
                if (vdm.hasVirtualDisplay()) {
                    val displayId = vdm.getDisplayId()
                    val generation = primaryPipeline.markVdCreated(displayId, "shizuku_reconnect")
                    primaryPipeline.touchInjector?.setVirtualDisplayInjector { vdm.injectMotionEvent(it) }
                    primaryPipeline.restoreContentLocked(generation, displayId)
                }
            }
        }
    }

    private fun trySetupVirtualDisplay(width: Int, height: Int, surface: Surface, onResult: (Boolean) -> Unit) {
        if (shizukuSetupInProgress) { shizukuSetupCallbacks.add(onResult); return }
        shizukuSetupInProgress = true; shizukuSetupCallbacks.clear(); shizukuSetupCallbacks.add(onResult)
        
        var resultDelivered = false
        val safeResult = { success: Boolean ->
            if (!resultDelivered) {
                resultDelivered = true; shizukuSetupInProgress = false
                if (!success) tearDownVdSession("virtual_display_setup_failed")
                val callbacks = synchronized(shizukuSetupCallbacks) { val list = shizukuSetupCallbacks.toList(); shizukuSetupCallbacks.clear(); list }
                callbacks.forEach { try { it(success) } catch (_: Exception) {} }
            }
        }

        val setup = ensureShizukuSetup() ?: run { safeResult(false); return }
        if (setup.privilegedService == null || !setup.isAvailable()) {
            Log.w(TAG, "[Recovery Safeguard] Stuck or Dead Shizuku binding detected — force resetting connection state.")
            setup.forceResetBindingState()
        }
        if (!setup.isAvailable() || !setup.hasPermission()) { safeResult(false); return }
        setup.bindPrivilegedService()

        serviceScope.launch {
            var isStable = false
            val startTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - startTime < BIND_WAIT_BUDGET_MS) {
                if (setup.serviceConnected.value && setup.privilegedService != null) {
                    val svc = setup.privilegedService
                    if (svc != null) {
                        val isAlive = try {
                            svc.asBinder().isBinderAlive
                        } catch (_: Exception) {
                            false
                        }

                        if (isAlive) {
                            // 바인더 안착 대기 마진 추가
                            kotlinx.coroutines.delay(250)
                            val finalCheck = try { svc.asBinder().isBinderAlive } catch (_: Exception) { false }
                            if (finalCheck) {
                                isStable = true
                                break
                            }
                        }
                    }
                }
                kotlinx.coroutines.delay(100)
            }

            if (!isStable) {
                Log.e(TAG, "[Shizuku Connect] Binding target failed to stabilize within budget.")
                shizukuBindRetryCount++
                
                // 🔴 [교착 상태 격파 핵심] 바인딩 초기화뿐만 아니라 
                // 기존 가상 디스플레이 인스턴스가 존재한다면 커널 자원을 완벽하게 리사이클링합니다.
                try {
                    virtualDisplayManager?.releaseVirtualDisplay()
                    virtualDisplayManager?.attachPrivilegedService(null)
                    virtualDisplayManager = null
                } catch (_: Exception) {}
                
                setup.forceResetBindingState()
                
                if (shizukuBindRetryCount < SHIZUKU_MAX_RETRIES) {
                    shizukuSetupInProgress = false 
                    tearDownVdSession("binding_timeout")
                    
                    // 리시버 누수 지연 오버헤드가 지나가길 충분히 기다려줍니다 (1.5초 -> 2초 보정)
                    kotlinx.coroutines.delay(2000)
                    
                    if (browserConnected) {
                        primaryPipeline.currentEncoderSurface?.let { 
                            Log.i(TAG, "[Shizuku Connect] Triggering rebind retry attempt #$shizukuBindRetryCount")
                            trySetupVirtualDisplay(primaryPipeline.width, primaryPipeline.height, it, onResult)
                            return@launch 
                        }
                    }
                    safeResult(false)
                } else { 
                    safeResult(false) 
                }
                return@launch
            }

            shizukuBindRetryCount = 0
            if (!browserConnected) { safeResult(false); return@launch }
            val svc = setup.privilegedService ?: run { safeResult(false); return@launch }
            
            try { 
                svc.enableStayAwakeMode() 
            } catch (e: Exception) {
                Log.w(TAG, "Failed to call enableStayAwakeMode immediately after stabilization", e)
                kotlinx.coroutines.delay(100)
                try { svc.enableStayAwakeMode() } catch (_: Exception) {}
            }

            // 구형 유령 자원 초기화 후 클린하게 신규 매핑
            try { virtualDisplayManager?.release() } catch (_: Exception) {}
            val vdm = VirtualDisplayManager().also { virtualDisplayManager = it }
            vdm.attachPrivilegedService(svc)
            
            val w = if (primaryPipeline.width > 0) primaryPipeline.width else width
            val h = if (primaryPipeline.height > 0) primaryPipeline.height else height
            val dpi = computeVirtualDisplayDpi(w, h)
            
            primaryVdOperationMutex.withLock {
                try {
                    vdm.createVirtualDisplay(w, h, dpi, primaryPipeline.currentEncoderSurface ?: surface)
                    if (vdm.hasVirtualDisplay()) {
                        val activeId = vdm.getDisplayId()
                        val generation = primaryPipeline.markVdCreated(activeId, "try_setup")
                        primaryPipeline.touchInjector?.setVirtualDisplayInjector { vdm.injectMotionEvent(it) }
                        startAppExitMonitor()
                        primaryPipeline.restoreContentLocked(generation, activeId)
                        serviceScope.launch(Dispatchers.IO) { setup.ensureShizukuHardened() }
                        safeResult(true)
                    } else { 
                        safeResult(false) 
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fatal error during Virtual Display execution setup", e)
                    safeResult(false)
                }
            }
        }
    }

    private fun cancelPendingBrowserDisconnect(reason: String) { pendingBrowserDisconnectJob?.cancel(); pendingBrowserDisconnectJob = null }

    private fun scheduleBrowserDisconnect() {
        if (pendingBrowserDisconnectJob != null) return
        val screenOff = screenOffPolicy.isScreenOff
        pendingBrowserDisconnectJob = serviceScope.launch {
            kotlinx.coroutines.delay(DisconnectPolicy.graceMs(screenOff))
            pendingBrowserDisconnectJob = null
            if (mirrorServer?.isBrowserConnected() == true) return@launch
            if (!DisconnectPolicy.shouldTeardown(screenOffPolicy.isScreenOff, isBrowserConnected = false)) return@launch
            if (browserConnected) { browserConnected = false; onBrowserDisconnected() }
            browserConnectionListener?.invoke(false)
        }
    }

    private fun onBrowserConnected() {
        try {
            powerLockManager.acquireWakeLocks()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalThrottleManager.broadcastThermalStatus(thermalThrottleManager.thermalStatus.value)
            }

            if (primaryPipeline.videoEncoder != null || primaryPipeline.jpegEncoder != null) {
                serviceScope.launch { primaryPipeline.rebuild(primaryPipeline.width, primaryPipeline.height, force = true) }
                ensureAudioCaptureState()
                return
            }

            val w = if (primaryPipeline.width > 0) primaryPipeline.width else 720
            val h = if (primaryPipeline.height > 0) primaryPipeline.height else 720
            targetBitrate = StreamMath.calculateBaseBitrate(w, h).also {
                primaryPipeline.currentBitrate = it
                preThermalTargetBitrate = it
            }

            adaptiveBitrateManager.startAbrLoop()
            adaptiveBitrateManager.startAutoScaleLoop(autoResolution, autoFps)

            serviceScope.launch { primaryPipeline.rebuild(w, h, force = true) }

        } catch (t: Throwable) {
            Log.e(TAG, "Failed to handle browser activation", t)
            markTerminal(TerminalReason.BROWSER_ACTIVATION_FAILED)
        }
    }

    private fun tearDownVdSession(reason: String) {
        stopAppExitMonitor()
        try { primaryPipeline.touchInjector?.setVirtualDisplayInjector(null) } catch (_: Exception) {}
        primaryPipeline.invalidateVd("teardown_$reason")
        try { virtualDisplayManager?.release() } catch (_: Exception) {}
        virtualDisplayManager = null
    }

    private fun ensureAudioCaptureState(codecOverride: String? = null) {
        audioOrchestrator?.apply { audioEnabled = pendingAudioEnabled && AudioCapture.isSupported(); browserConnected = this@MirrorForegroundService.browserConnected; ensure(codecOverride) }
    }

    private fun onBrowserDisconnected() {
        pendingBrowserDisconnectJob = null; browserConnected = false; lastImeState = false; lastBroadcastPane = null; haveSeenRealImeShow = false; bubbleClosedByUser = false
        cancelImeHideWatchdog()
        secondaryPipeline.release()
        try { removeAllVdTasks() } catch (_: Exception) {}
        tearDownVdSession("browser_disconnected")
        
        primaryPipeline.apply { videoEncoder?.release(); videoEncoder = null; jpegEncoder?.release(); jpegEncoder = null; currentEncoderSurface = null }
        audioOrchestrator?.stop()
        adaptiveBitrateManager.stopAbrLoop()
        powerLockManager.releaseWakeLocks()
    }

    private fun activeInputDisplayId(): Int = if (lastTouchPane == "secondary" && secondaryPipeline.displayId >= 0) secondaryPipeline.displayId else (virtualDisplayManager?.getDisplayId() ?: -1)

    private fun injectText(text: String) { serviceScope.launch(compositionDispatcher) { try { shizukuSetup?.privilegedService?.injectText(text, activeInputDisplayId()) } catch (_: Exception) {} } }

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
        var imeState = try { service.getImeState(activeDisplayId) } catch (e: android.os.DeadObjectException) { imeCheckSuspendUntil = System.currentTimeMillis() + 10_000; return null }
        if (imeState == 0) imeState = legacyImeState(service, activeDisplayId)

        var imeVisible = !bubbleClosedByUser && (imeState and ImeState.VISIBLE) != 0
        if (!imeVisible && !bubbleClosedByUser && activeDisplayId > 0) imeVisible = (imeState and ImeState.SERVED_INPUT) != 0
        if (imeVisible) haveSeenRealImeShow = true

        val combinedVisible = imeVisible || (!imeVisible && activeDisplayId > 0 && ImeVisibilityPolicy.shouldUseInputTargetFallback(activeDisplayId, (imeState and ImeState.INPUT_TARGET_ON_DISPLAY) != 0, haveSeenRealImeShow, bubbleClosedByUser))
        val now = System.currentTimeMillis()
        if (combinedVisible) { lastImeVisibleTime = now; imeHiddenSince = 0L }
        else if (lastImeState) {
            if (imeHiddenSince == 0L) imeHiddenSince = now
            if (now - imeHiddenSince < 700 || now - lastImeVisibleTime < 1200) return true
        }

        if (combinedVisible != lastImeState || (combinedVisible && lastImeState && lastBroadcastPane != activePane) || (combinedVisible && source == "check")) {
            lastImeState = combinedVisible; lastBroadcastPane = if (combinedVisible) activePane else null
            if (!combinedVisible) haveSeenRealImeShow = false
            mirrorServer?.broadcastControlMessage(if (combinedVisible) """{"type":"showKeyboard","pane":"$activePane"}""" else """{"type":"hideKeyboard"}""")
        }
        return combinedVisible
    }

    private fun legacyImeState(service: IPrivilegedService, activeDisplayId: Int): Int {
        val inputState = try { ImeState.parseInputMethodDump(service.execCommand("dumpsys input_method | grep -E 'mInputShown|mImeWindowVis|mDecorViewVisible|mWindowVisible|mServedView|mServedInputConnection|mShowRequested|mShowInputRequested|mIsInputViewShown|isInputViewShown|mInputViewStarted|mCurClient'")) } catch (e: android.os.DeadObjectException) { imeCheckSuspendUntil = System.currentTimeMillis() + 10_000; return 0 } catch (_: Exception) { 0 }
        if ((inputState and (ImeState.VISIBLE or ImeState.SERVED_INPUT)) != 0 || activeDisplayId <= 0) return inputState
        val targetState = try { ImeState.parseWindowDump(service.execCommand("dumpsys window | grep 'imeInputTarget in display'"), activeDisplayId) } catch (e: android.os.DeadObjectException) { imeCheckSuspendUntil = System.currentTimeMillis() + 10_000; 0 } catch (_: Exception) { 0 }
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
                    val activeId = if (activePane == "secondary" && secondaryPipeline.displayId >= 0) secondaryPipeline.displayId else (virtualDisplayManager?.getDisplayId() ?: -1)
                    if (imeCheckTick(activePane, activeId, "watchdog") == false) break
                }
            } catch (_: Exception) {} finally { imeHideWatchdogJob = null }
        }
    }

    private fun cancelImeHideWatchdog() { imeHideWatchdogJob?.cancel(); imeHideWatchdogJob = null }

    private fun checkImeAndNotifyBrowser() {
        val now = System.currentTimeMillis()
        if (now - lastImeCheckTime < 500 || now < imeCheckSuspendUntil) return
        lastImeCheckTime = now

        val activePane = lastTouchPane
        val activeId = if (activePane == "secondary" && secondaryPipeline.displayId >= 0) secondaryPipeline.displayId else (virtualDisplayManager?.getDisplayId() ?: -1)

        serviceScope.launch(Dispatchers.IO) {
            try {
                val delays = longArrayOf(300, 400, 500, 600)
                for (attempt in 0 until 4) {
                    kotlinx.coroutines.delay(delays[attempt])
                    imeCheckTick(activePane, activeId, "check") ?: return@launch
                    if (lastImeState) { startImeHideWatchdog(); break }
                }
            } catch (_: Exception) { imeCheckSuspendUntil = System.currentTimeMillis() + 10_000 }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val compositionDispatcher = kotlinx.coroutines.newSingleThreadContext("composition")

    private fun injectCompositionUpdate(backspaces: Int, text: String) { serviceScope.launch(compositionDispatcher) { try { shizukuSetup?.privilegedService?.injectComposingText(backspaces, text, activeInputDisplayId()) } catch (_: Exception) {} } }
    private fun injectKeyEvent(keyCode: Int) { serviceScope.launch(compositionDispatcher) { try { val id = activeInputDisplayId(); shizukuSetup?.privilegedService?.execCommand(if (id > 0) "input -d $id keyevent $keyCode" else "input keyevent $keyCode") } catch (_: Exception) {} } }

    // ==========================================
    // ENCAPSULATED VIRTUAL DISPLAY PIPELINE
    // ==========================================
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

        var currentBitrate = 0
        var currentApp = ""
        var currentWebUrl: String? = null
        var isVideoApp = false

        var touchInjector: TouchInjector? = null
        var resizeJob: Job? = null
        var requestedWidth: Int = 0
        var requestedHeight: Int = 0

        fun onViewportChange(w: Int, h: Int, layoutMode: String = "") {
            if (!isPrimary && w <= 0 && h <= 0) {
                release()
                return
            }
            requestedWidth = w
            requestedHeight = h
            resizeJob?.cancel()
            resizeJob = serviceScope.launch {
                kotlinx.coroutines.delay(500L)
                rebuild(w, h, forceSingle = (layoutMode == "single"))
            }
        }

        suspend fun rebuild(newWidth: Int, newHeight: Int, force: Boolean = false, forceSingle: Boolean = false) {
            if (isAppLaunchingContext) {
                Log.i(TAG, "Rebuild requested during active app launch context — deferring to protect DisplayId")
                return
            }


            if (newWidth <= 0 || newHeight <= 0) return
            val lock = if (isPrimary) pipelineMutex else secondaryPipelineMutex
            lock.withLock {
                if (pipelineState == PipelineState.REBUILDING) {
                    pendingRebuildRequest = RebuildRequest(newWidth, newHeight, force, forceSingle)
                    return@withLock
                }
                pipelineState = PipelineState.REBUILDING
                try {
                    executeActualRebuild(newWidth, newHeight, force, forceSingle)
                } finally {
                    val nextRequest = pendingRebuildRequest
                    if (nextRequest != null) {
                        pendingRebuildRequest = null
                        pipelineState = PipelineState.IDLE
                        serviceScope.launch { rebuild(nextRequest.width, nextRequest.height, nextRequest.force, nextRequest.forceSingle) }
                    } else {
                        pipelineState = PipelineState.IDLE
                    }
                }
            }
        }

    private suspend fun executeActualRebuild(targetWidth: Int, targetHeight: Int, force: Boolean = false, forceSingle: Boolean = false) {
            val effectiveMaxHeight = targetHeight.coerceAtMost(1080)
            var targetW = targetWidth
            var targetH = targetHeight
            if (targetH > effectiveMaxHeight) {
                val scale = effectiveMaxHeight.toFloat() / targetH
                targetH = effectiveMaxHeight
                targetW = (targetW * scale).toInt()
            }
            val alignedWidth = ((targetW + 15) and 15.inv()).coerceAtLeast(320)
            val alignedHeight = ((targetH + 15) and 15.inv()).coerceAtLeast(320)

            if (!force && isPrimary && alignedWidth == width && alignedHeight == height) return
            if (isPrimary && (alignedWidth > 3840 || alignedHeight > 3840)) return

            val w = alignedWidth
            val h = alignedHeight
            val dpi = computeVirtualDisplayDpi(w, h)

            if (isPrimary) {
                val newTargetBitrate = StreamMath.calculateBaseBitrate(w, h)
                targetBitrate = if (isVideoApp) StreamMath.calculateOttBitrate(newTargetBitrate) else newTargetBitrate
                currentBitrate = targetBitrate
            } else {
                if (virtualDisplayManager?.isBound() != true) return
                if (displayId >= 0 && width == w && height == h && ((currentCodecMode == "mjpeg" && jpegEncoder != null) || (currentCodecMode != "mjpeg" && videoEncoder != null))) return
            }

            try {
                if (isPrimary) {
                    virtualDisplayManager?.setSurface(null)
                } else {
                    virtualDisplayManager?.getPrivilegedService()?.setSurface(displayId, null)
                }
            } catch (_: Exception) {}

            videoEncoder?.release(); videoEncoder = null
            jpegEncoder?.release(); jpegEncoder = null

            delay(150)

            var startEncoderTask: (() -> Unit)? = null
            val surface = if (currentCodecMode == "mjpeg") {
                val jpeg = JpegEncoder(w, h, fps = 15, quality = 65)
                val inputSurface = jpeg.createInputSurface()
                jpegEncoder = jpeg
                startEncoderTask = {
                    jpeg.start { data, key -> mirrorServer?.broadcastFrame(data, key, name) }
                }
                mirrorServer?.setKeyframeRequester(name) {
                    serviceScope.launch { try { if (displayId >= 0) virtualDisplayManager?.getPrivilegedService()?.wakeUpDisplay(displayId); restoreContent() } catch (_: Exception) {} }
                }
                inputSurface
            } else {
                val baseBitrate = if (isPrimary) currentBitrate else StreamMath.calculateSecondaryBitrate(w, h)
                val encoder = VideoEncoder(w, h, baseBitrate, thermalFpsOverride ?: currentFps)
                val inputSurface = encoder.createInputSurface()
                videoEncoder = encoder
                encoder.onSpsPps = { mirrorServer?.broadcastSpsPps(it, name) }
                startEncoderTask = {
                    encoder.start { data, key -> mirrorServer?.broadcastFrame(data, key, name) }
                }
                mirrorServer?.setKeyframeRequester(name) { encoder.requestKeyFrame() }
                inputSurface
            }

            currentEncoderSurface = surface
            width = w
            height = h

            delay(100)

            if (isPrimary) {
                touchInjector = (touchInjector ?: TouchInjector(w, h)).also { it.updateDimensions(w, h) }
                if (virtualDisplayManager?.isBound() == true) {
                    primaryVdOperationMutex.withLock {
                        // 🔴 핵심 변경: 메모리 장부가 아닌 실제 커널 디스플레이 매니저의 활성화 ID를 직접 쿼리합니다.
                        val activeId = virtualDisplayManager!!.getDisplayId()
                        if (activeId >= 0) {
                            Log.i(TAG, "[VDSafeResize] Reusing existing primary Display $activeId")
                            virtualDisplayManager?.resizeDisplay(activeId, w, h, dpi)
                            virtualDisplayManager?.setSurface(surface)

                            displayId = activeId
                            touchInjector?.setVirtualDisplayInjector { virtualDisplayManager?.injectMotionEvent(it) }
                            startEncoderTask?.invoke()
                        } else {
                            // 🔴 [원인 격파] 이미 Shizuku 내부나 다른 코루틴이 디스플레이를 새로 만들고 있다면
                            // 무작비하게 release 후 Recreate 하지 말고 200ms 안착 대기 마진을 주어 가속 충돌을 방지합니다.
                            if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                                Log.w(TAG, "[VDSafeResize] Virtual display installation in progress... waiting for architecture stabilization.")
                                delay(200)
                            }

                            val doubleCheckId = virtualDisplayManager!!.getDisplayId()
                            if (doubleCheckId >= 0) {
                                // 대기 후 디스플레이가 정상 확보되었다면 즉시 재활용 파이프라인으로 우회 유도
                                virtualDisplayManager?.setSurface(null)
                                virtualDisplayManager?.resizeDisplay(doubleCheckId, w, h, dpi)
                                virtualDisplayManager?.setSurface(surface)
                                displayId = doubleCheckId
                                touchInjector?.setVirtualDisplayInjector { virtualDisplayManager?.injectMotionEvent(it) }
                                startEncoderTask?.invoke()
                                return@withLock
                            }

                            // 진짜 아무것도 없을 때만 최초 완벽한 클린 마운트 시행
                            virtualDisplayManager?.releaseVirtualDisplay()
                            delay(50) // 커널 자원 반납 최소 시간 보장

                            virtualDisplayManager?.createVirtualDisplay(w, h, dpi, surface)
                            if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                                val newActiveId = virtualDisplayManager!!.getDisplayId()
                                displayId = newActiveId
                                val gen = markVdCreated(newActiveId, "primary_rebuild")
                                touchInjector?.setVirtualDisplayInjector { virtualDisplayManager?.injectMotionEvent(it) }
                                startEncoderTask?.invoke()
                                restoreContentLocked(gen, newActiveId)
                                Log.i(TAG, "[VDRebuild] Created primary virtual display successfully. ID: $newActiveId")
                            } else {
                                Log.e(TAG, "[VDRebuild] Failed to create primary virtual display")
                                markTerminal(TerminalReason.VD_RECREATE_FAILED)
                            }
                        }
                    }
                } else if (!shizukuSetupInProgress) {
                    trySetupVirtualDisplay(w, h, surface) { success ->
                        if (!success) Log.e(TAG, "Shizuku rebind failed")
                        }
                    }
            } else {
                // Secondary 파이프라인 영역
                val oldDisplayId = displayId
                if (oldDisplayId >= 0) {
                    Log.i(TAG, "[VDSafeResize] Resizing secondary Display $oldDisplayId")
                    virtualDisplayManager?.getPrivilegedService()?.setSurface(oldDisplayId, null)
                    virtualDisplayManager?.resizeDisplay(oldDisplayId, w, h, dpi)
                    virtualDisplayManager?.getPrivilegedService()?.setSurface(oldDisplayId, surface)

                    startEncoderTask?.invoke()
                    touchInjector = (touchInjector ?: TouchInjector(w, h)).also { injector ->
                        injector.updateDimensions(w, h)
                        injector.setVirtualDisplayInjector { shizukuSetup?.privilegedService?.injectMotionEvent(oldDisplayId, it) }
                    }
                } else {
                    val newDisplayId = virtualDisplayManager?.createSecondaryVirtualDisplay(w, h, dpi, surface) ?: -1
                    if (newDisplayId < 0) {
                        Log.e(TAG, "[VDRebuild] Failed to create secondary virtual display")
                        release()
                        return
                    }
                    val gen = markVdCreated(newDisplayId, "secondary_rebuild")
                    try {
                        if (currentApp.isBlank()) {
                            currentApp = "HOME"
                            virtualDisplayManager?.getPrivilegedService()?.launchHomeOnDisplay(newDisplayId)
                        } else { restoreContentLocked(gen, newDisplayId) }
                    } catch (_: Exception) {}
                    displayId = newDisplayId
                    touchInjector = (touchInjector ?: TouchInjector(w, h)).also { injector ->
                        injector.updateDimensions(w, h)
                        injector.setVirtualDisplayInjector { shizukuSetup?.privilegedService?.injectMotionEvent(newDisplayId, it) }
                    }
                    startEncoderTask?.invoke()
                }
            }
        }

        fun invalidateVd(reason: String): Long { displayId = -1; return vdGeneration.incrementAndGet() }
        fun markVdCreated(activeId: Int, reason: String): Long { displayId = activeId; return vdGeneration.incrementAndGet() }

        fun isCurrentVd(expectedGeneration: Long, expectedDisplayId: Int): Boolean {
            return if (isPrimary) expectedDisplayId >= 0 && expectedGeneration == vdGeneration.get() && expectedDisplayId == displayId && virtualDisplayManager?.hasVirtualDisplay() == true && virtualDisplayManager?.getDisplayId() == expectedDisplayId
            else expectedDisplayId >= 0 && expectedGeneration == vdGeneration.get() && expectedDisplayId == displayId
        }

        fun currentVdToken(): Pair<Long, Int>? { val gen = vdGeneration.get(); val activeId = displayId; return if (isCurrentVd(gen, activeId)) gen to activeId else null }

        fun launchOwnActivity(activityClassName: String, url: String) {
            val targetDisplayId = this.displayId
            if (targetDisplayId < 0) return

            val options = android.app.ActivityOptions.makeBasic().apply { launchDisplayId = targetDisplayId }
            val intent = Intent().apply {
                setClassName(this@MirrorForegroundService, activityClassName)
                if (activityClassName.contains("WebBrowserActivity")) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra("url", url)
                putExtra("pane", this@VirtualDisplayPipeline.name)
            }
            try { 
                startActivity(intent, options.toBundle()) 
            } catch (e: Exception) {
                launchComponent(internalComponentName(activityClassName), "url", url, forceColdStart = false, forceDisplayId = true)
            }
        }
        
        fun getFreshDisplayId(): Int {
            val currentId = this.displayId
            return if (currentId >= 0 && (!isPrimary || isCurrentVd(vdGeneration.get(), currentId))) {
                currentId
            } else {
                if (isPrimary) {
                    virtualDisplayManager?.getDisplayId() ?: -1
                } else {
                    secondaryPipeline.displayId
                }
            }
        }

    fun launchComponent(
            packageOrComponent: String,
            extraKey: String? = null,
            extraValue: String? = null,
            forceColdStart: Boolean = false,
            forceDisplayId: Boolean = false
        ): Boolean {
            val cleanPkg = packageOrComponent
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore(' ')
                .trim()

            if (cleanPkg.isBlank() || cleanPkg == packageName || cleanPkg.contains("com.castla.mirror")) {
                return false
            }

            Log.i(TAG, "[$name Pipeline] launchComponent start: pkg=$cleanPkg, forceDisplayId=$forceDisplayId, forceColdStart=$forceColdStart")
            
            // 1. 최신 가상 디스플레이 ID 확보 (무조건 실시간 커널 ID 기준)
            val correctedDisplayId = getFreshDisplayId()
            if (correctedDisplayId < 0) {
                Log.w(TAG, "[$name Pipeline] Aborting launch: displayId is invalid ($correctedDisplayId)")
                return false
            }

            val service = virtualDisplayManager?.getPrivilegedService() ?: return false

            try {
                // 2. 강제 종료 분기 (순정 메커니즘 복원)
                if (forceColdStart && cleanPkg != "HOME") {
                    try {
                        val stopResult = service.execCommand("am force-stop $cleanPkg")
                        Log.i(TAG, "[$name Pipeline] Forced cold start. Force-stopped $cleanPkg. Result: $stopResult")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to force stop $cleanPkg for cold start", e)
                    }
                }

                // 3. Symmetric Task Routing (대칭적 태스크 라우팅 추적)
                val originalDisplayId = try { service.getDisplayIdForPackage(cleanPkg) } catch (_: Exception) { -1 }
                val primaryVdId = virtualDisplayManager?.getDisplayId() ?: -1
                val secondaryVdId = secondaryPipeline.displayId
                
                val targetDisplayId = if (!forceDisplayId && originalDisplayId >= 0 && 
                    (originalDisplayId == primaryVdId || originalDisplayId == secondaryVdId)) {
                    Log.i(TAG, "[$name Pipeline] Symmetric Task Routing: Redirecting $cleanPkg to original display $originalDisplayId")
                    originalDisplayId
                } else {
                    correctedDisplayId
                }

                // 4. 웜스타트 체크 및 스택 마이그레이션 (dumpsys 파싱 순정 로직)
                val dumpsys = service.execCommand("dumpsys activity activities")
                val matchingTaskIds = findAllTaskIds(dumpsys, cleanPkg)
                val isWarmStart = matchingTaskIds.isNotEmpty()
                Log.i(TAG, "[$name Pipeline] Warm start check: tasks=$matchingTaskIds, isWarmStart=$isWarmStart, targetDisplay=$targetDisplayId")

                // 중복 포커싱 현상으로 "준비중..."에 갇히는 걸 방지하기 위해 스택을 원자적으로 압송
                for (taskId in matchingTaskIds) {
                    try {
                        service.execCommand("cmd activity task move-to-display $taskId $targetDisplayId")
                        service.execCommand("cmd activity task move-to-front $taskId")
                        Log.i(TAG, "[$name Pipeline] Migrated and brought task $taskId to front of display $targetDisplayId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to migrate task $taskId to display $targetDisplayId", e)
                    }
                }

                // 🔴 [준비중 프리즈 격파 가드] 
                // 이미 타겟 가상 화면 최상단에 앱이 정체되어 "준비중..." 홈 껍데기 뒤에 숨은 경우,
                // 스택 포커스를 최상단으로 강제 리트리거하고 탈출합니다.
                if (isWarmStart && !forceColdStart) {
                    val activeTasks = try { service.getRunningTasksOnDisplay(targetDisplayId) } catch (_: Exception) { emptyList() }
                    if (activeTasks.firstOrNull()?.contains(cleanPkg) == true) {
                        Log.i(TAG, "[$name Pipeline] App is already on top of display $targetDisplayId. Re-ordering front to pop UI.")
                        for (taskId in matchingTaskIds) {
                            service.execCommand("cmd activity task move-to-front $taskId")
                        }
                        currentApp = packageOrComponent
                        return true
                    }
                }

                // 5. 쉘 실행 커맨드 집행 (순정 메커니즘)
                val command = buildShellLaunchCommand(targetDisplayId, packageOrComponent, extraKey, extraValue, reorderToFront = isWarmStart)
                Log.i(TAG, "[$name Pipeline] Executing Shell Launch: $command")
                val result = service.execCommand(command) ?: ""
                Log.i(TAG, "[$name Pipeline] Launch result: $result")

                // 6. 가상 화면 탈옥 방지 및 최종 압송 보정 (Display 0 탈옥 방지)
                if (result.contains("SecurityException") || result.contains("Permission Denial")) {
                    Log.w(TAG, "[$name Pipeline] Permission denial detected. Enforcing hard task migration chain.")
                    val retryTasks = try { service.getTaskIdsForPackage(cleanPkg) } catch (_: Exception) { intArrayOf() }
                    for (taskId in retryTasks) {
                        service.execCommand("cmd activity task move-to-display $taskId $targetDisplayId")
                        service.execCommand("cmd activity task move-to-front $taskId")
                    }
                }

                // 7. 서피스 리바인딩 검증 연계 (순정 메커니즘)
                if (isWarmStart) {
                    verifySurfaceAndFallback(this, service, targetDisplayId, cleanPkg, matchingTaskIds, packageOrComponent, extraKey, extraValue)
                }

                currentApp = packageOrComponent
                return true
            } catch (e: Exception) {
                Log.e(TAG, "[$name Pipeline] Fatal exception inside launchComponent", e)
                return false
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

        fun launchBrowser(url: String, sourceAppPackage: String? = null, allowFallback: Boolean = true) {
            val browser = BrowserResolver.resolve(this@MirrorForegroundService, url)
            val targetComponent = browser?.componentFlat ?: internalComponentName("com.castla.mirror.ui.WebBrowserActivity")
            val currentVdId = displayId

            if (currentVdId < 0) {
                currentApp = targetComponent; currentWebUrl = url; isVideoApp = (browser != null)
                if (isPrimary) {
                    activeSession = ActiveLaunchSession(if (browser != null) SessionMode.EXTERNAL_BROWSER else SessionMode.INTERNAL_WEBVIEW, targetComponent, url, sourceAppPackage)
                } else {
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            rebuild(if (requestedWidth > 0) requestedWidth else (primaryPipeline.width / 2).coerceAtLeast(320), if (requestedHeight > 0) requestedHeight else primaryPipeline.height)
                            val secondaryVdId = displayId
                            if (secondaryVdId >= 0) {
                                if (browser != null) {
                                    virtualDisplayManager?.getPrivilegedService()?.execCommand(buildExternalBrowserCommand(secondaryVdId, url, browser.componentFlat))
                                } else {
                                    launchOwnActivity("com.castla.mirror.ui.WebBrowserActivity", url)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                return
            }

            if (browser != null) {
                try {
                    virtualDisplayManager?.getPrivilegedService()?.execCommand(buildExternalBrowserCommand(currentVdId, url, browser.componentFlat))
                    if (currentApp.substringBefore('/') != browser.packageName) forceStopAppIfNeeded(currentApp)
                    currentApp = browser.componentFlat; currentWebUrl = url; isVideoApp = true
                    if (isPrimary) activeSession = ActiveLaunchSession(SessionMode.EXTERNAL_BROWSER, browser.componentFlat, url, sourceAppPackage, browser.packageName)
                    rebalanceDualDisplayBitrates()
                    return
                } catch (_: Exception) {}
            }

            if (allowFallback) {
                launchOwnActivity("com.castla.mirror.ui.WebBrowserActivity", url)
                currentApp = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"); currentWebUrl = url; isVideoApp = false
                if (isPrimary) activeSession = ActiveLaunchSession(SessionMode.INTERNAL_WEBVIEW, internalComponentName("com.castla.mirror.ui.WebBrowserActivity"), url, sourceAppPackage)
                rebalanceDualDisplayBitrates()
            }
        }

        fun launchStandard(launchTarget: String) {
            val resolvedTarget = normalizeLaunchTarget(launchTarget)
            val currentVdId = displayId

            val launched = if (currentVdId >= 0) launchComponent(resolvedTarget) else false
            if (!launched) {
                currentApp = resolvedTarget; currentWebUrl = null; isVideoApp = false
                if (!isPrimary) {
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            rebuild(if (requestedWidth > 0) requestedWidth else (primaryPipeline.width / 2).coerceAtLeast(320), if (requestedHeight > 0) requestedHeight else primaryPipeline.height)
                            launchComponent(resolvedTarget)
                        } catch (_: Exception) {}
                    }
                } else if (virtualDisplayManager?.hasVirtualDisplay() == true && currentVdId == virtualDisplayManager?.getDisplayId()) {
                    serviceScope.launch { try { rebuild(width, height, force = true); launchComponent(resolvedTarget) } catch (_: Exception) {} }
                }
            } else {
                currentApp = resolvedTarget; currentWebUrl = null; isVideoApp = false
                if (isPrimary) activeSession = ActiveLaunchSession(SessionMode.STANDARD_APP, resolvedTarget)
                rebalanceDualDisplayBitrates()
            }
        }

        fun launchWeb(activityClassName: String, url: String) {
            val currentVdId = displayId
            val targetComponent = internalComponentName(activityClassName)

            if (currentVdId < 0) {
                currentApp = targetComponent; currentWebUrl = url; isVideoApp = false
                if (isPrimary) {
                    activeSession = ActiveLaunchSession(SessionMode.INTERNAL_WEBVIEW, targetComponent, url)
                } else {
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            rebuild(if (requestedWidth > 0) requestedWidth else (primaryPipeline.width / 2).coerceAtLeast(320), if (requestedHeight > 0) requestedHeight else primaryPipeline.height)
                            launchOwnActivity(activityClassName, url)
                        } catch (_: Exception) {}
                    }
                }
                return
            }

            if (currentApp != targetComponent) forceStopAppIfNeeded(currentApp)
            launchOwnActivity(activityClassName, url)
            currentApp = targetComponent; currentWebUrl = url; isVideoApp = false
            if (isPrimary) activeSession = ActiveLaunchSession(SessionMode.INTERNAL_WEBVIEW, targetComponent, url)
            rebalanceDualDisplayBitrates()
        }

        fun launchAppFromWebLauncher(pkgName: String, componentName: String? = null) {
            if (pkgName.isBlank()) return
            if (isPrimary) lastAppLaunchTime = System.currentTimeMillis()

            val isAppInstalled = try {
                val pm = packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkgName, PackageManager.ApplicationInfoFlags.of(0)).enabled
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkgName, 0).enabled
                }
            } catch (_: PackageManager.NameNotFoundException) {
                false 
            }

            if (isAppInstalled) {
                Log.i(TAG, "[Launcher Route] App is installed. Launching Native App: $pkgName")
                launchStandard(componentName ?: pkgName)
            } else {
                val webUrl = OttCatalog.webUrlFor(pkgName)
                if (webUrl != null) {
                    Log.i(TAG, "[Launcher Route] App NOT installed. Falling back to Web URL: $webUrl")
                    launchBrowser(webUrl, pkgName)
                } else {
                    Log.w(TAG, "[Launcher Route] Neither App nor Web URL found for package: $pkgName")
                }
            }

            if (currentCodecMode == "mjpeg") {
                touchInjector?.onTouchEvent(com.castla.mirror.server.TouchEvent("down", 0.5f, 0.5f, 99))
                serviceScope.launch {
                    kotlinx.coroutines.delay(50)
                    touchInjector?.onTouchEvent(com.castla.mirror.server.TouchEvent("up", 0.5f, 0.5f, 99))
                }
            }
        }

        fun restoreContentLocked(expectedGeneration: Long, expectedDisplayId: Int) {
            if (!isCurrentVd(expectedGeneration, expectedDisplayId)) return
            val vdm = virtualDisplayManager ?: return
            if (isPrimary) startAppExitMonitor()

            val activeId = getFreshDisplayId()

            when (currentApp) {
                "HOME", "", "com.android.settings" -> {
                    currentApp = "HOME"
                    if (isPrimary) vdm.launchHomeOnDisplay()
                    else vdm.getPrivilegedService()?.launchHomeOnDisplay(activeId)
                }
                else -> {
                    if (currentWebUrl != null && !currentApp.contains("WebBrowserActivity")) {
                        val browser = BrowserResolver.resolve(this@MirrorForegroundService, currentWebUrl!!)
                        val cmd = browser?.let { buildExternalBrowserCommand(activeId, currentWebUrl!!, it.componentFlat) }
                        val launched = try {
                            if (cmd != null && isCurrentVd(vdGeneration.get(), activeId)) {
                                vdm.getPrivilegedService()?.execCommand(cmd); true
                            } else false
                        } catch (_: Exception) { false }

                        if (!launched) launchOwnActivity("com.castla.mirror.ui.WebBrowserActivity", currentWebUrl!!)
                    } else if (currentApp.contains("WebBrowserActivity")) {
                        launchOwnActivity(currentApp.substringAfter('/'), currentWebUrl ?: "https://m.youtube.com")
                    } else {
                        launchComponent(currentApp, forceColdStart = false)
                    }
                }
            }
        }

        fun restoreContent() {
            val token = currentVdToken() ?: return
            serviceScope.launch(Dispatchers.IO) {
                if (isPrimary) primaryVdOperationMutex.withLock { restoreContentLocked(token.first, token.second) }
                else restoreContentLocked(token.first, token.second)
            }
        }

        fun release(forcePhysical: Boolean = false) {
            videoEncoder?.release(); videoEncoder = null
            jpegEncoder?.release(); jpegEncoder = null
            currentEncoderSurface = null
            touchInjector?.release(); touchInjector = null
            isVideoApp = false
            
            if (!isPrimary) {
                if (displayId >= 0) {
                    cleanupDisplay(displayId)
                    virtualDisplayManager?.releaseSecondaryVirtualDisplay(displayId)
                }
                mirrorServer?.setKeyframeRequester("secondary") {}
                displayId = -1; width = 0; height = 0
                requestedWidth = 0; requestedHeight = 0
                currentApp = ""; currentWebUrl = null
                isSecondaryAppVideo = false
                rebalanceDualDisplayBitrates()
            } else {
                if (displayId >= 0 && forcePhysical) {
                    cleanupDisplay(displayId)
                    virtualDisplayManager?.releaseVirtualDisplay()
                    displayId = -1; width = 0; height = 0
                }
                mirrorServer?.setKeyframeRequester("primary") {}
                requestedWidth = 0; requestedHeight = 0
                currentApp = ""; currentWebUrl = null
                rebalanceDualDisplayBitrates()
            }
        }
    }

    private fun onCodecModeRequest(mode: String) {
        if (!CodecModeTransition.shouldApply(mode, currentCodecMode, primaryPipeline.jpegEncoder != null)) return

        currentCodecMode = CodecModeTransition.MODE_MJPEG
        Log.i(TAG, "Codec mode request: mjpeg")

        if (primaryPipeline.width == 0 || primaryPipeline.height == 0) {
            Log.i(TAG, "Viewport dimensions not yet set (0x0) — deferring pipeline build")
            return
        }

        Log.i(TAG, "Delegating to pipeline rebuild")
        serviceScope.launch {
            try {
                primaryPipeline.rebuild(primaryPipeline.width, primaryPipeline.height, force = true)
                if (secondaryPipeline.width > 0 && secondaryPipeline.height > 0) {
                    secondaryPipeline.rebuild(secondaryPipeline.width, secondaryPipeline.height)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch codec to mjpeg", e)
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
            Log.w(TAG, "Failed to grant CAPTURE_AUDIO_OUTPUT", e)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Castla")
            .setContentText("Streaming to Tesla")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop Mirroring", stopPending)
            .build()
    }

    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == ACTION_STOP) context.startService(Intent(context, MirrorForegroundService::class.java).apply { action = ACTION_STOP })
        }
    }
}
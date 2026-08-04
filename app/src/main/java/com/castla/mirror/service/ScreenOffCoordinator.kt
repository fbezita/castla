package com.castla.mirror.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.castla.mirror.compositor.DisplayTier
import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.MirrorDiagnostics
import com.castla.mirror.policy.ScreenOffEvent
import com.castla.mirror.policy.ScreenOffLoopGuard
import com.castla.mirror.policy.ScreenOffPolicy
import com.castla.mirror.policy.ScreenOffRecoveryPlanner
import com.castla.mirror.policy.ScreenOffReviveStrategy
import com.castla.mirror.policy.ScreenOffState
import com.castla.mirror.shizuku.IPrivilegedService
import com.castla.mirror.ui.ScreenOffBlackoutActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns physical screen-off state, freeze gating, blackout fallback, and VD keep-alive work.
 * MirrorForegroundService remains the lifecycle host and exposes only shared pipeline/server resources.
 */
class ScreenOffCoordinator(
    private val host: MirrorForegroundService,
) {
    companion object {
        private const val TAG = "MirrorService"
        // VirtualDevice-backed displays run in a power group independent from display 0.
        // Android 33+ therefore needs state tracking only, not VD wake/blackout recovery.
        private val SUPPORTS_VIRTUAL_DEVICE_POWER_ISOLATION = Build.VERSION.SDK_INT >= 33
    }

    val policy = ScreenOffPolicy()
    val reviveStrategy = ScreenOffReviveStrategy.select(Build.MANUFACTURER, Build.BRAND)
    val isPanelOffSupported: Boolean get() = policy.isPanelOffSupported
    val isPhysicalScreenOnForVideo: Boolean get() = physicalScreenOnForVideo
    val isPhysicalScreenOff: Boolean get() = physicalScreenOff
    val isLegacyRecoveryActive: Boolean
        get() = !SUPPORTS_VIRTUAL_DEVICE_POWER_ISOLATION && policy.isScreenOff
    private val loopGuard = ScreenOffLoopGuard()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val keyguardManager by lazy {
        host.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
    }

    private var receiver: BroadcastReceiver? = null
    private var vdKeepAliveJob: Job? = null
    private var vdKeepAliveStopJob: Job? = null
    private var appExitMonitorJob: Job? = null
    private var reviveMonitorJob: Job? = null
    private var physicalScreenStateMonitorJob: Job? = null
    private var screenOnResumeJob: Job? = null
    private var earlyVdKeepAliveJob: Job? = null
    private var reviveBurstJob: Job? = null

    @Volatile private var earlyFreezeSent = false
    @Volatile private var physicalScreenOnForVideo = false
    @Volatile private var physicalScreenOff = false
    @Volatile private var blackoutActivityRunning = false
    @Volatile private var blackoutActivityReady = false
    @Volatile private var reviveBurstInFlight = false
    @Volatile private var blackoutStartedAtMs = 0L

    fun start() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (SUPPORTS_VIRTUAL_DEVICE_POWER_ISOLATION) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> onIsolatedPowerGroupScreenOff()
                        Intent.ACTION_SCREEN_ON -> onIsolatedPowerGroupScreenOn()
                        Intent.ACTION_USER_PRESENT -> onIsolatedPowerGroupUserPresent()
                    }
                    return
                }
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        onPhoneScreenOff()
                        mainHandler.postDelayed({
                            if (keyguardManager.isKeyguardLocked) {
                                MirrorDiagnostics.log(DiagnosticEvent.KEYGUARD_LOCKED)
                            }
                        }, 500)
                    }
                    Intent.ACTION_SCREEN_ON -> onPhoneScreenOn()
                    Intent.ACTION_USER_PRESENT -> onUserPresent()
                }
            }
        }
        host.registerReceiver(receiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        if (!SUPPORTS_VIRTUAL_DEVICE_POWER_ISOLATION) {
            startPhysicalScreenStateMonitor()
        } else {
            physicalScreenOnForVideo = isPhysicalScreenReallyOn()
            physicalScreenOff = !physicalScreenOnForVideo
            host.logScreenOffInfo(
                "[SCREEN_OFF] [ISOLATED_POWER_GROUP] recoveryBypassed=true monitorBypassed=true"
            )
        }
    }

    fun stop() {
        stopPhysicalScreenStateMonitor()
        unregisterReceiver()
    }

    fun cleanup() {
        if (SUPPORTS_VIRTUAL_DEVICE_POWER_ISOLATION) {
            physicalScreenOff = false
            physicalScreenOnForVideo = false
            host.updatePanelOffState(ScreenOffState.ACTIVE)
            unregisterReceiver()
            return
        }
        if (policy.isScreenOff && reviveStrategy == ScreenOffReviveStrategy.PANEL_OFF) {
            try {
                host.pipelines.values.firstOrNull()?.controller?.setPhysicalDisplayPower(true)
            } catch (_: Exception) {}
        }
        stopScreenOffBlackout("cleanup")
        stopScreenOffReviveMonitor()
        stopVdKeepAlive()
        cancelScreenOffReviveBurst("cleanup")
        screenOnResumeJob?.cancel()
        screenOnResumeJob = null
        earlyVdKeepAliveJob?.cancel()
        earlyVdKeepAliveJob = null
        policy.reset()
        loopGuard.reset()
        host.updatePanelOffState(ScreenOffState.ACTIVE)
        unregisterReceiver()
    }

    private fun unregisterReceiver() {
        val current = receiver ?: return
        receiver = null
        val unregister = {
            try {
                host.unregisterReceiver(current)
                Log.i(TAG, "screenOffReceiver unregistered successfully.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister screenOffReceiver: ${e.message}")
            }
            Unit
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            unregister()
        } else {
            mainHandler.post(unregister)
        }
    }

    fun turnPanelOffForMirroring(): Boolean {
        if (!host.isRunning) {
            Log.w(TAG, "turnPanelOffForMirroring: service not running")
            return false
        }
        if (!host.browserConnected) {
            Log.w(TAG, "turnPanelOffForMirroring: browser not connected")
            return false
        }
        if (host.pipelines.values.none { it.controller.hasVirtualDisplay() }) {
            Log.w(TAG, "turnPanelOffForMirroring: no active virtual display")
            return false
        }
        if (SUPPORTS_VIRTUAL_DEVICE_POWER_ISOLATION) {
            if (physicalScreenOff) return true
            host.logScreenOffInfo("turnPanelOffForMirroring() requested via web/user button isolated=true")
            val success = host.pipelines.values.firstOrNull()
                ?.controller
                ?.setPhysicalDisplayPower(false) == true
            if (success) onIsolatedPowerGroupScreenOff()
            return success
        }
        if (policy.isScreenOff) return true
        host.logScreenOffInfo("turnPanelOffForMirroring() requested via web/user button")
        onPhoneScreenOff()
        return policy.isScreenOff
    }

    fun restorePhysicalPanel() {
        if (SUPPORTS_VIRTUAL_DEVICE_POWER_ISOLATION) {
            if (!physicalScreenOff) return
            host.logScreenOffInfo("restorePhysicalPanel() requested via web/user button isolated=true")
            val success = host.pipelines.values.firstOrNull()
                ?.controller
                ?.setPhysicalDisplayPower(true) == true
            if (success) onIsolatedPowerGroupScreenOn()
            return
        }
        if (!policy.isScreenOff) return
        host.logScreenOffInfo("restorePhysicalPanel() requested via web/user button")
        onUserRequestRestoreFromBlackout()
    }

    fun markKeepAlive() {
        if (!SUPPORTS_VIRTUAL_DEVICE_POWER_ISOLATION) {
            loopGuard.markKeepAlive(android.os.SystemClock.elapsedRealtime())
        }
    }
    private fun logScreenState(event: String) {
        val keyguardLocked = keyguardManager.isKeyguardLocked
        val deviceLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            keyguardManager.isDeviceLocked
        } else {
            keyguardLocked
        }
        val firstVdId = host.pipelines.values.firstOrNull()?.controller?.getDisplayId() ?: -1
        Log.i(
            TAG,
            "[BUILD:screen-off-v3] $event -> state=${policy.state}, keyguardLocked=$keyguardLocked, " +
                "deviceLocked=$deviceLocked, browserConnected=${host.browserConnected}, vdId=$firstVdId, " +
                "panelOffSupported=${policy.isPanelOffSupported}"
        )
    }

    private fun onIsolatedPowerGroupScreenOff() {
        screenOnResumeJob?.cancel()
        screenOnResumeJob = null
        physicalScreenOnForVideo = false
        physicalScreenOff = true
        MirrorDiagnostics.log(DiagnosticEvent.SCREEN_OFF)
        host.updatePanelOffState(ScreenOffState.BLACKOUT_ACTIVE)
        host.cancelPendingBrowserDisconnect("isolated_power_group_screen_off")
        host.broadcastWebDiagnostics("isolated_power_group_screen_off")
        host.logScreenOffInfo(
            "[SCREEN_OFF] [ISOLATED_POWER_GROUP] event=SCREEN_OFF tracked=true " +
                "legacyState=${policy.state} videoFrozen=false recovery=false"
        )
    }

    private fun onIsolatedPowerGroupScreenOn() {
        physicalScreenOnForVideo = true
        physicalScreenOff = false
        MirrorDiagnostics.log(DiagnosticEvent.SCREEN_ON)
        host.updatePanelOffState(ScreenOffState.ACTIVE)
        host.cancelPendingBrowserDisconnect("isolated_power_group_screen_on")
        host.broadcastWebDiagnostics("isolated_power_group_screen_on")
        host.logScreenOffInfo(
            "[SCREEN_OFF] [ISOLATED_POWER_GROUP] event=SCREEN_ON tracked=true " +
                "legacyState=${policy.state} videoFrozen=false recovery=false"
        )
    }

    private fun onIsolatedPowerGroupUserPresent() {
        MirrorDiagnostics.log(DiagnosticEvent.KEYGUARD_UNLOCKED)
        if (isPhysicalScreenReallyOn() && physicalScreenOff) {
            onIsolatedPowerGroupScreenOn()
        }
    }
    private fun onPhoneScreenOff() {
        screenOnResumeJob?.cancel()
        screenOnResumeJob = null
        MirrorDiagnostics.log(DiagnosticEvent.SCREEN_OFF)
        logScreenState("onPhoneScreenOff() called")
        physicalScreenOnForVideo = false
        physicalScreenOff = true
        host.broadcastVideoFreeze("freezeVideo", "screen_off_event")
        host.broadcastWebDiagnostics("screen_off_event")

        val source = loopGuard.classifyScreenOff(android.os.SystemClock.elapsedRealtime())
        logScreenOffLoop("SCREEN_OFF", source)
        startEarlyVdKeepAliveBurst("screen_off_event")
        if (source == ScreenOffLoopGuard.EventSource.WAKE_PULSE_RELATED) {
            return
        }

        val currentState = policy.state
        if (currentState == ScreenOffState.ACTIVE) {
            handleFsmTransition(ScreenOffEvent.SCREEN_OFF)
        } else {
            if (reviveStrategy == ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE &&
                (currentState == ScreenOffState.BLACKOUT_ACTIVE || currentState == ScreenOffState.BLACKOUT_PENDING) &&
                source == ScreenOffLoopGuard.EventSource.USER
            ) {
                host.logScreenOffInfo("[SCREEN_OFF] [USER_RESTORE] event=SCREEN_OFF state=$currentState phase=request reason=power_button_click")
                onUserRequestRestoreFromBlackout()
            } else {
                host.logScreenOffInfo("[SCREEN_OFF] [SCREEN_OFF_LOOP] event=SCREEN_OFF source=${source.name.lowercase()} state=$currentState ignored=already_off")
            }
        }
    }

    private fun handleFsmTransition(event: ScreenOffEvent) {
        val oldState = policy.state
        val newState = policy.transition(event)
        host.updatePanelOffState(newState)

        if (oldState != newState) {
            host.logScreenOffInfo("[SCREEN_OFF] [FSM] transition: $oldState -> $newState on event $event")
            applyStateActions(newState)

            if (newState == ScreenOffState.ACTIVE && oldState != ScreenOffState.ACTIVE) {
                executePhysicalDisplayWakeupAction()
            }
        } else {
            host.logScreenOffInfo("[SCREEN_OFF] [FSM] ignored event $event in state $oldState")
        }
    }

    private fun applyStateActions(state: ScreenOffState) {
        when (state) {
            ScreenOffState.ACTIVE -> {
                stopScreenOffBlackout("fsm_active")
                cancelScreenOffReviveBurst("fsm_active")
                stopScreenOffReviveMonitor()
                host.cancelPendingBrowserDisconnect("fsm_active")

                if (ScreenOffRecoveryPlanner.shouldKeepVdKeepAliveRunningAfterScreenOn(reviveStrategy)) {
                    scheduleVdKeepAliveStop()
                } else {
                    stopVdKeepAlive()
                }

                if (ScreenOffRecoveryPlanner.shouldRequestResumeBurst(reviveStrategy)) {
                    requestScreenOnResumeBurst("fsm_active")
                }
            }
            ScreenOffState.BLACKOUT_PENDING -> {
                loopGuard.markBlackoutStart(android.os.SystemClock.elapsedRealtime())
                host.powerLockManager.acquireWakeLocks()
                preparePipelinesForScreenOffRevive()

                if (reviveStrategy == ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE) {
                    startScreenOffBlackout("fsm_blackout_pending")
                    startVdKeepAlive()
                    startScreenOffReviveMonitor()
                    requestScreenOffReviveBurst("fsm_blackout_pending")
                } else {
                    startVdKeepAlive()
                    executePhysicalPanelOffAction()
                }
            }
            ScreenOffState.BLACKOUT_ACTIVE -> {
                // Stable state.
            }
        }
    }

    private fun executePhysicalPanelOffAction() {
        val anyController = host.pipelines.values.firstOrNull()?.controller
        if (anyController?.isBound() == true) {
            logPhysicalWakeBlocked("set_physical_display_power_false")
            val suppressReentryUntil = loopGuard.markPowerBurst(android.os.SystemClock.elapsedRealtime())
            host.logScreenOffInfo("[SCREEN_OFF] [POWER_BURST] start")

            host.serviceScope.launch {
                var success = false
                for (i in 1..10) {
                    try { success = anyController.setPhysicalDisplayPower(false) } catch (_: Exception) {}
                    kotlinx.coroutines.delay(100)
                }
                host.logScreenOffInfo("[SCREEN_OFF] [POWER_BURST] end suppressReentryUntil=$suppressReentryUntil success=$success")
                host.serviceScope.launch(Dispatchers.Main) {
                    if (success) {
                        handleFsmTransition(ScreenOffEvent.ON_BLACKOUT_READY)
                    } else {
                        policy.markPanelOffFailed()
                        startScreenOffBlackout("panel_off_fallback")
                        startScreenOffReviveMonitor()
                    }
                }
            }
        } else {
            Log.w(TAG, "Panel-off requested but VirtualDisplay binder architecture not stabilized yet.")
            policy.markPanelOffFailed()
            startScreenOffBlackout("panel_off_unbound_fallback")
            startScreenOffReviveMonitor()
        }
    }

    private fun executePhysicalDisplayWakeupAction() {
        val service = host.pipelines.values.firstOrNull()?.controller?.getPrivilegedService()
        host.serviceScope.launch {
            try {
                repeat(2) { attempt ->
                    service?.wakeUpDisplay(0)
                    host.logScreenOffInfo("[SCREEN_OFF] [USER_RESTORE] phase=request wake_display=0 attempt=$attempt success=requested")
                    kotlinx.coroutines.delay(150L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to execute physical wake-up pulse chain", e)
            }
        }
    }

    private fun preparePipelinesForScreenOffRevive() {
        host.pipelines.values.forEach { pipeline ->
            if (pipeline.displayTier != DisplayTier.ACTIVE && pipeline.displayTier != DisplayTier.VISIBLE) return@forEach
            if (!pipeline.controller.hasVirtualDisplay()) return@forEach

            pipeline.lastFrameRenderedTime = 0L
            pipeline.firstFrameMetadataSent = false
            host.logScreenOffInfo("[SCREEN_OFF] [REVIVE_PREP] pane=${pipeline.name} displayId=${pipeline.displayId} tier=${pipeline.displayTier} size=${pipeline.width}x${pipeline.height}")
        }
    }

    private fun isPhysicalScreenReallyOn(): Boolean {
        val dm = host.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val defaultDisplay = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return false
        return defaultDisplay.state == android.view.Display.STATE_ON
    }

    private fun onPhoneScreenOn() {
        MirrorDiagnostics.log(DiagnosticEvent.SCREEN_ON)
        logScreenState("onPhoneScreenOn() called")

        if (!isPhysicalScreenReallyOn()) {
            host.logScreenOffInfo("[SCREEN_OFF] [SCREEN_ON] waiting for physical display to reach STATE_ON")
            screenOnResumeJob?.cancel()
            screenOnResumeJob = host.serviceScope.launch {
                repeat(20) { attempt ->
                    delay(100L)
                    if (isPhysicalScreenReallyOn()) {
                        Log.i(TAG, "[SCREEN_OFF] [SCREEN_ON] physical display became STATE_ON attempt=$attempt")
                        onPhoneScreenOn()
                        return@launch
                    }
                }
                Log.w(TAG, "[SCREEN_OFF] [SCREEN_ON] physical display did not reach STATE_ON within 2s")
            }
            return
        }


        physicalScreenOnForVideo = true
        physicalScreenOff = false
        val source = loopGuard.classifyScreenOn(android.os.SystemClock.elapsedRealtime())
        logScreenOffLoop("SCREEN_ON", source)
        if (source == ScreenOffLoopGuard.EventSource.WAKE_PULSE_RELATED) {
            // A real STATE_ON event must be allowed to reach the user. The
            // keep-alive/blackout classifier can label it wake-pulse-related, but
            // reasserting panel-off here would swallow a physical power-button wake.
            Log.i(TAG, "[SCREEN_OFF] [SCREEN_ON] wake-pulse-related classification accepted without panel re-off")
            screenOnResumeJob?.cancel()
            screenOnResumeJob = host.serviceScope.launch {
                delay(500L)
                if (!isPhysicalScreenReallyOn()) return@launch
                earlyFreezeSent = false
                host.broadcastVideoFreeze("resumeVideo", "screen_on_stable_wake_pulse_related")
            }
            // A wake-pulse-related wake pulse is not a user restore. Keep the
            // blackout state so VD keep-alive/revive remains active, while
            // avoiding panel re-off so a real double-tap wake can remain on.
            // USER_PRESENT or a later user-classified SCREEN_ON will transition
            // the FSM to ACTIVE.
            host.logScreenOffInfo("[SCREEN_OFF] [FSM] wake-pulse-related SCREEN_ON accepted: state kept=${policy.state}")
            return
        }

        screenOnResumeJob?.cancel()
        screenOnResumeJob = host.serviceScope.launch {
            delay(500L)
            if (!isPhysicalScreenReallyOn() || policy.isScreenOff) return@launch
            earlyFreezeSent = false
            host.broadcastVideoFreeze("resumeVideo", "screen_on_stable")
            host.broadcastWebDiagnostics("screen_on_stable")
        }
        handleFsmTransition(ScreenOffEvent.SCREEN_ON)
    }

    private fun onUserPresent() {
        MirrorDiagnostics.log(DiagnosticEvent.KEYGUARD_UNLOCKED)
        logScreenState("onUserPresent() called")

        if (!policy.isScreenOff) return
        if (!isPhysicalScreenReallyOn()) {
            host.logScreenOffInfo("[SCREEN_OFF] [USER_RESTORE] event=USER_PRESENT ignored=physical_display_not_on")
            return
        }

        host.logScreenOffInfo("[SCREEN_OFF] [USER_RESTORE] event=USER_PRESENT phase=request reason=keyguard_unlocked")
        handleFsmTransition(ScreenOffEvent.USER_PRESENT)
    }

    fun startVdKeepAlive() {
        cancelPendingVdKeepAliveStop()
        stopVdKeepAlive()
        vdKeepAliveJob = host.serviceScope.launch {
            Log.i(TAG, "[KeepAlive] Symmetrical VD keep-awake pulse generator active.")
            while (true) {
                for (pipeline in host.pipelines.values) {
                    if (pipeline.controller.hasVirtualDisplay()) {
                        pipeline.controller.keepDisplayAwake()
                    }
                }
                kotlinx.coroutines.delay(
                    ScreenOffRecoveryPlanner.vdKeepAliveIntervalMs(
                        isScreenOff = policy.isScreenOff,
                        blackoutActivityReady = blackoutActivityReady
                    )
                )
            }
        }
        startAppExitMonitor()
    }

    fun stopVdKeepAlive() {
        cancelPendingVdKeepAliveStop()
        vdKeepAliveJob?.cancel(); vdKeepAliveJob = null
        stopAppExitMonitor()
    }

    private fun scheduleVdKeepAliveStop() {
        val delayMs = ScreenOffRecoveryPlanner.keepAliveStopDelayMs(reviveStrategy)
        if (delayMs <= 0L) {
            stopVdKeepAlive()
            return
        }
        cancelPendingVdKeepAliveStop()
        vdKeepAliveStopJob = host.serviceScope.launch {
            host.logScreenOffInfo("[SCREEN_OFF] [RESUME] reason=stop_keep_alive deferredStopMs=$delayMs")
            kotlinx.coroutines.delay(delayMs)
            vdKeepAliveJob?.cancel()
            vdKeepAliveJob = null
            stopAppExitMonitor()
            vdKeepAliveStopJob = null
            host.logScreenOffInfo("[SCREEN_OFF] [RESUME] reason=stop_keep_alive deferredStopComplete=true")
        }
    }

    private fun cancelPendingVdKeepAliveStop() {
        vdKeepAliveStopJob?.cancel()
        vdKeepAliveStopJob = null
    }

    private fun startPhysicalScreenStateMonitor() {
        stopPhysicalScreenStateMonitor()
        physicalScreenStateMonitorJob = host.serviceScope.launch {
            var lastInteractive = readPhysicalDisplayInteractive()
            while (isActive) {
                val interactive = readPhysicalDisplayInteractive()
                if (lastInteractive && !interactive && !earlyFreezeSent) {
                    earlyFreezeSent = true
                    Log.i(TAG, "[SCREEN_OFF] [EARLY_DETECT] physical display became non-interactive")
                    host.broadcastVideoFreeze("freezeVideo", "physical_display_state")
                    host.broadcastWebDiagnostics("physical_display_state")
                    startEarlyVdKeepAliveBurst("physical_display_state")
                } else if (!lastInteractive && interactive) {
                    Log.i(TAG, "[SCREEN_OFF] [EARLY_DETECT] interactive bounce ignored until SCREEN_ON")
                }
                lastInteractive = interactive
                delay(32L)
            }
        }
    }

    private fun stopPhysicalScreenStateMonitor() {
        physicalScreenStateMonitorJob?.cancel()
        physicalScreenStateMonitorJob = null
    }

    private fun startEarlyVdKeepAliveBurst(reason: String) {
        earlyVdKeepAliveJob?.cancel()
        earlyVdKeepAliveJob = host.serviceScope.launch(Dispatchers.IO) {
            try {
                repeat(12) { attempt ->
                    host.pipelines.values.forEach { pipeline ->
                        val displayId = pipeline.controller.getDisplayId()
                        val service = pipeline.controller.getPrivilegedService()
                        if (pipeline.controller.hasVirtualDisplay() && displayId > 0 && service != null) {
                            try {
                                service.keepVirtualDisplayAlive(displayId)
                                host.logScreenOffInfo("[SCREEN_OFF] [EARLY_VD_KEEPALIVE] reason=$reason displayId=$displayId attempt=$attempt")
                            } catch (e: Exception) {
                                Log.w(TAG, "Early VD keep-alive failed displayId=$displayId attempt=$attempt", e)
                            }
                        }
                    }
                    kotlinx.coroutines.delay(80L)
                }
            } finally {
                earlyVdKeepAliveJob = null
            }
        }
    }
    private fun readPhysicalDisplayInteractive(): Boolean {
        val powerManager = host.getSystemService(Context.POWER_SERVICE) as PowerManager
        val displayManager = host.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displayOn = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state != android.view.Display.STATE_OFF
        return powerManager.isInteractive && displayOn
    }

    private fun startScreenOffReviveMonitor() {
        stopScreenOffReviveMonitor()
        reviveMonitorJob = host.serviceScope.launch {
            kotlinx.coroutines.delay(4000L)
            if (!policy.isScreenOff) return@launch
            host.pipelines.values.forEach { pipeline ->
                if (!pipeline.controller.hasVirtualDisplay()) return@forEach
                if (pipeline.displayTier != DisplayTier.ACTIVE && pipeline.displayTier != DisplayTier.VISIBLE) return@forEach

                val frameMissing = pipeline.lastFrameRenderedTime == 0L
                if (!frameMissing) return@forEach

                host.logScreenOffWarn("[SCREEN_OFF] [REVIVE_REBUILD] monitor pane=${pipeline.name} displayId=${pipeline.displayId} tier=${pipeline.displayTier} firstFrameMissing=true")
                requestScreenOffRebuild(pipeline, "monitor_first_frame_missing")
            }
        }
    }

    private fun stopScreenOffReviveMonitor() {
        reviveMonitorJob?.cancel()
        reviveMonitorJob = null
    }

    private fun startAppExitMonitor() {
        stopAppExitMonitor()
        appExitMonitorJob = host.serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(
                    ScreenOffRecoveryPlanner.appExitMonitorIntervalMs(policy.isScreenOff)
                )
                host.pipelines.values.forEach { pipeline ->
                    val displayId = pipeline.displayId
                    if (displayId < 0) return@forEach
                    if (pipeline.currentApp.isBlank() ||
                        pipeline.currentApp == "HOME" ||
                        pipeline.currentApp == "com.android.settings") return@forEach
                    val service = pipeline.controller.getPrivilegedService() ?: return@forEach
                    try {
                        val activeTasks = service.getRunningTasksOnDisplay(displayId) ?: emptyList()
                        if (activeTasks.firstOrNull()?.contains("VirtualDisplayHomeActivity") == true) {
                            Log.i(TAG, "[ExitMonitor] Home activity detected at the top of pane (${pipeline.name}). BroadCasting APP_STREAM_STOPPED.")
                            pipeline.currentApp = "HOME"
                            host.mirrorServer?.broadcastControlMessage("{\"type\":\"APP_STREAM_STOPPED\", \"pane\":\"${pipeline.name}\"}")
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun stopAppExitMonitor() { appExitMonitorJob?.cancel(); appExitMonitorJob = null }

    private fun logScreenOffLoop(event: String, source: ScreenOffLoopGuard.EventSource) {
        host.logScreenOffInfo("[SCREEN_OFF] [SCREEN_OFF_LOOP] event=$event source=${source.name.lowercase()} state=${policy.state}")
    }

    private fun logPhysicalWakeBlocked(command: String) {
        host.logScreenOffInfo("[SCREEN_OFF] [PHYSICAL_WAKE_BLOCKED] command=$command")
    }

    fun onBlackoutActivityReady() {
        if (SUPPORTS_VIRTUAL_DEVICE_POWER_ISOLATION) return
        mainHandler.post {
            blackoutActivityReady = true
            host.logScreenOffInfo("[SCREEN_OFF] [BLACKOUT] blackout_activity_ready")
            handleFsmTransition(ScreenOffEvent.ON_BLACKOUT_READY)
            requestScreenOffReviveBurst("blackout_ready")
        }
    }

    fun onUserRequestRestoreFromBlackout() {
        if (SUPPORTS_VIRTUAL_DEVICE_POWER_ISOLATION) {
            restorePhysicalPanel()
            return
        }
        mainHandler.post {
            host.logScreenOffInfo("[SCREEN_OFF] [USER_RESTORE] reason=blackout_activity_double_tap")
            handleFsmTransition(ScreenOffEvent.RESTORE_REQUEST)
        }
    }

    private fun startScreenOffBlackout(reason: String) {
        if (blackoutActivityRunning) return
        blackoutActivityRunning = true
        blackoutActivityReady = false
        blackoutStartedAtMs = android.os.SystemClock.elapsedRealtime()
        loopGuard.markBlackoutStart(blackoutStartedAtMs)
        try {
            val intent = Intent(host, ScreenOffBlackoutActivity::class.java).apply {
                action = ScreenOffBlackoutActivity.ACTION_START
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            host.startActivity(intent)
            host.logScreenOffInfo("[SCREEN_OFF] [BLACKOUT] action=start reason=$reason")
        } catch (e: Exception) {
            blackoutActivityRunning = false
            Log.w(TAG, "Failed to start screen-off blackout activity", e)
        }
    }

    private fun stopScreenOffBlackout(reason: String) {
        if (!blackoutActivityRunning) return
        blackoutActivityRunning = false
        blackoutActivityReady = false
        blackoutStartedAtMs = 0L
        try {
            val intent = Intent(host, ScreenOffBlackoutActivity::class.java).apply {
                action = ScreenOffBlackoutActivity.ACTION_STOP
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            host.startActivity(intent)
            host.logScreenOffInfo("[SCREEN_OFF] [BLACKOUT] action=stop reason=$reason")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop screen-off blackout activity", e)
        }
    }

    private fun reassertPhysicalPanelOff(reason: String) {
        if (policy.state != ScreenOffState.BLACKOUT_ACTIVE &&
            policy.state != ScreenOffState.BLACKOUT_PENDING) return
        val controller = host.pipelines.values.firstOrNull()?.controller ?: return
        host.serviceScope.launch {
            val suppressReentryUntil = loopGuard.markPowerBurst(android.os.SystemClock.elapsedRealtime())
            host.logScreenOffInfo("[SCREEN_OFF] [POWER_BURST] reassert reason=$reason start")
            var success = false
            repeat(3) {
                try { success = controller.setPhysicalDisplayPower(false) } catch (_: Exception) {}
                kotlinx.coroutines.delay(75)
            }
            host.logScreenOffInfo("[SCREEN_OFF] [POWER_BURST] reassert reason=$reason end suppressReentryUntil=$suppressReentryUntil success=$success")
        }
    }

    private fun requestScreenOffReviveBurst(reason: String) {
        if (reviveStrategy != ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE) return
        if (!ScreenOffRecoveryPlanner.shouldPulseVirtualDisplayWake(reviveStrategy)) {
            host.logScreenOffInfo("[SCREEN_OFF] [REVIVE] reason=$reason skipped=strategy")
            return
        }
        if (reviveBurstInFlight) {
            host.logScreenOffInfo("[SCREEN_OFF] [REVIVE] reason=$reason skipped=in_flight")
            return
        }
        reviveBurstInFlight = true
        reviveBurstJob?.cancel()
        reviveBurstJob = host.serviceScope.launch {
            try {
                val startMs = android.os.SystemClock.elapsedRealtime()
                if (reason == "fsm_blackout_pending") {
                    kotlinx.coroutines.delay(250L)
                }
                val elapsed = android.os.SystemClock.elapsedRealtime() - startMs
                host.logScreenOffInfo("[SCREEN_OFF] [REVIVE] reason=$reason waitCompleteReady=$blackoutActivityReady elapsed=${elapsed}ms")

                repeat(1) { attempt ->
                    host.pipelines.values.forEach { pipeline ->
                        val controller = pipeline.controller
                        val displayId = controller.getDisplayId()
                        if (!controller.hasVirtualDisplay() || displayId < 0) return@forEach
                        pulseWakeDisplayForScreenOffRevive(
                            controller.getPrivilegedService(),
                            displayId,
                            "screen_off_revive_$reason#$attempt"
                        )
                        if (host.currentCodecMode != "mjpeg") {
                            try { pipeline.videoEncoder?.requestKeyFrame() } catch (_: Exception) {}
                        }
                    }
                    kotlinx.coroutines.delay(250L)
                }
            } finally {
                reviveBurstInFlight = false
                reviveBurstJob = null
            }
        }
    }

    private fun cancelScreenOffReviveBurst(reason: String) {
        val job = reviveBurstJob ?: return
        if (!job.isActive) return
        job.cancel()
        reviveBurstJob = null
        reviveBurstInFlight = false
        host.logScreenOffInfo("[SCREEN_OFF] [REVIVE] reason=$reason cancelled=true")
    }

    private fun requestScreenOnResumeBurst(reason: String) {
        host.serviceScope.launch {
            repeat(2) { attempt ->
                host.pipelines.values.forEach { pipeline ->
                    if (pipeline.displayTier != DisplayTier.ACTIVE && pipeline.displayTier != DisplayTier.VISIBLE) return@forEach
                    if (!pipeline.controller.hasVirtualDisplay()) return@forEach

                    if (host.currentCodecMode != "mjpeg") {
                        try { pipeline.videoEncoder?.requestKeyFrame() } catch (_: Exception) {}
                    }
                }
                kotlinx.coroutines.delay(180L)
            }
            host.logScreenOffInfo("[SCREEN_OFF] [RESUME] reason=$reason pulses=2")
        }
    }

    internal suspend fun requestScreenOffRebuild(pipeline: MirroringPipeline, reason: String) {
        val targetW = if (pipeline.requestedWidth > 0) pipeline.requestedWidth else pipeline.width.coerceAtLeast(384)
        val targetH = if (pipeline.requestedHeight > 0) pipeline.requestedHeight else pipeline.height.coerceAtLeast(672)
        host.logScreenOffInfo("[SCREEN_OFF] [REVIVE_REBUILD] pane=${pipeline.name} reason=$reason target=${targetW}x${targetH} currentDisplayId=${pipeline.displayId}")
        val rebuildDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        pipeline.requestRebuild(
            reason = "screen_off_revive_$reason",
            priority = MirrorForegroundService.RebuildPriority.HIGH,
            newWidth = targetW,
            newHeight = targetH,
            force = true,
            onComplete = rebuildDeferred,
        )
        withTimeoutOrNull(4000L) { rebuildDeferred.await() }
        pulseWakeDisplayForScreenOffRevive(
            pipeline.controller.getPrivilegedService(),
            pipeline.controller.getDisplayId(),
            "screen_off_rebuild_$reason"
        )
    }

    private fun pulseWakeDisplayForScreenOffRevive(
        service: IPrivilegedService?,
        displayId: Int,
        reason: String,
    ) {
        if (service == null || displayId < 0) return
        if (host.shouldThrottleRecoveryAction(displayId, reason)) return
        try {
            if (ScreenOffRecoveryPlanner.shouldUseDirectWakeForRevive(policy.isScreenOff)) {
                loopGuard.markKeepAlive(android.os.SystemClock.elapsedRealtime())
                service.wakeUpDisplay(displayId)
                host.logScreenOffInfo("[SCREEN_OFF] [WAKE_REVIVE] reason=$reason displayId=$displayId direct=true")
            } else {
                loopGuard.markKeepAlive(android.os.SystemClock.elapsedRealtime())
                service.keepVirtualDisplayAlive(displayId)
                host.logScreenOffInfo("[SCREEN_OFF] [VD_KEEPALIVE] reason=$reason displayId=$displayId source=revive")
            }
        } catch (e: Exception) {
            Log.w(TAG, "pulseWakeDisplayForScreenOffRevive failed reason=$reason displayId=$displayId", e)
        }
    }
}

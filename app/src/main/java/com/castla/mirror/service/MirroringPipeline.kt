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
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.castla.mirror.BuildConfig
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MirroringPipeline(private val host: MirrorForegroundService, val name: String, val displayName: String) {
    companion object { private const val TAG = "MirrorForegroundService" }
        val controller = VirtualDisplayController(displayName)
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        private val releasing = java.util.concurrent.atomic.AtomicBoolean(false)

        var width = 0; var height = 0; var displayId = -1
        val vdGeneration = java.util.concurrent.atomic.AtomicLong(0)

        // Timestamp of the last processed keyframe request to prevent coroutine and binder flood
        @Volatile var lastKeyframeRequestTime = 0L
        @Volatile var firstFrameMetadataSent = false
        // Backup fields to remember the last valid viewport dimensions for self-healing recovery
        @Volatile var lastValidWidth: Int = 384
        @Volatile var lastValidHeight: Int = 672

        // State guards to prevent concurrent self-healing re-entry which triggers duplicate am start shell command floods
        @Volatile var isSelfHealingInProgress = false
        @Volatile var activeFallbackJob: kotlinx.coroutines.Job? = null
        @Volatile var bootstrapNudgeJob: kotlinx.coroutines.Job? = null
        @Volatile var bootstrapNudgeAttempts = 0
        @Volatile var lastFrameRenderedTime = 0L
        @Volatile var lastMoveRejectLoggedAt = 0L

        private val encoderSession = java.util.concurrent.atomic.AtomicLong(0)

        internal val hostService: MirrorForegroundService get() = host
        private val encoderLifecycle = EncoderLifecycleCoordinator(this)

        var videoEncoder: VideoEncoder? = null; var jpegEncoder: JpegEncoder? = null; var currentEncoderSurface: Surface? = null
        var pipelineState = MirrorForegroundService.PipelineState.IDLE; var pendingRebuildRequest: MirrorForegroundService.RebuildRequest? = null
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
        private val touchFocusRecoveryCoordinator = TouchFocusRecoveryCoordinator()

        private fun beginStreamGeneration(displayId: Int, width: Int, height: Int): Long =
            host.mirrorServer?.beginStreamGeneration(name, displayId, width, height)?.toLong() ?: 0L
        internal fun nextEncoderSessionId(): Long = encoderSession.incrementAndGet()

        internal fun isCurrentEncoderSession(sessionId: Long): Boolean = encoderSession.get() == sessionId

        internal fun publishEncodedFrame(
            data: ByteArray,
            key: Boolean,
            sessionId: Long,
            frameWidth: Int,
            frameHeight: Int,
            rebuildStartedAtMs: Long,
        ) {
            lastFrameRenderedTime = System.currentTimeMillis()
            if (!firstFrameMetadataSent) {
                firstFrameMetadataSent = true
                cancelBootstrapNudge("first_frame_publish")
                host.logStreamBootstrapInfo(
                    "pane=$name session=$sessionId phase=first_frame_publish codec=${host.currentCodecMode} displayId=$displayId " +
                        "generation=${host.mirrorServer?.getCurrentStreamGeneration(name) ?: 0} bytes=${data.size} key=$key " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
                )
                host.mirrorServer?.markFirstFrameReady(name, displayId, frameWidth, frameHeight)
            }
            host.mirrorServer?.broadcastFrame(data, key, name)
        }

        internal fun requestThrottledKeyframe(force: Boolean, reason: String) {
            val now = System.currentTimeMillis()
            if (!force && now - lastKeyframeRequestTime < 1000L) return
            lastKeyframeRequestTime = now
            host.serviceScope.launch {
                try {
                    if (displayId >= 0) host.wakeDisplayForRecovery(controller.getPrivilegedService(), displayId, reason)
                    if (host.currentCodecMode != "mjpeg") host.requestKeyFrameForRecovery(this@MirroringPipeline, reason)
                } catch (e: Exception) {
                    Log.w(TAG, "[$name Pipeline] Failed to recover graphics for keyframe request", e)
                }
            }
        }
        fun isEncoderRunning(): Boolean {
            return if (host.currentCodecMode == "mjpeg") jpegEncoder != null else videoEncoder != null
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
            bootstrapNudgeAttempts = 0
            lastKeyframeRequestTime = 0L
            activeFallbackJob?.cancel()
            activeFallbackJob = null
            bootstrapNudgeJob?.cancel()
            bootstrapNudgeJob = null
            host.mirrorServer?.clearCachedSpsPps(name)
            host.mirrorServer?.pauseStream(name, displayId, width, height)
            Log.i(
                TAG,
                "[$name Pipeline] Marked fresh launch preparation required. reason=$reason displayId=$displayId currentApp=$currentApp"
            )
        }

        private fun cancelBootstrapNudge(reason: String) {
            bootstrapNudgeJob?.cancel()
            bootstrapNudgeJob = null
            host.logLaunchRecoveryInfo("bootstrap_nudge_cancel pane=$name reason=$reason displayId=$displayId")
        }

        private fun scheduleBootstrapNudge(
            sessionId: Long,
            targetDisplayId: Int,
        ) {
            bootstrapNudgeJob?.cancel()
            bootstrapNudgeJob = host.serviceScope.launch {
                val scheduledAtMs = android.os.SystemClock.elapsedRealtime()
                host.logLaunchRecoveryInfo(
                    "bootstrap_nudge_scheduled pane=$name session=$sessionId displayId=$targetDisplayId delayMs=${LaunchRecoveryPolicy.INITIAL_BOOTSTRAP_NUDGE_DELAY_MS}"
                )
                kotlinx.coroutines.delay(LaunchRecoveryPolicy.INITIAL_BOOTSTRAP_NUDGE_DELAY_MS)
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - scheduledAtMs
                val shouldTrigger = LaunchRecoveryPolicy.shouldTriggerInitialBootstrapNudge(
                    elapsedMs = elapsedMs,
                    firstFramePublished = firstFrameMetadataSent,
                    nudgeAttempts = bootstrapNudgeAttempts,
                )
                if (!shouldTrigger) {
                    host.logLaunchRecoveryInfo(
                        "bootstrap_nudge_noop pane=$name session=$sessionId displayId=$targetDisplayId elapsedMs=$elapsedMs " +
                            "firstFramePublished=$firstFrameMetadataSent attempts=$bootstrapNudgeAttempts"
                    )
                    return@launch
                }
                if (!host.browserConnected ||
                    (displayTier != DisplayTier.ACTIVE && displayTier != DisplayTier.VISIBLE) ||
                    displayId < 0 ||
                    displayId != targetDisplayId
                ) {
                    host.logLaunchRecoveryInfo(
                        "bootstrap_nudge_skipped pane=$name session=$sessionId targetDisplayId=$targetDisplayId " +
                            "currentDisplayId=$displayId host.browserConnected=${host.browserConnected} tier=$displayTier elapsedMs=$elapsedMs"
                    )
                    return@launch
                }
                bootstrapNudgeAttempts += 1
                host.logLaunchRecoveryInfo(
                    "bootstrap_nudge_fire pane=$name session=$sessionId displayId=$displayId elapsedMs=$elapsedMs"
                )
                val target = recoveryLaunchTarget()
                if (LaunchRecoveryPolicy.shouldAttemptBootstrapRealign(
                        currentApp = target,
                        displayId = displayId,
                        browserConnected = host.browserConnected,
                    )
                ) {
                    host.logLaunchRecoveryInfo(
                        "bootstrap_realign_begin pane=$name session=$sessionId displayId=$displayId target=$target"
                    )
                    val launched = launchComponent(
                        target,
                        forceColdStart = false,
                        forceTaskRealign = true,
                    )
                    host.logLaunchRecoveryInfo(
                        "bootstrap_realign_done pane=$name session=$sessionId displayId=$displayId target=$target launched=$launched"
                    )
                } else {
                    host.logLaunchRecoveryInfo(
                        "bootstrap_realign_skipped pane=$name session=$sessionId displayId=$displayId target=$target"
                    )
                }
            }
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

            host.serviceScope.launch {
                var downAccepted = false
                var upAccepted = false
                try {
                    Log.i(
                        TAG,
                        "[FocusTrace] inject_focus_nudge pane=$name displayId=$activeId reason=$reason x=${"%.1f".format(java.util.Locale.US, tapX)} y=${"%.1f".format(java.util.Locale.US, tapY)}"
                    )
                    host.wakeDisplayForRecovery(controller.getPrivilegedService(), activeId, "launch_soft_recovery")
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

        fun shouldMaterializeVirtualDisplay(): Boolean =
            displayTier != DisplayTier.PARKED

        suspend fun setTier(next: DisplayTier, reason: String) {
            if (displayTier == next && (next == DisplayTier.ACTIVE || next == DisplayTier.VISIBLE)) return
            displayTier = next
            Log.i(TAG, "[$name Pipeline] Display tier -> $next ($reason)")
            when (next) {
                DisplayTier.ACTIVE, DisplayTier.VISIBLE -> {
                    val targetW = requestedWidth.takeIf { it > 1 } ?: lastValidWidth.coerceAtLeast(720)
                    val targetH = requestedHeight.takeIf { it > 1 } ?: lastValidHeight.coerceAtLeast(720)
                    if (!isEncoderRunning() && displayId >= 0 && host.browserConnected) {
                        requestRebuild("tier_resume", MirrorForegroundService.RebuildPriority.HIGH, targetW, targetH, force = true)
                    }
                }
                DisplayTier.SUSPENDED, DisplayTier.PARKED -> {
                    suspendEncoder(reason)
                    if (next == DisplayTier.PARKED && displayId >= 0) {
                        Log.i(TAG, "[$name Pipeline] Releasing parked VirtualDisplay id=$displayId ($reason)")
                        host.runBinderSafe { controller.releaseVirtualDisplay() }
                        displayId = -1
                    }
                    host.broadcastWebDiagnostics("diagnostics_debounced")
                }
            }
        }

        suspend fun suspendEncoder(reason: String) {
            Log.i(TAG, "[$name Pipeline] Suspending encoder and stream while preserving VD/app session. Reason=$reason")
            cancelBootstrapNudge("suspend_encoder:$reason")
            if (resizeJob?.isActive == true) debugResizeCancels += 1
            resizeJob?.cancel()
            if (videoEncoder != null || jpegEncoder != null) debugEncoderReleases += 1
            videoEncoder?.release(); videoEncoder = null
            jpegEncoder?.release(); jpegEncoder = null
            currentEncoderSurface?.let { surf ->
                com.castla.mirror.diagnostics.ResourceTracker.trackSurfaceRelease(surf.hashCode(), "VideoEncoderInputSurface@${surf.hashCode()}")
                try { surf.release() } catch (_: Exception) {}
            }
            currentEncoderSurface = null
            lastFrameRenderedTime = 0L
            try { touchInjector?.detachController("suspend_encoder") } catch (_: Exception) {}
            if (displayId >= 0) {
                host.runBinderSafe { controller.setSurface(null) }
            }
            host.mirrorServer?.setKeyframeRequester(name) { _ -> }
            host.mirrorServer?.pauseStream(name, displayId, width, height)
            host.adaptiveBitrateManager.rebalanceBitrates()
        }

        fun onViewportChange(w: Int, h: Int, forceLayoutRealign: Boolean = false) {
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "[$name Pipeline] Viewport hidden or invalid -> suspending encoder without destroying VD.")
                resizeJob?.cancel(); host.serviceScope.launch { setTier(DisplayTier.SUSPENDED, "viewport_invalid") }; return
            }

            // Align dimensions to a 16-pixel grid and enforce a minimum threshold of 320px to match hardware virtual display constraints.
            val alignedW = ((w + 15) and 15.inv()).coerceAtLeast(320)
            val alignedH = ((h + 15) and 15.inv()).coerceAtLeast(320)
            // Log.i(TAG, "[$name Pipeline] viewportEvent raw=${w}x${h} aligned=${alignedW}x${alignedH} previous=${requestedWidth}x${requestedHeight} current=${width}x${height} forceLayoutRealign=$forceLayoutRealign displayId=$displayId")

            // Check if this is the initial setup phase. Runtime viewport changes use a short debounce.
            val isFirstSetup = requestedWidth <= 0 || displayId < 0

            // Cache the latest valid viewport sizes for runtime self-healing recovery
            lastValidWidth = alignedW
            lastValidHeight = alignedH
            requestedWidth = alignedW
            requestedHeight = alignedH
            if (resizeJob?.isActive == true) debugResizeCancels += 1
            resizeJob?.cancel()
            debugResizeSchedules += 1
            resizeJob = host.serviceScope.launch {
                if (!isFirstSetup) {
                    kotlinx.coroutines.delay(120L)
                }
                val forceResume = !isEncoderRunning()
                val nextPriority = when {
                    forceLayoutRealign -> MirrorForegroundService.RebuildPriority.IMMEDIATE
                    isFirstSetup || forceResume -> MirrorForegroundService.RebuildPriority.HIGH
                    else -> MirrorForegroundService.RebuildPriority.NORMAL
                }
                requestRebuild(
                    reason = "viewport_change",
                    priority = nextPriority,
                    newWidth = alignedW,
                    newHeight = alignedH,
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
            priority: MirrorForegroundService.RebuildPriority = MirrorForegroundService.RebuildPriority.NORMAL,
            newWidth: Int,
            newHeight: Int,
            force: Boolean = false,
            forceSingle: Boolean = false,
            onComplete: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        ) {
            host.requestRebuild(
                MirrorForegroundService.RebuildRequest(
                    requestId = host.rebuildRequestIdGenerator.incrementAndGet(),
                    pipelineName = name,
                    reason = reason,
                    priority = priority,
                    width = newWidth,
                    height = newHeight,
                    force = force,
                    forceSingle = forceSingle,
                    onComplete = onComplete,
                )
            )
        }

        suspend fun executeActualRebuild(requestId: Long, rebuildReason: String, targetWidth: Int, targetHeight: Int, force: Boolean = false, forceSingle: Boolean = false) {
            debugRebuildExecutions += 1
            markServiceMutation("rebuild_begin(force=$force,forceSingle=$forceSingle,target=${targetWidth}x${targetHeight})")
            val sessionId = encoderSession.incrementAndGet()
            val rebuildStartedAtMs = android.os.SystemClock.elapsedRealtime()
            val effectiveSize = DisplaySizePolicy.resolve(targetWidth, targetHeight, currentMaxHeight)
            val alignedWidth = effectiveSize.width
            val alignedHeight = effectiveSize.height

            if (!force && alignedWidth == width && alignedHeight == height) return
            if (alignedWidth > 3840 || alignedHeight > 3840) return

            val w = alignedWidth; val h = alignedHeight; val dpi = host.computeVirtualDisplayDpi(w, h)
            val calculatedBitrate = host.adaptiveBitrateManager.getSharedBitrateForPipeline(this)
            Log.i(
                TAG,
                "[PIPELINE_DEBUG] [$name] rebuild session=$sessionId target=${targetWidth}x${targetHeight} aligned=${w}x${h} force=$force forceSingle=$forceSingle currentDisplayId=$displayId currentApp=$currentApp"
            )
            host.logLaunchRecoveryInfo(
                "rebuild_execute_begin id=$requestId pane=$name session=$sessionId reason=$rebuildReason target=${targetWidth}x${targetHeight} " +
                    "aligned=${w}x${h} force=$force forceSingle=$forceSingle displayId=$displayId currentApp=$currentApp"
            )
            FileLogger.i("PIPELINE_DEBUG", "[$name] rebuild session=$sessionId target=${targetWidth}x${targetHeight} aligned=${w}x${h} force=$force forceSingle=$forceSingle currentDisplayId=$displayId currentApp=$currentApp")
            FileLogger.i("DISPLAY_STATE", "[$name] rebuild session=$sessionId displayId=$displayId currentApp=$currentApp target=${w}x${h}")

            // Reset frame indicator on viewport/encoder layout reconstruction to guarantee correct watchdog operation
            lastFrameRenderedTime = 0L
            bootstrapNudgeAttempts = 0
            host.mirrorServer?.clearCachedSpsPps(name)
            firstFrameMetadataSent = false
            cancelBootstrapNudge("rebuild_prepare")
            host.logStreamBootstrapInfo(
                "pane=$name session=$sessionId phase=rebuild_prepare codec=${host.currentCodecMode} displayId=$displayId " +
                    "target=${w}x${h} elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
            )

            encoderLifecycle.release(sessionId, "rebuild_prepare")
            delay(50)

            val startEncoderTask = encoderLifecycle.prepare(
                sessionId = sessionId,
                width = w,
                height = h,
                bitrate = calculatedBitrate,
                targetFps = targetFps,
                rebuildStartedAtMs = rebuildStartedAtMs,
            )
            val surface = currentEncoderSurface ?: throw IllegalStateException("Encoder input surface was not created")
            currentEncoderSurface = surface; width = w; height = h; currentBitrate = calculatedBitrate
            host.logStreamBootstrapInfo(
                "pane=$name session=$sessionId phase=surface_ready codec=${host.currentCodecMode} surfaceHash=${surface.hashCode()} " +
                    "displayId=$displayId width=$w height=$h elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
            )
            delay(100)

            if (controller.isBound()) {
                var success = false
                var activeId = -1
                var isNewVd = false
                var gen = -1L

                // Minimize mutex scope to exclude binder activity launches and delay suspends, preventing deadlocks.
                host.vdOperationGlobalMutex.withLock {
                    host.virtualDisplayHardwareMutex.withLock {
                        val currentId = controller.getDisplayId()
                        if (currentId >= 0) {
                            host.logStreamBootstrapInfo(
                                "pane=$name session=$sessionId phase=vd_reuse_begin displayId=$currentId width=$w height=$h dpi=$dpi " +
                                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
                            )
                            Log.i(TAG, "[DISPLAY_DEBUG] [$name] reusing VirtualDisplay id=$currentId resize=${w}x${h} dpi=$dpi")
                            FileLogger.i("DISPLAY_DEBUG", "[$name] reuseVirtualDisplay id=$currentId resize=${w}x${h} dpi=$dpi")
                            FileLogger.i("DISPLAY_STATE", "[$name] reuseVirtualDisplay id=$currentId width=$w height=$h dpi=$dpi")
                            host.runBinderSafe { controller.resizeDisplay(w, h, dpi) }
                            Log.i(TAG, "[DISPLAY_DEBUG] [$name] attaching surface to existing display id=$currentId")
                            FileLogger.i("DISPLAY_DEBUG", "[$name] setSurface existing id=$currentId")
                            FileLogger.i("DISPLAY_STATE", "[$name] setSurface existing id=$currentId")
                            host.runBinderSafe { controller.setSurface(surface) }
                            displayId = currentId
                            activeId = currentId
                            gen = markVdCreated(currentId, "${name}_reuse")
                            isNewVd = false
                            success = true
                            host.logStreamBootstrapInfo(
                                "pane=$name session=$sessionId phase=vd_reuse_ready displayId=$currentId generation=$gen " +
                                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
                            )
                        } else {
                            host.logStreamBootstrapInfo(
                                "pane=$name session=$sessionId phase=vd_create_begin width=$w height=$h dpi=$dpi " +
                                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
                            )
                            Log.i(TAG, "[DISPLAY_DEBUG] [$name] creating new VirtualDisplay target=${w}x${h} dpi=$dpi")
                            FileLogger.i("DISPLAY_DEBUG", "[$name] createVirtualDisplay target=${w}x${h} dpi=$dpi")
                            FileLogger.i("DISPLAY_STATE", "[$name] createVirtualDisplay width=$w height=$h dpi=$dpi")
                            host.runBinderSafe { controller.releaseVirtualDisplay() }
                            host.runBinderSafe { controller.createVirtualDisplay(w, h, dpi, surface) }
                            if (controller.hasVirtualDisplay()) {
                                val newActiveId = controller.getDisplayId()
                                displayId = newActiveId
                                activeId = newActiveId
                                gen = markVdCreated(newActiveId, "${name}_rebuild")
                                isNewVd = true
                                success = true
                                host.logStreamBootstrapInfo(
                                    "pane=$name session=$sessionId phase=vd_create_ready displayId=$newActiveId generation=$gen " +
                                        "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
                                )
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
                    val preStreamTarget = preStreamLaunchTarget()
                    val shouldLaunchBeforeStream = LaunchRecoveryPolicy.shouldLaunchTargetBeforeStreamBootstrap(
                        hasLaunchTarget = preStreamTarget != null,
                        requiresFreshLaunchPreparation = requiresFreshLaunchPreparation,
                        isNewVirtualDisplay = isNewVd,
                    )
                    if (shouldLaunchBeforeStream && preStreamTarget != null) {
                        host.logLaunchRecoveryInfo(
                            "prestream_launch_begin pane=$name session=$sessionId displayId=$activeId target=$preStreamTarget " +
                                "reason=$rebuildReason currentApp=$currentApp"
                        )
                        val launched = launchComponent(
                            preStreamTarget,
                            forceColdStart = false,
                            forceTaskRealign = true,
                            skipLaunchSelfHeal = true,
                            suppressStreamGenerationRestart = true,
                        )
                        host.logLaunchRecoveryInfo(
                            "prestream_launch_done pane=$name session=$sessionId displayId=$activeId target=$preStreamTarget " +
                                "reason=$rebuildReason launched=$launched"
                        )
                    }
                    host.logStreamBootstrapInfo(
                        "pane=$name session=$sessionId phase=stream_generation_begin_request displayId=$activeId isNewVd=$isNewVd " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
                    )
                    val streamGeneration = beginStreamGeneration(activeId, w, h)
                    Log.i(TAG, "[$name Pipeline] encoderLifecycle phase=stream_generation session=$sessionId generation=$streamGeneration displayId=$activeId target=${w}x${h}")
                    host.logStreamBootstrapInfo(
                        "pane=$name session=$sessionId phase=stream_generation_begin_done displayId=$activeId generation=$streamGeneration " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
                    )
                    startEncoderTask?.invoke()
                    scheduleBootstrapNudge(
                        sessionId = sessionId,
                        targetDisplayId = activeId,
                    )

                    delay(100) // Small stabilization delay outside lock
                    host.runBinderSafe { controller.keepDisplayAwake() }

                    if (isNewVd) {
                        try {
                            host.wakeDisplayForRecovery(controller.getPrivilegedService(), activeId, "rebuild_new_vd")
                            Log.i(TAG, "[DISPLAY_DEBUG] [$name] wakeUpDisplay after new VD id=$activeId")
                            FileLogger.i("DISPLAY_DEBUG", "[$name] wakeUpDisplay after new VD id=$activeId")
                            FileLogger.i("DISPLAY_STATE", "[$name] wakeUpDisplay id=$activeId")
                        } catch (e: Exception) {
                            Log.w(TAG, "[$name Pipeline] Failed to trigger early wakeup guard", e)
                        }
                    }

                    if (currentApp.isBlank()) {
                        currentApp = "HOME"
                        markServiceMutation("launch_home_after_rebuild")
                        host.runBinderSafe { controller.launchHomeOnDisplay() }
                    } else if (shouldLaunchBeforeStream) {
                        markServiceMutation("prestream_launch_after_rebuild")
                    } else if (isNewVd || forceSingle) {
                        markServiceMutation("soft_recovery_after_rebuild")
                        Log.i(TAG, "[$name Pipeline] Rebuild completed without automatic app relaunch. Waiting for explicit launch path.")
                    }
                    markServiceMutation("rebuild_end(newVd=$isNewVd,activeId=$activeId)")
                    Log.i(TAG, "[$name Pipeline] VirtualDisplay configured successfully. ID: $activeId (New VD: $isNewVd)")
                    Log.i(TAG, "[DISPLAY_DEBUG] [$name] configured activeId=$activeId generation=$gen isNewVd=$isNewVd currentApp=$currentApp")
                    FileLogger.i("DISPLAY_DEBUG", "[$name] configured activeId=$activeId generation=$gen isNewVd=$isNewVd currentApp=$currentApp")
                    FileLogger.i("DISPLAY_STATE", "[$name] configured activeId=$activeId generation=$gen isNewVd=$isNewVd currentApp=$currentApp")
                } else {
                    throw IllegalStateException("VirtualDisplay allocation completely failed via binder server.")
                }
            } else {
                if (host.trySetupVirtualDisplay(w, h, surface)) {
                    val preStreamTarget = preStreamLaunchTarget()
                    val shouldLaunchBeforeStream = LaunchRecoveryPolicy.shouldLaunchTargetBeforeStreamBootstrap(
                        hasLaunchTarget = preStreamTarget != null,
                        requiresFreshLaunchPreparation = requiresFreshLaunchPreparation,
                        isNewVirtualDisplay = displayId >= 0,
                    )
                    if (displayId >= 0 && shouldLaunchBeforeStream && preStreamTarget != null) {
                        host.logLaunchRecoveryInfo(
                            "prestream_launch_begin pane=$name session=$sessionId displayId=$displayId target=$preStreamTarget " +
                                "reason=$rebuildReason fallbackPath=true currentApp=$currentApp"
                        )
                        val launched = launchComponent(
                            preStreamTarget,
                            forceColdStart = false,
                            forceTaskRealign = true,
                            skipLaunchSelfHeal = true,
                            suppressStreamGenerationRestart = true,
                        )
                        host.logLaunchRecoveryInfo(
                            "prestream_launch_done pane=$name session=$sessionId displayId=$displayId target=$preStreamTarget " +
                                "reason=$rebuildReason fallbackPath=true launched=$launched"
                        )
                    }
                    if (displayId >= 0) {
                        host.logStreamBootstrapInfo(
                            "pane=$name session=$sessionId phase=stream_generation_begin_request displayId=$displayId isNewVd=true " +
                                "fallbackPath=true elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
                        )
                        val streamGeneration = beginStreamGeneration(displayId, w, h)
                        host.logStreamBootstrapInfo(
                            "pane=$name session=$sessionId phase=stream_generation_begin_done displayId=$displayId generation=$streamGeneration " +
                                "fallbackPath=true elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
                        )
                    }
                    startEncoderTask?.invoke()
                    if (displayId >= 0) {
                        scheduleBootstrapNudge(
                            sessionId = sessionId,
                            targetDisplayId = displayId,
                        )
                    }
                    if (displayId >= 0 && shouldLaunchBeforeStream) {
                        markServiceMutation("prestream_launch_after_rebuild_fallback")
                    }
                }
            }
            if (displayId >= 0) {
                try { host.mirrorServer?.broadcastControlMessage(org.json.JSONObject().apply { put("type", "resolutionChanged"); put("pane", name); put("width", w); put("height", h) }.toString()) } catch (_: Exception) {}
                // Wake the display and request a fresh frame without injecting synthetic touches
                // that can affect apps like maps during repeated split/expand cycles.
                host.serviceScope.launch {
                    try {
                        delay(150)
                        markServiceMutation("post_rebuild_wakeup")
                        host.wakeDisplayForRecovery(controller.getPrivilegedService(), displayId, "restore_content")
                        if (host.currentCodecMode != "mjpeg") {
                            markServiceMutation("post_rebuild_keyframe")
                        }
                        host.requestKeyFrameForRecovery(this@MirroringPipeline, "restore_content")
                        // Log.i(TAG, "[FRAME_DEBUG] [$name] post-rebuild wakeup/keyframe displayId=$displayId codec=${host.currentCodecMode}")
                        FileLogger.i("FRAME_DEBUG", "[$name] post-rebuild wakeup/keyframe displayId=$displayId codec=${host.currentCodecMode}")
                        FileLogger.i("KEYFRAME_REQUEST", "[$name] postRebuild displayId=$displayId codec=${host.currentCodecMode}")
                        Log.i(TAG, "[$name Pipeline] Requested post-rebuild wakeup/keyframe (codec: ${host.currentCodecMode})")
                    } catch (e: Exception) {
                        Log.w(TAG, "[$name Pipeline] Failed to force graphics wakeup post rebuild", e)
                    }
                }
            }

            host.broadcastWebDiagnostics("diagnostics_debounced")
        }

        fun invalidateVd(reason: String): Long {
            Log.w(TAG, "[$name Pipeline] Invalidating display channel cache token. Reason: $reason")
            Log.w(TAG, "[DISPLAY_DEBUG] [$name] invalidateVd reason=$reason oldDisplayId=$displayId currentApp=$currentApp")
            FileLogger.i("DISPLAY_DEBUG", "[$name] invalidateVd reason=$reason oldDisplayId=$displayId currentApp=$currentApp")
            FileLogger.i("DISPLAY_STATE", "[$name] invalidateVd reason=$reason oldDisplayId=$displayId currentApp=$currentApp")
            displayId = -1
            return vdGeneration.incrementAndGet()
        }

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
            host.serviceScope.launch {
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
                    host.virtualDisplayHardwareMutex.withLock {
                        host.wakeDisplayForRecovery(service, targetDisplayId, "launch_component")
                    }
                    delay(40)
                    host.requestKeyFrameForRecovery(this@MirroringPipeline, "launch_component")
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
            if (cleanPkg.isBlank() || cleanPkg == "HOME" || cleanPkg == "com.android.settings" || cleanPkg == host.packageName) return

            if (!touchFocusRecoveryCoordinator.shouldRecover(
                    activeDisplayId = activeId,
                    touchInteractionActive = isTouchInteractionActive(),
                    targetApp = targetApp,
                    topTask = topTask,
                    packageName = cleanPkg,
                    lastRecoveryAt = lastTouchFocusRecoveryAt,
                ) || cleanPkg == host.packageName) return
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

            host.serviceScope.launch(host.vdDispatcher) {
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

                host.wakeDisplayForRecovery(service, activeId, "touch_focus_recovery")
                host.dismissKeyguardForRecovery(service, "touch_focus_recovery")

                val taskIds = try {
                    host.runBinderSafe(1000L) { service.getTaskIdsForPackage(cleanPkg).toList() } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                var moveAttempted = false
                for (taskId in taskIds) {
                    try {
                        host.runBinderSafe {
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
                    host.runBinderSafe(1000L) { service.getRunningTasksOnDisplay(activeId).firstOrNull() }
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

            host.appendRecentServerTouchTrace(
                "reject pane=$name displayId=$activeId action=$action pointerCount=$pointerCount app=$currentApp"
            )
            host.broadcastWebDiagnostics("inject_reject:$name:$action")
            if (!debugFirstInjectFailureProbeLogged) {
                debugFirstInjectFailureProbeLogged = true
                logInjectionFailureProbe(activeId, action, pointerCount)
            }
            if (touchFocusRecoveryCoordinator.shouldRecoverFromInjectionReject(now, lastInjectionRecoveryAt)) {
                lastInjectionRecoveryAt = now
                debugInjectionRecoveries += 1
                Log.w(
                    TAG,
                    "[FocusTrace] inject_reject pane=$name displayId=$activeId action=$action pointerCount=$pointerCount app=$currentApp"
                )
                val relaunchTarget = recoveryLaunchTarget().ifBlank { touchFocusGateTarget.ifBlank { currentApp } }
                host.serviceScope.launch {
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
                    if (host.isLegacyScreenOffRecoveryActive) {
                        host.logScreenOffWarn("[SCREEN_OFF] [REVIVE_REBUILD] pane=$name reason=inject_reject displayId=$activeId")
                        host.requestScreenOffRebuild(this@MirroringPipeline, "inject_reject")
                    }
                    try {
                        host.wakeDisplayForRecovery(controller.getPrivilegedService(), activeId, "inject_reject")
                        host.dismissKeyguardForRecovery(controller.getPrivilegedService(), "inject_reject")
                    } catch (_: Exception) {}
                    delay(60L)
                    triggerInternalFocusNudge(activeId, "inject_reject")
                    delay(120L)
                    try {
                        if (host.currentCodecMode != "mjpeg") {
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
            host.serviceScope.launch(host.vdDispatcher) {
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
                    host.runBinderSafe(1500L) { service.execCommand("dumpsys activity activities") } ?: ""
                } catch (_: Exception) {
                    ""
                }
                val windowsDump = try {
                    host.runBinderSafe(1500L) { service.execCommand("dumpsys window windows") } ?: ""
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
                host.lastRejectProbeSummary =
                    "pane=$name displayId=$activeId app=$targetApp activities=$activitySummary windows=$windowSummary recent=${recentServiceActionSummary()}"
                Log.w(
                    TAG,
                    "[FocusTrace] inject_false_probe pane=$name displayId=$activeId action=$action pointerCount=$pointerCount app=$targetApp activities=$activitySummary windows=$windowSummary recentServiceAction=${recentServiceActionSummary()}"
                )
                host.broadcastWebDiagnostics("inject_false_probe:$name:$action")
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

        private fun internalComponentName(activityClassName: String): String = if (activityClassName.contains('/')) activityClassName else "${host.packageName}/$activityClassName"
        private fun recoveryLaunchTarget(): String {
            val active = currentApp.trim()
            if (active.isBlank() || active == "HOME" || active == "com.android.settings") return active
            if (active.startsWith("${host.packageName}/") || active.contains("WebBrowserActivity")) return active
            return active.substringBefore('/').ifBlank { active }
        }

        private fun preStreamLaunchTarget(): String? {
            val active = currentApp.trim()
            if (active.isBlank() || active == "HOME" || active == "com.android.settings") return null
            if (active.startsWith("${host.packageName}/") || active.contains("WebBrowserActivity")) return null
            return recoveryLaunchTarget().ifBlank { null }
        }

        private fun restartActiveStreamGeneration() {
            val token = currentVdToken() ?: return
            val encoderActive = if (host.currentCodecMode == "mjpeg") jpegEncoder != null else videoEncoder != null
            if (!encoderActive || width <= 0 || height <= 0) return
            firstFrameMetadataSent = false
            beginStreamGeneration(token.second, width, height)
        }

        fun launchOwnActivity(activityClassName: String, url: String) {
            val targetDisplayId = this.displayId
            if (targetDisplayId < 0) return
            Log.i(TAG, "[$name Pipeline] Spawning internal container panel component: $activityClassName")
            FileLogger.i(
                "IME_ROUTING",
                "pane=$name phase=internal_activity targetDisplayId=$targetDisplayId vdDisplayId=$displayId launchMode=activity_options activity=$activityClassName"
            )
            val options = android.app.ActivityOptions.makeBasic().apply { launchDisplayId = targetDisplayId }
            val intent = Intent().apply {
                setClassName(host, activityClassName)
                if (activityClassName.contains("WebBrowserActivity")) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra("url", url); putExtra("pane", name)
            }
            try { host.startActivity(intent, options.toBundle()) } catch (_: Exception) {
                host.serviceScope.launch { launchComponent(internalComponentName(activityClassName), "url", url, forceColdStart = false, forceDisplayId = true) }
            }
        }
        private suspend fun prepareDisplaySessionForLaunch(skipSelfHeal: Boolean): DisplayLaunchSession {
            val preparation = DisplaySessionPreparationPolicy.resolve(
                DisplaySessionPreparationInput(
                    requestedWidth = requestedWidth,
                    requestedHeight = requestedHeight,
                    lastValidWidth = lastValidWidth,
                    lastValidHeight = lastValidHeight,
                    currentWidth = width,
                    currentHeight = height,
                    currentMaxHeight = currentMaxHeight,
                    encoderReady = if (host.currentCodecMode == "mjpeg") jpegEncoder != null else videoEncoder != null,
                )
            )
            val session = preparation.session
            val targetWidth = session.targetWidth
            val targetHeight = session.targetHeight
            val alignedWidth = session.alignedWidth
            val alignedHeight = session.alignedHeight
            val encoderReleased = !session.encoderReady

            if (!skipSelfHeal && (encoderReleased || width <= 1 || preparation.needsRealignment) && !isSelfHealingInProgress) {
                isSelfHealingInProgress = true
                try {
                    Log.i(TAG, "[$name Pipeline] Display session preparation: target=${targetWidth}x${targetHeight} encoderReleased=$encoderReleased needsRealignment=${preparation.needsRealignment}")
                    val rebuildDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                    requestRebuild(
                        reason = "launch_display_session_prepare",
                        priority = MirrorForegroundService.RebuildPriority.HIGH,
                        newWidth = targetWidth,
                        newHeight = targetHeight,
                        onComplete = rebuildDeferred,
                    )
                    try {
                        rebuildDeferred.await()
                    } catch (e: Exception) {
                        Log.w(TAG, "[$name Pipeline] Display session preparation await failed", e)
                        delay(300)
                    }
                } finally {
                    isSelfHealingInProgress = false
                }
            } else if (!skipSelfHeal && isSelfHealingInProgress) {
                Log.d(TAG, "[$name Pipeline] Display session preparation already in progress")
            }

            return session.copy(
                encoderReady = if (host.currentCodecMode == "mjpeg") jpegEncoder != null else videoEncoder != null,
            )
        }
        suspend fun launchComponent(
            packageOrComponent: String,
            extraKey: String? = null,
            extraValue: String? = null,
            forceColdStart: Boolean = false,
            forceDisplayId: Boolean = false,
            forceTaskRealign: Boolean = false,
            skipLaunchSelfHeal: Boolean = false,
            suppressStreamGenerationRestart: Boolean = false,
        ): Boolean = withContext(host.vdDispatcher) {
            markServiceMutation("launch_component_begin(target=$packageOrComponent,cold=$forceColdStart,realign=$forceTaskRealign)")
            // Ensure lastFrameRenderedTime is reset only when actually switching to a different application package
            // or when a clean cold start is explicitly requested. This preserves frame rendering timestamps for
            // the active app, allowing the Command Equivalence Guard to accurately prevent duplicate launch floods.
            val cleanPkg = packageOrComponent.substringBefore('/').substringBefore('?').substringBefore(' ').trim()
            if (cleanPkg.isBlank() || cleanPkg == host.packageName || cleanPkg.contains("com.castla.mirror")) return@withContext false

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
            val isEncoderActiveBeforeSession = if (host.currentCodecMode == "mjpeg") jpegEncoder != null else videoEncoder != null
            val now = System.currentTimeMillis()
            val isFrameStreamingNormal = lastFrameRenderedTime > 0L && (now - lastFrameRenderedTime < 3000L)

            if (isAlreadyActive && isEncoderActiveBeforeSession && isFrameStreamingNormal && !needsFreshLaunchPreparation && !forceColdStart && !forceTaskRealign && !isSelfHealingInProgress) {
                Log.i(TAG, "[$name Pipeline] Command Equivalence Guard activated. $cleanPkg is already running and active on display $displayId. Bypassing redundant launch command.")
                FileLogger.i("PIPELINE_DEBUG", "[$name] launchDecision sameAppGuard=true pkg=$cleanPkg displayId=$displayId freshPrep=$needsFreshLaunchPreparation frameStreamingNormal=$isFrameStreamingNormal forceColdStart=$forceColdStart forceTaskRealign=$forceTaskRealign")
                // Keep-awake graphic trigger
                val correctedDisplayId = if (displayId >= 0) displayId else controller.getDisplayId()
                val service = controller.getPrivilegedService()
                if (correctedDisplayId >= 0 && service != null) {
                    executeAdaptiveWakeup(correctedDisplayId, cleanPkg, service)
                }
                val token = currentVdToken()
                if (token != null) {
                    firstFrameMetadataSent = false
                    beginStreamGeneration(token.second, width, height)
                }
                return@withContext true
            }


            val displaySession = prepareDisplaySessionForLaunch(skipLaunchSelfHeal)
            val targetW = displaySession.targetWidth
            val targetH = displaySession.targetHeight
            val alignedW = displaySession.alignedWidth
            val alignedH = displaySession.alignedHeight
            val isEncoderActive = displaySession.encoderReady

            val correctedDisplayId = if (displayId >= 0) displayId else controller.getDisplayId()
            if (correctedDisplayId < 0) return@withContext false
            val service = controller.getPrivilegedService() ?: return@withContext false

            try {
                if (forceColdStart && cleanPkg != "HOME") { try { service.execCommand("am force-stop $cleanPkg") } catch (_: Exception) {} }
                val originalDisplayId = try { host.runBinderSafe { service.getDisplayIdForPackage(cleanPkg) } ?: -1 } catch (_: Exception) { -1 }
                // Always route to the requested virtual display. A package task on Display 0 must not redirect this launch to the phone.
                val targetDisplayId = correctedDisplayId

                Log.i(TAG, "[$name Pipeline] Symmetric task processing initialized -> Routing $cleanPkg to Display token: $targetDisplayId freshLaunchPrep=$needsFreshLaunchPreparation previousPkg=$previousPkg lastPrepared=$lastPreparedTargetPackage")
                FileLogger.i("PIPELINE_DEBUG", "[$name] launchDecision pkg=$cleanPkg freshPrep=$needsFreshLaunchPreparation sameAppGuard=false originalDisplayId=$originalDisplayId correctedDisplayId=$correctedDisplayId targetDisplayId=$targetDisplayId previousPkg=$previousPkg lastPrepared=$lastPreparedTargetPackage forceDisplayId=$forceDisplayId")

                val matchingTaskIds = try { host.runBinderSafe(1000L) { service.getTaskIdsForPackage(cleanPkg).toList() } ?: emptyList() } catch (_: Exception) { emptyList() }
                val tasklessActiveRelaunch = LaunchRecoveryPolicy.shouldForceFreshPreparationForTasklessRelaunch(
                    targetPkg = cleanPkg,
                    currentAppPkg = currentApp.substringBefore('/'),
                    matchingTaskCount = matchingTaskIds.size,
                    forceTaskRealign = forceTaskRealign,
                    encoderActive = isEncoderActive,
                    requiresFreshLaunchPreparation = needsFreshLaunchPreparation,
                )
                val effectiveNeedsFreshLaunchPreparation = needsFreshLaunchPreparation || tasklessActiveRelaunch
                if (tasklessActiveRelaunch) {
                    lastFrameRenderedTime = 0L
                    requiresFreshLaunchPreparation = true
                    FileLogger.i(
                        "PIPELINE_DEBUG",
                        "[$name] launchDecision tasklessActiveRelaunch=true pkg=$cleanPkg currentApp=$currentApp forceTaskRealign=$forceTaskRealign"
                    )
                }
                val targetDisplayPackages = try {
                    host.runBinderSafe(1000L) { service.getRunningTasksOnDisplay(targetDisplayId) } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                val taskRoutingResult = TaskRoutingCoordinator().route(
                    TaskRoutingRequest(
                        targetDisplayId = targetDisplayId,
                        originalDisplayId = originalDisplayId,
                        matchingTaskIds = matchingTaskIds,
                        targetDisplayPackages = targetDisplayPackages,
                        packageName = cleanPkg,
                        forceColdStart = forceColdStart,
                        displaySizeMatches = width == alignedW && height == alignedH,
                        encoderReady = isEncoderActive,
                        encoderDisplayId = if (isEncoderActive) displayId else -1,
                        moveTaskNative = { taskId -> host.runBinderSafe { service.moveTaskToFrontNative(taskId) } ?: false },
                        moveTaskShell = { taskId -> service.execCommand("cmd activity task move-to-front $taskId") },
                    )
                )
                val targetDisplayHasTask = taskRoutingResult.targetDisplayHasTask
                val isWarmStart = taskRoutingResult.isWarmStart
                val launchPlan = taskRoutingResult.launchPlan
                val canReuseWarmTask = launchPlan.taskAction == TaskLaunchAction.MOVE_TASK_TO_FRONT
                Log.i(TAG, "[$name Pipeline] taskResidency pkg=$cleanPkg matching=${matchingTaskIds.size} originalDisplayId=$originalDisplayId targetDisplayId=$targetDisplayId targetDisplayHasTask=$targetDisplayHasTask targetEntries=${targetDisplayPackages.size} plan=${launchPlan.taskAction} reason=${launchPlan.reason} resize=${launchPlan.resizeRequired} encoderReconnect=${launchPlan.encoderReconnectRequired}")
                host.scheduleDisplayRoutingDiagnostics(
                    pane = name,
                    service = service,
                    targetPkg = cleanPkg,
                    targetDisplayId = targetDisplayId,
                    phase = "prelaunch",
                    launchMode = when (launchPlan.taskAction) { TaskLaunchAction.MOVE_TASK_TO_FRONT -> "warm_task_move"; TaskLaunchAction.CREATE_NEW_TASK -> "new_task_required"; TaskLaunchAction.MOVE_TASK_TO_DISPLAY_AND_FRONT -> "task_move"; TaskLaunchAction.WAIT_FOR_DISPLAY -> "pending" },
                    vdDisplayId = displayId
                )

                if (launchPlan.taskAction == TaskLaunchAction.MOVE_TASK_TO_FRONT) {
                    taskRoutingResult.moveResults.forEach { moveResult ->
                        if (moveResult.error == null) {
                            Log.i(TAG, "[$name Pipeline] warmTaskMove taskId=${moveResult.taskId} displayId=$targetDisplayId result=${moveResult.result}")
                        } else {
                            Log.w(TAG, "[$name Pipeline] warmTaskMove failed taskId=${moveResult.taskId} displayId=$targetDisplayId", moveResult.error)
                        }
                    }
                } else if (isWarmStart) {
                    FileLogger.i("PIPELINE_DEBUG", "[$name] launchDecision existingTaskOnOtherDisplay=true existingDisplayId=$originalDisplayId targetDisplayId=$targetDisplayId; creating separate task")
                }
                // Prevent redundant 'am start' shell command execution immediately following async task migration command.
                // Re-launching via 'am start' in parallel with active task displacement commands causes Android OS task stack conflict,
                // frequently forcing the primary Display 0 (MainActivity) to recede to the background Recents view.
                if (canReuseWarmTask && !BrowserLaunchPolicy.shouldBypassWarmTaskMove(cleanPkg)) {
                    markServiceMutation("launch_component_warm_start")
                    FileLogger.i("PIPELINE_DEBUG", "[$name] launchDecision warmStart=true pkg=$cleanPkg taskCount=${matchingTaskIds.size} targetDisplayId=$targetDisplayId freshPrep=$effectiveNeedsFreshLaunchPreparation")
                    host.scheduleDisplayRoutingDiagnostics(name, service, cleanPkg, targetDisplayId, "postlaunch", "warm_task_move", displayId)
                    // Trigger adaptive task residency-aware wakeup asynchronously instead of waiting on hardcoded timings
                    executeAdaptiveWakeup(targetDisplayId, cleanPkg, service)
                    if (effectiveNeedsFreshLaunchPreparation) {
                        host.mirrorServer?.onKeyframeRequest(name, "fresh_launch_prepare")
                    }

                    // Trigger the 4-second frame-based watchdog for graceful recovery on warm start layout transition
                    host.verifySurfaceAndFallback(
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
                    if (isEncoderActive && !suppressStreamGenerationRestart) {
                        val token = currentVdToken()
                        if (token != null) {
                            firstFrameMetadataSent = false
                            beginStreamGeneration(token.second, width, height)
                        }
                    }
                    return@withContext true
                }

                // WMS Transition Lock Prevention Guard:
                // If this is a realign/recovery request (forceTaskRealign = true) for the ALREADY ACTIVE application
                // (i.e. cleanPkg is already currentApp and we are currently streaming/encoder is running),
                // we MUST NOT execute native launchAppOnDisplayV2 or buildShellLaunchCommand.
                // Doing so forces Android OS to initiate a new Window Manager transition state,
                // which locks display focus and causes a perpetual touch injection rejection loop.
                val isAlreadyActiveApp = cleanPkg == currentApp.substringBefore('/')
                val isEncoderActive = if (host.currentCodecMode == "mjpeg") jpegEncoder != null else videoEncoder != null
                if (forceTaskRealign && isAlreadyActiveApp && isEncoderActive && !effectiveNeedsFreshLaunchPreparation) {
                    Log.w(TAG, "[$name Pipeline] Realignment requested for active app $cleanPkg. Bypassing native cold start to prevent WMS focus transition lock.")
                    FileLogger.i("PIPELINE_DEBUG", "[$name] launchDecision realignBypass=true pkg=$cleanPkg targetDisplayId=$targetDisplayId freshPrep=$effectiveNeedsFreshLaunchPreparation")
                    host.scheduleDisplayRoutingDiagnostics(name, service, cleanPkg, targetDisplayId, "postlaunch", "realign_bypass", displayId)
                    executeAdaptiveWakeup(targetDisplayId, cleanPkg, service)
                    currentApp = packageOrComponent
                    val token = currentVdToken()
                    if (token != null && !suppressStreamGenerationRestart) {
                        firstFrameMetadataSent = false
                        beginStreamGeneration(token.second, width, height)
                    }
                    return@withContext true
                }

                val isStandardAppLaunch = extraKey.isNullOrEmpty() && extraValue == null && !packageOrComponent.contains("/")
                val launchCommand = host.buildShellLaunchCommand(
                    targetDisplayId,
                    packageOrComponent,
                    extraKey,
                    extraValue,
                    reorderToFront = canReuseWarmTask,
                )
                val launchResult = ActivityLaunchCoordinator().launch(
                    ActivityLaunchRequest(
                        nativeLaunchAllowed = isStandardAppLaunch && !BrowserLaunchPolicy.shouldBypassNativeLaunchShortcut(cleanPkg),
                        shellCommand = launchCommand,
                        fallbackTaskIds = {
                            try {
                                host.runBinderSafe { service.getTaskIdsForPackage(cleanPkg) } ?: intArrayOf()
                            } catch (_: Exception) {
                                intArrayOf()
                            }
                        },
                        nativeLaunch = {
                            try {
                                host.runBinderSafe { controller.launchAppOnDisplayV2(cleanPkg, forceStop = false) } ?: false
                            } catch (e: Exception) {
                                Log.w(TAG, "[$name Pipeline] Native launchAppOnDisplayV2 failed, preparing shell fallback", e)
                                false
                            }
                        },
                        shellLaunch = { command ->
                            markServiceMutation("launch_component_shell_start")
                            Log.i(TAG, "[$name Pipeline] Executing fallback shell launch command for $packageOrComponent")
                            FileLogger.i("PIPELINE_DEBUG", "[$name] launchDecision nativeStarted=false usingShell=true pkg=$cleanPkg targetDisplayId=$targetDisplayId warmStart=$isWarmStart")
                            host.runBinderSafe { service.execCommand(command) } ?: ""
                        },
                        moveTaskToDisplay = { taskId ->
                            try {
                                // val retryNativeMoved = host.runBinderSafe { controller.moveTaskToDisplayNative(taskId) } ?: false
                                val retryNativeMoved = false
                                if (!retryNativeMoved) {
                                    service.execCommand("cmd activity task move-to-display $taskId $targetDisplayId")
                                    service.execCommand("cmd activity task move-to-front $taskId")
                                }
                            } catch (_: Exception) {}
                        },
                    )
                )
                val nativeStarted = launchResult.nativeStarted
                host.scheduleDisplayRoutingDiagnostics(
                    name,
                    service,
                    cleanPkg,
                    targetDisplayId,
                    "postlaunch",
                    if (nativeStarted) "native_launch_on_display" else "shell_am_start_display",
                    displayId
                )

                // Force an immediate graphics wakeup sequence and request encoder keyframe for Cold-Start apps to prevent early stream corruption.
                if (!isWarmStart || forceColdStart) {
                    markServiceMutation("launch_component_cold_start")
                    FileLogger.i("PIPELINE_DEBUG", "[$name] launchDecision coldStartPath=true pkg=$cleanPkg targetDisplayId=$targetDisplayId freshPrep=$effectiveNeedsFreshLaunchPreparation forceColdStart=$forceColdStart")
                    executeAdaptiveWakeup(targetDisplayId, cleanPkg, service)
                    markServiceMutation("launch_component_keyframe")
                    host.mirrorServer?.onKeyframeRequest(name, if (effectiveNeedsFreshLaunchPreparation) "fresh_launch_prepare" else "launch_component")
                }

                // Trigger the 4-second frame-based watchdog for graceful recovery on cold start layout transition
                host.verifySurfaceAndFallback(
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
                if (isEncoderActive && !suppressStreamGenerationRestart) {
                    val token = currentVdToken()
                    if (token != null) {
                        firstFrameMetadataSent = false
                        beginStreamGeneration(token.second, width, height)
                    }
                }
                return@withContext true
            } catch (e: Exception) { Log.e(TAG, "[$name Pipeline] Component push crashed inside system shell launcher layer.", e); return@withContext false }
        }


        suspend fun launchBrowser(
            url: String,
            sourceAppPackage: String? = null,
            allowFallback: Boolean = true,
            forceEmbeddedBrowser: Boolean = false,
        ) {
            val embeddedComponent = internalComponentName("com.castla.mirror.ui.WebBrowserActivity")
            PipelineBrowserLaunchCoordinator(
                resolveBrowser = { pageUrl -> BrowserResolver.resolve(host, pageUrl)?.componentFlat },
                forceEmbeddedBrowser = { pkg -> OttCatalog.forceEmbeddedBrowserFor(pkg) },
                embeddedComponent = { embeddedComponent },
                isSameActivePage = { pageUrl, component -> displayId >= 0 && currentWebUrl == pageUrl && currentApp == component },
                updateState = { component, pageUrl, video -> currentApp = component; currentWebUrl = pageUrl; isVideoApp = video },
                requestMissingDisplayRecovery = { pageUrl, browserComponent, targetWidth, targetHeight ->
                    host.serviceScope.launch(Dispatchers.IO) {
                        try {
                            requestRebuild(
                                reason = "browser_launch_missing_display",
                                priority = MirrorForegroundService.RebuildPriority.HIGH,
                                newWidth = targetWidth,
                                newHeight = targetHeight,
                            )
                            if (displayId >= 0) {
                                if (browserComponent != null) {
                                    controller.getPrivilegedService()?.execCommand(ShellLaunchCommandBuilder.buildExternalBrowserCommand(displayId, pageUrl, browserComponent))
                                } else {
                                    launchOwnActivity("com.castla.mirror.ui.WebBrowserActivity", pageUrl)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                },
                launchExternalBrowser = { pageUrl, component ->
                    try {
                        controller.getPrivilegedService()?.execCommand(ShellLaunchCommandBuilder.buildExternalBrowserCommand(displayId, pageUrl, component))
                        true
                    } catch (_: Exception) {
                        false
                    }
                },
                launchEmbeddedBrowser = { pageUrl, _ -> launchOwnActivity("com.castla.mirror.ui.WebBrowserActivity", pageUrl) },
                restartStream = ::restartActiveStreamGeneration,
                rebalanceBitrates = host.adaptiveBitrateManager::rebalanceBitrates,
            ).launch(
                url = url,
                sourceAppPackage = sourceAppPackage,
                allowFallback = allowFallback,
                forceEmbedded = forceEmbeddedBrowser,
                context = BrowserLaunchContext(displayId, lastValidWidth, lastValidHeight, requestedWidth, requestedHeight),
            )
        }
        suspend fun launchStandard(launchTarget: String, forceDisplayId: Boolean = false) {
            PipelineLaunchCoordinator(
                normalizeTarget = host::normalizeLaunchTarget,
                launchComponent = { target, force ->
                    if (displayId >= 0) launchComponent(target, forceDisplayId = force, forceTaskRealign = true) else false
                },
                logRecovery = host::logLaunchRecoveryInfo,
                updateState = { target -> currentApp = target; currentWebUrl = null; isVideoApp = false },
                requestRecovery = { target, force, targetWidth, targetHeight ->
                    host.serviceScope.launch(Dispatchers.IO) {
                        try {
                            requestRebuild(
                                reason = "standard_launch_missing_display",
                                priority = MirrorForegroundService.RebuildPriority.HIGH,
                                newWidth = targetWidth,
                                newHeight = targetHeight,
                            )
                            host.logLaunchRecoveryInfo(
                                "launch_standard_rebuild_requested pkg=$target displayId=$displayId target=${targetWidth}x${targetHeight}"
                            )
                            if (displayId >= 0) launchComponent(target, forceDisplayId = force, forceTaskRealign = true)
                        } catch (_: Exception) {}
                    }
                },
                rebalanceBitrates = host.adaptiveBitrateManager::rebalanceBitrates,
            ).launchStandard(
                launchTarget = launchTarget,
                forceDisplayId = forceDisplayId,
                context = StandardLaunchContext(displayId, requestedWidth, requestedHeight, lastValidWidth, lastValidHeight),
            )
        }
        suspend fun launchAppFromWebLauncher(pkgName: String, componentName: String? = null, forceDisplayId: Boolean = true) {
            if (pkgName.isBlank()) return
            val isAppInstalled = try {
                val pm = host.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getApplicationInfo(pkgName, PackageManager.ApplicationInfoFlags.of(0)).enabled
                else @Suppress("DEPRECATION") pm.getApplicationInfo(pkgName, 0).enabled
            } catch (_: PackageManager.NameNotFoundException) { false }

            if (isAppInstalled) launchStandard(componentName ?: pkgName, forceDisplayId = forceDisplayId)
            else OttCatalog.webUrlFor(pkgName)?.let { launchBrowser(it, pkgName) }

            if (host.currentCodecMode == "mjpeg") {
                host.wakeDisplayForRecovery(controller.getPrivilegedService(), displayId, "ime_focus_sync")
            }
        }

        suspend fun restoreContentLocked(expectedGeneration: Long, expectedDisplayId: Int) {
            val activeId = if (displayId >= 0) displayId else controller.getDisplayId()
            ContentRestoreCoordinator(
                markMutation = ::markServiceMutation,
                setCurrentApp = { currentApp = it },
                launchHome = controller::launchHomeOnDisplay,
                resolveBrowserComponent = { url -> BrowserResolver.resolve(host, url)?.componentFlat },
                launchExternalBrowser = { url, component ->
                    val command = ShellLaunchCommandBuilder.buildExternalBrowserCommand(activeId, url, component)
                    try {
                        if (isCurrentVd(vdGeneration.get(), activeId)) {
                            controller.getPrivilegedService()?.execCommand(command)
                            true
                        } else false
                    } catch (_: Exception) {
                        false
                    }
                },
                launchOwnActivity = { activity, url -> launchOwnActivity(activity, url) },
                launchComponent = { app -> launchComponent(app, forceColdStart = false, forceTaskRealign = true) },
            ).restore(
                ContentRestoreRequest(
                    currentApp = currentApp,
                    currentWebUrl = currentWebUrl,
                    activeDisplayId = activeId,
                    isVirtualDisplayCurrent = { isCurrentVd(expectedGeneration, expectedDisplayId) },
                )
            )
        }

        fun restoreContent() {
            val token = currentVdToken() ?: return
            host.serviceScope.launch(Dispatchers.IO) { restoreContentLocked(token.first, token.second) }
        }
        suspend fun release(forcePhysical: Boolean = false) {
            if (forcePhysical) executeReleaseInternal(forcePhysical = true)
            else {
                withContext(host.vdDispatcher) {
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
                    host.logInputDebugSnapshot("pipeline_release_begin:$name")

                    Log.i(TAG, "[CLEANUP_STOP_LOOPS]")
                    videoEncoder?.stop()
                    jpegEncoder?.stop()

                    Log.i(TAG, "[CLEANUP_CALLBACKS_UNREGISTERED]")
                    videoEncoder?.unregisterCallbacks()
                    jpegEncoder?.unregisterCallbacks()

                    Log.i(TAG, "[CLEANUP_VD_RELEASED]")
                    if (displayId >= 0) {
                        host.cleanupDisplay(displayId)
                        if (forcePhysical) { host.runBinderSafe { controller.releaseVirtualDisplay() }; displayId = -1 }
                        else { try { host.runBinderSafe { controller.resizeDisplay(1, 1, 160) }; width = 1; height = 1 } catch (_: Exception) {} }
                    }

                    Log.i(TAG, "[CLEANUP_CODEC_STOPPED]")
                    videoEncoder?.stopCodecOnly()

                    Log.i(TAG, "[CLEANUP_CODEC_RELEASED]")
                    videoEncoder?.releaseCodecOnly()
                    jpegEncoder?.releaseReaderOnly()

                    Log.i(TAG, "[CLEANUP_JOIN_ENCODERS]")
                    videoEncoder?.join()
                    jpegEncoder?.join()

                    Log.i(TAG, "[CLEANUP_SURFACE_RELEASED]")
                    currentEncoderSurface?.let { surf ->
                        com.castla.mirror.diagnostics.ResourceTracker.trackSurfaceRelease(surf.hashCode(), "VideoEncoderInputSurface@${surf.hashCode()}")
                        try { surf.release() } catch (_: Exception) {}
                    }
                    currentEncoderSurface = null

                    try {
                        videoEncoder?.release()
                        jpegEncoder?.release()
                    } catch (_: Exception) {}
                    videoEncoder = null
                    jpegEncoder = null

                    try {
                        resizeJob?.cancel()
                        resizeJob?.join()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to join resizeJob", e)
                    }
                    resizeJob = null

                    try { touchInjector?.detachController("pipeline_release") } catch (_: Exception) {}
                    touchInjector?.release()
                    isVideoApp = false
                    host.mirrorServer?.setKeyframeRequester(name) { _ -> }
                    width = 0; height = 0; requestedWidth = 0; requestedHeight = 0
                    currentApp = ""; currentWebUrl = null
                    host.adaptiveBitrateManager.rebalanceBitrates()

                    released.set(true)
                    host.logInputDebugSnapshot("pipeline_release_end:$name")
                    Log.i(TAG, "[CLEANUP_DONE] [$name Pipeline] Display shutdown completed.")
                }
            } finally {
                releasing.set(false)
            }
        }    }
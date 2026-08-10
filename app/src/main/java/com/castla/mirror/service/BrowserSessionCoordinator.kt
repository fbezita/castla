package com.castla.mirror.service

import android.os.Build
import android.util.Log
import android.util.Size
import com.castla.mirror.compositor.DisplayTier
import com.castla.mirror.diagnostics.ResourceTracker
import com.castla.mirror.diagnostics.TerminalReason
import com.castla.mirror.policy.DisconnectPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

internal class BrowserSessionCoordinator(private val host: MirrorForegroundService) {
    companion object { private const val TAG = "MirrorForegroundService" }

    private var lastVisiblePaneCount = 1
    @Volatile var hasReceivedLayout = false
        private set
    var pendingDisconnectJob: Job? = null
        private set

    fun onConnected() {
        try {
            Log.i(TAG, "onBrowserConnected() - WebSocket link stabilized. Launching encoder engines.")
            host.powerLockManager.acquireWakeLocks()
            host.startVdKeepAlive()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                host.thermalThrottleManager.broadcastThermalStatus(host.thermalThrottleManager.thermalStatus.value)
            }
            host.adaptiveBitrateManager.startAllLoops()

            host.serviceScope.launch {
                delay(200)
                host.isInitialRebuildTriggered = true
                val primary = host.pipelines["primary"] ?: return@launch
                primary.markFreshLaunchPreparation("browser_connected")
                host.paneVisibility["primary"] = true
                primary.setTier(DisplayTier.ACTIVE, "browser_connected")
                if (!LaunchRecoveryPolicy.shouldDeferInitialBrowserConnectedRebuild(hasReceivedLayout, primary.displayId)) {
                    val finalW = when {
                        primary.width > 1 -> primary.width
                        primary.requestedWidth > 1 -> primary.requestedWidth
                        else -> primary.lastValidWidth.coerceAtLeast(720)
                    }
                    val finalH = when {
                        primary.height > 1 -> primary.height
                        primary.requestedHeight > 1 -> primary.requestedHeight
                        else -> primary.lastValidHeight.coerceAtLeast(720)
                    }
                    host.triggerPipelineRebuildWithPolicy(primary.name, finalW, finalH, force = true)
                } else {
                    host.logLaunchRecoveryInfo(
                        "initial_rebuild_deferred pane=${primary.name} reason=await_first_browser_layout " +
                            "displayId=${primary.displayId} requested=${primary.requestedWidth}x${primary.requestedHeight}"
                    )
                }

                host.pipelines["secondary"]?.let { secondary ->
                    secondary.markFreshLaunchPreparation("browser_connected_secondary")
                    if (host.paneVisibility["secondary"] != true) {
                        secondary.setTier(DisplayTier.SUSPENDED, "browser_connected_secondary_hidden")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed onBrowserConnected", t)
            host.markTerminal(TerminalReason.BROWSER_ACTIVATION_FAILED)
        }
        host.broadcastWebDiagnostics("diagnostics_debounced")
        host.broadcastWebDiagnostics("browser_connected")
    }

    fun applyLayout(panes: JSONArray) {
        hasReceivedLayout = panes.length() > 0
        val paneStates = mutableListOf<Triple<String, Size, Boolean>>()
        val seen = mutableSetOf<String>()
        for (i in 0 until panes.length()) {
            val paneObj = panes.optJSONObject(i) ?: continue
            val paneId = paneObj.optString("id")
            if (paneId.isBlank()) continue
            val width = paneObj.optInt("width", 0)
            val height = paneObj.optInt("height", 0)
            val visible = paneObj.optBoolean("visible", width > 0 && height > 0)
            seen += paneId
            host.paneVisibility[paneId] = visible
            paneStates += Triple(paneId, Size(width, height), visible)
        }

        val visiblePanes = paneStates.filter { (_, size, visible) -> visible && size.width > 0 && size.height > 0 }
        val visiblePaneCount = visiblePanes.size
        val singleVisiblePane = visiblePanes.singleOrNull()?.first
        for ((paneId, size, visible) in paneStates) {
            val pipeline = host.pipelines[paneId] ?: continue
            if (visible && size.width > 0 && size.height > 0) {
                val forceLayoutRealign = BrowserLayoutPolicy.shouldForceViewportRealign(
                    previousVisiblePaneCount = lastVisiblePaneCount,
                    currentVisiblePaneCount = visiblePaneCount,
                    previousWidth = pipeline.requestedWidth,
                    previousHeight = pipeline.requestedHeight,
                    nextWidth = size.width,
                    nextHeight = size.height,
                )
                val tier = if (singleVisiblePane == paneId || (singleVisiblePane == null && paneId == "primary")) {
                    DisplayTier.ACTIVE
                } else {
                    DisplayTier.VISIBLE
                }
                host.serviceScope.launch { pipeline.setTier(tier, "browser_layout_visible") }
                pipeline.onViewportChange(size.width, size.height, forceLayoutRealign)
            } else {
                host.serviceScope.launch { pipeline.setTier(DisplayTier.SUSPENDED, "browser_layout_hidden") }
            }
        }
        lastVisiblePaneCount = visiblePaneCount

        host.pipelines.forEach { (paneId, pipeline) ->
            if (!seen.contains(paneId) && host.paneVisibility[paneId] == true) {
                host.paneVisibility[paneId] = false
                host.serviceScope.launch { pipeline.setTier(DisplayTier.SUSPENDED, "browser_layout_absent") }
            }
        }
    }

    fun onDisconnected() {
        Log.w(TAG, "onBrowserDisconnected() - Target web panel dropped connection link.")
        pendingDisconnectJob = null
        host.browserConnected = false
        host.isInitialRebuildTriggered = false
        hasReceivedLayout = false
        host.browserTeardownPhase = "begin"
        host.stopVdKeepAlive()

        val oldEncoders = host.pipelines.values.map { pipeline ->
            val video = pipeline.videoEncoder
            val jpeg = pipeline.jpegEncoder
            pipeline.videoEncoder = null
            pipeline.jpegEncoder = null
            pipeline.currentEncoderSurface?.let { surface ->
                ResourceTracker.trackSurfaceRelease(surface.hashCode(), "VideoEncoderInputSurface@${surface.hashCode()}")
                try { surface.release() } catch (_: Exception) {}
            }
            pipeline.currentEncoderSurface = null
            video to jpeg
        }

        host.pipelines.values.forEach { pipeline ->
            try { pipeline.touchInjector?.detachController("browser_disconnected") } catch (_: Exception) {}
            pipeline.markFreshLaunchPreparation("browser_disconnected")
        }

        host.audioOrchestrator?.stop()
        host.adaptiveBitrateManager.stopAllLoops()
        host.powerLockManager.releaseWakeLocks()
        host.broadcastWebDiagnostics("browser_disconnected_sync")

        host.serviceScope.launch(Dispatchers.IO) {
            host.browserTeardownPhase = "releasing"
            oldEncoders.forEach { (video, jpeg) ->
                try { video?.release() } catch (_: Exception) {}
                try { jpeg?.release() } catch (_: Exception) {}
            }
            // Keep the display token and privileged binder alive until release() has removed
            // the tasks belonging to that VD. Releasing/invalidation first makes task cleanup
            // impossible and can leave the mirrored app in the system task stack.
            host.pipelines.values.forEach { try { it.release(forcePhysical = true) } catch (_: Exception) {} }
            host.browserTeardownPhase = "released"
            host.broadcastWebDiagnostics("browser_disconnected_async_done")
        }
        host.broadcastWebDiagnostics("diagnostics_debounced")
        host.broadcastWebDiagnostics("browser_disconnected")
    }

    fun cancelPendingDisconnect(reason: String) {
        pendingDisconnectJob?.cancel()
        pendingDisconnectJob = null
        host.broadcastWebDiagnostics("cancel_pending_disconnect:$reason")
    }

    fun scheduleDisconnect() {
        if (pendingDisconnectJob != null) return
        val screenOff = host.isPhysicalScreenOff
        host.broadcastWebDiagnostics("schedule_disconnect")
        pendingDisconnectJob = host.serviceScope.launch {
            delay(DisconnectPolicy.graceMs(screenOff))
            pendingDisconnectJob = null
            host.broadcastWebDiagnostics("disconnect_grace_elapsed")
            if (host.mirrorServer?.isBrowserConnected() == true) return@launch
            if (!DisconnectPolicy.shouldTeardown(screenOff, isBrowserConnected = false)) return@launch
            if (host.browserConnected) onDisconnected()
            host.notifyBrowserConnection(false)
        }
    }

    fun cleanup() {
        pendingDisconnectJob?.cancel()
        pendingDisconnectJob = null
    }
}

package com.castla.mirror.service

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class VirtualDisplayRebuildCoordinator(private val host: MirrorForegroundService) {
    companion object { private const val TAG = "MirrorForegroundService" }

    private val channel = Channel<MirrorForegroundService.VdHardwareRequest>(RebuildRequestPolicy.MAX_PENDING_REQUESTS)
    private val requestMutex = Mutex()
    private val lastRequestByPane = java.util.concurrent.ConcurrentHashMap<String, RebuildRequestPolicy.RequestSnapshot>()
    private var workerJob: Job? = null

    suspend fun request(request: MirrorForegroundService.RebuildRequest) {
        val pipeline = host.pipelines[request.pipelineName]
        if (pipeline == null || MirrorForegroundService.isAppLaunchingContext || request.width <= 0 || request.height <= 0) {
            request.onComplete?.complete(Unit)
            return
        }
        host.logLaunchRecoveryInfo(
            "rebuild_request_received id=${request.requestId} pane=${request.pipelineName} reason=${request.reason} " +
                "priority=${request.priority} target=${request.width}x${request.height} force=${request.force} forceSingle=${request.forceSingle} " +
                "launchingContext=${MirrorForegroundService.isAppLaunchingContext}"
        )

        val now = SystemClock.elapsedRealtime()
        var coalesced = false
        requestMutex.withLock {
            val last = lastRequestByPane[request.pipelineName]
            coalesced = RebuildRequestPolicy.shouldCoalesce(
                previous = last,
                width = request.width,
                height = request.height,
                force = request.force,
                forceSingle = request.forceSingle,
                requestedAt = now,
                hasCompletion = request.onComplete != null,
                immediate = request.priority == MirrorForegroundService.RebuildPriority.IMMEDIATE,
            )
            if (!coalesced) {
                lastRequestByPane[request.pipelineName] = RebuildRequestPolicy.RequestSnapshot(
                    request.width,
                    request.height,
                    request.force,
                    request.forceSingle,
                    now,
                )
            }
        }
        if (coalesced) {
            host.logLaunchRecoveryInfo("rebuild_request_coalesced id=${request.requestId} pane=${request.pipelineName} reason=${request.reason} target=${request.width}x${request.height}")
            return
        }

        val deferStart = SystemClock.elapsedRealtime()
        var deferredForTouch = false
        var skippedForTouchQuietWindow = false
        while (request.priority != MirrorForegroundService.RebuildPriority.IMMEDIATE) {
            val recentTouchAgeMs = host.mostRecentTouchAgeMs()
            val requiresQuietWindow = request.priority == MirrorForegroundService.RebuildPriority.LOW
            val touchBlocked = host.isAnyTouchInteractionActive() ||
                (requiresQuietWindow && recentTouchAgeMs != null && recentTouchAgeMs < 2500L)
            if (!touchBlocked) break
            deferredForTouch = true
            delay(60L)
            if (SystemClock.elapsedRealtime() - deferStart >= 1500L) {
                if (requiresQuietWindow) skippedForTouchQuietWindow = true
                break
            }
        }
        if (deferredForTouch) {
            host.logLaunchRecoveryInfo("rebuild_request_touch_deferred id=${request.requestId} pane=${request.pipelineName}")
        }
        if (skippedForTouchQuietWindow) {
            host.logLaunchRecoveryInfo("rebuild_request_skipped id=${request.requestId} pane=${request.pipelineName} reason=${request.reason} priority=${request.priority} source=touch_quiet_window target=${request.width}x${request.height}")
            request.onComplete?.complete(Unit)
            return
        }

        pipeline.debugRebuildRequests += 1
        try {
            channel.send(
                MirrorForegroundService.VdHardwareRequest.Rebuild(
                    request.requestId, request.reason, request.pipelineName, request.width, request.height,
                    request.force, request.forceSingle, request.onComplete,
                )
            )
        } catch (t: Throwable) {
            request.onComplete?.completeExceptionally(t)
            throw t
        }
    }

    fun start() {
        workerJob?.cancel()
        workerJob = host.serviceScope.launch(host.vdDispatcher) {
            for (request in channel) {
                if (!isActive) break
                try {
                    when (request) {
                        is MirrorForegroundService.VdHardwareRequest.Rebuild -> process(request)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "[VdWorker] Failed to process sequential hardware request", e)
                }
            }
        }
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
    }

    private suspend fun process(request: MirrorForegroundService.VdHardwareRequest.Rebuild) {
        try {
            host.logLaunchRecoveryInfo("rebuild_worker_begin id=${request.requestId} pane=${request.pipelineName} reason=${request.reason} target=${request.targetWidth}x${request.targetHeight} force=${request.force} forceSingle=${request.forceSingle}")
            val pipeline = host.pipelines[request.pipelineName] ?: return
            val skipStale = RebuildRequestPolicy.shouldSkipStaleRequest(
                request.targetWidth,
                request.targetHeight,
                RebuildRequestPolicy.PendingViewport(
                    pipeline.requestedWidth,
                    pipeline.requestedHeight,
                    host.hasReceivedBrowserLayout,
                ),
            )
            if (skipStale) {
                host.logLaunchRecoveryInfo("rebuild_worker_skip_stale id=${request.requestId} pane=${request.pipelineName} reason=${request.reason} target=${request.targetWidth}x${request.targetHeight} latest=${pipeline.requestedWidth}x${pipeline.requestedHeight}")
                return
            }
            pipeline.executeActualRebuild(
                request.requestId, request.reason, request.targetWidth, request.targetHeight,
                request.force, request.forceSingle,
            )
        } finally {
            host.logLaunchRecoveryInfo("rebuild_worker_end id=${request.requestId} pane=${request.pipelineName}")
            request.onComplete?.complete(Unit)
        }
    }
}

package com.castla.mirror.service

import android.content.Context
import android.util.Log
import com.castla.mirror.utils.StreamMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.castla.mirror.server.MirrorServer
import com.castla.mirror.policy.AutoScaleDecision
import com.castla.mirror.policy.AutoScaleInput
import com.castla.mirror.policy.AutoScalePolicy

class AdaptiveBitrateManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val getPipelines: () -> Map<String, MirrorForegroundService.MirroringPipeline>,
    private val getBrowserConnected: () -> Boolean,
    private val getIsServiceRunning: () -> Boolean,
    private val getThermalActive: () -> Boolean,
    private val getThermalFpsOverride: () -> Int?,
    private val getThermalMaxHeight: () -> Int?,
    private val getMirrorServer: () -> MirrorServer?
) {
    private val TAG = "AdaptiveBitrateManager"

    data class AutoTier(val maxHeight: Int, val fps: Int, val bitrate: Int, val label: String)
    
    companion object {
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
    }

    var globalBitrateBudget: Int = 5_000_000
    var abrJob: Job? = null
    var lastCongestionTimeMs: Long = 0L
    var autoScaleJob: Job? = null

    private val pipelineScaleTiers = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val pipelineStableCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    
    // 💡 [오토스케일 평가 쿨타임 가드 맵 컨텍스트 추가]
    private val lastScaleEvaluationTimeMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile var lastQualityDroppedFrames: Int = 0
    @Volatile var lastQualityAvgDelayMs: Double = 0.0
    @Volatile var lastQualityBacklogDrops: Int = 0

    fun updateQualityMetrics(dropped: Int, delay: Double, backlog: Int) {
        lastQualityDroppedFrames = dropped
        lastQualityAvgDelayMs = delay
        lastQualityBacklogDrops = backlog
    }

    fun startAllLoops() {
        stopAllLoops()
        
        abrJob = serviceScope.launch {
            while (getIsServiceRunning() && getBrowserConnected()) {
                kotlinx.coroutines.delay(2000)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastCongestionTimeMs >= 2000) {
                    // ABR 증량 필요 시 가중치 분배 엔진 호출 라우팅 유도 가능
                    MirrorForegroundService.instance?.contentAwareQualityEngine?.rebalanceMultiDisplayBitrates(
                        getPipelines().values.toList()
                    )
                }
            }
        }

        autoScaleJob = serviceScope.launch {
            kotlinx.coroutines.delay(AUTO_SCALE_INITIAL_DELAY_MS)
            while (getIsServiceRunning() && getBrowserConnected()) {
                getPipelines().values.filter { it.width > 0 && it.height > 0 }.forEach { pipeline ->
                    evaluateSinglePipelineScale(pipeline)
                }
                kotlinx.coroutines.delay(AUTO_SCALE_INTERVAL_MS)
            }
        }
    }

    fun stopAllLoops() {
        abrJob?.cancel(); abrJob = null
        autoScaleJob?.cancel(); autoScaleJob = null
        pipelineScaleTiers.clear(); pipelineStableCounts.clear(); lastScaleEvaluationTimeMs.clear()
    }

    fun resetTiers() { pipelineScaleTiers.clear(); pipelineStableCounts.clear() }

    fun onNetworkCongestion() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCongestionTimeMs > 500) { 
            lastCongestionTimeMs = now
            getPipelines().values.filter { it.width > 0 && it.height > 0 }.forEach { pipeline ->
                pipeline.currentBitrate = (pipeline.currentBitrate * 0.8).toInt().coerceAtLeast(400_000)
                pipeline.videoEncoder?.setBitrate(pipeline.currentBitrate)
            }
        }
    }

    fun getSharedBitrateForPipeline(pipeline: MirrorForegroundService.MirroringPipeline): Int {
        val allActivePipelines = getPipelines().values.filter { it.displayId >= 0 && it.width > 0 }
        val activeCount = allActivePipelines.size.coerceAtLeast(1)

        return if (activeCount > 1) {
            if (pipeline.isVideoApp) StreamMath.calculateSplitVideoBitrate(pipeline.width, pipeline.height)
            else StreamMath.calculateSplitCompanionBitrate(pipeline.width, pipeline.height)
        } else {
            val tierIdx = pipelineScaleTiers[pipeline.name] ?: 1
            val activeTiers = AUTO_TIERS.filter { it.maxHeight == pipeline.currentMaxHeight }
            val tierBitrate = if (activeTiers.isNotEmpty()) activeTiers[tierIdx.coerceIn(0, activeTiers.size - 1)].bitrate else 3_000_000
            if (pipeline.isVideoApp && !getThermalActive()) StreamMath.calculateOttBitrate(tierBitrate) else tierBitrate
        }
    }

    fun rebalanceBitrates() {
        // 💡 기존의 레거시 구조를 무너뜨리지 않고 고성능 엔진 단일 통로 채널로 자동 토스 처리를 수행합니다.
        MirrorForegroundService.instance?.contentAwareQualityEngine?.rebalanceMultiDisplayBitrates(
            getPipelines().values.toList()
        )
    }

    fun evaluateSinglePipelineScale(pipeline: MirrorForegroundService.MirroringPipeline) {
        val now = android.os.SystemClock.elapsedRealtime()
        val lastEval = lastScaleEvaluationTimeMs[pipeline.name] ?: 0L
        
        // 💡 [중복 겹침 방지 레이어] 1.5초 이내에 연속으로 들어온 요구 사항은 중복 과부하 처리이므로 원천 거절합니다.
        if (now - lastEval < 1500L) {
            Log.d(TAG, "[AutoScale Guard] Debounced duplicate evaluation request for ${pipeline.name}")
            return
        }
        lastScaleEvaluationTimeMs[pipeline.name] = now

        val activeTiers = AUTO_TIERS.filter { it.maxHeight == pipeline.currentMaxHeight }
        if (activeTiers.isEmpty()) return

        val tierIdx = pipelineScaleTiers[pipeline.name] ?: activeTiers.indexOfFirst { it.fps == 30 }.coerceAtLeast(0)
        val stableCount = pipelineStableCounts[pipeline.name] ?: 0

        val input = AutoScaleInput(
            thermalStatus = _getThermalStatusValue(),
            networkStable = now - lastCongestionTimeMs >= AUTO_SCALE_INTERVAL_MS,
            browserHealthy = AutoScalePolicy.isBrowserHealthy(lastQualityDroppedFrames, lastQualityBacklogDrops, lastQualityAvgDelayMs),
            currentTierIndex = tierIdx.coerceIn(0, activeTiers.size - 1),
            stableCount = stableCount,
            tierCount = activeTiers.size
        )

        when (val decision = AutoScalePolicy.evaluate(input)) {
            is AutoScaleDecision.DropToTier -> {
                pipelineScaleTiers[pipeline.name] = decision.tierIndex.coerceIn(0, activeTiers.size - 1)
                pipelineStableCounts[pipeline.name] = 0
                applyPipelineScale(pipeline)
                notifyAutoTierChange(pipeline, activeTiers, decision.reason)
            }
            is AutoScaleDecision.StepDown -> {
                pipelineScaleTiers[pipeline.name] = decision.newTierIndex.coerceIn(0, activeTiers.size - 1)
                pipelineStableCounts[pipeline.name] = 0
                applyPipelineScale(pipeline)
                notifyAutoTierChange(pipeline, activeTiers, decision.reason)
            }
            is AutoScaleDecision.StepUp -> {
                pipelineScaleTiers[pipeline.name] = decision.newTierIndex.coerceIn(0, activeTiers.size - 1)
                pipelineStableCounts[pipeline.name] = 0
                applyPipelineScale(pipeline)
                notifyAutoTierChange(pipeline, activeTiers, "stable")
            }
            is AutoScaleDecision.Hold -> { pipelineStableCounts[pipeline.name] = decision.newStableCount }
            AutoScaleDecision.Block -> { pipelineStableCounts[pipeline.name] = 0 }
        }
    }

    private fun applyPipelineScale(pipeline: MirrorForegroundService.MirroringPipeline) {
        val activeTiers = AUTO_TIERS.filter { it.maxHeight == pipeline.currentMaxHeight }
        if (activeTiers.isEmpty()) return
        val tierIdx = pipelineScaleTiers[pipeline.name] ?: 0
        val tier = activeTiers[tierIdx.coerceIn(0, activeTiers.size - 1)]

        val isResolutionChanging = pipeline.autoResolution && (pipeline.currentMaxHeight != tier.maxHeight)
        if (pipeline.autoResolution) pipeline.currentMaxHeight = tier.maxHeight
        if (pipeline.autoFps) pipeline.targetFps = tier.fps

        // 💡 변경된 오토스케일 예하 비트레이트 주입도 일원화된 엔진 연산 체계를 거치도록 보정
        MirrorForegroundService.instance?.contentAwareQualityEngine?.rebalanceMultiDisplayBitrates(
            getPipelines().values.toList()
        )

        if (isResolutionChanging && getBrowserConnected()) {
            serviceScope.launch {
                pipeline.requestRebuild(
                    reason = "adaptive_scale_resolution",
                    priority = MirrorForegroundService.RebuildPriority.LOW,
                    newWidth = pipeline.width,
                    newHeight = pipeline.height,
                    force = true
                )
            }
        }
    }

    private fun notifyAutoTierChange(pipeline: MirrorForegroundService.MirroringPipeline, activeTiers: List<AutoTier>, reason: String) {
        val tierIdx = pipelineScaleTiers[pipeline.name] ?: 0
        val label = activeTiers[tierIdx.coerceIn(0, activeTiers.size - 1)].label
        getMirrorServer()?.broadcastControlMessage(JSONObject().apply {
            put("type", "autoScaleChange"); put("pane", pipeline.name); put("tier", label); put("reason", reason)
        }.toString())
    }

    private fun _getThermalStatusValue(): Int {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) pm.currentThermalStatus else 0
    }
}

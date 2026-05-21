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
    // 특정 개별 변수 대신 전체 파이프라인 풀을 조회하는 람다 하나만 수용
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

    // 기본 네트워크 보장 대역폭 버젯
    var globalBitrateBudget: Int = 5_000_000
    var abrJob: Job? = null
    var lastCongestionTimeMs: Long = 0L
    var autoScaleJob: Job? = null

    // 각 독립 파이프라인별 오토스케일 제어 상태 인덱스를 격리 관리하기 위한 매핑 컨텍스트
    private val pipelineScaleTiers = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val pipelineStableCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

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
        
        // ABR 대역폭 복구 루프 시작
        abrJob = serviceScope.launch {
            while (getIsServiceRunning() && getBrowserConnected()) {
                kotlinx.coroutines.delay(2000)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastCongestionTimeMs >= 2000) {
                    var incrementalApplied = false
                    getPipelines().values.filter { it.width > 0 && it.height > 0 }.forEach { pipeline ->
                        val target = getSharedBitrateForPipeline(pipeline)
                        if (pipeline.currentBitrate < target) {
                            pipeline.currentBitrate = (pipeline.currentBitrate * 1.1).toInt().coerceAtMost(target)
                            pipeline.videoEncoder?.setBitrate(pipeline.currentBitrate)
                            incrementalApplied = true
                        }
                    }
                    if (incrementalApplied) Log.i(TAG, "ABR: Network stable. Step-increasing shared allocation.")
                }
            }
        }

        // 오토스케일 루프 시작
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
        lastQualityDroppedFrames = 0; lastQualityAvgDelayMs = 0.0; lastQualityBacklogDrops = 0
        pipelineScaleTiers.clear(); pipelineStableCounts.clear()
    }

    fun resetTiers() {
        pipelineScaleTiers.clear()
        pipelineStableCounts.clear()
    }

    fun onNetworkCongestion() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCongestionTimeMs > 500) { 
            lastCongestionTimeMs = now
            getPipelines().values.filter { it.width > 0 && it.height > 0 }.forEach { pipeline ->
                pipeline.currentBitrate = (pipeline.currentBitrate * 0.8).toInt().coerceAtLeast(400_000)
                pipeline.videoEncoder?.setBitrate(pipeline.currentBitrate)
            }
            Log.w(TAG, "ABR: Network congestion -> Symmetrically dropping bitrates across all active loops.")
        }
    }

    // [독립화 핵심] 가상화면에 주어질 대역폭을 독립 파이프라인의 해상도 및 비디오 여부 상태에 맞춰 순수 계산식으로 도출
    fun getSharedBitrateForPipeline(pipeline: MirrorForegroundService.MirroringPipeline): Int {
        val allActivePipelines = getPipelines().values.filter { it.displayId >= 0 && it.width > 0 }
        val activeCount = allActivePipelines.size.coerceAtLeast(1)

        val baseBitrate = if (activeCount > 1) {
            // 다중 결합 화면 구동 시 비디오 여부 파이를 수식으로 도출 (Symmetric Fair Share)
            if (pipeline.isVideoApp) StreamMath.calculateSplitVideoBitrate(pipeline.width, pipeline.height)
            else StreamMath.calculateSplitCompanionBitrate(pipeline.width, pipeline.height)
        } else {
            val tierIdx = pipelineScaleTiers[pipeline.name] ?: 1
            val activeTiers = AUTO_TIERS.filter { it.maxHeight == pipeline.currentMaxHeight }
            val tierBitrate = if (activeTiers.isNotEmpty()) activeTiers[tierIdx.coerceIn(0, activeTiers.size - 1)].bitrate else 3_000_000
            if (pipeline.isVideoApp && !getThermalActive()) StreamMath.calculateOttBitrate(tierBitrate) else tierBitrate
        }
        return baseBitrate
    }

    fun rebalanceBitrates() {
        getPipelines().values.filter { it.width > 0 && it.height > 0 }.forEach { pipeline ->
            val budget = getSharedBitrateForPipeline(pipeline)
            pipeline.currentBitrate = budget
            pipeline.videoEncoder?.setBitrate(budget)
        }
    }

    fun evaluateSinglePipelineScale(pipeline: MirrorForegroundService.MirroringPipeline) {
        val activeTiers = AUTO_TIERS.filter { it.maxHeight == pipeline.currentMaxHeight }
        if (activeTiers.isEmpty()) return

        val tierIdx = pipelineScaleTiers[pipeline.name] ?: activeTiers.indexOfFirst { it.fps == 30 }.coerceAtLeast(0)
        val stableCount = pipelineStableCounts[pipeline.name] ?: 0

        val now = android.os.SystemClock.elapsedRealtime()
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

        val targetBudget = getSharedBitrateForPipeline(pipeline)
        pipeline.currentBitrate = targetBudget
        pipeline.videoEncoder?.setBitrate(targetBudget)

        if (isResolutionChanging && getBrowserConnected()) {
            serviceScope.launch { pipeline.rebuild(pipeline.width, pipeline.height, force = true) }
        }
    }

    private fun notifyAutoTierChange(pipeline: MirrorForegroundService.MirroringPipeline, activeTiers: List<AutoTier>, reason: String) {
        val tierIdx = pipelineScaleTiers[pipeline.name] ?: 0
        val label = activeTiers[tierIdx.coerceIn(0, activeTiers.size - 1)].label
        val json = JSONObject().apply {
            put("type", "autoScaleChange")
            put("pane", pipeline.name)
            put("tier", label)
            put("reason", reason)
        }.toString()
        getMirrorServer()?.broadcastControlMessage(json)
    }

    private fun _getThermalStatusValue(): Int {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) pm.currentThermalStatus else 0
    }
}
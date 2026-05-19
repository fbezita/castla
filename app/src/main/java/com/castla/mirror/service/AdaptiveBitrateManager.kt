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
import com.castla.mirror.service.MirrorForegroundService

class AdaptiveBitrateManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val primaryPipeline: MirrorForegroundService.VirtualDisplayPipeline,
    private val secondaryPipeline: MirrorForegroundService.VirtualDisplayPipeline,
    private val getBrowserConnected: () -> Boolean,
    private val getIsServiceRunning: () -> Boolean,
    private val getIsCurrentAppVideo: () -> Boolean,
    private val getIsSecondaryAppVideo: () -> Boolean,
    private val getHasSplit: () -> Boolean,
    private val getThermalActive: () -> Boolean,
    private val getThermalFpsOverride: () -> Int?,
    private val getThermalMaxHeight: () -> Int?,
    private val getMirrorServer: () -> MirrorServer?,
    private val getCurrentFps: () -> Int,
    private val setCurrentFps: (Int) -> Unit,
    private val getCurrentMaxHeight: () -> Int,
    private val setCurrentMaxHeight: (Int) -> Unit,
    private val rebuildPipeline: suspend (Int, Int, Boolean, Boolean) -> Unit,
    private val rebuildSecondaryPipeline: suspend (Int, Int) -> Unit
) {
    private val TAG = "AdaptiveBitrateManager"

    data class AutoTier(val maxHeight: Int, val fps: Int, val bitrate: Int, val label: String)
    
    companion object {
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
        
        private const val AUTO_SCALE_INTERVAL_MS = 10_000L
        private const val AUTO_SCALE_INITIAL_DELAY_MS = 5_000L
    }

    var targetBitrate: Int = 4_000_000
    var abrJob: Job? = null
    var lastCongestionTimeMs: Long = 0L

    var autoTierIndex: Int = 0
    var autoStableCount: Int = 0
    var autoScaleJob: Job? = null

    @Volatile var lastQualityDroppedFrames: Int = 0
    @Volatile var lastQualityAvgDelayMs: Double = 0.0
    @Volatile var lastQualityBacklogDrops: Int = 0

    fun startAbrLoop() {
        abrJob?.cancel()
        abrJob = serviceScope.launch {
            while (getIsServiceRunning() && getBrowserConnected()) {
                kotlinx.coroutines.delay(2000)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastCongestionTimeMs >= 2000 && primaryPipeline.currentBitrate < targetBitrate) {
                    primaryPipeline.currentBitrate = (primaryPipeline.currentBitrate * 1.1).toInt().coerceAtMost(targetBitrate)
                    primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)
                    Log.i(TAG, "ABR: Network stable. Increasing bitrate to ${primaryPipeline.currentBitrate / 1000}kbps")
                }
            }
        }
    }

    fun stopAbrLoop() {
        abrJob?.cancel()
        abrJob = null
        lastQualityDroppedFrames = 0
        lastQualityAvgDelayMs = 0.0
        lastQualityBacklogDrops = 0
    }

    fun startAutoScaleLoop(autoResolution: Boolean, autoFps: Boolean) {
        if (!autoResolution && !autoFps) return
        autoScaleJob?.cancel()
        
        val activeTiers = AUTO_TIERS.filter { it.maxHeight == getCurrentMaxHeight() }
        autoTierIndex = activeTiers.indexOfFirst { it.fps == 30 }.coerceAtLeast(0)
        autoStableCount = 0

        autoScaleJob = serviceScope.launch {
            kotlinx.coroutines.delay(AUTO_SCALE_INITIAL_DELAY_MS)
            while (getIsServiceRunning() && getBrowserConnected()) {
                evaluateAutoScale(autoResolution, autoFps)
                kotlinx.coroutines.delay(AUTO_SCALE_INTERVAL_MS)
            }
        }
    }

    fun stopAutoScaleLoop() {
        autoScaleJob?.cancel()
        autoScaleJob = null
    }

    fun resetTiers() {
        autoTierIndex = 0
        autoStableCount = 0
    }

    fun onNetworkCongestion() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCongestionTimeMs > 500) { 
            lastCongestionTimeMs = now
            val minBitrate = 500_000
            primaryPipeline.currentBitrate = (primaryPipeline.currentBitrate * 0.8).toInt().coerceAtLeast(minBitrate)
            primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)
            Log.w(TAG, "ABR: Network congestion detected! Dropping bitrate to ${primaryPipeline.currentBitrate / 1000}kbps")
        }
    }

    private fun evaluateAutoScale(autoResolution: Boolean, autoFps: Boolean) {
        val activeTiers = AUTO_TIERS.filter { it.maxHeight == getCurrentMaxHeight() }
        if (activeTiers.isEmpty()) return

        val now = android.os.SystemClock.elapsedRealtime()
        val input = AutoScaleInput(
            thermalStatus = _getThermalStatusValue(),
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
                applyAutoTier(autoResolution, autoFps)
                notifyAutoTierChange(decision.reason)
                Log.i(TAG, "AutoScale: ${decision.reason} ??dropped to ${activeTiers[autoTierIndex].label}")
            }
            is AutoScaleDecision.StepDown -> {
                autoTierIndex = decision.newTierIndex.coerceIn(0, activeTiers.size - 1)
                autoStableCount = 0
                applyAutoTier(autoResolution, autoFps)
                notifyAutoTierChange(decision.reason)
                Log.i(TAG, "AutoScale: ${decision.reason} ??stepped down to ${activeTiers[autoTierIndex].label}")
            }
            is AutoScaleDecision.StepUp -> {
                autoTierIndex = decision.newTierIndex.coerceIn(0, activeTiers.size - 1)
                autoStableCount = 0
                applyAutoTier(autoResolution, autoFps)
                notifyAutoTierChange("stable")
                Log.i(TAG, "AutoScale: stable ??stepped up to ${activeTiers[autoTierIndex].label}")
            }
            is AutoScaleDecision.Hold -> {
                autoStableCount = decision.newStableCount
            }
            AutoScaleDecision.Block -> {
                autoStableCount = 0
            }
        }
    }

    fun notifyAutoTierChange(reason: String) {
        val activeTiers = AUTO_TIERS.filter { it.maxHeight == getCurrentMaxHeight() }
        if (activeTiers.isEmpty()) return
        val tier = activeTiers[autoTierIndex.coerceIn(0, activeTiers.size - 1)]
        val json = JSONObject().apply {
            put("type", "autoTierChange")
            put("tier", tier.label)
            put("reason", reason)
        }.toString()
        getMirrorServer()?.broadcastControlMessage(json)
    }

    fun applyAutoTier(autoResolution: Boolean, autoFps: Boolean) {
        val activeTiers = AUTO_TIERS.filter { it.maxHeight == getCurrentMaxHeight() }
        if (activeTiers.isEmpty()) return
        val tier = activeTiers[autoTierIndex.coerceIn(0, activeTiers.size - 1)]
        
        val isResolutionChanging = autoResolution && (getCurrentMaxHeight() != tier.maxHeight)
        
        if (autoResolution) setCurrentMaxHeight(tier.maxHeight)
        if (autoFps) setCurrentFps(tier.fps)

        targetBitrate = tier.bitrate

        val now = android.os.SystemClock.elapsedRealtime()
        val canApply = now - lastCongestionTimeMs > 2000

        val thermalActive = getThermalActive()
        val isCurrentAppVideo = getIsCurrentAppVideo()
        val isSecondaryAppVideo = getIsSecondaryAppVideo()
        val hasSplit = getHasSplit()

        if (hasSplit && (isCurrentAppVideo || isSecondaryAppVideo) && !thermalActive) {
            val primaryBps = if (isCurrentAppVideo)
                StreamMath.calculateSplitVideoBitrate(primaryPipeline.width, primaryPipeline.height)
            else
                StreamMath.calculateSplitCompanionBitrate(primaryPipeline.width, primaryPipeline.height)

            val secondaryBps = if (isSecondaryAppVideo)
                StreamMath.calculateSplitVideoBitrate(secondaryPipeline.width, secondaryPipeline.height)
            else
                StreamMath.calculateSplitCompanionBitrate(secondaryPipeline.width, secondaryPipeline.height)

            primaryPipeline.currentBitrate = primaryBps
            primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)

            secondaryPipeline.currentBitrate = secondaryBps
            secondaryPipeline.videoEncoder?.setBitrate(secondaryPipeline.currentBitrate)

            Log.i(TAG, "AutoScale: splits active ??forcing strict split bitrates (Primary: ${primaryBps/1000}kbps, Secondary: ${secondaryBps/1000}kbps)")
        } else {
            val baseBitrate = if (canApply) targetBitrate else primaryPipeline.currentBitrate
            val effectiveTarget = if (isCurrentAppVideo && !thermalActive)
                StreamMath.calculateOttBitrate(baseBitrate)
            else
                baseBitrate

            primaryPipeline.currentBitrate = effectiveTarget
            primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)
            Log.i(TAG, "AutoScale: applied ${tier.label} ??bitrate: ${primaryPipeline.currentBitrate/1000}kbps, fps: ${getCurrentFps()} (targetBitrate: ${targetBitrate/1000}kbps)")
        }

        if (isResolutionChanging && getBrowserConnected()) {
            serviceScope.launch {
                rebuildPipeline(primaryPipeline.width, primaryPipeline.height, true, false)
                if (secondaryPipeline.width > 0 && secondaryPipeline.height > 0) {
                    rebuildSecondaryPipeline(secondaryPipeline.width, secondaryPipeline.height)
                }
            }
        }
    }

    private fun _getThermalStatusValue(): Int {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            pm.currentThermalStatus
        } else {
            0
        }
    }
}

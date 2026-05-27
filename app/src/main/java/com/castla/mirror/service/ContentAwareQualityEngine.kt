package com.castla.mirror.service

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import com.castla.mirror.capture.VideoEncoder
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Content-aware image quality optimization and runtime parameter tuning engine
 */
class ContentAwareQualityEngine(
    private val getGlobalBudget: () -> Int,
    private val broadcastControlMessage: (String) -> Unit
) {
    private val TAG = "ContentAwareQualityEngine"

    // Define content profiles
    enum class ContentProfile {
        TEXT_HEAVY,   // Maps, navigators, browsers (prioritizes readability and sharpness)
        MOTION_HEAVY, // YouTube, Netflix and other OTT apps (prioritizes smooth frames and jitter suppression)
        BALANCED      // General UI, settings, etc.
    }

    // Dynamic tuning parameter state management data structure per pipeline
    data class TuningState(
        val pipelineName: String,
        var currentProfile: ContentProfile = ContentProfile.BALANCED,
        var currentBitrateFloor: Int = 1_500_000,
        var targetBitrate: Int = 2_500_000,
        var qpOffset: Int = 0,               // Autonomous QP offset controlled by the feedback loop
        var consecutiveUnhealthyCount: Int = 0
    )

    private val tuningRegistry = ConcurrentHashMap<String, TuningState>()

    companion object {
        // Key navigation and map app package signature map
        private val TEXT_HEAVY_PACKAGES = setOf(
            "com.google.android.apps.maps",
            "com.skt.tmap.ku",
            "com.naver.vnavigator",
            "com.locnall.KimGiRok",
            "com.kakao.taxi"
        )
    }

    /**
     * [Task 1] Real-time content profile determination based on app package name
     */
    fun resolveContentProfile(packageName: String, isVideoApp: Boolean): ContentProfile {
        val cleanPkg = packageName.substringBefore('/').trim()
        return when {
            TEXT_HEAVY_PACKAGES.contains(cleanPkg) -> ContentProfile.TEXT_HEAVY
            isVideoApp -> ContentProfile.MOTION_HEAVY
            else -> ContentProfile.BALANCED
        }
    }

    /**
     * Flexibly distributes content-weight-based bandwidth matching N virtual displays
     * according to the physical lower limit (Floor) and the remaining budget ratio.
     */
    fun rebalanceMultiDisplayBitrates(
        activePipelines: List<MirrorForegroundService.MirroringPipeline>
    ) {
        val validPipelines = activePipelines.filter { it.width > 0 && it.height > 0 }
        if (validPipelines.isEmpty()) return

        // Dynamic profile and physical floor mapping based on N screen states
        val profileMap = validPipelines.associateWith {
            resolveContentProfile(it.currentApp, it.isVideoApp)
        }

        val floorMap = validPipelines.associateWith { pipeline ->
            when (profileMap[pipeline]) {
                ContentProfile.TEXT_HEAVY -> 2_200_000 // Map readability floor
                ContentProfile.MOTION_HEAVY -> 1_200_000
                ContentProfile.BALANCED -> 1_000_000
                else -> 1_000_000
            }
        }

        // Secure safe bandwidth based on the sum of minimum bandwidth required to drive N displays
        val requiredTotalFloor = floorMap.values.sum()
        val totalBudget = getGlobalBudget().coerceAtLeast(requiredTotalFloor).coerceAtLeast(3_000_000)

        val allocations = mutableMapOf<MirrorForegroundService.MirroringPipeline, Int>()

        // ─────────────────────────────────────────────────────────────────
        // Step 1: [Branch Control] Calculate bitrate distribution for single or multi-screen
        // ─────────────────────────────────────────────────────────────────
        if (validPipelines.size == 1) {
            allocations[validPipelines.first()] = totalBudget
        } else {
            var remainingBudget = totalBudget

            // Securely allocate the physical floor for each profile first
            validPipelines.forEach { pipeline ->
                val floor = floorMap[pipeline] ?: 1_000_000
                val state = tuningRegistry.getOrPut(pipeline.name) { TuningState(pipeline.name) }

                state.currentProfile = profileMap[pipeline] ?: ContentProfile.BALANCED
                state.currentBitrateFloor = floor

                allocations[pipeline] = floor
                remainingBudget -= floor
            }

            // Distribute remaining budget according to the weight ratios of N apps
            if (remainingBudget > 0) {
                // 💡 Add else branch for compiler safety
                val totalWeight = validPipelines.sumOf { pipeline ->
                    when (profileMap[pipeline]) {
                        ContentProfile.MOTION_HEAVY -> 1.5
                        ContentProfile.TEXT_HEAVY -> 1.2
                        ContentProfile.BALANCED -> 1.0
                        else -> 1.0 // ◀ Add else for runtime null safety
                    }
                }

                validPipelines.forEach { pipeline ->
                    // 💡 Add else branch for compiler safety
                    val weight = when (profileMap[pipeline]) {
                        ContentProfile.MOTION_HEAVY -> 1.5
                        ContentProfile.TEXT_HEAVY -> 1.2
                        ContentProfile.BALANCED -> 1.0
                        else -> 1.0 // ◀ Add else for runtime null safety
                    }
                    val bonus = (remainingBudget * (weight / totalWeight)).toInt()
                    allocations[pipeline] = allocations[pipeline]!! + bonus
                }
            }
        }
        // ─────────────────────────────────────────────────────────────────
        // Step 2: [Common Single Loop] Reuse existing applyEncoderParams to apply directly
        // ─────────────────────────────────────────────────────────────────
        validPipelines.forEach { pipeline ->
            val targetBitrate = allocations[pipeline] ?: totalBudget
            val profile = profileMap[pipeline] ?: ContentProfile.BALANCED
            val state = tuningRegistry.getOrPut(pipeline.name) { TuningState(pipeline.name) }

            state.targetBitrate = targetBitrate

            // 💡 Eliminate duplicate code and delegate logic to applyEncoderParams
            applyEncoderParams(
                pipeline = pipeline,
                bitrate = targetBitrate,
                profile = profile,
                qpOffset = state.qpOffset
            )
        }
    }

    /**
     * [Task 4] 5% micro-feedback autonomous self-tuning loop
     * Fine-tunes the encoder based on periodically collected frame loss rates and latency trends.
     */
    fun executeSelfTuningFeedback(
        pipelineName: String,
        pipeline: MirrorForegroundService.MirroringPipeline,
        droppedFrames: Int,
        avgDelayMs: Double
    ) {
        val state = tuningRegistry[pipelineName] ?: return
        val encoder = pipeline.videoEncoder ?: return

        // Combined metrics for network health and decoder backlog
        val isUnhealthy = droppedFrames > 4 || avgDelayMs > 180.0
        val isExtremelyHealthy = droppedFrames == 0 && avgDelayMs < 70.0

        if (isUnhealthy) {
            state.consecutiveUnhealthyCount++
            if (state.consecutiveUnhealthyCount >= 2) {
                // Congestion detected: instantly compress bandwidth by 5% and ease macroblock QP upper limits (prioritize recovering from dropped frames)
                state.targetBitrate = (state.targetBitrate * 0.95).toInt()
                    .coerceAtLeast(state.currentBitrateFloor)

                // Keep global minimum floor higher for TEXT_HEAVY to protect readability
                if (state.currentProfile == ContentProfile.TEXT_HEAVY) {
                    state.targetBitrate = state.targetBitrate.coerceAtLeast(2_000_000)
                }

                state.qpOffset = (state.qpOffset + 1).coerceAtMost(6)

                // 💡 Call parameters matching the newly defined VideoEncoder.setQualityProfile interface
                encoder.setQualityProfile(
                    bps = state.targetBitrate,
                    isTextHeavy = (state.currentProfile == ContentProfile.TEXT_HEAVY),
                    qpOffset = state.qpOffset
                )

                state.consecutiveUnhealthyCount = 0
                Log.d(TAG, "[$pipelineName] Self-Tuning Down: Bitrate=${state.targetBitrate}, QpOffset=${state.qpOffset}")
            }
        } else if (isExtremelyHealthy) {
            state.consecutiveUnhealthyCount = 0
            // Extremely healthy state: gradually restore bitrate by 5% and lower QP offset to recover detail and sharpness
            val maxBudgetCap = getGlobalBudget()
            if (state.targetBitrate < maxBudgetCap) {
                state.targetBitrate = (state.targetBitrate * 1.05).toInt().coerceAtMost(maxBudgetCap)
                state.qpOffset = (state.qpOffset - 1).coerceAtLeast(-4)

                // 💡 Call matching the VideoEncoder interface signature
                encoder.setQualityProfile(
                    bps = state.targetBitrate,
                    isTextHeavy = (state.currentProfile == ContentProfile.TEXT_HEAVY),
                    qpOffset = state.qpOffset
                )

                Log.d(TAG, "[$pipelineName] Self-Tuning Up: Bitrate=${state.targetBitrate}, QpOffset=${state.qpOffset}")
            }
        } else {
            state.consecutiveUnhealthyCount = 0
        }
    }

    private fun applyEncoderParams(
        pipeline: MirrorForegroundService.MirroringPipeline,
        bitrate: Int,
        profile: ContentProfile,
        qpOffset: Int
    ) {
        // 1. Update cached pipeline-wide bitrate state
        pipeline.currentBitrate = bitrate
        val encoder = pipeline.videoEncoder ?: return

        // 2. Determine if text-heavy mode is active
        val isTextHeavy = (profile == ContentProfile.TEXT_HEAVY)

        // 💡 Remove hardcoded Bundle assembly and pass arguments directly to the encoder function
        encoder.setQualityProfile(
            bps = bitrate,
            isTextHeavy = isTextHeavy,
            qpOffset = qpOffset
        )
        // 3. Send feedback notification to the browser frontend receiver
        broadcastControlMessage(JSONObject().apply {
            put("type", "encoderProfileChanged")
            put("pane", pipeline.name)
            put("profile", profile.name)
            put("bitrate", bitrate)
        }.toString())
    }

    private fun stateKey(name: String): String = name
}

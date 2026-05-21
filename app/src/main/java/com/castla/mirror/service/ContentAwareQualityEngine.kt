package com.castla.mirror.service

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import com.castla.mirror.capture.VideoEncoder
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 콘텐츠 인지형 화질 최적화 및 런타임 파라미터 튜닝 엔진
 */
class ContentAwareQualityEngine(
    private val getGlobalBudget: () -> Int,
    private val broadcastControlMessage: (String) -> Unit
) {
    private val TAG = "ContentAwareQualityEngine"

    // 콘텐츠 프로파일 정의
    enum class ContentProfile {
        TEXT_HEAVY,   // 지도, 내비게이션, 브라우저 (가독성/선명도 우선)
        MOTION_HEAVY, // 유튜브, Netflix 등 OTT (부드러운 프레임/정크 억제 우선)
        BALANCED      // 일반 UI, 설정 등
    }

    // 파이프라인별 동적 튜닝 파라미터 상태 관리 데이터 구조
    data class TuningState(
        val pipelineName: String,
        var currentProfile: ContentProfile = ContentProfile.BALANCED,
        var currentBitrateFloor: Int = 1_500_000,
        var targetBitrate: Int = 2_500_000,
        var qpOffset: Int = 0,               // 피드백 루프로 제어되는 자율 QP 오프셋
        var consecutiveUnhealthyCount: Int = 0
    )

    private val tuningRegistry = ConcurrentHashMap<String, TuningState>()

    companion object {
        // 주요 내비게이션 및 지도 앱 패키지 시그니처 맵
        private val TEXT_HEAVY_PACKAGES = setOf(
            "com.google.android.apps.maps",
            "com.skt.tmap.ku",
            "com.naver.vnavigator",
            "com.locnall.KimGiRok",
            "com.kakao.taxi"
        )
    }

    /**
     * [Task 1] 앱 패키지 기반 실시간 콘텐츠 프로파일 판별
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
     * N개의 가상 디스플레이 구동 상황에 맞춰 콘텐츠 가중치 기반 대역폭을
     * 물리적 하한선(Floor)과 잔여 파이 비율에 따라 유연하게 다중 분배합니다.
     */
    /**
     * N개의 가상 디스플레이 구동 상황에 맞춰 콘텐츠 가중치 기반 대역폭을
     * 물리적 하한선(Floor)과 잔여 파이 비율에 따라 유연하게 다중 분배합니다.
     */
    fun rebalanceMultiDisplayBitrates(
        activePipelines: List<MirrorForegroundService.MirroringPipeline>
    ) {
        val validPipelines = activePipelines.filter { it.width > 0 && it.height > 0 }
        if (validPipelines.isEmpty()) return

        // N개 화면 상태에 따른 동적 프로파일 및 물리 하한선 매핑
        val profileMap = validPipelines.associateWith {
            resolveContentProfile(it.currentApp, it.isVideoApp)
        }

        val floorMap = validPipelines.associateWith { pipeline ->
            when (profileMap[pipeline]) {
                ContentProfile.TEXT_HEAVY -> 2_200_000 // 지도 가독성 하한선
                ContentProfile.MOTION_HEAVY -> 1_200_000
                ContentProfile.BALANCED -> 1_000_000
                else -> 1_000_000
            }
        }

        // N개 디스플레이 구동에 필요한 최소 대역폭 총합을 기준으로 안전 대역폭 확보
        val requiredTotalFloor = floorMap.values.sum()
        val totalBudget = getGlobalBudget().coerceAtLeast(requiredTotalFloor).coerceAtLeast(3_000_000)

        val allocations = mutableMapOf<MirrorForegroundService.MirroringPipeline, Int>()

        // ─────────────────────────────────────────────────────────────────
        // 1단계: [분기 제어] 단일 화면과 N개 다중 화면 상황에 맞는 비트레이트 분배 계산
        // ─────────────────────────────────────────────────────────────────
        if (validPipelines.size == 1) {
            allocations[validPipelines.first()] = totalBudget
        } else {
            var remainingBudget = totalBudget

            // 프로파일별 물리 하한선 우선 안심 배정
            validPipelines.forEach { pipeline ->
                val floor = floorMap[pipeline] ?: 1_000_000
                val state = tuningRegistry.getOrPut(pipeline.name) { TuningState(pipeline.name) }

                state.currentProfile = profileMap[pipeline] ?: ContentProfile.BALANCED
                state.currentBitrateFloor = floor

                allocations[pipeline] = floor
                remainingBudget -= floor
            }

            // 남은 잔여 버젯을 N개 앱 가중치 비율에 맞춰 분배
            if (remainingBudget > 0) {
                // 💡 [수정 구간 1] 컴파일러 안정성을 위해 else 브랜치 추가
                val totalWeight = validPipelines.sumOf { pipeline ->
                    when (profileMap[pipeline]) {
                        ContentProfile.MOTION_HEAVY -> 1.5
                        ContentProfile.TEXT_HEAVY -> 1.2
                        ContentProfile.BALANCED -> 1.0
                        else -> 1.0 // ◀ 런타임 Null 방어용 else 추가
                    }
                }

                validPipelines.forEach { pipeline ->
                    // 💡 [수정 구간 2] 컴파일러 안정성을 위해 else 브랜치 추가
                    val weight = when (profileMap[pipeline]) {
                        ContentProfile.MOTION_HEAVY -> 1.5
                        ContentProfile.TEXT_HEAVY -> 1.2
                        ContentProfile.BALANCED -> 1.0
                        else -> 1.0 // ◀ 런타임 Null 방어용 else 추가
                    }
                    val bonus = (remainingBudget * (weight / totalWeight)).toInt()
                    allocations[pipeline] = allocations[pipeline]!! + bonus
                }
            }
        }
        // ─────────────────────────────────────────────────────────────────
        // 2단계: [공통 단일 루프] 기존 applyEncoderParams 기능을 그대로 재사용하여 직분사
        // ─────────────────────────────────────────────────────────────────
        validPipelines.forEach { pipeline ->
            val targetBitrate = allocations[pipeline] ?: totalBudget
            val profile = profileMap[pipeline] ?: ContentProfile.BALANCED
            val state = tuningRegistry.getOrPut(pipeline.name) { TuningState(pipeline.name) }

            state.targetBitrate = targetBitrate

            // 💡 중복 코드를 완벽히 제거하고 기존 applyEncoderParams에 연산을 위임합니다.
            applyEncoderParams(
                pipeline = pipeline,
                bitrate = targetBitrate,
                profile = profile,
                qpOffset = state.qpOffset
            )
        }
    }

    /**
     * [Task 4] 5% 마이크로 피드백 루프 자율 동적 튜닝 루프 (Self-Tuning Loop)
     * 주기적으로 수집된 프레임 유실률과 지연시간 추이를 기반으로 인코더를 파인튜닝합니다.
     */
    fun executeSelfTuningFeedback(
        pipelineName: String,
        pipeline: MirrorForegroundService.MirroringPipeline,
        droppedFrames: Int,
        avgDelayMs: Double
    ) {
        val state = tuningRegistry[pipelineName] ?: return
        val encoder = pipeline.videoEncoder ?: return

        // 네트워크 안정성 및 디코더 백로그 결합 평가 지표
        val isUnhealthy = droppedFrames > 4 || avgDelayMs > 180.0
        val isExtremelyHealthy = droppedFrames == 0 && avgDelayMs < 70.0

        if (isUnhealthy) {
            state.consecutiveUnhealthyCount++
            if (state.consecutiveUnhealthyCount >= 2) {
                // 혼잡 감지: 즉시 대역폭 5% 압착 조절 및 매크로블록 QP 상한 완화 (프레임 드롭 탈출 우선)
                state.targetBitrate = (state.targetBitrate * 0.95).toInt()
                    .coerceAtLeast(state.currentBitrateFloor)

                // TEXT_HEAVY의 경우 가독성 방어를 위해 전역 예외 최소 하한값 상향 유지
                if (state.currentProfile == ContentProfile.TEXT_HEAVY) {
                    state.targetBitrate = state.targetBitrate.coerceAtLeast(2_000_000)
                }

                state.qpOffset = (state.qpOffset + 1).coerceAtMost(6)

                // 💡 [수정 핵심] 새로 정의한 VideoEncoder.setQualityProfile 규격에 맞게 인자 매핑 호출
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
            // 고품질 여유 상태: 비트레이트를 5%씩 점진적 복구하고 QP 오프셋을 낮춰 디테일 선명도 회복
            val maxBudgetCap = getGlobalBudget()
            if (state.targetBitrate < maxBudgetCap) {
                state.targetBitrate = (state.targetBitrate * 1.05).toInt().coerceAtMost(maxBudgetCap)
                state.qpOffset = (state.qpOffset - 1).coerceAtLeast(-4)

                // 💡 [수정 핵심] 동일하게 VideoEncoder 인터페이스 규격에 맞춰 호출
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
        // 1. 파이프라인 전역 비트레이트 상태 캐싱 업데이트
        pipeline.currentBitrate = bitrate
        val encoder = pipeline.videoEncoder ?: return

        // 2. 텍스트 선명도 모드 여부 판별
        val isTextHeavy = (profile == ContentProfile.TEXT_HEAVY)

        // 💡 [수정 핵심] 하드코딩된 Bundle 조립을 걷어내고, 인자들을 encoder 함수로 정직하게 패스합니다.
        encoder.setQualityProfile(
            bps = bitrate,
            isTextHeavy = isTextHeavy,
            qpOffset = qpOffset
        )
        // 3. 브라우저 프론트엔드 수신단 대응 피드백 알림
        broadcastControlMessage(JSONObject().apply {
            put("type", "encoderProfileChanged")
            put("pane", pipeline.name)
            put("profile", profile.name)
            put("bitrate", bitrate)
        }.toString())
    }

    private fun stateKey(name: String): String = name
}

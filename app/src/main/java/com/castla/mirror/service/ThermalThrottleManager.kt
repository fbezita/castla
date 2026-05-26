package com.castla.mirror.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.castla.mirror.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import com.castla.mirror.server.MirrorServer

class ThermalThrottleManager(
    private val context: Context,
    private val mainExecutor: java.util.concurrent.Executor,
    private val getPipelines: () -> Map<String, MirrorForegroundService.MirroringPipeline>,
    private val getAudioOrchestrator: () -> AudioCaptureOrchestrator?,
    private val getMirrorServer: () -> MirrorServer?,
    private val onThermalThrottled: () -> Unit
) {
    private val TAG = "ThermalThrottleManager"
    
    private val _thermalStatus = MutableStateFlow(0)
    val thermalStatus: StateFlow<Int> = _thermalStatus
    
    // 부하 방지 가드: 이전 써멀 상태를 기억하여 무분별한 연쇄 rebuild를 차단합니다.
    private var lastProcessedStatus: Int = PowerManager.THERMAL_STATUS_NONE

    var thermalFpsOverride: Int? = null
    var thermalMaxHeight: Int? = null
    
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    fun register() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalListener = PowerManager.OnThermalStatusChangedListener { status -> handleThermalStatusChange(status) }
            pm.addThermalStatusListener(mainExecutor, thermalListener!!)
        }
    }

    fun unregister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(thermalListener!!) } catch (_: Exception) {}
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun handleThermalStatusChange(status: Int) {
        // 상태가 실제로 변하지 않았다면 중복 실행을 막아 부하를 원천 차단합니다.
        if (_thermalStatus.value == status && lastProcessedStatus == status) return
        _thermalStatus.value = status
        
        val pipelineMap = getPipelines()
        val isStatusChanged = lastProcessedStatus != status
        lastProcessedStatus = status

        when (status) {
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> {
                android.os.Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(context, context.getString(R.string.toast_thermal_warning), android.widget.Toast.LENGTH_LONG).show()
                }
            }
            PowerManager.THERMAL_STATUS_SEVERE -> {
                Log.w(TAG, "Thermal SEVERE -> 오직 비트레이트 압착(소프트웨어 제어) 우선 수행")
                // 1단계: 코덱을 재시작하지 않는 가벼운 비트레이트 조절을 먼저 전체 순회 적용
                pipelineMap.values.forEach { pipeline ->
                    val dynamicSevereTarget = (pipeline.currentBitrate * 0.4).toInt().coerceAtLeast(400_000)
                    pipeline.videoEncoder?.setBitrate(dynamicSevereTarget)
                    pipeline.jpegEncoder?.setFps(8)
                }
                
                getAudioOrchestrator()?.stop()
                thermalFpsOverride = 15
                thermalMaxHeight = 720
                onThermalThrottled()
                
                if (isStatusChanged) {
                    Log.i(TAG, "Thermal SEVERE state changed -> rebuild skipped; soft throttle only")
                }
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                Log.i(TAG, "Thermal MODERATE -> 비트레이트 중등도 압착")
                pipelineMap.values.forEach { pipeline ->
                    val dynamicModerateTarget = (pipeline.currentBitrate * 0.6).toInt().coerceAtLeast(400_000)
                    pipeline.videoEncoder?.setBitrate(dynamicModerateTarget)
                    pipeline.jpegEncoder?.setFps(12)
                }
                thermalFpsOverride = 20
                thermalMaxHeight = null
                onThermalThrottled()
                
                if (isStatusChanged) {
                    Log.i(TAG, "Thermal MODERATE state changed -> rebuild skipped; soft throttle only")
                }
            }
            PowerManager.THERMAL_STATUS_LIGHT -> {
                Log.i(TAG, "Thermal LIGHT -> 경량 가이드 압착")
                pipelineMap.values.forEach { pipeline -> 
                    val dynamicLightTarget = (pipeline.currentBitrate * 0.85).toInt().coerceAtLeast(500_000)
                    pipeline.videoEncoder?.setBitrate(dynamicLightTarget) 
                }
                thermalFpsOverride = null
                thermalMaxHeight = null
                // LIGHT 단계는 가벼운 비트레이트 조절만 하고 rebuild를 호출하지 않아 부하를 최소화합니다.
            }
            PowerManager.THERMAL_STATUS_NONE -> {
                Log.i(TAG, "Thermal NONE -> 정상 상태 복원")
                thermalFpsOverride = null
                thermalMaxHeight = null
                onThermalThrottled()
                
                if (isStatusChanged) {
                    Log.i(TAG, "Thermal NONE state changed -> rebuild skipped; existing encoders recover on normal lifecycle")
                }
            }
        }
        broadcastThermalStatus(status)
    }

    fun broadcastThermalStatus(status: Int) {
        val level = when (status) {
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> "severe"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            else -> "none"
        }
        getMirrorServer()?.broadcastControlMessage(JSONObject().apply { put("type", "thermalStatus"); put("level", level) }.toString())
    }
}

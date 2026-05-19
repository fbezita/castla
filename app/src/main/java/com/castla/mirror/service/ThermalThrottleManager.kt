package com.castla.mirror.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.castla.mirror.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.castla.mirror.server.MirrorServer
import com.castla.mirror.service.AudioCaptureOrchestrator
import com.castla.mirror.service.MirrorForegroundService

class ThermalThrottleManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val mainExecutor: java.util.concurrent.Executor,
    private val primaryPipeline: MirrorForegroundService.VirtualDisplayPipeline,
    private val getTargetBitrate: () -> Int,
    private val setTargetBitrate: (Int) -> Unit,
    private val getAudioOrchestrator: () -> AudioCaptureOrchestrator?,
    private val getBrowserConnected: () -> Boolean,
    private val getMirrorServer: () -> MirrorServer?,
    private val rebuildPipeline: suspend (Int, Int, Boolean) -> Unit,
    private val onThermalThrottled: () -> Unit
) {
    private val TAG = "ThermalThrottleManager"
    
    private val _thermalStatus = MutableStateFlow(0)
    val thermalStatus: StateFlow<Int> = _thermalStatus
    
    var preThermalTargetBitrate: Int = 0
    var thermalFpsOverride: Int? = null
    var thermalMaxHeight: Int? = null
    
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    fun register() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                handleThermalStatusChange(status)
            }
            pm.addThermalStatusListener(mainExecutor, thermalListener!!)
        }
    }

    fun unregister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                thermalListener?.let { pm.removeThermalStatusListener(it) }
            } catch (_: Exception) {}
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun handleThermalStatusChange(status: Int) {
        _thermalStatus.value = status

        val currentTargetBitrate = getTargetBitrate()
        if (preThermalTargetBitrate == 0 && currentTargetBitrate > 0) {
            preThermalTargetBitrate = currentTargetBitrate
        }

        when (status) {
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> {
                Log.w(TAG, "Thermal status CRITICAL/EMERGENCY ($status) ??warning only, continuing")
                android.os.Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.toast_thermal_warning),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            PowerManager.THERMAL_STATUS_SEVERE -> {
                Log.w(TAG, "Thermal status SEVERE ($status) - Throttling encoder heavily + fps/resolution")
                val newBitrate = (preThermalTargetBitrate * 0.4).toInt().coerceAtLeast(500_000)
                primaryPipeline.currentBitrate = newBitrate
                setTargetBitrate(newBitrate)
                primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)
                primaryPipeline.jpegEncoder?.setFps(8)
                Log.w(TAG, "Thermal SEVERE ??stopping audio capture to reduce CPU load")
                getAudioOrchestrator()?.stop()
                thermalFpsOverride = 15
                thermalMaxHeight = 720
                onThermalThrottled()
                if (getBrowserConnected()) {
                    serviceScope.launch { rebuildPipeline(primaryPipeline.width, primaryPipeline.height, true) }
                }
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                Log.w(TAG, "Thermal status MODERATE ($status) - Throttling encoder + fps drop to 20")
                val newBitrate = (preThermalTargetBitrate * 0.6).toInt().coerceAtLeast(500_000)
                primaryPipeline.currentBitrate = newBitrate
                setTargetBitrate(newBitrate)
                primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)
                primaryPipeline.jpegEncoder?.setFps(12)
                thermalFpsOverride = 20
                thermalMaxHeight = null
                onThermalThrottled()
                if (getBrowserConnected()) {
                    serviceScope.launch { rebuildPipeline(primaryPipeline.width, primaryPipeline.height, true) }
                }
            }
            PowerManager.THERMAL_STATUS_LIGHT -> {
                Log.i(TAG, "Thermal status LIGHT ($status) - Preemptive throttling")
                val newBitrate = (preThermalTargetBitrate * 0.85).toInt().coerceAtLeast(500_000)
                primaryPipeline.currentBitrate = newBitrate
                setTargetBitrate(newBitrate)
                primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)
                thermalFpsOverride = null
                thermalMaxHeight = null
            }
            PowerManager.THERMAL_STATUS_NONE -> {
                Log.i(TAG, "Thermal status NONE ($status) - Restoring full bitrate and fps")
                thermalFpsOverride = null
                thermalMaxHeight = null
                if (preThermalTargetBitrate > 0) {
                    setTargetBitrate(preThermalTargetBitrate)
                    primaryPipeline.currentBitrate = preThermalTargetBitrate
                    primaryPipeline.videoEncoder?.setBitrate(primaryPipeline.currentBitrate)
                    primaryPipeline.jpegEncoder?.setFps(15)
                    if (getBrowserConnected()) {
                        serviceScope.launch { rebuildPipeline(primaryPipeline.width, primaryPipeline.height, true) }
                    }
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
        val json = JSONObject().apply {
            put("type", "thermalStatus")
            put("level", level)
        }.toString()
        getMirrorServer()?.broadcastControlMessage(json)
    }
}

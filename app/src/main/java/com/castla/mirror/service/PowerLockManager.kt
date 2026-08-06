package com.castla.mirror.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

class PowerLockManager(private val context: Context) {
    companion object {
        private const val TAG = "PowerLockManager"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    val isHeld: Boolean
        get() = wakeLock?.isHeld == true

    fun acquireWakeLocks() {
        try {
            releaseWakeLocks()
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Castla::StreamingWakeLock").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // 10 minutes timeout safeguard
            }
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wifiMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(wifiMode, "Castla::StreamingWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "[WAKELOCK]\nacquire")
            Log.i(TAG, "[WAKELOCK]\nisHeld=$isHeld")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake locks", e)
        }
    }

    fun releaseWakeLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            wifiLock = null
            Log.i(TAG, "[WAKELOCK]\nrelease")
            Log.i(TAG, "[WAKELOCK]\nisHeld=$isHeld")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake locks", e)
        }
    }
}

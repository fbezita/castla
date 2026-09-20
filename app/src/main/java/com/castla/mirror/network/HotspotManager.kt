package com.castla.mirror.network

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Manages WiFi hotspot for Tesla connection.
 * Uses LocalOnlyHotspot API (Android 8.0+) for basic hotspot,
 * or Shizuku for full tethering control (Phase 5).
 */
class HotspotManager(private val context: Context) {

    companion object {
        private const val TAG = "HotspotManager"
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    data class HotspotInfo(
        val ssid: String,
        val password: String,
        val isActive: Boolean
    )

    fun startHotspot(callback: (HotspotInfo?) -> Unit) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start hotspot: permission denied ($permission)")
            callback(null)
            return
        }
        startHotspotWithPermission(callback)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startHotspotWithPermission(callback: (HotspotInfo?) -> Unit) {
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    @Suppress("DEPRECATION")
                    val config = reservation.wifiConfiguration
                    @Suppress("DEPRECATION")
                    val info = HotspotInfo(
                        ssid = config?.SSID ?: "Unknown",
                        password = config?.preSharedKey ?: "",
                        isActive = true
                    )
                    Log.i(TAG, "Hotspot started: ${info.ssid}")
                    callback(info)
                }

                override fun onStopped() {
                    Log.i(TAG, "Hotspot stopped")
                    hotspotReservation = null
                    callback(null)
                }

                override fun onFailed(reason: Int) {
                    Log.e(TAG, "Hotspot failed: reason=$reason")
                    callback(null)
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hotspot", e)
            callback(null)
        }
    }

    fun stopHotspot() {
        hotspotReservation?.close()
        hotspotReservation = null
        Log.i(TAG, "Hotspot stopped")
    }
}

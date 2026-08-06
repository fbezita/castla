package com.castla.mirror.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

internal class BluetoothAudioRouteMonitor(context: Context, private val onChanged: (Boolean) -> Unit) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = dispatch()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = dispatch()
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        dispatch()
    }

    fun stop() = audioManager.unregisterAudioDeviceCallback(callback)

    fun isBluetoothAudioConnected(): Boolean = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                device.type == AudioDeviceInfo.TYPE_HEARING_AID
        }
    } catch (_: SecurityException) { false }

    private fun dispatch() = onChanged(isBluetoothAudioConnected())
}

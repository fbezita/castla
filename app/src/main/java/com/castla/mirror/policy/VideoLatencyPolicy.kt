package com.castla.mirror.policy

object VideoLatencyPolicy {
    const val MIN_LATENCY_MS = 0
    const val MAX_LATENCY_MS = 1000
    const val DEFAULT_STREAMED_AUDIO_LATENCY_MS = 300

    fun resolve(
        audioEnabled: Boolean,
        bluetoothAudioConnected: Boolean,
        bluetoothRoutedApp: Boolean,
        bluetoothLatencyMs: Int,
        streamedAudioLatencyMs: Int,
    ): Int {
        if (audioEnabled) return streamedAudioLatencyMs.coerceIn(MIN_LATENCY_MS, MAX_LATENCY_MS)
        if (bluetoothAudioConnected && bluetoothRoutedApp) {
            return bluetoothLatencyMs.coerceIn(MIN_LATENCY_MS, MAX_LATENCY_MS)
        }
        return 0
    }
}

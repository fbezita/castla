package com.castla.mirror.policy

object VideoLatencyPolicy {
    const val MIN_LATENCY_MS = 0
    const val MAX_LATENCY_MS = 1000
    const val MIN_STREAMED_AV_OFFSET_MS = -1000
    const val DEFAULT_STREAMED_AUDIO_LATENCY_MS = -30

    fun clampStreamedAvOffset(offsetMs: Int): Int =
        offsetMs.coerceIn(MIN_STREAMED_AV_OFFSET_MS, MAX_LATENCY_MS)

    fun resolveStreamedAudioDelay(offsetMs: Int): Int =
        clampStreamedAvOffset(offsetMs).coerceAtLeast(0)

    fun resolve(
        audioEnabled: Boolean,
        bluetoothAudioConnected: Boolean,
        bluetoothRoutedApp: Boolean,
        bluetoothLatencyMs: Int,
        streamedAudioLatencyMs: Int,
    ): Int {
        if (audioEnabled) {
            return (-clampStreamedAvOffset(streamedAudioLatencyMs)).coerceAtLeast(0)
        }
        if (bluetoothAudioConnected && bluetoothRoutedApp) {
            return bluetoothLatencyMs.coerceIn(MIN_LATENCY_MS, MAX_LATENCY_MS)
        }
        return 0
    }
}

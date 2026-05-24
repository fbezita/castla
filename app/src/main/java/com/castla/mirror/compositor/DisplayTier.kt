package com.castla.mirror.compositor

enum class DisplayTier {
    ACTIVE,
    VISIBLE,
    SUSPENDED,
    PARKED
}

data class StreamProfile(
    val bitrate: Int,
    val fps: Int,
    val encoderRunning: Boolean
) {
    companion object {
        fun forTier(baseBitrate: Int, baseFps: Int, tier: DisplayTier): StreamProfile {
            return when (tier) {
                DisplayTier.ACTIVE -> StreamProfile(baseBitrate, baseFps, encoderRunning = true)
                DisplayTier.VISIBLE -> StreamProfile((baseBitrate * 0.45f).toInt().coerceAtLeast(350_000), baseFps.coerceAtMost(15), encoderRunning = true)
                DisplayTier.SUSPENDED -> StreamProfile(0, 0, encoderRunning = false)
                DisplayTier.PARKED -> StreamProfile(0, 0, encoderRunning = false)
            }
        }
    }
}

package com.castla.mirror.compositor

@JvmInline
value class DisplaySessionId(val value: String) {
    companion object {
        val PRIMARY = DisplaySessionId("primary")
        val SECONDARY = DisplaySessionId("secondary")
    }
}

data class DisplaySpec(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val baseBitrate: Int,
    val baseFps: Int
) {
    fun aligned(): DisplaySpec {
        val alignedWidth = (width + 15) and 15.inv()
        val alignedHeight = (height + 15) and 15.inv()
        return copy(width = alignedWidth, height = alignedHeight)
    }
}

data class DisplaySessionDiagnostics(
    val sessionId: DisplaySessionId,
    val vdId: Int,
    val tier: DisplayTier,
    val generation: Int,
    val width: Int,
    val height: Int,
    val encoderRunning: Boolean,
    val streamReady: Boolean,
    val firstFrameReady: Boolean,
    val reconnectCount: Int,
    val lastFrameTimestampMs: Long,
    val droppedFrames: Int,
    val generationMismatchCount: Int
)

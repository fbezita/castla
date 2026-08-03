package com.castla.mirror.service

/** Inputs used to decide whether launch-time VD/encoder preparation is required. */
data class DisplaySessionPreparationInput(
    val requestedWidth: Int,
    val requestedHeight: Int,
    val lastValidWidth: Int,
    val lastValidHeight: Int,
    val currentWidth: Int,
    val currentHeight: Int,
    val currentMaxHeight: Int,
    val encoderReady: Boolean,
)

data class DisplaySessionPreparationResult(
    val session: DisplayLaunchSession,
    val needsRealignment: Boolean,
)

/** Pure launch-time display-session decision logic. */
object DisplaySessionPreparationPolicy {
    fun resolve(input: DisplaySessionPreparationInput): DisplaySessionPreparationResult {
        val targetWidth = input.requestedWidth.takeIf { it > 0 }
            ?: input.lastValidWidth.takeIf { it > 0 }
            ?: 384
        val targetHeight = input.requestedHeight.takeIf { it > 0 }
            ?: input.lastValidHeight.takeIf { it > 0 }
            ?: 672
        val effectiveSize = DisplaySizePolicy.resolve(targetWidth, targetHeight, input.currentMaxHeight)
        val needsRealignment = input.currentWidth != effectiveSize.width || input.currentHeight != effectiveSize.height
        return DisplaySessionPreparationResult(
            session = DisplayLaunchSession(
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                alignedWidth = effectiveSize.width,
                alignedHeight = effectiveSize.height,
                encoderReady = input.encoderReady,
            ),
            needsRealignment = needsRealignment,
        )
    }
}
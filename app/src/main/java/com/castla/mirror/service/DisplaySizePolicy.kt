package com.castla.mirror.service

data class EffectiveDisplaySize(val width: Int, val height: Int)

/** Resolves the exact aligned size used by both the virtual display and encoder. */
object DisplaySizePolicy {
    fun resolve(requestWidth: Int, requestHeight: Int, maxHeight: Int): EffectiveDisplaySize {
        var width = requestWidth.coerceAtLeast(1)
        var height = requestHeight.coerceAtLeast(1)
        val heightLimit = maxHeight.coerceAtLeast(1)
        if (height > heightLimit) {
            val scale = heightLimit.toFloat() / height.toFloat()
            height = heightLimit
            width = (width * scale).toInt().coerceAtLeast(1)
        }
        return EffectiveDisplaySize(
            width = ((width + 15) and 15.inv()).coerceAtLeast(320),
            height = ((height + 15) and 15.inv()).coerceAtLeast(320),
        )
    }
}

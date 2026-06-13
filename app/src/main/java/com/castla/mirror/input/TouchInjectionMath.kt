package com.castla.mirror.input

data class TouchInjectionDimensions(
    val width: Int,
    val height: Int,
)

object TouchInjectionMath {
    fun resolveDimensions(
        fallbackWidth: Int,
        fallbackHeight: Int,
        mappedWidth: Int,
        mappedHeight: Int,
    ): TouchInjectionDimensions {
        val rawWidth = if (mappedWidth > 0) mappedWidth else fallbackWidth
        val rawHeight = if (mappedHeight > 0) mappedHeight else fallbackHeight
        val width = rawWidth.coerceAtLeast(320)
        val height = rawHeight.coerceAtLeast(320)
        return TouchInjectionDimensions(width = width, height = height)
    }
}

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
        val width = if (mappedWidth > 0) mappedWidth else fallbackWidth
        val height = if (mappedHeight > 0) mappedHeight else fallbackHeight
        return TouchInjectionDimensions(width = width, height = height)
    }
}

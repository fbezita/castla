package com.castla.mirror.input

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchInjectionMathTest {
    @Test
    fun prefersMappedDimensionsWhenProvided() {
        val dimensions = TouchInjectionMath.resolveDimensions(
            fallbackWidth = 1088,
            fallbackHeight = 1088,
            mappedWidth = 1024,
            mappedHeight = 720,
        )

        assertEquals(1024, dimensions.width)
        assertEquals(720, dimensions.height)
    }

    @Test
    fun fallsBackToInjectorDimensionsWhenMappedDimensionsMissing() {
        val dimensions = TouchInjectionMath.resolveDimensions(
            fallbackWidth = 1088,
            fallbackHeight = 1088,
            mappedWidth = 0,
            mappedHeight = 0,
        )

        assertEquals(1088, dimensions.width)
        assertEquals(1088, dimensions.height)
    }

    @Test
    fun clampsDimensionsToAtLeast320Pixels() {
        val dimensions = TouchInjectionMath.resolveDimensions(
            fallbackWidth = 200,
            fallbackHeight = 200,
            mappedWidth = 150,
            mappedHeight = 150,
        )

        assertEquals(320, dimensions.width)
        assertEquals(320, dimensions.height)
    }
}

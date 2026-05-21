package com.castla.mirror.capture

import android.content.Context
import android.view.Surface
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Basic unit tests for VirtualDisplayController.
 * Validates initialization, bounds, and release behavior.
 */
@RunWith(RobolectricTestRunner::class)
class VirtualDisplayControllerBasicTest {

    private lateinit var context: Context
    private lateinit var surface: Surface
    private lateinit var controller: VirtualDisplayController

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        surface = mockk(relaxed = true)
        controller = VirtualDisplayController("Castla_Test")
    }

    @Test
    fun `createVirtualDisplay returns null when Shizuku unavailable`() {
        // Without Shizuku/privilegedService, should return null gracefully
        val display = controller.createVirtualDisplay(1280, 720, 160, surface)
        assertNull(display)
    }

    @Test
    fun `release does not crash when no display was created`() {
        controller.release() // should not throw
    }

    @Test
    fun `release after createVirtualDisplay cleans up`() {
        controller.createVirtualDisplay(1280, 720, 160, surface)
        controller.release() // should not throw
    }

    @Test
    fun `multiple release calls are idempotent`() {
        controller.release()
        controller.release() // should not throw
    }

    @Test
    fun `createVirtualDisplay with zero dimensions does not crash`() {
        val display = controller.createVirtualDisplay(0, 0, 0, surface)
        assertNull(display)
    }

    @Test
    fun `hasVirtualDisplay returns false initially`() {
        assertFalse(controller.hasVirtualDisplay())
    }

    @Test
    fun `getDisplayId returns -1 initially`() {
        assertEquals(-1, controller.getDisplayId())
    }

    @Test
    fun `injectInput does not crash when no display active`() {
        controller.injectInput(0, 0f, 0f, 0) // should not throw
    }
}

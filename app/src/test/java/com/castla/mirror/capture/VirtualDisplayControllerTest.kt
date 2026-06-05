package com.castla.mirror.capture

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.castla.mirror.shizuku.IPrivilegedService
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Advanced unit tests for VirtualDisplayController validating stale display detection,
 * state changes, exceptions, and launch app behaviors.
 */
@RunWith(RobolectricTestRunner::class)
class VirtualDisplayControllerTest {

    private lateinit var controller: VirtualDisplayController
    private lateinit var mockService: IPrivilegedService

    @Before
    fun setup() {
        // Mock Android framework statics to avoid "Method not mocked" errors
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0

        mockkStatic(Looper::class)
        val mockLooper = mockk<Looper>(relaxed = true)
        every { Looper.getMainLooper() } returns mockLooper
        every { Looper.myLooper() } returns mockLooper

        // Mock Handler construction
        mockkConstructor(Handler::class)
        every { anyConstructed<Handler>().post(any()) } answers {
            // Execute the runnable immediately
            firstArg<Runnable>().run()
            true
        }

        controller = VirtualDisplayController("Castla_Test")
        mockService = mockk(relaxed = true)

        // Inject mock service and a valid displayId via reflection
        setField("privilegedService", mockService)
        setField("displayId", 42)
        setField("isBound", true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun setField(name: String, value: Any?) {
        val field = VirtualDisplayController::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(controller, value)
    }

    // ── launchAppOnDisplay ──

    @Test
    fun `launchAppOnDisplay succeeds normally`() {
        val result = controller.launchAppOnDisplay("com.example.app")
        assertTrue(result)
        assertEquals(42, controller.getDisplayId())
    }

    @Test
    fun `launchAppOnDisplay returns false and invalidates on SecurityException`() {
        every { mockService.launchAppOnDisplay(42, "com.example.app") } throws SecurityException("Permission Denial")

        val result = controller.launchAppOnDisplay("com.example.app")
        assertFalse(result)
        assertEquals(-1, controller.getDisplayId())
        assertFalse(controller.hasVirtualDisplay())
    }

    @Test
    fun `launchAppOnDisplay returns false on generic exception without invalidating`() {
        every { mockService.launchAppOnDisplay(42, "com.example.app") } throws RuntimeException("some error")

        val result = controller.launchAppOnDisplay("com.example.app")
        assertFalse(result)
        // Generic exceptions should NOT invalidate displayId
        assertEquals(42, controller.getDisplayId())
    }

    @Test
    fun `launchAppOnDisplay returns false when displayId is negative`() {
        setField("displayId", -1)
        val result = controller.launchAppOnDisplay("com.example.app")
        assertFalse(result)
    }

    @Test
    fun `launchAppOnDisplay returns false when packageName is empty`() {
        val result = controller.launchAppOnDisplay("")
        assertFalse(result)
    }

    // ── launchAppWithExtraOnDisplay ──

    @Test
    fun `launchAppWithExtraOnDisplay succeeds normally`() {
        val result = controller.launchAppWithExtraOnDisplay("com.example.app", "key", "value")
        assertTrue(result)
        assertEquals(42, controller.getDisplayId())
    }

    @Test
    fun `launchAppWithExtraOnDisplay returns false and invalidates on SecurityException`() {
        every {
            mockService.launchAppWithExtraOnDisplay(42, "com.example.app", "key", "value")
        } throws SecurityException("Permission Denial")

        val result = controller.launchAppWithExtraOnDisplay("com.example.app", "key", "value")
        assertFalse(result)
        assertEquals(-1, controller.getDisplayId())
    }

    @Test
    fun `launchAppWithExtraOnDisplay returns false on generic exception without invalidating`() {
        every {
            mockService.launchAppWithExtraOnDisplay(42, "com.example.app", "key", "value")
        } throws RuntimeException("error")

        val result = controller.launchAppWithExtraOnDisplay("com.example.app", "key", "value")
        assertFalse(result)
        assertEquals(42, controller.getDisplayId())
    }

    // ── resizeDisplay ──

    @Test
    fun `resizeDisplay succeeds normally`() {
        val result = controller.resizeDisplay(1280, 720, 160)
        assertTrue(result)
    }

    @Test
    fun `resizeDisplay returns false on exception`() {
        every {
            mockService.resizeVirtualDisplay(42, 1280, 720, 160)
        } throws IllegalStateException("Virtual display 42 not found")

        val result = controller.resizeDisplay(1280, 720, 160)
        assertFalse(result)
    }

    @Test
    fun `resizeDisplay returns false when displayId is negative`() {
        setField("displayId", -1)
        val result = controller.resizeDisplay(1280, 720, 160)
        assertFalse(result)
    }

    // ── launchHomeOnDisplay ──

    @Test
    fun `launchHomeOnDisplay succeeds normally`() {
        val result = controller.launchHomeOnDisplay()
        assertTrue(result)
    }

    @Test
    fun `keepDisplayAwake uses VD-only keepalive path`() {
        controller.keepDisplayAwake()

        verify(exactly = 1) { mockService.keepVirtualDisplayAlive(42) }
        verify(exactly = 0) { mockService.wakeUpDisplay(42) }
    }

    @Test
    fun `launchHomeOnDisplay returns false when displayId is negative`() {
        setField("displayId", -1)
        val result = controller.launchHomeOnDisplay()
        assertFalse(result)
    }

    @Test
    fun `launchHomeOnDisplay returns false on exception`() {
        every { mockService.launchHomeOnDisplay(42) } throws RuntimeException("error")

        val result = controller.launchHomeOnDisplay()
        assertFalse(result)
    }

    // ── hasVirtualDisplay state transitions ──

    @Test
    fun `hasVirtualDisplay returns true when displayId is valid and service bound`() {
        assertTrue(controller.hasVirtualDisplay())
    }

    @Test
    fun `hasVirtualDisplay returns false after SecurityException invalidates display`() {
        every { mockService.launchAppOnDisplay(42, "com.example.app") } throws SecurityException("stale")

        assertTrue(controller.hasVirtualDisplay())
        controller.launchAppOnDisplay("com.example.app")
        assertFalse(controller.hasVirtualDisplay())
    }

    @Test
    fun `hasVirtualDisplay returns false when service is null`() {
        setField("privilegedService", null)
        assertFalse(controller.hasVirtualDisplay())
    }
}

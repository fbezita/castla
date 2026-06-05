package com.castla.mirror.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOffLoopGuardTest {

    private val guard = ScreenOffLoopGuard(suppressWindowMs = 2_500L)

    @Test
    fun `screen off inside suppression window is self induced`() {
        guard.markPowerBurst(nowMs = 1_000L)

        assertEquals(ScreenOffLoopGuard.EventSource.SELF_INDUCED, guard.classifyScreenOff(nowMs = 2_000L))
    }

    @Test
    fun `screen off after suppression window is user induced`() {
        guard.markPowerBurst(nowMs = 1_000L)

        assertEquals(ScreenOffLoopGuard.EventSource.USER, guard.classifyScreenOff(nowMs = 3_600L))
    }

    @Test
    fun `screen on shortly after keepalive is self induced`() {
        guard.markKeepAlive(nowMs = 5_000L)

        assertEquals(ScreenOffLoopGuard.EventSource.SELF_INDUCED, guard.classifyScreenOn(nowMs = 7_000L))
    }

    @Test
    fun `screen on long after keepalive is user induced`() {
        guard.markKeepAlive(nowMs = 5_000L)

        assertEquals(ScreenOffLoopGuard.EventSource.USER, guard.classifyScreenOn(nowMs = 7_600L))
    }

    @Test
    fun `reset clears suppression state`() {
        guard.markPowerBurst(nowMs = 1_000L)
        guard.markKeepAlive(nowMs = 2_000L)

        guard.reset()

        assertEquals(ScreenOffLoopGuard.EventSource.USER, guard.classifyScreenOff(nowMs = 2_000L))
        assertEquals(ScreenOffLoopGuard.EventSource.USER, guard.classifyScreenOn(nowMs = 2_100L))
    }
}

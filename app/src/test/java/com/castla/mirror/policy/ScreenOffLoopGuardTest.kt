package com.castla.mirror.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOffLoopGuardTest {

    private val guard = ScreenOffLoopGuard(
        suppressWindowMs = 2_500L,
        suppressScreenOnAfterKeepAliveMs = 900L,
    )

    @Test
    fun `screen off inside suppression window is self induced`() {
        guard.markPowerBurst(nowMs = 1_000L)

        assertEquals(ScreenOffLoopGuard.EventSource.WAKE_PULSE_RELATED, guard.classifyScreenOff(nowMs = 2_000L))
    }

    @Test
    fun `screen off after suppression window is user induced`() {
        guard.markPowerBurst(nowMs = 1_000L)

        assertEquals(ScreenOffLoopGuard.EventSource.USER, guard.classifyScreenOff(nowMs = 3_600L))
    }

    @Test
    fun `screen on shortly after keepalive is self induced`() {
        guard.markKeepAlive(nowMs = 5_000L)

        assertEquals(ScreenOffLoopGuard.EventSource.WAKE_PULSE_RELATED, guard.classifyScreenOn(nowMs = 5_800L))
    }

    @Test
    fun `screen on long after keepalive is user induced`() {
        guard.markKeepAlive(nowMs = 5_000L)

        assertEquals(ScreenOffLoopGuard.EventSource.USER, guard.classifyScreenOn(nowMs = 6_100L))
    }

    @Test
    fun `reset clears suppression state`() {
        guard.markPowerBurst(nowMs = 1_000L)
        guard.markKeepAlive(nowMs = 2_000L)
        guard.markBlackoutStart(nowMs = 3_000L)

        guard.reset()

        assertEquals(ScreenOffLoopGuard.EventSource.USER, guard.classifyScreenOff(nowMs = 2_000L))
        assertEquals(ScreenOffLoopGuard.EventSource.USER, guard.classifyScreenOn(nowMs = 2_100L))
        assertEquals(ScreenOffLoopGuard.EventSource.USER, guard.classifyScreenOn(nowMs = 3_100L))
    }

    @Test
    fun `screen on shortly after blackout start is self induced`() {
        guard.markBlackoutStart(nowMs = 5_000L)

        assertEquals(ScreenOffLoopGuard.EventSource.WAKE_PULSE_RELATED, guard.classifyScreenOn(nowMs = 5_500L))
    }

    @Test
    fun `screen on long after blackout start is user induced`() {
        guard.markBlackoutStart(nowMs = 5_000L)

        assertEquals(ScreenOffLoopGuard.EventSource.USER, guard.classifyScreenOn(nowMs = 6_000L))
    }
}

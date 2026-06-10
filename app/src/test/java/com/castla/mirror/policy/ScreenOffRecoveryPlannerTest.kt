package com.castla.mirror.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOffRecoveryPlannerTest {

    @Test
    fun `shouldRequestResumeBurst returns true for any strategy`() {
        assertTrue(ScreenOffRecoveryPlanner.shouldRequestResumeBurst(ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE))
        assertTrue(ScreenOffRecoveryPlanner.shouldRequestResumeBurst(ScreenOffReviveStrategy.PANEL_OFF))
    }

    @Test
    fun `keepAliveStopDelayMs returns delay only for blackout strategy`() {
        assertEquals(
            ScreenOffRecoveryPlanner.BLACKOUT_KEEP_ALIVE_STOP_DELAY_MS,
            ScreenOffRecoveryPlanner.keepAliveStopDelayMs(ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE)
        )
        assertEquals(
            0L,
            ScreenOffRecoveryPlanner.keepAliveStopDelayMs(ScreenOffReviveStrategy.PANEL_OFF)
        )
    }

    @Test
    fun `shouldKeepVdKeepAliveRunningAfterScreenOn returns true only for blackout strategy`() {
        assertTrue(
            ScreenOffRecoveryPlanner.shouldKeepVdKeepAliveRunningAfterScreenOn(ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE)
        )
        assertFalse(
            ScreenOffRecoveryPlanner.shouldKeepVdKeepAliveRunningAfterScreenOn(ScreenOffReviveStrategy.PANEL_OFF)
        )
    }

    @Test
    fun `shouldPulseVirtualDisplayWake only depends on blackout strategy`() {
        assertFalse(
            ScreenOffRecoveryPlanner.shouldPulseVirtualDisplayWake(
                ScreenOffReviveStrategy.PANEL_OFF
            )
        )
        assertTrue(
            ScreenOffRecoveryPlanner.shouldPulseVirtualDisplayWake(
                ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE
            )
        )
    }

    @Test
    fun `shouldUseDirectWakeForRevive is enabled while screen is off`() {
        assertTrue(
            ScreenOffRecoveryPlanner.shouldUseDirectWakeForRevive(isScreenOff = true)
        )
        assertTrue(
            ScreenOffRecoveryPlanner.shouldUseDirectWakeForRevive(isScreenOff = false)
        )
    }

    @Test
    fun `appExitMonitorIntervalMs slows down while screen is off`() {
        assertEquals(
            2_000L,
            ScreenOffRecoveryPlanner.appExitMonitorIntervalMs(isScreenOff = false)
        )
        assertEquals(
            6_000L,
            ScreenOffRecoveryPlanner.appExitMonitorIntervalMs(isScreenOff = true)
        )
    }

    @Test
    fun `vdKeepAliveIntervalMs slows down when screen off is stable`() {
        assertEquals(
            1_000L,
            ScreenOffRecoveryPlanner.vdKeepAliveIntervalMs(
                isScreenOff = false,
                blackoutActivityReady = false
            )
        )
        assertEquals(
            1_000L,
            ScreenOffRecoveryPlanner.vdKeepAliveIntervalMs(
                isScreenOff = true,
                blackoutActivityReady = false
            )
        )
        assertEquals(
            2_500L,
            ScreenOffRecoveryPlanner.vdKeepAliveIntervalMs(
                isScreenOff = true,
                blackoutActivityReady = true
            )
        )
    }

    @Test
    fun `fallbackWatchdogDelayMs is longer while screen is off`() {
        assertEquals(
            5_500L,
            ScreenOffRecoveryPlanner.fallbackWatchdogDelayMs(isScreenOff = false)
        )
        assertEquals(
            8_000L,
            ScreenOffRecoveryPlanner.fallbackWatchdogDelayMs(isScreenOff = true)
        )
    }
}

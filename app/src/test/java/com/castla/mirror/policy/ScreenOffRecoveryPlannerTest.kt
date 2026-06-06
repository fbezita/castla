package com.castla.mirror.policy

import org.junit.Assert.assertEquals
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
        org.junit.Assert.assertFalse(
            ScreenOffRecoveryPlanner.shouldKeepVdKeepAliveRunningAfterScreenOn(ScreenOffReviveStrategy.PANEL_OFF)
        )
    }
}


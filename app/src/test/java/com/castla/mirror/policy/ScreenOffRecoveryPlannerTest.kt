package com.castla.mirror.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOffRecoveryPlannerTest {

    @Test
    fun `blackout strategy revives only when keep alive starts`() {
        assertFalse(
            ScreenOffRecoveryPlanner.shouldRequestReviveBurst(
                action = ScreenOffAction.TURN_PANEL_OFF,
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
            )
        )
        assertTrue(
            ScreenOffRecoveryPlanner.shouldRequestReviveBurst(
                action = ScreenOffAction.START_KEEP_ALIVE,
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
            )
        )
    }

    @Test
    fun `panel off strategy does not use blackout revive burst`() {
        assertFalse(
            ScreenOffRecoveryPlanner.shouldRequestReviveBurst(
                action = ScreenOffAction.START_KEEP_ALIVE,
                strategy = ScreenOffReviveStrategy.PANEL_OFF,
            )
        )
    }

    @Test
    fun `blackout strategy skips keep alive resume burst`() {
        assertFalse(
            ScreenOffRecoveryPlanner.shouldRequestResumeBurst(
                action = ScreenOffAction.STOP_KEEP_ALIVE,
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
            )
        )
    }

    @Test
    fun `restore panel still requests resume burst`() {
        assertTrue(
            ScreenOffRecoveryPlanner.shouldRequestResumeBurst(
                action = ScreenOffAction.RESTORE_PANEL,
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
            )
        )
        assertTrue(
            ScreenOffRecoveryPlanner.shouldRequestResumeBurst(
                action = ScreenOffAction.RESTORE_PANEL,
                strategy = ScreenOffReviveStrategy.PANEL_OFF,
            )
        )
    }

    @Test
    fun `panel off keep alive stop can still request resume burst`() {
        assertTrue(
            ScreenOffRecoveryPlanner.shouldRequestResumeBurst(
                action = ScreenOffAction.STOP_KEEP_ALIVE,
                strategy = ScreenOffReviveStrategy.PANEL_OFF,
            )
        )
    }

    @Test
    fun `blackout strategy ignores immediate screen on after blackout starts`() {
        assertTrue(
            ScreenOffRecoveryPlanner.shouldIgnoreTransientScreenOn(
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
                blackoutActive = true,
                sinceBlackoutStartMs = 80L,
            )
        )
        assertFalse(
            ScreenOffRecoveryPlanner.shouldIgnoreTransientScreenOn(
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
                blackoutActive = true,
                sinceBlackoutStartMs = 1_500L,
            )
        )
    }

    @Test
    fun `non blackout flow does not ignore screen on`() {
        assertFalse(
            ScreenOffRecoveryPlanner.shouldIgnoreTransientScreenOn(
                strategy = ScreenOffReviveStrategy.PANEL_OFF,
                blackoutActive = true,
                sinceBlackoutStartMs = 80L,
            )
        )
        assertFalse(
            ScreenOffRecoveryPlanner.shouldIgnoreTransientScreenOn(
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
                blackoutActive = false,
                sinceBlackoutStartMs = 80L,
            )
        )
    }

    @Test
    fun `blackout restore is deferred until real screen on`() {
        assertTrue(
            ScreenOffRecoveryPlanner.shouldDeferBlackoutRestoreUntilScreenOn(
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
                blackoutActive = true,
            )
        )
        assertFalse(
            ScreenOffRecoveryPlanner.shouldDeferBlackoutRestoreUntilScreenOn(
                strategy = ScreenOffReviveStrategy.PANEL_OFF,
                blackoutActive = true,
            )
        )
        assertFalse(
            ScreenOffRecoveryPlanner.shouldDeferBlackoutRestoreUntilScreenOn(
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
                blackoutActive = false,
            )
        )
    }

    @Test
    fun `blackout strategy delays keep alive stop on screen on`() {
        assertTrue(
            ScreenOffRecoveryPlanner.keepAliveStopDelayMs(
                action = ScreenOffAction.STOP_KEEP_ALIVE,
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
            ) > 0L
        )
        assertFalse(
            ScreenOffRecoveryPlanner.keepAliveStopDelayMs(
                action = ScreenOffAction.STOP_KEEP_ALIVE,
                strategy = ScreenOffReviveStrategy.PANEL_OFF,
            ) > 0L
        )
        assertFalse(
            ScreenOffRecoveryPlanner.keepAliveStopDelayMs(
                action = ScreenOffAction.RESTORE_PANEL,
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
            ) > 0L
        )
    }

    @Test
    fun `blackout restore skips direct wake`() {
        assertFalse(
            ScreenOffRecoveryPlanner.shouldDirectWakeOnBlackoutRestore(
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
            )
        )
        assertTrue(
            ScreenOffRecoveryPlanner.shouldDirectWakeOnBlackoutRestore(
                strategy = ScreenOffReviveStrategy.PANEL_OFF,
            )
        )
    }

    @Test
    fun `blackout strategy keeps vd keepalive running after screen on`() {
        assertTrue(
            ScreenOffRecoveryPlanner.shouldKeepVdKeepAliveRunningAfterScreenOn(
                action = ScreenOffAction.STOP_KEEP_ALIVE,
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
            )
        )
        assertFalse(
            ScreenOffRecoveryPlanner.shouldKeepVdKeepAliveRunningAfterScreenOn(
                action = ScreenOffAction.STOP_KEEP_ALIVE,
                strategy = ScreenOffReviveStrategy.PANEL_OFF,
            )
        )
        assertFalse(
            ScreenOffRecoveryPlanner.shouldKeepVdKeepAliveRunningAfterScreenOn(
                action = ScreenOffAction.RESTORE_PANEL,
                strategy = ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
            )
        )
    }
}

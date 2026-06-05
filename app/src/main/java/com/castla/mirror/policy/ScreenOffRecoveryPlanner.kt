package com.castla.mirror.policy

/**
 * Pure policy decisions for screen-off recovery pulses.
 *
 * Samsung blackout mode already keeps the mirrored pipeline alive, so we avoid
 * duplicate revive/resume bursts unless a real panel restore happens.
 */
object ScreenOffRecoveryPlanner {

    const val BLACKOUT_TRANSIENT_SCREEN_ON_WINDOW_MS = 1_000L
    const val BLACKOUT_KEEP_ALIVE_STOP_DELAY_MS = 1_500L

    fun shouldRequestReviveBurst(
        action: ScreenOffAction,
        strategy: ScreenOffReviveStrategy,
    ): Boolean {
        if (strategy != ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE) return false
        return action == ScreenOffAction.START_KEEP_ALIVE
    }

    fun shouldRequestResumeBurst(
        action: ScreenOffAction,
        strategy: ScreenOffReviveStrategy,
    ): Boolean {
        return when (action) {
            ScreenOffAction.RESTORE_PANEL -> true
            ScreenOffAction.STOP_KEEP_ALIVE -> strategy != ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE
            else -> false
        }
    }

    fun shouldIgnoreTransientScreenOn(
        strategy: ScreenOffReviveStrategy,
        blackoutActive: Boolean,
        sinceBlackoutStartMs: Long,
    ): Boolean {
        if (strategy != ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE) return false
        if (!blackoutActive) return false
        return sinceBlackoutStartMs in 0..BLACKOUT_TRANSIENT_SCREEN_ON_WINDOW_MS
    }

    fun shouldDeferBlackoutRestoreUntilScreenOn(
        strategy: ScreenOffReviveStrategy,
        blackoutActive: Boolean,
    ): Boolean {
        return strategy == ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE && blackoutActive
    }

    fun keepAliveStopDelayMs(
        action: ScreenOffAction,
        strategy: ScreenOffReviveStrategy,
    ): Long {
        return if (action == ScreenOffAction.STOP_KEEP_ALIVE && strategy == ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE) {
            BLACKOUT_KEEP_ALIVE_STOP_DELAY_MS
        } else {
            0L
        }
    }

    fun shouldKeepVdKeepAliveRunningAfterScreenOn(
        action: ScreenOffAction,
        strategy: ScreenOffReviveStrategy,
    ): Boolean {
        return action == ScreenOffAction.STOP_KEEP_ALIVE && strategy == ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE
    }

    fun shouldDirectWakeOnBlackoutRestore(
        strategy: ScreenOffReviveStrategy,
    ): Boolean {
        return strategy != ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE
    }
}

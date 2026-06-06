package com.castla.mirror.policy

/**
 * Pure policy decisions for screen-off recovery pulses.
 * Simplified to remove ScreenOffAction and align with the state machine.
 */
object ScreenOffRecoveryPlanner {

    const val BLACKOUT_KEEP_ALIVE_STOP_DELAY_MS = 1_500L

    fun shouldRequestResumeBurst(
        strategy: ScreenOffReviveStrategy,
    ): Boolean {
        return true
    }

    fun keepAliveStopDelayMs(
        strategy: ScreenOffReviveStrategy,
    ): Long {
        return if (strategy == ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE) {
            BLACKOUT_KEEP_ALIVE_STOP_DELAY_MS
        } else {
            0L
        }
    }

    fun shouldKeepVdKeepAliveRunningAfterScreenOn(
        strategy: ScreenOffReviveStrategy,
    ): Boolean {
        return strategy == ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE
    }
}


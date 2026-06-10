package com.castla.mirror.policy

/**
 * Pure policy decisions for screen-off recovery pulses.
 * Simplified to remove ScreenOffAction and align with the state machine.
 */
object ScreenOffRecoveryPlanner {

    const val BLACKOUT_KEEP_ALIVE_STOP_DELAY_MS = 1_500L
    const val APP_EXIT_MONITOR_INTERVAL_MS = 2_000L
    const val APP_EXIT_MONITOR_SCREEN_OFF_INTERVAL_MS = 6_000L
    const val VD_KEEP_ALIVE_INTERVAL_MS = 1_000L
    const val VD_KEEP_ALIVE_SCREEN_OFF_STABLE_INTERVAL_MS = 2_500L
    const val FALLBACK_WATCHDOG_DELAY_MS = 5_500L
    const val FALLBACK_WATCHDOG_SCREEN_OFF_DELAY_MS = 8_000L

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

    fun shouldPulseVirtualDisplayWake(
        strategy: ScreenOffReviveStrategy,
    ): Boolean {
        return strategy == ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE
    }

    fun shouldUseDirectWakeForRevive(
        isScreenOff: Boolean,
    ): Boolean {
        return true
    }

    fun appExitMonitorIntervalMs(
        isScreenOff: Boolean,
    ): Long {
        return if (isScreenOff) {
            APP_EXIT_MONITOR_SCREEN_OFF_INTERVAL_MS
        } else {
            APP_EXIT_MONITOR_INTERVAL_MS
        }
    }

    fun vdKeepAliveIntervalMs(
        isScreenOff: Boolean,
        blackoutActivityReady: Boolean,
    ): Long {
        return if (isScreenOff && blackoutActivityReady) {
            VD_KEEP_ALIVE_SCREEN_OFF_STABLE_INTERVAL_MS
        } else {
            VD_KEEP_ALIVE_INTERVAL_MS
        }
    }

    fun fallbackWatchdogDelayMs(
        isScreenOff: Boolean,
    ): Long {
        return if (isScreenOff) {
            FALLBACK_WATCHDOG_SCREEN_OFF_DELAY_MS
        } else {
            FALLBACK_WATCHDOG_DELAY_MS
        }
    }
}

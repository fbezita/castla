package com.castla.mirror.policy

/**
 * States for the screen-off mirroring state machine.
 */
enum class ScreenOffState {
    /** Normal operation — physical screen is on, VD rendering normally. */
    ACTIVE,
    /** Screen off detected: blackout overlay launched and waiting for layout ready. */
    BLACKOUT_PENDING,
    /** Blackout overlay active and virtual display keeping alive. */
    BLACKOUT_ACTIVE
}

/**
 * Events that trigger state transitions.
 */
enum class ScreenOffEvent {
    SCREEN_OFF,
    ON_BLACKOUT_READY,
    RESTORE_REQUEST,
    SCREEN_ON,
    USER_PRESENT,
    RESET
}

/**
 * Pure-logic state machine for screen-off mirroring.
 * Simplified to 3 states (ACTIVE, BLACKOUT_PENDING, BLACKOUT_ACTIVE).
 */
class ScreenOffPolicy {

    var state: ScreenOffState = ScreenOffState.ACTIVE
        private set

    /** Whether this device supports panel-off. Set to false on first failure. */
    var isPanelOffSupported: Boolean = true
        private set

    /** True when the physical screen is off (any non-ACTIVE state). */
    val isScreenOff: Boolean
        get() = state != ScreenOffState.ACTIVE

    fun transition(event: ScreenOffEvent): ScreenOffState {
        state = when (state) {
            ScreenOffState.ACTIVE -> when (event) {
                ScreenOffEvent.SCREEN_OFF -> ScreenOffState.BLACKOUT_PENDING
                else -> state
            }
            ScreenOffState.BLACKOUT_PENDING -> when (event) {
                ScreenOffEvent.ON_BLACKOUT_READY -> ScreenOffState.BLACKOUT_ACTIVE
                ScreenOffEvent.RESTORE_REQUEST -> ScreenOffState.ACTIVE
                ScreenOffEvent.SCREEN_ON -> ScreenOffState.ACTIVE
                ScreenOffEvent.USER_PRESENT -> ScreenOffState.ACTIVE
                ScreenOffEvent.RESET -> ScreenOffState.ACTIVE
                else -> state
            }
            ScreenOffState.BLACKOUT_ACTIVE -> when (event) {
                ScreenOffEvent.RESTORE_REQUEST -> ScreenOffState.ACTIVE
                ScreenOffEvent.SCREEN_ON -> ScreenOffState.ACTIVE
                ScreenOffEvent.USER_PRESENT -> ScreenOffState.ACTIVE
                ScreenOffEvent.RESET -> ScreenOffState.ACTIVE
                else -> state
            }
        }
        return state
    }

    fun markPanelOffFailed() {
        isPanelOffSupported = false
    }

    /** Reset to initial state (e.g., on service restart). */
    fun reset() {
        state = ScreenOffState.ACTIVE
        isPanelOffSupported = true
    }
}

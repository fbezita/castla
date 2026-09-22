package com.castla.mirror.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineAutomationPolicyTest {
    @Test
    fun `only the dedicated shortcut action requests an automatic start`() {
        assertTrue(RoutineAutomationPolicy.isServerStartAction(RoutineAutomationPolicy.ACTION_START_SERVER))
        assertFalse(RoutineAutomationPolicy.isServerStartAction(null))
        assertFalse(RoutineAutomationPolicy.isServerStartAction("android.intent.action.MAIN"))
    }

    @Test
    fun `only the dedicated stop shortcut action requests an automatic stop`() {
        assertTrue(RoutineAutomationPolicy.isServerStopAction(RoutineAutomationPolicy.ACTION_STOP_SERVER))
        assertFalse(RoutineAutomationPolicy.isServerStopAction(null))
        assertFalse(RoutineAutomationPolicy.isServerStopAction(RoutineAutomationPolicy.ACTION_START_SERVER))
    }

    @Test
    fun `automatic start waits for setup and remains idempotent`() {
        assertFalse(
            RoutineAutomationPolicy.canStartNow(
                startRequested = true,
                setupReady = false,
                serviceRunning = false,
                startInProgress = false,
            ),
        )
        assertFalse(
            RoutineAutomationPolicy.canStartNow(
                startRequested = true,
                setupReady = true,
                serviceRunning = true,
                startInProgress = false,
            ),
        )
        assertFalse(
            RoutineAutomationPolicy.canStartNow(
                startRequested = true,
                setupReady = true,
                serviceRunning = false,
                startInProgress = true,
            ),
        )
        assertFalse(
            RoutineAutomationPolicy.canStartNow(
                startRequested = true,
                setupReady = true,
                serviceRunning = false,
                startInProgress = false,
                cleanupInProgress = true,
            ),
        )
        assertTrue(
            RoutineAutomationPolicy.canStartNow(
                startRequested = true,
                setupReady = true,
                serviceRunning = false,
                startInProgress = false,
            ),
        )
    }
}

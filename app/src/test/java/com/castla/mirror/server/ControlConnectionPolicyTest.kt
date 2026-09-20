package com.castla.mirror.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ControlConnectionPolicyTest {
    @Test
    fun `existing connection keeps ownership when another browser connects`() {
        assertFalse(
            ControlConnectionPolicy.newConnectionTakesControl(
                hasActiveConnection = true,
                takeoverRequested = false,
            ),
        )
        assertEquals(
            InactiveControlMessageAction.IGNORE,
            ControlConnectionPolicy.inactiveMessageAction(hasActiveConnection = true),
        )
    }

    @Test
    fun `passive connection can take over after active connection closes`() {
        assertEquals(
            true,
            ControlConnectionPolicy.newConnectionTakesControl(
                hasActiveConnection = false,
                takeoverRequested = false,
            ),
        )
        assertEquals(
            InactiveControlMessageAction.PROMOTE,
            ControlConnectionPolicy.inactiveMessageAction(hasActiveConnection = false),
        )
    }

    @Test
    fun `explicit takeover replaces an existing controller`() {
        assertEquals(
            true,
            ControlConnectionPolicy.newConnectionTakesControl(
                hasActiveConnection = true,
                takeoverRequested = true,
            ),
        )
    }
}

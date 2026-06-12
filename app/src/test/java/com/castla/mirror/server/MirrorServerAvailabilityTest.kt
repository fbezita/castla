package com.castla.mirror.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorServerAvailabilityTest {
    @Test
    fun readyHttpCountsAsReady() {
        assertTrue(MirrorServerAvailability(MirrorServerAvailabilityState.READY_HTTP).isReady)
    }

    @Test
    fun readyHttpsCountsAsReady() {
        assertTrue(MirrorServerAvailability(MirrorServerAvailabilityState.READY_HTTPS).isReady)
    }

    @Test
    fun waitingRelayDoesNotCountAsReady() {
        assertFalse(MirrorServerAvailability(MirrorServerAvailabilityState.WAITING_RELAY).isReady)
    }

    @Test
    fun errorDoesNotCountAsReady() {
        assertFalse(MirrorServerAvailability(MirrorServerAvailabilityState.ERROR).isReady)
    }
}

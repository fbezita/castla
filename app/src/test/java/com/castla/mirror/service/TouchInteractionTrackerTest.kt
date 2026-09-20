package com.castla.mirror.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchInteractionTrackerTest {
    @Test
    fun `tracks active pointer until up`() {
        val tracker = TouchInteractionTracker()
        tracker.note("down", 1_000L)
        assertTrue(tracker.isActive(5_000L))

        tracker.note("up", 5_000L)
        assertTrue(tracker.isActive(5_200L))
        assertFalse(tracker.isActive(5_251L))
    }

    @Test
    fun `cancel never makes pointer count negative`() {
        val tracker = TouchInteractionTracker()
        tracker.note("cancel", 100L)
        assertFalse(tracker.isActive(351L))
    }
}

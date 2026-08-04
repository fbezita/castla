package com.castla.mirror.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebuildRequestPolicyTest {
    @Test
    fun queueCapacityIsBounded() {
        assertTrue(RebuildRequestPolicy.MAX_PENDING_REQUESTS > 0)
        assertTrue(RebuildRequestPolicy.MAX_PENDING_REQUESTS <= 64)
    }

    @Test
    fun coalescesEquivalentRecentRequestWithoutCompletion() {
        assertTrue(
            RebuildRequestPolicy.shouldCoalesce(
                previous = RebuildRequestPolicy.RequestSnapshot(720, 1280, force = false, forceSingle = false, requestedAt = 1_000L),
                width = 720,
                height = 1280,
                force = false,
                forceSingle = false,
                requestedAt = 1_100L,
                hasCompletion = false,
                immediate = false,
            )
        )
    }

    @Test
    fun keepsEquivalentRequestWhenCompletionMustBeSignalled() {
        assertFalse(
            RebuildRequestPolicy.shouldCoalesce(
                previous = RebuildRequestPolicy.RequestSnapshot(720, 1280, force = false, forceSingle = false, requestedAt = 1_000L),
                width = 720,
                height = 1280,
                force = false,
                forceSingle = false,
                requestedAt = 1_100L,
                hasCompletion = true,
                immediate = false,
            )
        )
    }
    @Test
    fun keepsRequestWhenBrowserLayoutNotReceived() {
        val skip = RebuildRequestPolicy.shouldSkipStaleRequest(
            requestWidth = 464,
            requestHeight = 736,
            viewport = RebuildRequestPolicy.PendingViewport(
                requestedWidth = 1024,
                requestedHeight = 720,
                hasReceivedBrowserLayout = false,
            ),
        )

        assertFalse(skip)
    }

    @Test
    fun keepsLatestRequestThatMatchesViewport() {
        val skip = RebuildRequestPolicy.shouldSkipStaleRequest(
            requestWidth = 464,
            requestHeight = 736,
            viewport = RebuildRequestPolicy.PendingViewport(
                requestedWidth = 464,
                requestedHeight = 736,
                hasReceivedBrowserLayout = true,
            ),
        )

        assertFalse(skip)
    }

    @Test
    fun skipsOlderRequestAfterViewportChanged() {
        val skip = RebuildRequestPolicy.shouldSkipStaleRequest(
            requestWidth = 464,
            requestHeight = 736,
            viewport = RebuildRequestPolicy.PendingViewport(
                requestedWidth = 1024,
                requestedHeight = 720,
                hasReceivedBrowserLayout = true,
            ),
        )

        assertTrue(skip)
    }
}

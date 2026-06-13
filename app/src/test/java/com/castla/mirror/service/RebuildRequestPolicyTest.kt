package com.castla.mirror.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebuildRequestPolicyTest {
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

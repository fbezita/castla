package com.castla.mirror.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLayoutPolicyTest {
    @Test
    fun forcesRealignWhenVisiblePaneCountChanges() {
        assertTrue(
            BrowserLayoutPolicy.shouldForceViewportRealign(
                previousVisiblePaneCount = 2,
                currentVisiblePaneCount = 1,
                previousWidth = 464,
                previousHeight = 736,
                nextWidth = 1024,
                nextHeight = 736,
            )
        )
    }

    @Test
    fun forcesRealignWhenPaneSizeChangesWithoutPaneCountChange() {
        assertTrue(
            BrowserLayoutPolicy.shouldForceViewportRealign(
                previousVisiblePaneCount = 2,
                currentVisiblePaneCount = 2,
                previousWidth = 464,
                previousHeight = 736,
                nextWidth = 640,
                nextHeight = 736,
            )
        )
    }

    @Test
    fun doesNotForceRealignWhenPaneSizeIsUnchanged() {
        assertFalse(
            BrowserLayoutPolicy.shouldForceViewportRealign(
                previousVisiblePaneCount = 2,
                currentVisiblePaneCount = 2,
                previousWidth = 464,
                previousHeight = 736,
                nextWidth = 464,
                nextHeight = 736,
            )
        )
    }
}

package com.castla.mirror.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeVisibilityPolicyTest {

    @Test
    fun `uses input target fallback for active virtual display`() {
        assertTrue(
            ImeVisibilityPolicy.shouldUseInputTargetFallback(
                activeDisplayId = 53,
                displaysWithInputTarget = setOf(53),
                haveSeenRealImeShow = false,
                bubbleClosedByUser = false
            )
        )
    }

    @Test
    fun `does not use fallback for physical display`() {
        assertFalse(
            ImeVisibilityPolicy.shouldUseInputTargetFallback(
                activeDisplayId = 0,
                displaysWithInputTarget = setOf(0),
                haveSeenRealImeShow = false,
                bubbleClosedByUser = false
            )
        )
    }

    @Test
    fun `does not use fallback for other display target`() {
        assertFalse(
            ImeVisibilityPolicy.shouldUseInputTargetFallback(
                activeDisplayId = 53,
                displaysWithInputTarget = setOf(54),
                haveSeenRealImeShow = false,
                bubbleClosedByUser = false
            )
        )
    }

    @Test
    fun `real IME visibility takes precedence over input target fallback`() {
        assertFalse(
            ImeVisibilityPolicy.shouldUseInputTargetFallback(
                activeDisplayId = 53,
                displaysWithInputTarget = setOf(53),
                haveSeenRealImeShow = true,
                bubbleClosedByUser = false
            )
        )
    }

    @Test
    fun `manual bubble close suppresses input target fallback`() {
        assertFalse(
            ImeVisibilityPolicy.shouldUseInputTargetFallback(
                activeDisplayId = 53,
                displaysWithInputTarget = setOf(53),
                haveSeenRealImeShow = false,
                bubbleClosedByUser = true
            )
        )
    }
}

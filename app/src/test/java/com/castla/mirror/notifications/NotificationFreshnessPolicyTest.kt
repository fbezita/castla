package com.castla.mirror.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFreshnessPolicyTest {

    @Test
    fun `drops notifications that predate the listener connection`() {
        assertFalse(
            NotificationFreshnessPolicy.shouldForward(
                postedAtMs = 10_000L,
                listenerConnectedAtMs = 20_000L,
            )
        )
    }

    @Test
    fun `allows notifications posted around or after the listener connection`() {
        assertTrue(
            NotificationFreshnessPolicy.shouldForward(
                postedAtMs = 19_500L,
                listenerConnectedAtMs = 20_000L,
            )
        )
        assertTrue(
            NotificationFreshnessPolicy.shouldForward(
                postedAtMs = 21_000L,
                listenerConnectedAtMs = 20_000L,
            )
        )
    }

    @Test
    fun `allows events when the listener connection time is not known`() {
        assertTrue(
            NotificationFreshnessPolicy.shouldForward(
                postedAtMs = 1_000L,
                listenerConnectedAtMs = 0L,
            )
        )
    }
}

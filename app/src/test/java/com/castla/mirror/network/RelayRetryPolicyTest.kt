package com.castla.mirror.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRetryPolicyTest {

    @Test
    fun `retry delay backs off and caps at thirty seconds`() {
        assertEquals(3_000L, RelayRetryPolicy.delayAfterFailure(1))
        assertEquals(7_000L, RelayRetryPolicy.delayAfterFailure(2))
        assertEquals(15_000L, RelayRetryPolicy.delayAfterFailure(3))
        assertEquals(30_000L, RelayRetryPolicy.delayAfterFailure(4))
        assertEquals(30_000L, RelayRetryPolicy.delayAfterFailure(20))
    }

    @Test
    fun `invalid failure counts use first retry delay`() {
        assertEquals(3_000L, RelayRetryPolicy.delayAfterFailure(0))
        assertEquals(3_000L, RelayRetryPolicy.delayAfterFailure(-1))
    }

    @Test
    fun `slow test server timeouts leave enough cold start headroom`() {
        assertEquals(10_000, RelayRetryPolicy.CONNECT_TIMEOUT_MS)
        assertEquals(30_000, RelayRetryPolicy.READ_TIMEOUT_MS)
        assertTrue(
            RelayRetryPolicy.SERVICE_START_TIMEOUT_MS >=
                RelayRetryPolicy.CONNECT_TIMEOUT_MS + RelayRetryPolicy.READ_TIMEOUT_MS + 10_000L,
        )
    }
}

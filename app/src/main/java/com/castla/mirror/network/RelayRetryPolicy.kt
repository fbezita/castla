package com.castla.mirror.network

/** Timing policy for relay registration against a cold or slow backend. */
object RelayRetryPolicy {
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 30_000
    const val SERVICE_START_TIMEOUT_MS = 60_000L

    private val retryDelaysMs = longArrayOf(3_000L, 7_000L, 15_000L, 30_000L)

    fun delayAfterFailure(failureCount: Int): Long {
        val index = (failureCount.coerceAtLeast(1) - 1).coerceAtMost(retryDelaysMs.lastIndex)
        return retryDelaysMs[index]
    }
}

package com.castla.mirror.server

object TlsCertificateRefreshPolicy {
    private const val REFRESH_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val PROACTIVE_REFRESH_WINDOW_MS = 7L * REFRESH_CHECK_INTERVAL_MS

    fun shouldRefresh(
        nowMs: Long,
        certificateNotAfterMs: Long?,
        lastRefreshCheckMs: Long?,
    ): Boolean {
        val notAfterMs = certificateNotAfterMs ?: return true

        if (notAfterMs <= nowMs) {
            return true
        }

        if (notAfterMs - nowMs > PROACTIVE_REFRESH_WINDOW_MS) {
            return false
        }

        val lastCheckedMs = lastRefreshCheckMs ?: return true
        return nowMs - lastCheckedMs >= REFRESH_CHECK_INTERVAL_MS
    }
}

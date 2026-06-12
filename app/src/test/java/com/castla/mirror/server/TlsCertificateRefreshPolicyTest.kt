package com.castla.mirror.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsCertificateRefreshPolicyTest {
    private val nowMs = 1_700_000_000_000L
    private val oneDayMs = 24L * 60L * 60L * 1000L
    private val sevenDaysMs = 7L * oneDayMs

    @Test
    fun refreshesWhenCertificateMetadataIsMissing() {
        val shouldRefresh = TlsCertificateRefreshPolicy.shouldRefresh(
            nowMs = nowMs,
            certificateNotAfterMs = null,
            lastRefreshCheckMs = null,
        )

        assertTrue(shouldRefresh)
    }

    @Test
    fun refreshesWhenCertificateIsExpired() {
        val shouldRefresh = TlsCertificateRefreshPolicy.shouldRefresh(
            nowMs = nowMs,
            certificateNotAfterMs = nowMs - 1L,
            lastRefreshCheckMs = nowMs,
        )

        assertTrue(shouldRefresh)
    }

    @Test
    fun skipsRefreshWhenCertificateIsHealthyAndOutsideRefreshWindow() {
        val shouldRefresh = TlsCertificateRefreshPolicy.shouldRefresh(
            nowMs = nowMs,
            certificateNotAfterMs = nowMs + sevenDaysMs + oneDayMs,
            lastRefreshCheckMs = nowMs - (3L * oneDayMs),
        )

        assertFalse(shouldRefresh)
    }

    @Test
    fun refreshesWhenCertificateIsNearExpiryAndLastCheckIsStale() {
        val shouldRefresh = TlsCertificateRefreshPolicy.shouldRefresh(
            nowMs = nowMs,
            certificateNotAfterMs = nowMs + oneDayMs,
            lastRefreshCheckMs = nowMs - oneDayMs,
        )

        assertTrue(shouldRefresh)
    }

    @Test
    fun skipsRefreshWhenCertificateIsNearExpiryButCheckedRecently() {
        val shouldRefresh = TlsCertificateRefreshPolicy.shouldRefresh(
            nowMs = nowMs,
            certificateNotAfterMs = nowMs + oneDayMs,
            lastRefreshCheckMs = nowMs - (oneDayMs - 1L),
        )

        assertFalse(shouldRefresh)
    }
}

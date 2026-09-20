package com.castla.mirror.notifications

/** Filters the active-notification replay emitted when Android reconnects a listener. */
object NotificationFreshnessPolicy {
    private const val CONNECTION_SKEW_TOLERANCE_MS = 2_000L

    fun shouldForward(postedAtMs: Long, listenerConnectedAtMs: Long): Boolean {
        if (listenerConnectedAtMs <= 0L) return true
        return postedAtMs >= listenerConnectedAtMs - CONNECTION_SKEW_TOLERANCE_MS
    }
}

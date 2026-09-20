package com.castla.mirror.service

internal class TouchInteractionTracker(
    private val recentWindowMs: Long = 250L,
) {
    @Volatile
    var lastEventAt: Long = 0L
        private set

    @Volatile
    private var activePointerCount = 0

    fun note(action: String, now: Long) {
        lastEventAt = now
        when (action) {
            "down" -> activePointerCount += 1
            "up", "cancel" -> activePointerCount = (activePointerCount - 1).coerceAtLeast(0)
        }
    }

    fun isActive(now: Long): Boolean {
        if (activePointerCount > 0) return true
        if (lastEventAt <= 0L) return false
        return now - lastEventAt <= recentWindowMs
    }
}

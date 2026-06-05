package com.castla.mirror.policy

class ScreenOffLoopGuard(
    private val suppressWindowMs: Long = DEFAULT_SUPPRESS_WINDOW_MS,
) {

    companion object {
        const val DEFAULT_SUPPRESS_WINDOW_MS = 2_500L
    }

    enum class EventSource {
        USER,
        SELF_INDUCED,
    }

    private var suppressScreenOffUntilMs: Long = 0L
    private var lastKeepAliveAtMs: Long = 0L

    fun markPowerBurst(nowMs: Long): Long {
        suppressScreenOffUntilMs = nowMs + suppressWindowMs
        return suppressScreenOffUntilMs
    }

    fun markKeepAlive(nowMs: Long) {
        lastKeepAliveAtMs = nowMs
    }

    fun classifyScreenOff(nowMs: Long): EventSource {
        return if (nowMs <= suppressScreenOffUntilMs) EventSource.SELF_INDUCED else EventSource.USER
    }

    fun classifyScreenOn(nowMs: Long): EventSource {
        return if (lastKeepAliveAtMs > 0L && nowMs - lastKeepAliveAtMs <= suppressWindowMs) {
            EventSource.SELF_INDUCED
        } else {
            EventSource.USER
        }
    }

    fun reset() {
        suppressScreenOffUntilMs = 0L
        lastKeepAliveAtMs = 0L
    }
}

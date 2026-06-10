package com.castla.mirror.policy

class ScreenOffLoopGuard(
    private val suppressWindowMs: Long = DEFAULT_SUPPRESS_WINDOW_MS,
    private val suppressScreenOnAfterKeepAliveMs: Long = DEFAULT_SUPPRESS_SCREEN_ON_AFTER_KEEP_ALIVE_MS,
    private val suppressBlackoutWindowMs: Long = DEFAULT_SUPPRESS_BLACKOUT_WINDOW_MS,
) {

    companion object {
        const val DEFAULT_SUPPRESS_WINDOW_MS = 2_500L
        const val DEFAULT_SUPPRESS_SCREEN_ON_AFTER_KEEP_ALIVE_MS = 900L
        const val DEFAULT_SUPPRESS_BLACKOUT_WINDOW_MS = 800L
    }

    enum class EventSource {
        USER,
        SELF_INDUCED,
    }

    private var suppressScreenOffUntilMs: Long = 0L
    private var lastKeepAliveAtMs: Long = 0L
    private var lastBlackoutStartedAtMs: Long = 0L

    fun markPowerBurst(nowMs: Long): Long {
        suppressScreenOffUntilMs = nowMs + suppressWindowMs
        return suppressScreenOffUntilMs
    }

    fun markKeepAlive(nowMs: Long) {
        lastKeepAliveAtMs = nowMs
    }

    fun markBlackoutStart(nowMs: Long) {
        lastBlackoutStartedAtMs = nowMs
    }

    fun classifyScreenOff(nowMs: Long): EventSource {
        return if (nowMs <= suppressScreenOffUntilMs) EventSource.SELF_INDUCED else EventSource.USER
    }

    fun classifyScreenOn(nowMs: Long): EventSource {
        val keepAliveValid = lastKeepAliveAtMs > 0L && nowMs - lastKeepAliveAtMs <= suppressScreenOnAfterKeepAliveMs
        val blackoutStartValid = lastBlackoutStartedAtMs > 0L && nowMs - lastBlackoutStartedAtMs <= suppressBlackoutWindowMs
        return if (keepAliveValid || blackoutStartValid) {
            EventSource.SELF_INDUCED
        } else {
            EventSource.USER
        }
    }

    fun reset() {
        suppressScreenOffUntilMs = 0L
        lastKeepAliveAtMs = 0L
        lastBlackoutStartedAtMs = 0L
    }
}

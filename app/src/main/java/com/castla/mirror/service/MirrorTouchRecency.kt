package com.castla.mirror.service

internal object MirrorTouchRecency {
    fun mostRecentAge(timestamps: Iterable<Long>, now: Long): Long? {
        val lastTouchAt = timestamps.filter { it > 0L }.maxOrNull() ?: return null
        return (now - lastTouchAt).coerceAtLeast(0L)
    }
}

package com.castla.mirror.compositor

class EncoderBudgetManager(
    private val maxActiveEncoders: Int = MAX_ACTIVE_ENCODERS
) {
    companion object {
        const val MAX_ACTIVE_ENCODERS = 2
    }

    fun assign(
        sessions: Collection<PersistentVirtualDisplaySession>,
        priorities: StreamPriorityManager
    ): Map<DisplaySessionId, DisplayTier> {
        val ordered = sessions.sortedByDescending { priorities.priorityOf(it.sessionId) }
        val result = linkedMapOf<DisplaySessionId, DisplayTier>()
        ordered.forEachIndexed { index, session ->
            result[session.sessionId] = when {
                session.sessionId == DisplaySessionId.PRIMARY -> DisplayTier.ACTIVE
                index < maxActiveEncoders -> DisplayTier.ACTIVE
                session.isViewportVisible -> DisplayTier.VISIBLE
                else -> DisplayTier.SUSPENDED
            }
        }
        return result
    }
}

class StreamPriorityManager {
    private val priorities = linkedMapOf<DisplaySessionId, Int>()

    fun setPriority(sessionId: DisplaySessionId, priority: Int) {
        priorities[sessionId] = priority
    }

    fun priorityOf(sessionId: DisplaySessionId): Int {
        return priorities[sessionId] ?: if (sessionId == DisplaySessionId.PRIMARY) Int.MAX_VALUE else 0
    }
}

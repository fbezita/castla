package com.castla.mirror.compositor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DisplaySessionRegistry(
    val resourcePolicyManager: ResourcePolicyManager = ResourcePolicyManager(),
    val encoderBudgetManager: EncoderBudgetManager = EncoderBudgetManager(),
    val streamPriorityManager: StreamPriorityManager = StreamPriorityManager(),
    val layoutCoordinator: LayoutCoordinator = LayoutCoordinator()
) {
    private val mutex = Mutex()
    private val sessions = linkedMapOf<DisplaySessionId, PersistentVirtualDisplaySession>()

    suspend fun register(session: PersistentVirtualDisplaySession) = mutex.withLock {
        sessions[session.sessionId] = session
        if (session.sessionId == DisplaySessionId.PRIMARY) {
            streamPriorityManager.setPriority(session.sessionId, Int.MAX_VALUE)
        }
    }

    suspend fun unregister(sessionId: DisplaySessionId) = mutex.withLock {
        sessions.remove(sessionId)?.release()
    }

    suspend fun applyPolicy(signals: ResourceSignals) = mutex.withLock {
        val budget = encoderBudgetManager.assign(sessions.values, streamPriorityManager)
        for (session in sessions.values) {
            session.setViewportVisible(signals.viewportVisible[session.sessionId] ?: true)
            val nextTier = resourcePolicyManager.chooseTier(
                session = session,
                budgetTier = budget[session.sessionId] ?: DisplayTier.SUSPENDED,
                signals = signals
            )
            session.setTier(nextTier)
        }
    }

    fun session(sessionId: DisplaySessionId): PersistentVirtualDisplaySession? = sessions[sessionId]

    fun diagnostics(): List<DisplaySessionDiagnostics> = sessions.values.map { it.diagnostics() }
}

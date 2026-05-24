package com.castla.mirror.compositor

import android.os.PowerManager

data class ResourceSignals(
    val thermalStatus: Int,
    val browserConnected: Boolean,
    val viewportVisible: Map<DisplaySessionId, Boolean>,
    val inactiveSessions: Set<DisplaySessionId>
)

class ResourcePolicyManager {
    fun chooseTier(
        session: PersistentVirtualDisplaySession,
        budgetTier: DisplayTier,
        signals: ResourceSignals
    ): DisplayTier {
        if (!signals.browserConnected && session.sessionId != DisplaySessionId.PRIMARY) return DisplayTier.SUSPENDED
        if (signals.inactiveSessions.contains(session.sessionId)) return DisplayTier.SUSPENDED

        val visible = signals.viewportVisible[session.sessionId] ?: session.isViewportVisible
        if (!visible && session.sessionId != DisplaySessionId.PRIMARY) return DisplayTier.SUSPENDED

        val thermalHigh = signals.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        if (thermalHigh && session.sessionId != DisplaySessionId.PRIMARY) return DisplayTier.SUSPENDED
        if (thermalHigh && budgetTier == DisplayTier.ACTIVE) return DisplayTier.VISIBLE

        return budgetTier
    }
}

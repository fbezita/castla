package com.castla.mirror.utils

/**
 * Decides when Castla's browser-side keyboard should stand in for Android's
 * soft keyboard on a virtual display.
 */
object ImeVisibilityPolicy {
    fun shouldUseInputTargetFallback(
        activeDisplayId: Int,
        displaysWithInputTarget: Set<Int>,
        haveSeenRealImeShow: Boolean,
        bubbleClosedByUser: Boolean
    ): Boolean {
        return shouldUseInputTargetFallback(
            activeDisplayId = activeDisplayId,
            hasInputTargetOnActiveDisplay = displaysWithInputTarget.contains(activeDisplayId),
            haveSeenRealImeShow = haveSeenRealImeShow,
            bubbleClosedByUser = bubbleClosedByUser
        )
    }

    fun shouldUseInputTargetFallback(
        activeDisplayId: Int,
        hasInputTargetOnActiveDisplay: Boolean,
        haveSeenRealImeShow: Boolean,
        bubbleClosedByUser: Boolean
    ): Boolean {
        if (activeDisplayId <= 0) return false
        if (haveSeenRealImeShow) return false
        if (bubbleClosedByUser) return false
        return hasInputTargetOnActiveDisplay
    }
}

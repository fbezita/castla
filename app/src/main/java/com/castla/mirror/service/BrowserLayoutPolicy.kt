package com.castla.mirror.service

object BrowserLayoutPolicy {
    fun shouldForceViewportRealign(
        previousVisiblePaneCount: Int,
        currentVisiblePaneCount: Int,
        previousWidth: Int,
        previousHeight: Int,
        nextWidth: Int,
        nextHeight: Int,
    ): Boolean {
        if (previousVisiblePaneCount != currentVisiblePaneCount) {
            return true
        }
        if (nextWidth <= 0 || nextHeight <= 0) {
            return false
        }
        return previousWidth != nextWidth || previousHeight != nextHeight
    }
}

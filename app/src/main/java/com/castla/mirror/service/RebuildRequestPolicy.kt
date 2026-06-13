package com.castla.mirror.service

object RebuildRequestPolicy {
    data class PendingViewport(
        val requestedWidth: Int,
        val requestedHeight: Int,
        val hasReceivedBrowserLayout: Boolean,
    )

    fun shouldSkipStaleRequest(
        requestWidth: Int,
        requestHeight: Int,
        viewport: PendingViewport,
    ): Boolean {
        if (!viewport.hasReceivedBrowserLayout) return false
        if (requestWidth <= 0 || requestHeight <= 0) return false
        if (viewport.requestedWidth <= 0 || viewport.requestedHeight <= 0) return false
        return requestWidth != viewport.requestedWidth || requestHeight != viewport.requestedHeight
    }
}

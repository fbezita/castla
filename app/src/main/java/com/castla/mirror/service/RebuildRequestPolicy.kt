package com.castla.mirror.service

object RebuildRequestPolicy {
    const val MAX_PENDING_REQUESTS = 16
    private const val COALESCE_WINDOW_MS = 120L

    data class PendingViewport(
        val requestedWidth: Int,
        val requestedHeight: Int,
        val hasReceivedBrowserLayout: Boolean,
    )

    data class RequestSnapshot(
        val width: Int,
        val height: Int,
        val force: Boolean,
        val forceSingle: Boolean,
        val requestedAt: Long,
    )

    fun shouldCoalesce(
        previous: RequestSnapshot?,
        width: Int,
        height: Int,
        force: Boolean,
        forceSingle: Boolean,
        requestedAt: Long,
        hasCompletion: Boolean,
        immediate: Boolean,
    ): Boolean {
        if (previous == null || hasCompletion || immediate) return false
        val ageMs = requestedAt - previous.requestedAt
        return ageMs in 0..COALESCE_WINDOW_MS &&
            previous.width == width &&
            previous.height == height &&
            previous.force == force &&
            previous.forceSingle == forceSingle
    }

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

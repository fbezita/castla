package com.castla.mirror.compositor

data class ViewportState(
    val sessionId: DisplaySessionId,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val displayWidth: Int,
    val displayHeight: Int,
    val zIndex: Int,
    val visible: Boolean
)

class LayoutCoordinator {
    private val viewports = linkedMapOf<DisplaySessionId, ViewportState>()

    fun updateViewport(viewport: ViewportState) {
        viewports[viewport.sessionId] = viewport
    }

    fun viewportFor(sessionId: DisplaySessionId): ViewportState? = viewports[sessionId]

    fun visibleMap(): Map<DisplaySessionId, Boolean> = viewports.mapValues { it.value.visible }
}

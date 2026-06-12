package com.castla.mirror.server

enum class MirrorServerAvailabilityState {
    IDLE,
    STARTING,
    READY_HTTP,
    WAITING_RELAY,
    READY_HTTPS,
    ERROR,
}

data class MirrorServerAvailability(
    val state: MirrorServerAvailabilityState,
    val detail: String = "",
) {
    val isReady: Boolean
        get() = state == MirrorServerAvailabilityState.READY_HTTP ||
            state == MirrorServerAvailabilityState.READY_HTTPS

    companion object {
        val IDLE = MirrorServerAvailability(MirrorServerAvailabilityState.IDLE)
        val STARTING = MirrorServerAvailability(MirrorServerAvailabilityState.STARTING)
    }
}

package com.castla.mirror.server

internal enum class InactiveControlMessageAction {
    IGNORE,
    PROMOTE,
}

/**
 * Keeps additional browser tabs passive without forcing them into a reconnect loop.
 * The most recently connected tab owns control; an older tab may take over only
 * after the active connection disappears.
 */
internal object ControlConnectionPolicy {
    fun newConnectionTakesControl(
        hasActiveConnection: Boolean,
        takeoverRequested: Boolean,
    ): Boolean = !hasActiveConnection || takeoverRequested

    fun inactiveMessageAction(hasActiveConnection: Boolean): InactiveControlMessageAction =
        if (hasActiveConnection) {
            InactiveControlMessageAction.IGNORE
        } else {
            InactiveControlMessageAction.PROMOTE
        }
}

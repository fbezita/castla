package com.castla.mirror.automation

object RoutineAutomationPolicy {
    const val ACTION_START_SERVER = "com.castla.mirror.action.START_SERVER"
    const val ACTION_STOP_SERVER = "com.castla.mirror.action.STOP_SERVER"

    fun isServerStartAction(action: String?): Boolean = action == ACTION_START_SERVER

    fun isServerStopAction(action: String?): Boolean = action == ACTION_STOP_SERVER

    fun canStartNow(
        startRequested: Boolean,
        setupReady: Boolean,
        serviceRunning: Boolean,
        startInProgress: Boolean,
        cleanupInProgress: Boolean = false,
    ): Boolean = startRequested &&
        setupReady &&
        !serviceRunning &&
        !startInProgress &&
        !cleanupInProgress
}

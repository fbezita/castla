package com.castla.mirror.service

data class ActivityLaunchRequest(
    val nativeLaunchAllowed: Boolean,
    val shellCommand: String,
    val fallbackTaskIds: suspend () -> IntArray,
    val delayBeforeShellMs: Long = 150L,
    val nativeLaunch: suspend () -> Boolean,
    val shellLaunch: suspend (String) -> String,
    val moveTaskToDisplay: suspend (Int) -> Unit,
)

data class ActivityLaunchResult(
    val nativeStarted: Boolean,
    val shellResult: String = "",
    val fallbackTaskIds: IntArray = intArrayOf(),
)

/** Runs the Activity launch/fallback sequence without owning pipeline or encoder state. */
class ActivityLaunchCoordinator {
    suspend fun launch(request: ActivityLaunchRequest): ActivityLaunchResult {
        val nativeStarted = if (request.nativeLaunchAllowed) request.nativeLaunch() else false
        if (nativeStarted) return ActivityLaunchResult(nativeStarted = true)

        if (request.delayBeforeShellMs > 0) {
            kotlinx.coroutines.delay(request.delayBeforeShellMs)
        }
        val shellResult = request.shellLaunch(request.shellCommand)
        val fallbackTaskIds = if (
            shellResult.contains("SecurityException") || shellResult.contains("Permission Denial")
        ) {
            val taskIds = request.fallbackTaskIds()
            taskIds.forEach { taskId -> request.moveTaskToDisplay(taskId) }
            taskIds
        } else {
            intArrayOf()
        }
        return ActivityLaunchResult(
            nativeStarted = false,
            shellResult = shellResult,
            fallbackTaskIds = fallbackTaskIds,
        )
    }
}

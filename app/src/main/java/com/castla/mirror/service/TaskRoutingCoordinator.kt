package com.castla.mirror.service

/** Inputs needed to decide and execute target-display task routing. */
data class TaskRoutingRequest(
    val targetDisplayId: Int,
    val originalDisplayId: Int,
    val matchingTaskIds: List<Int>,
    val targetDisplayPackages: List<String>,
    val packageName: String,
    val forceColdStart: Boolean,
    val displaySizeMatches: Boolean,
    val encoderReady: Boolean,
    val encoderDisplayId: Int,
    val moveTaskNative: suspend (Int) -> Boolean,
    val moveTaskShell: suspend (Int) -> String,
)

data class TaskRoutingResult(
    val targetDisplayHasTask: Boolean,
    val isWarmStart: Boolean,
    val launchPlan: LaunchPlan,
    val moveResults: List<TaskFrontMoveResult>,
)

/** Coordinates task residency and warm-task movement without launching activities. */
class TaskRoutingCoordinator {
    suspend fun route(request: TaskRoutingRequest): TaskRoutingResult {
        val isWarmStart = request.matchingTaskIds.isNotEmpty()
        val targetDisplayHasTask = request.targetDisplayPackages.any {
            it == request.packageName || it.startsWith("${request.packageName}/")
        } || (isWarmStart && request.originalDisplayId == request.targetDisplayId)
        val launchPlan = LaunchPlanner.plan(
            LaunchState(
                targetDisplayId = request.targetDisplayId,
                displayReady = request.targetDisplayId >= 0,
                targetTaskIds = if (targetDisplayHasTask) request.matchingTaskIds else emptyList(),
                otherDisplayTaskExists = isWarmStart && !targetDisplayHasTask &&
                    request.originalDisplayId >= 0 && request.originalDisplayId != request.targetDisplayId,
                forceColdStart = request.forceColdStart,
                displaySizeMatches = request.displaySizeMatches,
                encoderReady = request.encoderReady,
                encoderDisplayId = request.encoderDisplayId,
            )
        )
        val moveResults = if (launchPlan.taskAction == TaskLaunchAction.MOVE_TASK_TO_FRONT) {
            TaskFrontMover(request.moveTaskNative, request.moveTaskShell).move(request.matchingTaskIds)
        } else {
            emptyList()
        }
        return TaskRoutingResult(
            targetDisplayHasTask = targetDisplayHasTask,
            isWarmStart = isWarmStart,
            launchPlan = launchPlan,
            moveResults = moveResults,
        )
    }
}
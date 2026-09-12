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
    val moveTaskToDisplay: suspend (Int, Int) -> Boolean,
    val moveTaskNative: suspend (Int) -> Boolean,
    val moveTaskShell: suspend (Int) -> String,
)

data class TaskRoutingResult(
    val targetDisplayHasTask: Boolean,
    val isWarmStart: Boolean,
    val launchPlan: LaunchPlan,
    val moveResults: List<TaskFrontMoveResult>,
    val borrowedTaskId: Int? = null,
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
                // Borrow only from the phone's primary display. Moving between Castla VDs would
                // create an ownership chain whose origin may be destroyed before cleanup.
                otherDisplayTaskExists = isWarmStart && !targetDisplayHasTask &&
                    request.originalDisplayId == 0 && request.originalDisplayId != request.targetDisplayId,
                forceColdStart = request.forceColdStart,
                displaySizeMatches = request.displaySizeMatches,
                encoderReady = request.encoderReady,
                encoderDisplayId = request.encoderDisplayId,
            )
        )
        val borrowedTaskId = if (launchPlan.taskAction == TaskLaunchAction.MOVE_TASK_TO_DISPLAY_AND_FRONT) {
            request.matchingTaskIds.firstOrNull()?.takeIf { taskId ->
                request.moveTaskToDisplay(taskId, request.targetDisplayId)
            }
        } else null
        val tasksToFront = when {
            launchPlan.taskAction == TaskLaunchAction.MOVE_TASK_TO_FRONT -> request.matchingTaskIds
            borrowedTaskId != null -> listOf(borrowedTaskId)
            else -> emptyList()
        }
        val moveResults = TaskFrontMover(request.moveTaskNative, request.moveTaskShell).move(tasksToFront)
        return TaskRoutingResult(
            targetDisplayHasTask = targetDisplayHasTask,
            isWarmStart = isWarmStart,
            launchPlan = launchPlan,
            moveResults = moveResults,
            borrowedTaskId = borrowedTaskId,
        )
    }
}

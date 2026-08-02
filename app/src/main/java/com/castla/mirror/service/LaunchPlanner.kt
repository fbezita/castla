package com.castla.mirror.service

/** The task operation selected by the launch planner. */
enum class TaskLaunchAction {
    WAIT_FOR_DISPLAY,
    CREATE_NEW_TASK,
    MOVE_TASK_TO_FRONT,
    MOVE_TASK_TO_DISPLAY_AND_FRONT,
}

data class LaunchState(
    val targetDisplayId: Int,
    val displayReady: Boolean,
    val targetTaskIds: List<Int>,
    val otherDisplayTaskExists: Boolean,
    val forceColdStart: Boolean,
    val displaySizeMatches: Boolean,
    val encoderReady: Boolean,
    val encoderDisplayId: Int,
)

data class LaunchPlan(
    val taskAction: TaskLaunchAction,
    val resizeRequired: Boolean,
    val encoderReconnectRequired: Boolean,
    val reason: String,
)

/** Pure decision logic for routing an app task and preparing its display session. */
object LaunchPlanner {
    fun plan(state: LaunchState): LaunchPlan {
        if (!state.displayReady || state.targetDisplayId < 0) {
            return LaunchPlan(
                taskAction = TaskLaunchAction.WAIT_FOR_DISPLAY,
                resizeRequired = false,
                encoderReconnectRequired = false,
                reason = "display_not_ready",
            )
        }

        val taskAction = when {
            state.forceColdStart -> TaskLaunchAction.CREATE_NEW_TASK
            state.targetTaskIds.isNotEmpty() -> TaskLaunchAction.MOVE_TASK_TO_FRONT
            state.otherDisplayTaskExists -> TaskLaunchAction.CREATE_NEW_TASK
            else -> TaskLaunchAction.CREATE_NEW_TASK
        }

        return LaunchPlan(
            taskAction = taskAction,
            resizeRequired = !state.displaySizeMatches,
            encoderReconnectRequired = !state.encoderReady || state.encoderDisplayId != state.targetDisplayId,
            reason = when (taskAction) {
                TaskLaunchAction.MOVE_TASK_TO_FRONT -> "target_display_task_reuse"
                TaskLaunchAction.CREATE_NEW_TASK -> if (state.otherDisplayTaskExists) "task_exists_on_other_display" else "target_display_task_missing"
                TaskLaunchAction.MOVE_TASK_TO_DISPLAY_AND_FRONT -> "task_move_required"
                TaskLaunchAction.WAIT_FOR_DISPLAY -> "display_not_ready"
            },
        )
    }
}

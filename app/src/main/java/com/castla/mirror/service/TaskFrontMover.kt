package com.castla.mirror.service

/** Result of attempting to bring one existing task to the front. */
data class TaskFrontMoveResult(
    val taskId: Int,
    val result: String,
    val error: Throwable? = null,
)

/**
 * Executes warm-task promotion without deciding whether a task should be reused.
 * The caller owns the target-display policy; this class only handles native/shell compatibility.
 */
class TaskFrontMover(
    private val moveNative: suspend (Int) -> Boolean,
    private val moveShell: suspend (Int) -> String,
) {
    suspend fun move(taskIds: List<Int>): List<TaskFrontMoveResult> = taskIds.map { taskId ->
        try {
            val result = if (moveNative(taskId)) {
                "native"
            } else {
                "shell=${moveShell(taskId)}"
            }
            TaskFrontMoveResult(taskId = taskId, result = result)
        } catch (error: Throwable) {
            TaskFrontMoveResult(taskId = taskId, result = "failed", error = error)
        }
    }
}
package com.castla.mirror.service

/** Builds the cross-display root-task command exposed by modern ActivityManager shells. */
internal object TaskDisplayShellCommand {
    fun candidates(taskId: Int, displayId: Int): List<String> = listOf(
        "cmd activity display move-stack $taskId $displayId",
        "cmd activity task move-to-display $taskId $displayId",
    )

    fun succeeded(output: String): Boolean =
        !output.contains("error", ignoreCase = true) &&
            !output.contains("exception", ignoreCase = true) &&
            !output.contains("failed", ignoreCase = true) &&
            !output.contains("unknown command", ignoreCase = true)

    suspend fun move(
        taskId: Int,
        displayId: Int,
        execute: suspend (String) -> String?,
    ): Boolean {
        for (command in candidates(taskId, displayId)) {
            val output = try {
                execute(command)
            } catch (_: Exception) {
                null
            }
            if (output != null && succeeded(output)) return true
        }
        return false
    }
}

package com.castla.mirror.service

/** Removes task instances owned by one virtual display without force-stopping their packages. */
internal object VirtualDisplayTaskCleaner {
    suspend fun cleanup(
        displayId: Int,
        getTaskIdsOnDisplay: suspend (Int) -> IntArray,
        removeTask: suspend (Int) -> Unit,
        launchHome: suspend (Int) -> Unit,
    ): List<Int> {
        if (displayId < 0) return emptyList()

        val taskIds = try {
            getTaskIdsOnDisplay(displayId)
                .filter { it >= 0 }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }

        taskIds.forEach { taskId ->
            try {
                removeTask(taskId)
            } catch (_: Exception) {
                // A task can disappear while shutdown is in progress; continue with the rest.
            }
        }

        try {
            launchHome(displayId)
        } catch (_: Exception) {
            // The display may already be gone. Cleanup remains best-effort.
        }
        return taskIds
    }
}

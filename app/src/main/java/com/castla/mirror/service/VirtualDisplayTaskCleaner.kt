package com.castla.mirror.service

data class VirtualDisplayCleanupResult(
    val restoredTaskIds: List<Int> = emptyList(),
    val removedTaskIds: List<Int> = emptyList(),
    val restoreFailedTaskIds: List<Int> = emptyList(),
)

/** Restores borrowed tasks and removes task instances owned by one virtual display. */
internal object VirtualDisplayTaskCleaner {
    suspend fun cleanup(
        displayId: Int,
        borrowedTasks: Map<Int, Int>,
        getTaskIdsOnDisplay: suspend (Int) -> IntArray,
        restoreTask: suspend (Int, Int) -> Boolean,
        removeTask: suspend (Int) -> Unit,
        launchHome: suspend (Int) -> Unit,
    ): VirtualDisplayCleanupResult {
        if (displayId < 0) return VirtualDisplayCleanupResult()

        val taskIds = try {
            getTaskIdsOnDisplay(displayId)
                .filter { it >= 0 }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }

        val restoredTaskIds = mutableListOf<Int>()
        val removedTaskIds = mutableListOf<Int>()
        val restoreFailedTaskIds = mutableListOf<Int>()
        taskIds.forEach { taskId ->
            val originDisplayId = borrowedTasks[taskId]
            if (originDisplayId != null) {
                val restored = try {
                    restoreTask(taskId, originDisplayId)
                } catch (_: Exception) {
                    false
                }
                if (restored) restoredTaskIds += taskId else restoreFailedTaskIds += taskId
                return@forEach
            }
            try {
                removeTask(taskId)
                removedTaskIds += taskId
            } catch (_: Exception) {
                // A task can disappear while shutdown is in progress; continue with the rest.
            }
        }

        try {
            launchHome(displayId)
        } catch (_: Exception) {
            // The display may already be gone. Cleanup remains best-effort.
        }
        return VirtualDisplayCleanupResult(
            restoredTaskIds = restoredTaskIds,
            removedTaskIds = removedTaskIds,
            restoreFailedTaskIds = restoreFailedTaskIds,
        )
    }
}

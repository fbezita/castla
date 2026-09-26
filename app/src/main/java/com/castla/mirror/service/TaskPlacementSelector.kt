package com.castla.mirror.service

data class TaskPlacementSelection(
    val taskIds: List<Int>,
    val originalDisplayId: Int,
)

/** Selects one verified task instance for deterministic cross-display routing. */
object TaskPlacementSelector {
    fun select(
        packageTaskIds: List<Int>,
        targetDisplayTaskIds: List<Int>,
        phoneDisplayTaskIds: List<Int>,
        targetDisplayId: Int,
    ): TaskPlacementSelection {
        val packageIds = packageTaskIds.toHashSet()
        val targetTaskId = targetDisplayTaskIds.firstOrNull { it in packageIds }
        if (targetTaskId != null) {
            return TaskPlacementSelection(
                taskIds = listOf(targetTaskId),
                originalDisplayId = targetDisplayId,
            )
        }

        val phoneTaskId = phoneDisplayTaskIds.firstOrNull { it in packageIds }
        if (phoneTaskId != null) {
            return TaskPlacementSelection(
                taskIds = listOf(phoneTaskId),
                originalDisplayId = 0,
            )
        }

        return TaskPlacementSelection(
            taskIds = emptyList(),
            originalDisplayId = -1,
        )
    }
}

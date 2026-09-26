package com.castla.mirror.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskPlacementSelectorTest {
    @Test
    fun selectsOnlyOneExistingTaskOnTheTargetDisplay() {
        val result = TaskPlacementSelector.select(
            packageTaskIds = listOf(11, 12, 13),
            targetDisplayTaskIds = listOf(12, 13),
            phoneDisplayTaskIds = listOf(11),
            targetDisplayId = 8,
        )

        assertEquals(listOf(12), result.taskIds)
        assertEquals(8, result.originalDisplayId)
    }

    @Test
    fun selectsOnlyOnePhoneTaskWhenTheTargetDisplayHasNone() {
        val result = TaskPlacementSelector.select(
            packageTaskIds = listOf(21, 22),
            targetDisplayTaskIds = emptyList(),
            phoneDisplayTaskIds = listOf(22, 21),
            targetDisplayId = 8,
        )

        assertEquals(listOf(22), result.taskIds)
        assertEquals(0, result.originalDisplayId)
    }

    @Test
    fun ignoresTasksWhoseDisplayCannotBeVerified() {
        val result = TaskPlacementSelector.select(
            packageTaskIds = listOf(31, 32),
            targetDisplayTaskIds = emptyList(),
            phoneDisplayTaskIds = emptyList(),
            targetDisplayId = 8,
        )

        assertEquals(emptyList<Int>(), result.taskIds)
        assertEquals(-1, result.originalDisplayId)
    }
}

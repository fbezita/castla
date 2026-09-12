package com.castla.mirror.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskOwnershipTrackerTest {
    @Test
    fun `records only successfully borrowed tasks with a different valid origin display`() {
        val tracker = TaskOwnershipTracker()

        tracker.recordBorrowed(taskId = 10, originDisplayId = 0, targetDisplayId = 7)
        tracker.recordBorrowed(taskId = -1, originDisplayId = 0, targetDisplayId = 7)
        tracker.recordBorrowed(taskId = 11, originDisplayId = -1, targetDisplayId = 7)
        tracker.recordBorrowed(taskId = 12, originDisplayId = 7, targetDisplayId = 7)

        assertEquals(mapOf(10 to 0), tracker.borrowedTasks())
    }

    @Test
    fun `clearing a session forgets all borrowed tasks`() {
        val tracker = TaskOwnershipTracker()
        tracker.recordBorrowed(taskId = 10, originDisplayId = 0, targetDisplayId = 7)

        tracker.clear()

        assertEquals(emptyMap<Int, Int>(), tracker.borrowedTasks())
    }
}

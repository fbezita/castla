package com.castla.mirror.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualDisplayTaskCleanerTest {
    @Test
    fun `cleanup removes only distinct tasks reported for the target display before opening home`() = runBlocking {
        val operations = mutableListOf<String>()

        val result = VirtualDisplayTaskCleaner.cleanup(
            displayId = 42,
            borrowedTasks = emptyMap(),
            getTaskIdsOnDisplay = { displayId ->
                operations += "query:$displayId"
                intArrayOf(11, 12, 11, -1)
            },
            restoreTask = { _, _ -> false },
            removeTask = { taskId -> operations += "remove:$taskId" },
            launchHome = { displayId -> operations += "home:$displayId" },
        )

        assertEquals(listOf(11, 12), result.removedTaskIds)
        assertEquals(
            listOf("query:42", "remove:11", "remove:12", "home:42"),
            operations,
        )
    }

    @Test
    fun `cleanup skips binder operations for an invalid display`() = runBlocking {
        val operations = mutableListOf<String>()

        val result = VirtualDisplayTaskCleaner.cleanup(
            displayId = -1,
            borrowedTasks = emptyMap(),
            getTaskIdsOnDisplay = { operations += "query"; intArrayOf(11) },
            restoreTask = { _, _ -> operations += "restore"; true },
            removeTask = { operations += "remove" },
            launchHome = { operations += "home" },
        )

        assertEquals(emptyList<Int>(), result.removedTaskIds)
        assertEquals(emptyList<String>(), operations)
    }

    @Test
    fun `cleanup continues removing remaining VD tasks when one removal fails`() = runBlocking {
        val operations = mutableListOf<String>()

        val result = VirtualDisplayTaskCleaner.cleanup(
            displayId = 7,
            borrowedTasks = emptyMap(),
            getTaskIdsOnDisplay = { intArrayOf(21, 22) },
            restoreTask = { _, _ -> false },
            removeTask = { taskId ->
                operations += "remove:$taskId"
                if (taskId == 21) error("already gone")
            },
            launchHome = { operations += "home" },
        )

        assertEquals(listOf(22), result.removedTaskIds)
        assertEquals(listOf("remove:21", "remove:22", "home"), operations)
    }

    @Test
    fun `cleanup restores borrowed phone task and removes only VD owned tasks`() = runBlocking {
        val operations = mutableListOf<String>()

        val result = VirtualDisplayTaskCleaner.cleanup(
            displayId = 9,
            borrowedTasks = mapOf(31 to 0),
            getTaskIdsOnDisplay = { intArrayOf(31, 32) },
            restoreTask = { taskId, originDisplayId ->
                operations += "restore:$taskId:$originDisplayId"
                true
            },
            removeTask = { taskId -> operations += "remove:$taskId" },
            launchHome = { displayId -> operations += "home:$displayId" },
        )

        assertEquals(listOf(31), result.restoredTaskIds)
        assertEquals(listOf(32), result.removedTaskIds)
        assertEquals(emptyList<Int>(), result.restoreFailedTaskIds)
        assertEquals(listOf("restore:31:0", "remove:32", "home:9"), operations)
    }

    @Test
    fun `cleanup never removes a borrowed task when restoring it fails`() = runBlocking {
        val operations = mutableListOf<String>()

        val result = VirtualDisplayTaskCleaner.cleanup(
            displayId = 9,
            borrowedTasks = mapOf(41 to 0),
            getTaskIdsOnDisplay = { intArrayOf(41, 42) },
            restoreTask = { taskId, originDisplayId ->
                operations += "restore:$taskId:$originDisplayId"
                false
            },
            removeTask = { taskId -> operations += "remove:$taskId" },
            launchHome = { displayId -> operations += "home:$displayId" },
        )

        assertEquals(emptyList<Int>(), result.restoredTaskIds)
        assertEquals(listOf(42), result.removedTaskIds)
        assertEquals(listOf(41), result.restoreFailedTaskIds)
        assertEquals(listOf("restore:41:0", "remove:42", "home:9"), operations)
    }
}

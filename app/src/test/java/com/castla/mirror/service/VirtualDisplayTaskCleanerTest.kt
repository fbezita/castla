package com.castla.mirror.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualDisplayTaskCleanerTest {
    @Test
    fun `cleanup removes only distinct tasks reported for the target display before opening home`() = runBlocking {
        val operations = mutableListOf<String>()

        val removed = VirtualDisplayTaskCleaner.cleanup(
            displayId = 42,
            getTaskIdsOnDisplay = { displayId ->
                operations += "query:$displayId"
                intArrayOf(11, 12, 11, -1)
            },
            removeTask = { taskId -> operations += "remove:$taskId" },
            launchHome = { displayId -> operations += "home:$displayId" },
        )

        assertEquals(listOf(11, 12), removed)
        assertEquals(
            listOf("query:42", "remove:11", "remove:12", "home:42"),
            operations,
        )
    }

    @Test
    fun `cleanup skips binder operations for an invalid display`() = runBlocking {
        val operations = mutableListOf<String>()

        val removed = VirtualDisplayTaskCleaner.cleanup(
            displayId = -1,
            getTaskIdsOnDisplay = { operations += "query"; intArrayOf(11) },
            removeTask = { operations += "remove" },
            launchHome = { operations += "home" },
        )

        assertEquals(emptyList<Int>(), removed)
        assertEquals(emptyList<String>(), operations)
    }

    @Test
    fun `cleanup continues removing remaining VD tasks when one removal fails`() = runBlocking {
        val operations = mutableListOf<String>()

        val removed = VirtualDisplayTaskCleaner.cleanup(
            displayId = 7,
            getTaskIdsOnDisplay = { intArrayOf(21, 22) },
            removeTask = { taskId ->
                operations += "remove:$taskId"
                if (taskId == 21) error("already gone")
            },
            launchHome = { operations += "home" },
        )

        assertEquals(listOf(21, 22), removed)
        assertEquals(listOf("remove:21", "remove:22", "home"), operations)
    }
}

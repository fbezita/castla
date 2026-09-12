package com.castla.mirror.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskRoutingCoordinatorTest {
    @Test
    fun `moves only the first matching task from another display`() = runBlocking {
        val operations = mutableListOf<String>()

        val result = TaskRoutingCoordinator().route(
            request(
                moveTaskToDisplay = { taskId, displayId ->
                    operations += "display:$taskId:$displayId"
                    true
                },
                moveTaskNative = { taskId -> operations += "front:$taskId"; true },
            )
        )

        assertEquals(TaskLaunchAction.MOVE_TASK_TO_DISPLAY_AND_FRONT, result.launchPlan.taskAction)
        assertEquals(51, result.borrowedTaskId)
        assertEquals(listOf("display:51:8", "front:51"), operations)
    }

    @Test
    fun `failed cross display move is left for launch fallback`() = runBlocking {
        val operations = mutableListOf<String>()

        val result = TaskRoutingCoordinator().route(
            request(
                moveTaskToDisplay = { taskId, displayId ->
                    operations += "display:$taskId:$displayId"
                    false
                },
                moveTaskNative = { taskId -> operations += "front:$taskId"; true },
            )
        )

        assertNull(result.borrowedTaskId)
        assertEquals(emptyList<TaskFrontMoveResult>(), result.moveResults)
        assertEquals(listOf("display:51:8"), operations)
    }

    @Test
    fun `task on another virtual display is not borrowed`() = runBlocking {
        val operations = mutableListOf<String>()

        val result = TaskRoutingCoordinator().route(
            request(
                originalDisplayId = 7,
                moveTaskToDisplay = { taskId, displayId ->
                    operations += "display:$taskId:$displayId"
                    true
                },
                moveTaskNative = { true },
            )
        )

        assertEquals(TaskLaunchAction.CREATE_NEW_TASK, result.launchPlan.taskAction)
        assertNull(result.borrowedTaskId)
        assertEquals(emptyList<String>(), operations)
    }

    private fun request(
        originalDisplayId: Int = 0,
        moveTaskToDisplay: suspend (Int, Int) -> Boolean,
        moveTaskNative: suspend (Int) -> Boolean,
    ) = TaskRoutingRequest(
        targetDisplayId = 8,
        originalDisplayId = originalDisplayId,
        matchingTaskIds = listOf(51, 52),
        targetDisplayPackages = emptyList(),
        packageName = "example.app",
        forceColdStart = false,
        displaySizeMatches = true,
        encoderReady = true,
        encoderDisplayId = 8,
        moveTaskToDisplay = moveTaskToDisplay,
        moveTaskNative = moveTaskNative,
        moveTaskShell = { taskId -> "front:$taskId" },
    )
}

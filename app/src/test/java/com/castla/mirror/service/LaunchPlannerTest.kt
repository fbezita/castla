package com.castla.mirror.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPlannerTest {
    @Test
    fun `missing target task creates a new task`() {
        val plan = LaunchPlanner.plan(LaunchState(6, true, emptyList(), false, false, true, true, 6))
        assertEquals(TaskLaunchAction.CREATE_NEW_TASK, plan.taskAction)
    }

    @Test
    fun `target task is brought to front`() {
        val plan = LaunchPlanner.plan(LaunchState(6, true, listOf(42), false, false, true, true, 6))
        assertEquals(TaskLaunchAction.MOVE_TASK_TO_FRONT, plan.taskAction)
    }

    @Test
    fun `task on another display creates a separate task`() {
        val plan = LaunchPlanner.plan(LaunchState(6, true, emptyList(), true, false, true, true, 6))
        assertEquals(TaskLaunchAction.CREATE_NEW_TASK, plan.taskAction)
    }

    @Test
    fun `display and encoder changes are independent of task action`() {
        val plan = LaunchPlanner.plan(LaunchState(6, true, listOf(42), false, false, false, false, 0))
        assertEquals(TaskLaunchAction.MOVE_TASK_TO_FRONT, plan.taskAction)
        assertTrue(plan.resizeRequired)
        assertTrue(plan.encoderReconnectRequired)
    }
}

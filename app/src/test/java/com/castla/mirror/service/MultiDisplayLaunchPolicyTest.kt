package com.castla.mirror.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiDisplayLaunchPolicyTest {
    @Test
    fun newVirtualDisplayLaunchCreatesSeparateTask() {
        assertEquals(0x18000000, MultiDisplayLaunchPolicy.flags(reorderToFront = false))
        assertEquals("0x18000000", MultiDisplayLaunchPolicy.shellFlags(reorderToFront = false))
    }

    @Test
    fun warmTaskLaunchKeepsReorderToFrontBehavior() {
        assertEquals(0x10020000, MultiDisplayLaunchPolicy.flags(reorderToFront = true))
        assertEquals("0x10020000", MultiDisplayLaunchPolicy.shellFlags(reorderToFront = true))
    }
    @Test
    fun doesNotReusePhoneTaskWhenTargetIsVirtualDisplay() {
        assertEquals(
            false,
            MultiDisplayLaunchPolicy.shouldReuseWarmTask(
                hasMatchingTasks = true,
                existingTaskDisplayId = 0,
                targetDisplayId = 6,
                forceColdStart = false,
            )
        )
    }

    @Test
    fun reusesTaskAlreadyOnTargetDisplay() {
        assertEquals(
            true,
            MultiDisplayLaunchPolicy.shouldReuseWarmTask(
                hasMatchingTasks = true,
                existingTaskDisplayId = 6,
                targetDisplayId = 6,
                forceColdStart = false,
            )
        )
    }
}


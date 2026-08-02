package com.castla.mirror.shizuku

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GetTasksQueryPolicyTest {
    @Test
    fun `three integer getTasks overload receives target display`() {
        assertArrayEquals(intArrayOf(100, 12), GetTasksQueryPolicy.intArguments(2, 12))
    }

    @Test
    fun `global query uses invalid display to include all displays`() {
        assertArrayEquals(intArrayOf(100, -1), GetTasksQueryPolicy.intArguments(2, null))
    }
}


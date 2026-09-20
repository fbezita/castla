package com.castla.mirror.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorTouchRecencyTest {
    @Test
    fun `returns age of newest valid touch`() {
        assertEquals(250L, MirrorTouchRecency.mostRecentAge(listOf(0L, 500L, 750L), 1_000L))
    }

    @Test
    fun `ignores unset timestamps and clamps clock rollback`() {
        assertNull(MirrorTouchRecency.mostRecentAge(listOf(0L, -1L), 1_000L))
        assertEquals(0L, MirrorTouchRecency.mostRecentAge(listOf(1_100L), 1_000L))
    }
}

package com.castla.mirror.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NonBlockingPostLaunchCoordinatorTest {
    @Test
    fun `launch completes before deferred post processing starts`() = runBlocking {
        val events = mutableListOf<String>()
        var deferredWork: (() -> Unit)? = null
        val coordinator = NonBlockingPostLaunchCoordinator { work ->
            events += "scheduled"
            deferredWork = work
        }

        coordinator.execute(
            launch = { events += "launched" },
            postLaunch = { events += "post" },
        )

        assertEquals(listOf("launched", "scheduled"), events)
        assertFalse(events.contains("post"))

        deferredWork?.invoke()
        assertEquals(listOf("launched", "scheduled", "post"), events)
    }
}

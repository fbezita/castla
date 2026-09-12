package com.castla.mirror.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskDisplayShellCommandTest {
    @Test
    fun `provides current and legacy cross display commands in compatibility order`() {
        assertEquals(
            listOf(
                "cmd activity display move-stack 11642 16",
                "cmd activity task move-to-display 11642 16",
            ),
            TaskDisplayShellCommand.candidates(taskId = 11642, displayId = 16),
        )
    }

    @Test
    fun `recognizes unsupported and failed shell responses`() {
        assertEquals(false, TaskDisplayShellCommand.succeeded("Error: unknown command 'move-to-display'"))
        assertEquals(false, TaskDisplayShellCommand.succeeded("java.lang.SecurityException"))
        assertEquals(false, TaskDisplayShellCommand.succeeded("Failed to move task"))
        assertEquals(true, TaskDisplayShellCommand.succeeded(""))
    }

    @Test
    fun `falls back to the legacy command when the first command is unsupported`() = runBlocking {
        val executed = mutableListOf<String>()

        val moved = TaskDisplayShellCommand.move(11642, 16) { command ->
            executed += command
            if (executed.size == 1) "Error: unknown command" else ""
        }

        assertEquals(true, moved)
        assertEquals(TaskDisplayShellCommand.candidates(11642, 16), executed)
    }
}

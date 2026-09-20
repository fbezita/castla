package com.castla.mirror.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupUiStateTest {

    @Test
    fun `requires every Shizuku prerequisite before becoming ready`() {
        assertEquals(
            SetupUiState.NotInstalled,
            SetupUiState.resolve(SetupSnapshot(installed = false)),
        )
        assertEquals(
            SetupUiState.NotRunning,
            SetupUiState.resolve(SetupSnapshot(installed = true)),
        )
        assertEquals(
            SetupUiState.PermissionRequired,
            SetupUiState.resolve(
                SetupSnapshot(installed = true, running = true),
            ),
        )
        assertEquals(
            SetupUiState.Connecting,
            SetupUiState.resolve(
                SetupSnapshot(installed = true, running = true, permitted = true),
            ),
        )
        assertEquals(
            SetupUiState.Ready,
            SetupUiState.resolve(
                SetupSnapshot(
                    installed = true,
                    running = true,
                    permitted = true,
                    serviceConnected = true,
                ),
            ),
        )
    }

    @Test
    fun `reported failures take precedence once Shizuku is installed`() {
        assertEquals(
            SetupUiState.Failed("binder died"),
            SetupUiState.resolve(
                SetupSnapshot(installed = true, failure = "binder died"),
            ),
        )
    }
}

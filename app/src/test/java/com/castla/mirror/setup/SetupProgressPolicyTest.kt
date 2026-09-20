package com.castla.mirror.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupProgressPolicyTest {
    @Test
    fun `each setup state points to the next actionable step`() {
        assertEquals(0, SetupProgressPolicy.activeStep(SetupUiState.NotInstalled))
        assertEquals(1, SetupProgressPolicy.activeStep(SetupUiState.NotRunning))
        assertEquals(2, SetupProgressPolicy.activeStep(SetupUiState.PermissionRequired))
        assertEquals(3, SetupProgressPolicy.activeStep(SetupUiState.Connecting))
        assertEquals(4, SetupProgressPolicy.activeStep(SetupUiState.Ready))
    }

    @Test
    fun `failed setup keeps the connection step actionable`() {
        assertEquals(3, SetupProgressPolicy.activeStep(SetupUiState.Failed("timeout")))
    }
}

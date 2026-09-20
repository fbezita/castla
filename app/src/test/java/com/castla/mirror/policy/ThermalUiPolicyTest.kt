package com.castla.mirror.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalUiPolicyTest {
    @Test
    fun `light status is informational rather than a heat warning`() {
        assertEquals(ThermalUiSeverity.INFO, ThermalUiPolicy.severity(status = 1))
        assertFalse(ThermalUiPolicy.isUrgent(status = 1))
    }

    @Test
    fun `moderate and above are emphasized warnings`() {
        assertEquals(ThermalUiSeverity.WARNING, ThermalUiPolicy.severity(status = 2))
        assertEquals(ThermalUiSeverity.CRITICAL, ThermalUiPolicy.severity(status = 3))
        assertTrue(ThermalUiPolicy.isUrgent(status = 2))
        assertTrue(ThermalUiPolicy.isUrgent(status = 5))
    }

    @Test
    fun `normal and invalid statuses are hidden`() {
        assertEquals(ThermalUiSeverity.NONE, ThermalUiPolicy.severity(status = 0))
        assertEquals(ThermalUiSeverity.NONE, ThermalUiPolicy.severity(status = -1))
    }
}

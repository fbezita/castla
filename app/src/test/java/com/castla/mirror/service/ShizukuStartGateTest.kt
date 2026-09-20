package com.castla.mirror.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuStartGateTest {
    @Test
    fun `starts only when the full privileged runtime is ready`() {
        assertFalse(ShizukuStartGate.canStart(available = false, permitted = false, connected = false, binding = false))
        assertFalse(ShizukuStartGate.canStart(available = true, permitted = false, connected = false, binding = false))
        assertFalse(ShizukuStartGate.canStart(available = true, permitted = true, connected = false, binding = false))
        assertTrue(ShizukuStartGate.canStart(available = true, permitted = true, connected = false, binding = true))
        assertTrue(ShizukuStartGate.canStart(available = true, permitted = true, connected = true, binding = false))
    }
}

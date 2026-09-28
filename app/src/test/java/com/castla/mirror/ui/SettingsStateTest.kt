package com.castla.mirror.ui

import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsStateTest {

    @Test
    fun `automatic hotspot is disabled by default`() {
        assertFalse(StreamSettings.DEFAULT_AUTO_HOTSPOT)
        assertFalse(StreamSettings().autoHotspot)
    }
}

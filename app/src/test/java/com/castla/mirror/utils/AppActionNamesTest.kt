package com.castla.mirror.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AppActionNamesTest {
    private val applicationId = "com.castla.mirror.client"

    @Test
    fun `builds package scoped service actions`() {
        assertEquals(
            "com.castla.mirror.client.ACTION_STOP",
            AppActionNames.stop(applicationId),
        )
        assertEquals(
            "com.castla.mirror.client.ACTION_RESTORE_IME",
            AppActionNames.restoreIme(applicationId),
        )
    }

    @Test
    fun `builds package scoped widget and companion actions`() {
        assertEquals(
            "com.castla.mirror.client.WIDGET_TOGGLE",
            AppActionNames.widgetToggle(applicationId),
        )
        assertEquals(
            "com.castla.mirror.client.ACTION_START_MIRRORING_FROM_CDM",
            AppActionNames.startMirroringFromCompanion(applicationId),
        )
    }

    @Test
    fun `builds package scoped blackout actions`() {
        assertEquals(
            "com.castla.mirror.client.action.SCREEN_OFF_BLACKOUT_START",
            AppActionNames.screenOffBlackoutStart(applicationId),
        )
        assertEquals(
            "com.castla.mirror.client.action.SCREEN_OFF_BLACKOUT_STOP",
            AppActionNames.screenOffBlackoutStop(applicationId),
        )
    }
}

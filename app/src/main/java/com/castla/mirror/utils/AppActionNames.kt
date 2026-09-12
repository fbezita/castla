package com.castla.mirror.utils

object AppActionNames {
    fun stop(applicationId: String): String = "$applicationId.ACTION_STOP"

    fun restoreIme(applicationId: String): String = "$applicationId.ACTION_RESTORE_IME"

    fun widgetToggle(applicationId: String): String = "$applicationId.WIDGET_TOGGLE"

    fun startMirroringFromCompanion(applicationId: String): String =
        "$applicationId.ACTION_START_MIRRORING_FROM_CDM"

    fun screenOffBlackoutStart(applicationId: String): String =
        "$applicationId.action.SCREEN_OFF_BLACKOUT_START"

    fun screenOffBlackoutStop(applicationId: String): String =
        "$applicationId.action.SCREEN_OFF_BLACKOUT_STOP"
}

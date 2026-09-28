package com.castla.mirror.setup

object BootRecoveryNotificationPolicy {
    fun shouldNotify(setupCompleted: Boolean, shizukuRunning: Boolean): Boolean =
        setupCompleted && !shizukuRunning
}

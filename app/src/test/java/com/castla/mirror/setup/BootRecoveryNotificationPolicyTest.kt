package com.castla.mirror.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRecoveryNotificationPolicyTest {
    @Test
    fun notifiesCompletedUsersWhenShizukuIsNotRunningAfterBoot() {
        assertTrue(BootRecoveryNotificationPolicy.shouldNotify(setupCompleted = true, shizukuRunning = false))
    }

    @Test
    fun skipsFirstTimeUsersAndAlreadyRunningShizuku() {
        assertFalse(BootRecoveryNotificationPolicy.shouldNotify(setupCompleted = false, shizukuRunning = false))
        assertFalse(BootRecoveryNotificationPolicy.shouldNotify(setupCompleted = true, shizukuRunning = true))
    }
}

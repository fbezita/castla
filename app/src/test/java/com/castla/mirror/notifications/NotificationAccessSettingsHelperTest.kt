package com.castla.mirror.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAccessSettingsHelperTest {
    @Test
    fun matchesFullComponentNameFromSecureSetting() {
        val enabled = "com.example/.OtherListener:com.castla.mirror/com.castla.mirror.notifications.CastlaNotificationListenerService"

        assertTrue(
            NotificationAccessSettingsHelper.isNotificationAccessEnabled(
                enabledListenersValue = enabled,
                packageName = "com.castla.mirror",
                className = "com.castla.mirror.notifications.CastlaNotificationListenerService",
            )
        )
    }

    @Test
    fun matchesShortComponentNameFromSecureSetting() {
        val enabled = "com.castla.mirror/.notifications.CastlaNotificationListenerService"

        assertTrue(
            NotificationAccessSettingsHelper.isNotificationAccessEnabled(
                enabledListenersValue = enabled,
                packageName = "com.castla.mirror",
                className = "com.castla.mirror.notifications.CastlaNotificationListenerService",
            )
        )
    }

    @Test
    fun ignoresOtherListenersAndBlankState() {
        assertFalse(
            NotificationAccessSettingsHelper.isNotificationAccessEnabled(
                enabledListenersValue = null,
                packageName = "com.castla.mirror",
                className = "com.castla.mirror.notifications.CastlaNotificationListenerService",
            )
        )
        assertFalse(
            NotificationAccessSettingsHelper.isNotificationAccessEnabled(
                enabledListenersValue = "com.other/.Listener",
                packageName = "com.castla.mirror",
                className = "com.castla.mirror.notifications.CastlaNotificationListenerService",
            )
        )
    }

    @Test
    fun autoOpenPolicyOnlyRunsOnceUntilGranted() {
        assertTrue(
            NotificationAccessSettingsHelper.shouldAutoOpenSettings(
                hasAccess = false,
                hasPromptedBefore = false,
            )
        )
        assertFalse(
            NotificationAccessSettingsHelper.shouldAutoOpenSettings(
                hasAccess = true,
                hasPromptedBefore = false,
            )
        )
        assertFalse(
            NotificationAccessSettingsHelper.shouldAutoOpenSettings(
                hasAccess = false,
                hasPromptedBefore = true,
            )
        )
    }
}

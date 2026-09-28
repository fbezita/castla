package com.castla.mirror.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionReadinessPolicyTest {
    @Test
    fun requiredReadinessDependsOnShizukuAndBatteryExemption() {
        val result = ConnectionReadinessPolicy.evaluate(
            shizukuConnected = true,
            batteryUnrestricted = false,
            notificationAccess = false,
        )

        assertFalse(result.requiredReady)
        assertEquals(1, result.requiredComplete)
        assertEquals(2, result.requiredTotal)
        assertEquals(ReadinessState.ACTION_REQUIRED, result.items[1].state)
    }

    @Test
    fun optionalFeaturesDoNotBlockMirroringReadiness() {
        val result = ConnectionReadinessPolicy.evaluate(
            shizukuConnected = true,
            batteryUnrestricted = true,
            notificationAccess = false,
        )

        assertTrue(result.requiredReady)
        assertEquals(0, result.optionalComplete)
        assertEquals(1, result.optionalTotal)
        assertEquals(ReadinessState.OPTIONAL, result.items[2].state)
    }

    @Test
    fun reportsAllConfiguredItemsAsComplete() {
        val result = ConnectionReadinessPolicy.evaluate(
            shizukuConnected = true,
            batteryUnrestricted = true,
            notificationAccess = true,
        )

        assertTrue(result.requiredReady)
        assertEquals(2, result.requiredComplete)
        assertEquals(1, result.optionalComplete)
        assertTrue(result.items.all { it.state == ReadinessState.COMPLETE })
    }

    @Test
    fun localNetworkPermissionIsRequiredWhenThePlatformUsesIt() {
        val result = ConnectionReadinessPolicy.evaluate(
            shizukuConnected = true,
            batteryUnrestricted = true,
            notificationAccess = false,
            localNetworkRequired = true,
            localNetworkGranted = false,
        )

        assertFalse(result.requiredReady)
        assertEquals(3, result.requiredTotal)
        assertEquals(ReadinessItemKind.LOCAL_NETWORK, result.items[2].kind)
        assertEquals(ReadinessState.ACTION_REQUIRED, result.items[2].state)
    }

    @Test
    fun appNotificationPermissionIsShownSeparatelyFromNotificationReading() {
        val result = ConnectionReadinessPolicy.evaluate(
            shizukuConnected = true,
            batteryUnrestricted = true,
            notificationAccess = false,
            appNotificationsRequired = true,
            appNotificationsGranted = false,
        )

        assertEquals(2, result.optionalTotal)
        assertEquals(ReadinessItemKind.APP_NOTIFICATIONS, result.items[2].kind)
        assertEquals(ReadinessItemKind.NOTIFICATION_ACCESS, result.items[3].kind)
        assertTrue(result.items.drop(2).all { it.state == ReadinessState.OPTIONAL })
    }
}

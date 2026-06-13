package com.castla.mirror.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLaunchPolicyTest {
    @Test
    fun browserPackagesBypassWarmTaskMove() {
        assertTrue(BrowserLaunchPolicy.shouldBypassWarmTaskMove("com.android.chrome"))
        assertTrue(BrowserLaunchPolicy.shouldBypassWarmTaskMove("com.sec.android.app.sbrowser"))
    }

    @Test
    fun browserPackagesBypassNativeLaunchShortcut() {
        assertTrue(BrowserLaunchPolicy.shouldBypassNativeLaunchShortcut("com.android.chrome"))
        assertTrue(BrowserLaunchPolicy.shouldBypassNativeLaunchShortcut("org.mozilla.firefox"))
    }

    @Test
    fun regularAppsStayOnDefaultLaunchPath() {
        assertFalse(BrowserLaunchPolicy.shouldBypassWarmTaskMove("com.google.android.apps.maps"))
        assertFalse(BrowserLaunchPolicy.shouldBypassNativeLaunchShortcut("com.google.android.apps.maps"))
    }
}

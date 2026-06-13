package com.castla.mirror.service

import com.castla.mirror.utils.LaunchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchRoutingTest {
    @Test
    fun routesKnownOttPackagesToTheirWebUrlEvenWhenComponentIsProvided() {
        val decision = LaunchRouting.resolve(
            packageName = "com.disney.disneyplus",
            className = "com.disney.disneyplus/com.bamtechmedia.dominguez.main.MainActivity",
            launchMode = LaunchMode.STANDARD_APP,
        )

        assertEquals(LaunchRoutingKind.WEB_URL, decision.kind)
        assertEquals("https://www.disneyplus.com", decision.launchTarget)
        assertEquals("com.disney.disneyplus", decision.sourceAppPackage)
        assertTrue(decision.allowEmbeddedFallback)
        assertFalse(decision.forceEmbeddedBrowser)
    }

    @Test
    fun forcesEmbeddedBrowserForNetflixOttLaunches() {
        val decision = LaunchRouting.resolve(
            packageName = "com.netflix.mediaclient",
            className = "com.netflix.mediaclient/.ui.launch.UIWebViewActivity",
            launchMode = LaunchMode.STANDARD_APP,
        )

        assertEquals(LaunchRoutingKind.WEB_URL, decision.kind)
        assertEquals("https://www.netflix.com", decision.launchTarget)
        assertEquals("com.netflix.mediaclient", decision.sourceAppPackage)
        assertTrue(decision.forceEmbeddedBrowser)
    }

    @Test
    fun preservesDirectUrlLaunchesAsWebTargets() {
        val decision = LaunchRouting.resolve(
            packageName = "https://www.disneyplus.com",
            className = null,
            launchMode = LaunchMode.EXTERNAL_BROWSER_URL,
        )

        assertEquals(LaunchRoutingKind.WEB_URL, decision.kind)
        assertEquals("https://www.disneyplus.com", decision.launchTarget)
    }

    @Test
    fun keepsRegularInstalledAppsOnTheStandardLaunchPath() {
        val decision = LaunchRouting.resolve(
            packageName = "com.google.android.apps.maps",
            className = "com.google.android.apps.maps/com.google.android.maps.MapsActivity",
            launchMode = LaunchMode.STANDARD_APP,
        )

        assertEquals(LaunchRoutingKind.STANDARD_APP, decision.kind)
        assertEquals(
            "com.google.android.apps.maps/com.google.android.maps.MapsActivity",
            decision.launchTarget,
        )
        assertEquals("com.google.android.apps.maps", decision.sourceAppPackage)
    }
}

package com.castla.mirror.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUserAgentPolicyTest {
    @Test
    fun usesTabletUserAgentForNetflixEvenWhenFollowingDisplayShape() {
        assertEquals(
            BrowserUserAgentPolicy.IPAD_SAFARI_UA,
            BrowserUserAgentPolicy.resolve(
                url = "https://www.netflix.com/browse",
                followDisplayShape = true,
            )
        )
    }

    @Test
    fun keepsMobileUserAgentForNonOttSitesInFollowDisplayShapeMode() {
        assertEquals(
            BrowserUserAgentPolicy.ANDROID_CHROME_UA,
            BrowserUserAgentPolicy.resolve(
                url = "https://example.com",
                followDisplayShape = true,
            )
        )
    }

    @Test
    fun keepsTabletUserAgentForFullscreenFallbackMode() {
        assertEquals(
            BrowserUserAgentPolicy.IPAD_SAFARI_UA,
            BrowserUserAgentPolicy.resolve(
                url = "https://example.com",
                followDisplayShape = false,
            )
        )
    }
}

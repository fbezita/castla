package com.castla.mirror.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuInstallLinksTest {

    @Test
    fun `download action opens official release page instead of an apk`() {
        val url = ShizukuInstallLinks.OFFICIAL_RELEASE_PAGE

        assertTrue(url.startsWith("https://github.com/RikkaApps/Shizuku/"))
        assertTrue(url.endsWith("/releases/latest"))
        assertFalse(url.endsWith(".apk"))
    }
}

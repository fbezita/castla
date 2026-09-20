package com.castla.mirror.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuInstallLinksTest {

    @Test
    fun `install action prefers the Play Store and keeps an official web fallback`() {
        val marketUrl = ShizukuInstallLinks.PLAY_STORE_APP
        val fallbackUrl = ShizukuInstallLinks.OFFICIAL_DOWNLOAD_PAGE

        assertTrue(marketUrl.startsWith("market://details?id="))
        assertTrue(fallbackUrl.startsWith("https://shizuku.rikka.app/"))
        assertFalse(fallbackUrl.endsWith(".apk"))
    }
}

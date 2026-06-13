package com.castla.mirror.ui

import android.net.Uri

object BrowserUserAgentPolicy {
    const val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 15; SM-F741N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    const val IPAD_SAFARI_UA =
        "Mozilla/5.0 (iPad; CPU OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"

    private val tabletPreferredHosts = setOf(
        "netflix.com",
        "youtube.com",
        "youtu.be",
        "disneyplus.com",
        "wavve.com",
        "tving.com",
        "coupangplay.com",
        "watcha.com",
    )

    fun resolve(url: String, followDisplayShape: Boolean): String {
        if (!followDisplayShape) return IPAD_SAFARI_UA
        return if (shouldPreferTabletUserAgent(url)) {
            IPAD_SAFARI_UA
        } else {
            ANDROID_CHROME_UA
        }
    }

    private fun shouldPreferTabletUserAgent(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: return false
        return tabletPreferredHosts.any { host == it || host.endsWith(".$it") }
    }
}

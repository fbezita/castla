package com.castla.mirror.ui

import java.net.URI

object BrowserUserAgentPolicy {
    const val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 15; SM-F741N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    const val DESKTOP_CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
    const val IPAD_SAFARI_UA =
        "Mozilla/5.0 (iPad; CPU OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"

    private val desktopPreferredHosts = setOf(
        "netflix.com",
    )

    private val tabletPreferredHosts = setOf(
        "youtube.com",
        "youtu.be",
        "disneyplus.com",
        "wavve.com",
        "tving.com",
        "coupangplay.com",
        "watcha.com",
    )

    fun resolve(url: String, followDisplayShape: Boolean): String {
        if (shouldUseDesktopExperience(url)) return DESKTOP_CHROME_UA
        if (!followDisplayShape) return IPAD_SAFARI_UA
        return if (shouldPreferTabletUserAgent(url)) {
            IPAD_SAFARI_UA
        } else {
            ANDROID_CHROME_UA
        }
    }

    fun shouldUseDesktopExperience(url: String): Boolean {
        return hostMatches(url, desktopPreferredHosts)
    }

    private fun shouldPreferTabletUserAgent(url: String): Boolean {
        return hostMatches(url, tabletPreferredHosts)
    }

    private fun hostMatches(url: String, allowedHosts: Set<String>): Boolean {
        return try {
            val host = URI(url).host?.lowercase()?.removePrefix("www.") ?: return false
            allowedHosts.any { host == it || host.endsWith(".$it") }
        } catch (e: Exception) {
            false
        }
    }
}

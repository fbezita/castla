package com.castla.mirror.service

import com.castla.mirror.ott.OttCatalog
import com.castla.mirror.utils.LaunchMode

enum class LaunchRoutingKind {
    STANDARD_APP,
    WEB_URL,
}

data class LaunchRoutingDecision(
    val kind: LaunchRoutingKind,
    val launchTarget: String,
    val sourceAppPackage: String? = null,
    val allowEmbeddedFallback: Boolean = true,
)

object LaunchRouting {
    fun resolve(
        packageName: String,
        className: String?,
        launchMode: LaunchMode,
    ): LaunchRoutingDecision {
        val trimmedPackage = packageName.trim()
        if (trimmedPackage.startsWith("http://") || trimmedPackage.startsWith("https://")) {
            return LaunchRoutingDecision(
                kind = LaunchRoutingKind.WEB_URL,
                launchTarget = trimmedPackage,
            )
        }

        val ottTarget = OttCatalog.resolve(trimmedPackage)
        if (ottTarget != null || launchMode == LaunchMode.EXTERNAL_BROWSER_URL) {
            return LaunchRoutingDecision(
                kind = LaunchRoutingKind.WEB_URL,
                launchTarget = ottTarget?.webUrl ?: trimmedPackage,
                sourceAppPackage = trimmedPackage,
                allowEmbeddedFallback = ottTarget?.allowEmbeddedFallback ?: true,
            )
        }

        return LaunchRoutingDecision(
            kind = LaunchRoutingKind.STANDARD_APP,
            launchTarget = className?.takeIf { it.isNotBlank() } ?: trimmedPackage,
            sourceAppPackage = trimmedPackage,
        )
    }
}

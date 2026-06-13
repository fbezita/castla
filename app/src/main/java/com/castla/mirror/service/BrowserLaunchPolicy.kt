package com.castla.mirror.service

object BrowserLaunchPolicy {
    private val browserPackages = setOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
    )

    fun shouldBypassWarmTaskMove(packageName: String): Boolean {
        return browserPackages.contains(packageName.trim())
    }

    fun shouldBypassNativeLaunchShortcut(packageName: String): Boolean {
        return browserPackages.contains(packageName.trim())
    }
}

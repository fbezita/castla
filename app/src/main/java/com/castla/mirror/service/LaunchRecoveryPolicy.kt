package com.castla.mirror.service

object LaunchRecoveryPolicy {
    const val NORMAL_LAUNCH_FALLBACK_WATCHDOG_DELAY_MS = 4_000L
    const val INITIAL_BOOTSTRAP_NUDGE_DELAY_MS = 1_200L

    fun shouldDeferInitialBrowserConnectedRebuild(
        hasReceivedBrowserLayout: Boolean,
        displayId: Int,
    ): Boolean {
        if (displayId >= 0) return false
        return !hasReceivedBrowserLayout
    }

    fun shouldForceFreshPreparationForTasklessRelaunch(
        targetPkg: String,
        currentAppPkg: String,
        matchingTaskCount: Int,
        forceTaskRealign: Boolean,
        encoderActive: Boolean,
        requiresFreshLaunchPreparation: Boolean,
    ): Boolean {
        if (requiresFreshLaunchPreparation) return false
        if (!forceTaskRealign || !encoderActive) return false
        if (targetPkg.isBlank() || currentAppPkg.isBlank()) return false
        if (targetPkg != currentAppPkg) return false
        return matchingTaskCount == 0
    }

    fun fallbackWatchdogDelayMs(
        isScreenOff: Boolean,
    ): Long {
        return if (isScreenOff) {
            com.castla.mirror.policy.ScreenOffRecoveryPlanner.FALLBACK_WATCHDOG_SCREEN_OFF_DELAY_MS
        } else {
            NORMAL_LAUNCH_FALLBACK_WATCHDOG_DELAY_MS
        }
    }

    fun shouldTriggerInitialBootstrapNudge(
        elapsedMs: Long,
        firstFramePublished: Boolean,
        nudgeAttempts: Int,
    ): Boolean {
        if (nudgeAttempts > 0) return false
        if (firstFramePublished) return false
        return elapsedMs >= INITIAL_BOOTSTRAP_NUDGE_DELAY_MS
    }

    fun shouldAttemptBootstrapRealign(
        currentApp: String,
        displayId: Int,
        browserConnected: Boolean,
    ): Boolean {
        if (!browserConnected || displayId < 0) return false
        val trimmed = currentApp.trim()
        if (trimmed.isBlank()) return false
        if (trimmed == "HOME" || trimmed == "com.android.settings") return false
        return true
    }

    fun shouldLaunchTargetBeforeStreamBootstrap(
        hasLaunchTarget: Boolean,
        requiresFreshLaunchPreparation: Boolean,
        isNewVirtualDisplay: Boolean,
    ): Boolean {
        if (!hasLaunchTarget) return false
        if (!requiresFreshLaunchPreparation) return false
        return isNewVirtualDisplay
    }

    fun shouldDeferFallbackMaterialization(
        paneName: String,
        paneVisible: Boolean,
        browserConnected: Boolean,
    ): Boolean {
        if (!browserConnected) return false
        if (paneName != "secondary") return false
        return !paneVisible
    }
}

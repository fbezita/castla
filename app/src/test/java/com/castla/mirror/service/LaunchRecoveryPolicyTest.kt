package com.castla.mirror.service

import com.castla.mirror.policy.ScreenOffRecoveryPlanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchRecoveryPolicyTest {
    @Test
    fun forcesFreshPreparationWhenSameAppWasClosedButRelaunchLooksActive() {
        assertTrue(
            LaunchRecoveryPolicy.shouldForceFreshPreparationForTasklessRelaunch(
                targetPkg = "ai.rhinos.mapcon",
                currentAppPkg = "ai.rhinos.mapcon",
                matchingTaskCount = 0,
                forceTaskRealign = true,
                encoderActive = true,
                requiresFreshLaunchPreparation = false,
            )
        )
    }

    @Test
    fun doesNotForceFreshPreparationWhenTaskStillExists() {
        assertFalse(
            LaunchRecoveryPolicy.shouldForceFreshPreparationForTasklessRelaunch(
                targetPkg = "ai.rhinos.mapcon",
                currentAppPkg = "ai.rhinos.mapcon",
                matchingTaskCount = 1,
                forceTaskRealign = true,
                encoderActive = true,
                requiresFreshLaunchPreparation = false,
            )
        )
    }

    @Test
    fun doesNotForceFreshPreparationForDifferentApp() {
        assertFalse(
            LaunchRecoveryPolicy.shouldForceFreshPreparationForTasklessRelaunch(
                targetPkg = "ai.rhinos.mapcon",
                currentAppPkg = "com.google.android.apps.maps",
                matchingTaskCount = 0,
                forceTaskRealign = true,
                encoderActive = true,
                requiresFreshLaunchPreparation = false,
            )
        )
    }

    @Test
    fun usesEarlierFallbackWatchdogForNormalLaunchesOnly() {
        assertEquals(
            LaunchRecoveryPolicy.NORMAL_LAUNCH_FALLBACK_WATCHDOG_DELAY_MS,
            LaunchRecoveryPolicy.fallbackWatchdogDelayMs(isScreenOff = false)
        )
        assertEquals(
            ScreenOffRecoveryPlanner.FALLBACK_WATCHDOG_SCREEN_OFF_DELAY_MS,
            LaunchRecoveryPolicy.fallbackWatchdogDelayMs(isScreenOff = true)
        )
    }

    @Test
    fun defersInitialBrowserConnectedRebuildUntilFirstLayoutArrives() {
        assertTrue(
            LaunchRecoveryPolicy.shouldDeferInitialBrowserConnectedRebuild(
                hasReceivedBrowserLayout = false,
                displayId = -1,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldDeferInitialBrowserConnectedRebuild(
                hasReceivedBrowserLayout = true,
                displayId = -1,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldDeferInitialBrowserConnectedRebuild(
                hasReceivedBrowserLayout = false,
                displayId = 42,
            )
        )
    }

    @Test
    fun triggersInitialBootstrapNudgeOnlyOnceAfterDelayWithoutFirstFrame() {
        assertFalse(
            LaunchRecoveryPolicy.shouldTriggerInitialBootstrapNudge(
                elapsedMs = LaunchRecoveryPolicy.INITIAL_BOOTSTRAP_NUDGE_DELAY_MS - 1L,
                firstFramePublished = false,
                nudgeAttempts = 0,
            )
        )
        assertTrue(
            LaunchRecoveryPolicy.shouldTriggerInitialBootstrapNudge(
                elapsedMs = LaunchRecoveryPolicy.INITIAL_BOOTSTRAP_NUDGE_DELAY_MS,
                firstFramePublished = false,
                nudgeAttempts = 0,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldTriggerInitialBootstrapNudge(
                elapsedMs = LaunchRecoveryPolicy.INITIAL_BOOTSTRAP_NUDGE_DELAY_MS,
                firstFramePublished = true,
                nudgeAttempts = 0,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldTriggerInitialBootstrapNudge(
                elapsedMs = LaunchRecoveryPolicy.INITIAL_BOOTSTRAP_NUDGE_DELAY_MS,
                firstFramePublished = false,
                nudgeAttempts = 1,
            )
        )
    }

    @Test
    fun allowsBootstrapRealignOnlyForRecoverableActiveApps() {
        assertTrue(
            LaunchRecoveryPolicy.shouldAttemptBootstrapRealign(
                currentApp = "com.google.android.apps.maps/com.google.android.maps.MapsActivity",
                displayId = 7,
                browserConnected = true,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldAttemptBootstrapRealign(
                currentApp = "",
                displayId = 7,
                browserConnected = true,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldAttemptBootstrapRealign(
                currentApp = "HOME",
                displayId = 7,
                browserConnected = true,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldAttemptBootstrapRealign(
                currentApp = "com.android.settings",
                displayId = 7,
                browserConnected = true,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldAttemptBootstrapRealign(
                currentApp = "com.google.android.apps.maps/com.google.android.maps.MapsActivity",
                displayId = -1,
                browserConnected = true,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldAttemptBootstrapRealign(
                currentApp = "com.google.android.apps.maps/com.google.android.maps.MapsActivity",
                displayId = 7,
                browserConnected = false,
            )
        )
    }

    @Test
    fun launchesTargetBeforeStreamBootstrapOnlyForFreshLaunchOnNewDisplay() {
        assertTrue(
            LaunchRecoveryPolicy.shouldLaunchTargetBeforeStreamBootstrap(
                hasLaunchTarget = true,
                requiresFreshLaunchPreparation = true,
                isNewVirtualDisplay = true,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldLaunchTargetBeforeStreamBootstrap(
                hasLaunchTarget = false,
                requiresFreshLaunchPreparation = true,
                isNewVirtualDisplay = true,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldLaunchTargetBeforeStreamBootstrap(
                hasLaunchTarget = true,
                requiresFreshLaunchPreparation = false,
                isNewVirtualDisplay = true,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldLaunchTargetBeforeStreamBootstrap(
                hasLaunchTarget = true,
                requiresFreshLaunchPreparation = true,
                isNewVirtualDisplay = false,
            )
        )
    }

    @Test
    fun defersHiddenSecondaryFallbackMaterializationWhileBrowserIsConnected() {
        assertTrue(
            LaunchRecoveryPolicy.shouldDeferFallbackMaterialization(
                paneName = "secondary",
                paneVisible = false,
                browserConnected = true,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldDeferFallbackMaterialization(
                paneName = "secondary",
                paneVisible = true,
                browserConnected = true,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldDeferFallbackMaterialization(
                paneName = "primary",
                paneVisible = false,
                browserConnected = true,
            )
        )
        assertFalse(
            LaunchRecoveryPolicy.shouldDeferFallbackMaterialization(
                paneName = "secondary",
                paneVisible = false,
                browserConnected = false,
            )
        )
    }

}

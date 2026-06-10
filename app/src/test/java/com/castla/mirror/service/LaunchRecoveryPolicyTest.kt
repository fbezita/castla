package com.castla.mirror.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}

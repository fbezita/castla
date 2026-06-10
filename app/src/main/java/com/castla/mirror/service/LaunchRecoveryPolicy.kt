package com.castla.mirror.service

object LaunchRecoveryPolicy {
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
}

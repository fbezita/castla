package com.castla.mirror.service

internal object AudioCapturePermissionGrantPolicy {
    private const val ROLE_MANAGED_CAPTURE_PERMISSION_SDK = 36

    fun shouldAttemptLegacyGrant(sdkInt: Int): Boolean =
        sdkInt < ROLE_MANAGED_CAPTURE_PERMISSION_SDK

    fun shouldAttemptPackageGrant(appOpsResult: String): Boolean {
        val normalized = appOpsResult.lowercase()
        return "unknown operation string" !in normalized &&
            "not a valid app op" !in normalized
    }

    fun isRoleManagedFailure(message: String?): Boolean =
        message?.contains("managed by role", ignoreCase = true) == true
}

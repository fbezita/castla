package com.castla.mirror.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCapturePermissionGrantPolicyTest {
    @Test
    fun `skips legacy permission commands on role-managed Android versions`() {
        assertTrue(AudioCapturePermissionGrantPolicy.shouldAttemptLegacyGrant(sdkInt = 35))
        assertFalse(AudioCapturePermissionGrantPolicy.shouldAttemptLegacyGrant(sdkInt = 36))
        assertFalse(AudioCapturePermissionGrantPolicy.shouldAttemptLegacyGrant(sdkInt = 37))
    }

    @Test
    fun `skips package grant when capture audio app-op is unsupported`() {
        assertFalse(
            AudioCapturePermissionGrantPolicy.shouldAttemptPackageGrant(
                "Error: Unknown operation string: CAPTURE_AUDIO_OUTPUT",
            ),
        )
        assertFalse(
            AudioCapturePermissionGrantPolicy.shouldAttemptPackageGrant(
                "Error: CAPTURE_AUDIO_OUTPUT is not a valid app op",
            ),
        )
    }

    @Test
    fun `attempts package grant when app-op command is accepted`() {
        assertTrue(AudioCapturePermissionGrantPolicy.shouldAttemptPackageGrant(""))
        assertTrue(AudioCapturePermissionGrantPolicy.shouldAttemptPackageGrant("Success"))
    }

    @Test
    fun `recognizes role-managed permission rejection`() {
        assertTrue(
            AudioCapturePermissionGrantPolicy.isRoleManagedFailure(
                "java.lang.SecurityException: Permission android.permission.CAPTURE_AUDIO_OUTPUT is managed by role",
            ),
        )
        assertFalse(AudioCapturePermissionGrantPolicy.isRoleManagedFailure("Permission Denial"))
        assertFalse(AudioCapturePermissionGrantPolicy.isRoleManagedFailure(null))
    }
}

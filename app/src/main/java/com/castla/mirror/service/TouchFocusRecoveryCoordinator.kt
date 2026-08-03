package com.castla.mirror.service

import android.os.SystemClock

/** Keeps touch-focus recovery admission rules independent from the injection plumbing. */
internal class TouchFocusRecoveryCoordinator {
    fun shouldRecover(
        activeDisplayId: Int,
        touchInteractionActive: Boolean,
        targetApp: String,
        topTask: String?,
        packageName: String,
        lastRecoveryAt: Long,
    ): Boolean {
        if (activeDisplayId < 0 || touchInteractionActive) return false
        val normalized = targetApp.lowercase(java.util.Locale.US)
        if (normalized.contains("launchactivity") || normalized.contains("introactivity") || normalized.contains("splash")) return false
        if (packageName.isBlank() || packageName == "HOME" || packageName == "com.android.settings") return false
        if (topTask?.contains(packageName) == true) return false
        return SystemClock.elapsedRealtime() - lastRecoveryAt >= RECOVERY_COOLDOWN_MS
    }

    fun shouldRecoverFromInjectionReject(now: Long, lastRecoveryAt: Long): Boolean =
        now - lastRecoveryAt >= RECOVERY_COOLDOWN_MS

    companion object {
        const val RECOVERY_COOLDOWN_MS = 2_000L
    }
}

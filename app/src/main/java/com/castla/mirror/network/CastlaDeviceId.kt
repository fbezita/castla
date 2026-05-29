package com.castla.mirror.network

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale

object CastlaDeviceId {
    /**
     * Stable-ish non-secret device id with optional private IP mixed hashing.
     * When IP is provided, combines ANDROID_ID and IP to prevent DNS cache conflicts when IP changes.
     *
     * deviceId example:
     *   9f3a12b7aa
     *
     * relay hostname:
     *   c-9f3a12b7aa.castla.fbezita.com
     */
    fun getDeviceId(context: Context, ip: String? = null): String {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"

        // Combine ANDROID_ID and private IP if available to ensure unique id per network session
        val combined = if (ip.isNullOrBlank()) raw else "${raw}_$ip"
        return sha256(combined).take(10).lowercase(Locale.US)
    }

    fun getRelayLabel(context: Context, ip: String? = null): String {
        return "c-${getDeviceId(context, ip)}"
    }

    fun getRelayHostname(context: Context, ip: String? = null, rootDomain: String = "castla.fbezita.com"): String {
        return "${getRelayLabel(context, ip)}.$rootDomain"
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}


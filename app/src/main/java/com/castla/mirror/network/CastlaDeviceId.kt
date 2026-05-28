package com.castla.mirror.network

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale

object CastlaDeviceId {
    /**
     * Stable-ish non-secret device id.
     *
     * deviceId example:
     *   9f3a12b7aa
     *
     * relay hostname:
     *   c-9f3a12b7aa.castla.fbezita.com
     */
    fun getDeviceId(context: Context): String {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"

        return sha256(raw).take(10).lowercase(Locale.US)
    }

    fun getRelayLabel(context: Context): String {
        return "c-${getDeviceId(context)}"
    }

    fun getRelayHostname(context: Context, rootDomain: String = "castla.fbezita.com"): String {
        return "${getRelayLabel(context)}.$rootDomain"
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

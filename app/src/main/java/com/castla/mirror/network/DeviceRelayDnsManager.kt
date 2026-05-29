package com.castla.mirror.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DeviceRelayDnsManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val relayUpdateToken: String,
    private val rootDomain: String = "castla.fbezita.com",
    private val backendEndpointUrl: String = "https://car.fbezita.com/api/castla/relay"
) {
    companion object {
        private const val TAG = "DeviceRelayDnsManager"
        const val PUBLIC_ENTRY_URL = "https://castla.fbezita.com"
    }

    @Volatile
    private var lastPublishedIp: String? = null

    fun getDeviceId(ip: String? = null): String {
        return CastlaDeviceId.getDeviceId(context, ip)
    }

    fun getPublicEntryUrl(ip: String? = null): String {
        return "$PUBLIC_ENTRY_URL?device=${getDeviceId(ip)}"
    }

    fun getDeviceHostname(ip: String? = null): String {
        return CastlaDeviceId.getRelayHostname(context, ip, rootDomain)
    }

    fun getDeviceRelayUrl(ip: String? = null, port: Int = 9090): String {
        return "https://${getDeviceHostname(ip)}:$port"
    }

    fun publishCurrentIpIfNeeded(
        force: Boolean = false,
        preferredIp: String? = null,
        onResult: ((success: Boolean, publicUrl: String, relayUrl: String, ip: String?) -> Unit)? = null
    ) {
        val ip = preferredIp
            ?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
            ?: HotspotIpDetector.getReachableLocalIpv4(context)

        if (ip == null) {
            Log.e(TAG, "❌ Cannot publish relay DNS: no reachable local IPv4 found")
            val fallbackPublicUrl = getPublicEntryUrl(null)
            val fallbackRelayUrl = getDeviceRelayUrl(null)
            onResult?.invoke(false, fallbackPublicUrl, fallbackRelayUrl, null)
            return
        }

        // Generate IP-mixed configurations to isolate sessions dynamically
        val deviceId = getDeviceId(ip)
        val hostname = getDeviceHostname(ip)
        val relayUrl = getDeviceRelayUrl(ip)
        val publicUrl = getPublicEntryUrl(ip)

        if (!force && ip == lastPublishedIp) {
            Log.i(TAG, "Relay DNS already current: device=$deviceId $hostname -> $ip")
            onResult?.invoke(true, publicUrl, relayUrl, ip)
            return
        }

        scope.launch {
            val ok = RelayRegistrationApi(
                endpointUrl = backendEndpointUrl,
                token = relayUpdateToken
            ).updateRelay(
                deviceId = deviceId,
                hostname = hostname,
                ip = ip,
                relayUrl = relayUrl
            )

            if (ok) lastPublishedIp = ip

            Log.i(
                TAG,
                "Relay publish result ok=$ok public=$publicUrl relay=$relayUrl ip=$ip"
            )

            onResult?.invoke(ok, publicUrl, relayUrl, ip)
        }
    }
}

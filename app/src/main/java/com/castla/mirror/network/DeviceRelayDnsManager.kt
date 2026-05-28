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

    fun getDeviceId(): String {
        return CastlaDeviceId.getDeviceId(context)
    }

    fun getPublicEntryUrl(): String {
        return "$PUBLIC_ENTRY_URL?device=${getDeviceId()}"
    }

    fun getDeviceHostname(): String {
        return CastlaDeviceId.getRelayHostname(context, rootDomain)
    }

    fun getDeviceRelayUrl(port: Int = 9090): String {
        return "https://${getDeviceHostname()}:$port"
    }

    fun publishCurrentIpIfNeeded(
        force: Boolean = false,
        preferredIp: String? = null,
        onResult: ((success: Boolean, publicUrl: String, relayUrl: String, ip: String?) -> Unit)? = null
    ) {
        val ip = preferredIp
            ?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
            ?: HotspotIpDetector.getReachableLocalIpv4(context)
        val deviceId = getDeviceId()
        val hostname = getDeviceHostname()
        val relayUrl = getDeviceRelayUrl()
        val publicUrl = getPublicEntryUrl()

        if (ip == null) {
            Log.e(TAG, "❌ Cannot publish relay DNS: no reachable local IPv4 found")
            onResult?.invoke(false, publicUrl, relayUrl, null)
            return
        }

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

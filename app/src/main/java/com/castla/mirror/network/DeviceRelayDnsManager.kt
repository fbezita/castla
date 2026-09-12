package com.castla.mirror.network

import android.content.Context
import android.util.Log
import com.castla.mirror.diagnostics.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    private var publishJob: Job? = null

    fun getDeviceId(ip: String? = null): String {
        return CastlaDeviceId.getDeviceId(context, ip)
    }

    fun getPublicEntryUrl(ip: String? = null): String {
        return "$PUBLIC_ENTRY_URL?device=${getDeviceId(ip)}"
    }

    fun getDeviceHostname(ip: String? = null): String {
        val safeIp = ip
            ?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
            ?: return CastlaDeviceId.getRelayHostname(context, null, rootDomain)

        return "c-${safeIp.replace(".", "-")}.$rootDomain"
    }

    fun getDeviceRelayUrl(ip: String? = null, port: Int = 9090): String {
        return "https://${getDeviceHostname(ip)}:$port"
    }

    fun publishCurrentIpIfNeeded(
        force: Boolean = false,
        preferredIp: String? = null,
        onResult: ((success: Boolean, publicUrl: String, relayUrl: String, ip: String?) -> Unit)? = null
    ) {
        if (relayUpdateToken.isBlank()) {
            Log.e(TAG, "❌ Cannot publish relay DNS: CASTLA_RELAY_TOKEN is missing")
            FileLogger.e("RELAY_REGISTRATION", "registration_aborted reason=token_missing")
            onResult?.invoke(false, getPublicEntryUrl(null), getDeviceRelayUrl(null), null)
            return
        }

        synchronized(this) {
            publishJob?.cancel()
            publishJob = scope.launch {
                var failureCount = 0
                while (currentCoroutineContext().isActive) {
                    val ip = preferredIp
                        ?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                        ?: HotspotIpDetector.getReachableLocalIpv4(context)

                    if (ip == null) {
                        failureCount++
                        val delayMs = RelayRetryPolicy.delayAfterFailure(failureCount)
                        Log.e(TAG, "❌ Cannot publish relay DNS: no reachable local IPv4 found; retrying in ${delayMs}ms")
                        FileLogger.w(
                            "RELAY_REGISTRATION",
                            "retry_scheduled attempt=$failureCount delayMs=$delayMs reason=no_reachable_ip",
                        )
                        delay(delayMs)
                        continue
                    }

                    val deviceId = getDeviceId(ip)
                    val hostname = getDeviceHostname(ip)
                    val relayUrl = getDeviceRelayUrl(ip)
                    val publicUrl = getPublicEntryUrl(ip)

                    if (!force && ip == lastPublishedIp) {
                        Log.i(TAG, "Relay DNS already current: device=$deviceId $hostname -> $ip")
                        FileLogger.i("RELAY_REGISTRATION", "registration_reused ip=$ip")
                        onResult?.invoke(true, publicUrl, relayUrl, ip)
                        return@launch
                    }

                    val attempt = failureCount + 1
                    FileLogger.i("RELAY_REGISTRATION", "attempt_start attempt=$attempt ip=$ip")
                    val ok = RelayRegistrationApi(
                        endpointUrl = backendEndpointUrl,
                        token = relayUpdateToken,
                    ).updateRelay(
                        deviceId = deviceId,
                        hostname = hostname,
                        ip = ip,
                        relayUrl = relayUrl,
                    )

                    if (ok) {
                        lastPublishedIp = ip
                        Log.i(TAG, "Relay publish result ok=true public=$publicUrl relay=$relayUrl ip=$ip")
                        FileLogger.i("RELAY_REGISTRATION", "registration_ready attempt=$attempt ip=$ip")
                        onResult?.invoke(true, publicUrl, relayUrl, ip)
                        return@launch
                    }

                    failureCount++
                    val delayMs = RelayRetryPolicy.delayAfterFailure(failureCount)
                    Log.w(TAG, "Relay publish failed; retrying attempt=${failureCount + 1} in ${delayMs}ms")
                    FileLogger.w(
                        "RELAY_REGISTRATION",
                        "retry_scheduled attempt=${failureCount + 1} delayMs=$delayMs reason=request_failed ip=$ip",
                    )
                    delay(delayMs)
                }
            }
        }
    }
}

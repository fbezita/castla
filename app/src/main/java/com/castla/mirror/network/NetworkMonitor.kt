package com.castla.mirror.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.castla.mirror.diagnostics.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

sealed class NetworkState {
    object Disconnected : NetworkState()
    data class Connected(val ip: String) : NetworkState()
}

class NetworkMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Disconnected)
    val state: StateFlow<NetworkState> = _state

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshState("available")
        }

        override fun onLost(network: Network) {
            // Another usable network (for example cellular 192.0.0.x after Wi-Fi
            // is disabled) may still be present, so never assume full disconnect.
            refreshState("lost")
        }
    }

    private fun refreshState(trigger: String) {
        val ip = getDeviceIp()
        if (ip == "0.0.0.0") {
            _state.value = NetworkState.Disconnected
            Log.i(TAG, "Network $trigger: no reachable IP")
        } else {
            _state.value = NetworkState.Connected(ip)
            Log.i(TAG, "Network $trigger: $ip")
        }
    }

    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        // Set initial state — also covers hotspot mode where callback may not fire
        val ip = getDeviceIp()
        if (ip != "0.0.0.0") {
            _state.value = NetworkState.Connected(ip)
            Log.i(TAG, "Initial IP: $ip")
        }
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    private fun getDeviceIp(): String {
        try {
            val allInterfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return "0.0.0.0"

            // Log ALL interfaces for debugging
            for (iface in allInterfaces) {
                if (!iface.isUp) continue
                val addrs = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress }
                if (addrs.isNotEmpty()) {
                    Log.i(TAG, "Interface: ${iface.name} IPs: $addrs loopback=${iface.isLoopback}")
                }
            }

            val candidates = mutableListOf<ReachableIpCandidate>()

            for (iface in allInterfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        candidates.add(ReachableIpCandidate(iface.name, ip))
                    }
                }
            }

            val summary = candidates.joinToString(",") { candidate ->
                "${candidate.interfaceName}=${candidate.ip}:${ReachableIpSelector.score(candidate) ?: "rejected"}"
            }
            val best = ReachableIpSelector.select(candidates)
            FileLogger.i(
                "IP_SELECTION",
                "candidates=[$summary] selected=${best?.interfaceName ?: "none"}=${best?.ip ?: "0.0.0.0"}",
            )
            if (best != null) {
                Log.i(TAG, "Selected IP ${best.ip} on ${best.interfaceName}")
                return best.ip
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get device IP", e)
        }
        return "0.0.0.0"
    }
}

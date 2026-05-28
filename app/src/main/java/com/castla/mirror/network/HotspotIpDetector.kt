package com.castla.mirror.network

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotIpDetector {
    private const val TAG = "HotspotIpDetector"

    /**
     * Detects the IPv4 address reachable from the current Tesla/PC network.
     *
     * This intentionally returns RFC1918 private IPv4 because the browser keeps
     * secure context through the hostname/certificate, while transport stays local.
     */
    fun getReachableLocalIpv4(context: Context): String? {
        // Prefer active network IP when available.
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val lp = cm.getLinkProperties(active)
            val activeIp = lp?.linkAddresses
                ?.mapNotNull { it.address as? Inet4Address }
                ?.map { it.hostAddress }
                ?.firstOrNull { isUsablePrivateIpv4(it) }

            if (activeIp != null) {
                Log.i(TAG, "Selected active network IP: $activeIp")
                return activeIp
            }
        } catch (e: Exception) {
            Log.w(TAG, "Active network IP detection failed", e)
        }

        // Fallback: scan interfaces.
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .map { addr -> nif.name to addr.hostAddress }
                }
                .filter { (_, ip) -> isUsablePrivateIpv4(ip) }
                .sortedWith(
                    compareByDescending<Pair<String, String>> { (name, _) ->
                        name.startsWith("wlan") ||
                            name.startsWith("swlan") ||
                            name.startsWith("ap") ||
                            name.startsWith("bridge")
                    }.thenByDescending { (_, ip) ->
                        ip.startsWith("192.168.")
                    }
                )

            Log.i(TAG, "Local IPv4 candidates: $candidates")
            candidates.firstOrNull()?.second
        } catch (e: Exception) {
            Log.e(TAG, "Interface scan failed", e)
            null
        }
    }

    private fun isUsablePrivateIpv4(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        if (ip.startsWith("127.")) return false
        if (ip.startsWith("169.254.")) return false
        return ip.startsWith("192.168.") ||
            ip.startsWith("10.") ||
            ip.matches(Regex("""172\.(1[6-9]|2[0-9]|3[0-1])\..*"""))
    }
}

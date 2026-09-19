package com.castla.mirror.network

import android.content.Context
import android.util.Log
import com.castla.mirror.diagnostics.FileLogger
import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotIpDetector {
    private const val TAG = "HotspotIpDetector"

    /**
     * Detects the IPv4 address reachable from the current Tesla/PC network.
     *
     * Returns an address from a trusted hotspot/LAN interface, or Android's
     * 192.0.0.x cellular continuity address used by tethered clients.
     */
    fun getReachableLocalIpv4(@Suppress("UNUSED_PARAMETER") context: Context): String? {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { addr ->
                            addr.hostAddress?.let { ip -> ReachableIpCandidate(nif.name, ip) }
                        }
                }

            val selected = ReachableIpSelector.select(candidates)
            val summary = candidates.joinToString(",") { candidate ->
                "${candidate.interfaceName}=${candidate.ip}:${ReachableIpSelector.score(candidate) ?: "rejected"}"
            }
            FileLogger.i(
                "IP_SELECTION",
                "fallback_candidates=[$summary] selected=${selected?.interfaceName ?: "none"}=${selected?.ip ?: "0.0.0.0"}",
            )
            Log.i(TAG, "Local IPv4 candidates: $summary; selected=$selected")
            selected?.ip
        } catch (e: Exception) {
            Log.e(TAG, "Interface scan failed", e)
            null
        }
    }
}

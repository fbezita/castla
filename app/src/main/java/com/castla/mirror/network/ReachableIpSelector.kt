package com.castla.mirror.network

data class ReachableIpCandidate(
    val interfaceName: String,
    val ip: String,
)

/** Selects an address that a browser on the hotspot or local LAN can reach. */
object ReachableIpSelector {
    private val hotspotPrefixes = listOf("ap", "swlan", "softap")
    private val cellularPrefixes = listOf(
        "rmnet", "ccmni", "pdp", "clat", "v4-rmnet", "r_rmnet",
    )
    private val rejectedPrefixes = listOf(
        "tun", "tap", "ppp", "wg", "ipsec", "vpn", "tailscale",
    )

    fun select(candidates: List<ReachableIpCandidate>): ReachableIpCandidate? {
        return candidates
            .mapNotNull { candidate -> score(candidate)?.let { candidate to it } }
            .sortedWith(
                compareByDescending<Pair<ReachableIpCandidate, Int>> { it.second }
                    .thenBy { it.first.interfaceName.lowercase() }
                    .thenBy { it.first.ip }
            )
            .firstOrNull()
            ?.first
    }

    fun score(candidate: ReachableIpCandidate): Int? {
        val name = candidate.interfaceName.lowercase()
        val ip = candidate.ip
        if (!isIpv4(ip) || rejectedPrefixes.any(name::startsWith)) return null

        val hotspot = hotspotPrefixes.any(name::startsWith) || name == "wlan1"
        val cellular = cellularPrefixes.any(name::startsWith)
        val privateAddress = isRfc1918(ip)
        val cellularContinuityAddress = ip.startsWith("192.0.0.")

        return when {
            hotspot && cellularContinuityAddress -> 500
            hotspot && privateAddress -> 450
            name.startsWith("wlan") && privateAddress -> 300
            (name.startsWith("eth") || name.startsWith("rndis") || name.startsWith("usb")) && privateAddress -> 200
            cellular && cellularContinuityAddress -> 100
            else -> null
        }
    }

    private fun isRfc1918(ip: String): Boolean {
        val octets = parseIpv4(ip) ?: return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private fun isIpv4(ip: String): Boolean = parseIpv4(ip) != null

    private fun parseIpv4(ip: String): List<Int>? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) return null
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
    }
}

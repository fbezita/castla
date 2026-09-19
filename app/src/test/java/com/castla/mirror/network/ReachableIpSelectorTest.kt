package com.castla.mirror.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReachableIpSelectorTest {

    @Test
    fun `hotspot interface wins over upstream wifi and unrelated interfaces`() {
        val selected = ReachableIpSelector.select(
            listOf(
                ReachableIpCandidate("tun0", "94.23.203.112"),
                ReachableIpCandidate("wlan0", "172.18.0.214"),
                ReachableIpCandidate("swlan0", "192.0.0.4"),
            )
        )

        assertEquals(ReachableIpCandidate("swlan0", "192.0.0.4"), selected)
    }

    @Test
    fun `public address is never selected even on a lan-like interface`() {
        val selected = ReachableIpSelector.select(
            listOf(ReachableIpCandidate("wlan0", "94.23.203.112"))
        )

        assertNull(selected)
    }

    @Test
    fun `vpn and cellular addresses are rejected even when private`() {
        val selected = ReachableIpSelector.select(
            listOf(
                ReachableIpCandidate("tun0", "192.168.10.2"),
                ReachableIpCandidate("rmnet_data0", "192.168.20.2"),
                ReachableIpCandidate("ccmni0", "172.20.0.2"),
            )
        )

        assertNull(selected)
    }

    @Test
    fun `private address on unknown virtual interface is rejected`() {
        val selected = ReachableIpSelector.select(
            listOf(ReachableIpCandidate("dummy0", "172.18.0.214"))
        )

        assertNull(selected)
    }

    @Test
    fun `ordinary wifi private address remains supported without hotspot`() {
        val selected = ReachableIpSelector.select(
            listOf(ReachableIpCandidate("wlan0", "10.20.30.40"))
        )

        assertEquals(ReachableIpCandidate("wlan0", "10.20.30.40"), selected)
    }

    @Test
    fun `protocol range address is accepted on hotspot or cellular fallback`() {
        assertEquals(
            ReachableIpCandidate("ap0", "192.0.0.8"),
            ReachableIpSelector.select(listOf(ReachableIpCandidate("ap0", "192.0.0.8")))
        )
        assertEquals(
            ReachableIpCandidate("rmnet_data1", "192.0.0.2"),
            ReachableIpSelector.select(listOf(ReachableIpCandidate("rmnet_data1", "192.0.0.2")))
        )
        assertNull(
            ReachableIpSelector.select(listOf(ReachableIpCandidate("wlan0", "192.0.0.8")))
        )
    }

    @Test
    fun `wifi private address wins over cellular protocol range fallback`() {
        val selected = ReachableIpSelector.select(
            listOf(
                ReachableIpCandidate("rmnet_data1", "192.0.0.2"),
                ReachableIpCandidate("wlan0", "192.168.50.87"),
            )
        )

        assertEquals(ReachableIpCandidate("wlan0", "192.168.50.87"), selected)
    }

    @Test
    fun `cellular address outside 192 0 0 subnet is rejected`() {
        assertNull(
            ReachableIpSelector.select(listOf(ReachableIpCandidate("rmnet_data1", "192.0.1.2")))
        )
    }

    @Test
    fun `selection is deterministic among candidates with the same priority`() {
        val selected = ReachableIpSelector.select(
            listOf(
                ReachableIpCandidate("wlan1", "192.168.49.1"),
                ReachableIpCandidate("ap0", "192.168.43.1"),
            )
        )

        assertEquals(ReachableIpCandidate("ap0", "192.168.43.1"), selected)
    }
}

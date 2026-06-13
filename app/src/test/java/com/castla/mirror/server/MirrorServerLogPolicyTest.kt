package com.castla.mirror.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorServerLogPolicyTest {
    @Test
    fun suppressesNoisyAppIconHttpRequests() {
        assertFalse(shouldLogHttpRequest("/api/icon"))
        assertFalse(shouldLogHttpRequest("/api/icon?pkg=com.example.app"))
    }

    @Test
    fun keepsImportantHttpRequests() {
        assertTrue(shouldLogHttpRequest("/index.html"))
        assertTrue(shouldLogHttpRequest("/api/apps"))
    }

    @Test
    fun logsOnlyInitialAndPeriodicBroadcastFrames() {
        assertTrue(shouldLogBroadcastFrame(seq = 1))
        assertTrue(shouldLogBroadcastFrame(seq = 3))
        assertFalse(shouldLogBroadcastFrame(seq = 31))
        assertTrue(shouldLogBroadcastFrame(seq = 300))
    }
}

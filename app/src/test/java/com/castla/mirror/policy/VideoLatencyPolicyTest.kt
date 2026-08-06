package com.castla.mirror.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLatencyPolicyTest {
    @Test
    fun `latency range supports one second with a 300ms default`() {
        assertEquals(300, VideoLatencyPolicy.DEFAULT_STREAMED_AUDIO_LATENCY_MS)
        assertEquals(1000, VideoLatencyPolicy.MAX_LATENCY_MS)
        assertEquals(1000, VideoLatencyPolicy.resolve(true, false, false, 0, 1500))
    }

    @Test
    fun `BT latency applies only to video app while Bluetooth audio is connected`() {
        assertEquals(180, VideoLatencyPolicy.resolve(false, true, true, 180, 120))
        assertEquals(0, VideoLatencyPolicy.resolve(false, true, false, 180, 120))
    }

    @Test
    fun `direct output has no additional latency`() {
        assertEquals(0, VideoLatencyPolicy.resolve(false, false, true, 180, 120))
    }

    @Test
    fun `streamed audio uses its own latency`() {
        assertEquals(120, VideoLatencyPolicy.resolve(true, true, true, 180, 120))
    }
}

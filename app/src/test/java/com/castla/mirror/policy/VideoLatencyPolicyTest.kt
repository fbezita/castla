package com.castla.mirror.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLatencyPolicyTest {
    @Test
    fun `latency range supports one second with a negative 30ms default`() {
        assertEquals(-30, VideoLatencyPolicy.DEFAULT_STREAMED_AUDIO_LATENCY_MS)
        assertEquals(1000, VideoLatencyPolicy.MAX_LATENCY_MS)
        assertEquals(-1000, VideoLatencyPolicy.clampStreamedAvOffset(-1500))
        assertEquals(1000, VideoLatencyPolicy.clampStreamedAvOffset(1500))
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
    fun `streamed AV offset delays only the side that is early`() {
        assertEquals(140, VideoLatencyPolicy.resolve(true, true, true, 180, -140))
        assertEquals(0, VideoLatencyPolicy.resolve(true, true, true, 180, 140))
        assertEquals(0, VideoLatencyPolicy.resolveStreamedAudioDelay(-140))
        assertEquals(140, VideoLatencyPolicy.resolveStreamedAudioDelay(140))
    }
}

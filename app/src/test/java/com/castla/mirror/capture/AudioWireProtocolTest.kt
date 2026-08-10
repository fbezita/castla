package com.castla.mirror.capture

import com.castla.mirror.policy.AudioCodec
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioWireProtocolTest {
    @Test
    fun `config includes browser audio output delay`() {
        val protocol = AudioWireProtocol(7, 48_000, 2, 128_000, 20_000, 350)
        val config = protocol.config(AudioCodec.OPUS)
        val json = config.copyOfRange(1, config.size).decodeToString()

        assertTrue(json.contains("\"streamId\":7"))
        assertTrue(json.contains("\"outputDelayMs\":350"))
    }
}

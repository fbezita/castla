package com.castla.mirror.capture

import com.castla.mirror.policy.AudioCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioWireProtocol(
    private val streamId: Long,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitrate: Int,
    private val frameDurationUs: Long,
    private val outputDelayMs: Int,
) {
    private var sequence = 0

    fun config(codec: AudioCodec): ByteArray {
        val selectedBitrate = if (codec == AudioCodec.OPUS) bitrate else 0
        val delayMs = outputDelayMs.coerceIn(0, 1000)
        val json = """{"type":"audioConfig","streamId":$streamId,"codec":"${codec.wireName}","sampleRate":$sampleRate,"channels":$channels,"bitrate":$selectedBitrate,"frameDurationUs":$frameDurationUs,"timestampBaseUs":0,"outputDelayMs":$delayMs}""".toByteArray()
        return byteArrayOf(0x00) + json
    }

    fun packet(timestampUs: Long, payload: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN)
        header.put(0x01)
        header.putLong(streamId)
        header.putInt(sequence++)
        header.putLong(timestampUs)
        return header.array() + payload
    }
}

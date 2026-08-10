package com.castla.mirror.capture

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import com.castla.mirror.policy.AudioSampleClock
import com.castla.mirror.policy.EncoderOutputWatchdog
import com.castla.mirror.policy.PcmFrameAccumulator

class RemoteSubmixOpusTranscoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitrate: Int,
    private val frameBytes: Int,
    private val samplesPerFrame: Int,
) {
    companion object {
        private const val TAG = "RemoteSubmixOpus"

        fun isSupported(): Boolean = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, true) }
            }
        } catch (_: Exception) { false }
    }

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    fun start(
        pipe: ParcelFileDescriptor,
        protocol: AudioWireProtocol,
        onPacket: (ByteArray) -> Unit,
        onRuntimeFailure: (String) -> Unit,
    ): Boolean {
        if (!isSupported()) return false
        val localCodec = try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes)
            }
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Opus encoder initialization failed", e)
            return false
        }
        codec = localCodec
        running = true
        thread = Thread({
            val input = ParcelFileDescriptor.AutoCloseInputStream(pipe)
            val accumulator = PcmFrameAccumulator(frameBytes)
            val clock = AudioSampleClock(sampleRate, samplesPerFrame)
            val outputWatchdog = EncoderOutputWatchdog(maxInputFramesWithoutOutput = 50)
            val readBuffer = ByteArray(frameBytes)
            val info = MediaCodec.BufferInfo()
            var loggedFirstPcmInput = false
            var loggedFirstAudiblePcm = false
            var loggedFirstOpusOutput = false
            var loggedFirstNonSilenceOpus = false
            try {
                while (running) {
                    val read = input.read(readBuffer)
                    if (read < 0) break
                    if (read > 0 && !loggedFirstPcmInput) {
                        loggedFirstPcmInput = true
                        Log.i(TAG, "Opus PCM input started bytes=$read")
                    }
                    if (read > 1 && !loggedFirstAudiblePcm) {
                        var peak = 0
                        var offset = 0
                        while (offset + 1 < read) {
                            val sample = ((readBuffer[offset + 1].toInt() shl 8) or (readBuffer[offset].toInt() and 0xff)).toShort().toInt()
                            peak = maxOf(peak, kotlin.math.abs(sample))
                            offset += 2
                        }
                        if (peak > 32) {
                            loggedFirstAudiblePcm = true
                            Log.i(TAG, "Audible PCM captured peak=$peak")
                        }
                    }
                    for (frame in accumulator.append(readBuffer, read)) {
                        val timestampUs = clock.nextTimestampUs()
                        var queued = false
                        while (running && !queued) {
                            val index = localCodec.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                localCodec.getInputBuffer(index)?.apply { clear(); put(frame) }
                                localCodec.queueInputBuffer(index, 0, frame.size, timestampUs, 0)
                                outputWatchdog.onInputQueued()
                                queued = true
                            }
                            repeat(drain(localCodec, info, protocol, onPacket) { payloadBytes, timestampUs ->
                                if (!loggedFirstOpusOutput) {
                                    loggedFirstOpusOutput = true
                                    Log.i(TAG, "Opus output started payloadBytes=$payloadBytes timestampUs=$timestampUs")
                                }
                                if (payloadBytes > 3 && !loggedFirstNonSilenceOpus) {
                                    loggedFirstNonSilenceOpus = true
                                    Log.i(TAG, "Non-silence Opus output payloadBytes=$payloadBytes timestampUs=$timestampUs")
                                }
                            }) {
                                outputWatchdog.onOutputProduced()
                            }
                        }
                        repeat(drain(localCodec, info, protocol, onPacket) { payloadBytes, timestampUs ->
                            if (!loggedFirstOpusOutput) {
                                loggedFirstOpusOutput = true
                                Log.i(TAG, "Opus output started payloadBytes=$payloadBytes timestampUs=$timestampUs")
                            }
                            if (payloadBytes > 3 && !loggedFirstNonSilenceOpus) {
                                loggedFirstNonSilenceOpus = true
                                Log.i(TAG, "Non-silence Opus output payloadBytes=$payloadBytes timestampUs=$timestampUs")
                            }
                        }) {
                            outputWatchdog.onOutputProduced()
                        }
                        if (outputWatchdog.isStalled) {
                            throw IllegalStateException("Opus encoder produced no output after 50 input frames")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    running = false
                    Log.e(TAG, "Opus encoder runtime failure", e)
                    val reason = if (outputWatchdog.isStalled) "encoder-no-output" else "encoder-runtime-error"
                    onRuntimeFailure(reason)
                }
            } finally {
                try { input.close() } catch (_: Exception) {}
            }
        }, "AudioCapture-RemoteSubmix-Opus").also { it.start() }
        return true
    }

    private fun drain(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        protocol: AudioWireProtocol,
        onPacket: (ByteArray) -> Unit,
        onOutput: (payloadBytes: Int, timestampUs: Long) -> Unit,
    ): Int {
        var outputCount = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return outputCount
            try {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val output = codec.getOutputBuffer(index) ?: continue
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    val payload = ByteArray(info.size)
                    output.get(payload)
                    onPacket(protocol.packet(info.presentationTimeUs, payload))
                    onOutput(payload.size, info.presentationTimeUs)
                    outputCount += 1
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    fun stop() {
        running = false
        if (thread !== Thread.currentThread()) thread?.join(2000)
        thread = null
        codec?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        codec = null
    }
}

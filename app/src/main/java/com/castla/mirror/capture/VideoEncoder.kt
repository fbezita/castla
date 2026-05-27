package com.castla.mirror.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = 4_000_000,
    private val fps: Int = 30,
    val preferredProfile: String = "High"
) {
    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = "video/avc"
        private const val KEYFRAME_INTERVAL = 1
    }

    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private var isRunning = false
    private var lastAppliedBitrate: Int? = null
    private var lastAppliedTextMode: Boolean? = null
    private var lastAppliedQpOffset: Int? = null

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /**
     * 💡 [추가 완료] MediaCodec을 재시작하지 않고 화질 프로파일 및 비트레이트를 동적으로 가변 제어합니다.
     */
    fun setQualityProfile(bps: Int, isTextHeavy: Boolean, qpOffset: Int) {
        val currentCodec = codec ?: return
        if (lastAppliedBitrate == bps && lastAppliedTextMode == isTextHeavy && lastAppliedQpOffset == qpOffset) {
            return
        }
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
                
                if (isTextHeavy) {
                    putInt("video-qp-i-min", (18 + qpOffset).coerceIn(1, 51))
                    putInt("video-qp-i-max", (25 + qpOffset).coerceIn(1, 51))
                    putInt("video-qp-p-min", (20 + qpOffset).coerceIn(1, 51))
                    putInt("video-qp-p-max", (28 + qpOffset).coerceIn(1, 51))
                    putInt("intra-refresh-period", 0) 
                } else {
                    putInt("video-qp-i-min", (22 + qpOffset).coerceIn(1, 51))
                    putInt("video-qp-i-max", (38 + qpOffset).coerceIn(1, 51))
                    putInt("video-qp-p-min", (24 + qpOffset).coerceIn(1, 51))
                    putInt("video-qp-p-max", (40 + qpOffset).coerceIn(1, 51))
                }
            }
            currentCodec.setParameters(params)
            lastAppliedBitrate = bps
            lastAppliedTextMode = isTextHeavy
            lastAppliedQpOffset = qpOffset
            Log.i(TAG, "Dynamic encoder params applied. Bitrate: ${bps / 1000}kbps, TextMode: $isTextHeavy, QpOffset: $qpOffset")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set dynamic quality profile parameters", e)
        }
    }

    fun createInputSurface(): Surface {
        if (preferredProfile.equals("Baseline", ignoreCase = true)) {
            Log.i(TAG, "Enforcing Baseline H.264 profile for software decoder compatibility.")
            return createEncoderWithProfile(
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31,
                "Baseline"
            )
        }
        return try {
            createEncoderWithProfile(
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel4,
                "High"
            )
        } catch (e: Exception) {
            Log.w(TAG, "High Profile failed, falling back to Baseline", e)
            createEncoderWithProfile(
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31,
                "Baseline"
            )
        }
    }

    private fun createEncoderWithProfile(profile: Int, level: Int, profileName: String): Surface {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PROFILE, profile)
            setInteger(MediaFormat.KEY_LEVEL, level)
            setInteger(MediaFormat.KEY_LATENCY, 0)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_OPERATING_RATE, 32767) 
            }
            
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger("android.media.playback-params.low-latency", 1)
            setInteger(MediaFormat.KEY_PRIORITY, 1)
            setInteger("max-bframes", 0)
            
            // Explicitly disable CABAC on hardware encoders when Baseline is requested to guarantee Wasm Broadway decoder compatibility
            if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) {
                setInteger("cabac", 0)
                setInteger("cabac-mode", 0)
            }
            
            setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
        }

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        codec = encoder

        Log.i(TAG, "Encoder created ($profileName): ${width}x${height} @ ${bitrate / 1000}kbps, ${fps}fps")
        return surface
    }

    var onSpsPps: ((ByteArray) -> Unit)? = null

    fun start(onEncodedFrame: (data: ByteArray, isKeyFrame: Boolean) -> Unit) {
        if (released.get()) return
        val encoder = codec ?: throw IllegalStateException("Call createInputSurface() first")
        encoderThread = HandlerThread("VideoEncoder").also { it.start() }
        encoderHandler = Handler(encoderThread!!.looper)
        isRunning = true

        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released.get() || !isRunning) return
                try {
                    val buffer = codec.getOutputBuffer(index) ?: return
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        extractSpsPps(buffer, info)
                        if (sps != null && pps != null) {
                            onSpsPps?.invoke(sps!! + pps!!)
                        }
                        codec.releaseOutputBuffer(index, false)
                        return
                    }

                    if (info.size > 0) {
                        val data = ByteArray(info.size + 8)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(data, 8, info.size)

                        val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        onEncodedFrame(data, isKeyFrame)
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing output buffer", e)
                    try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                if (released.get()) return
                Log.e(TAG, "Encoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (released.get()) return
                Log.i(TAG, "Output format changed: $format")
            }
        }, encoderHandler)

        encoder.start()
    }

    private fun extractSpsPps(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val configData = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(configData)

        var i = 0
        while (i < configData.size - 4) {
            if (configData[i] == 0.toByte() && configData[i + 1] == 0.toByte() &&
                configData[i + 2] == 0.toByte() && configData[i + 3] == 1.toByte()) {

                val nalType = configData[i + 4].toInt() and 0x1F
                val nalStart = i
                var nalEnd = configData.size
                var j = i + 4
                while (j < configData.size - 3) {
                    if (configData[j] == 0.toByte() && configData[j + 1] == 0.toByte() &&
                        configData[j + 2] == 0.toByte() && configData[j + 3] == 1.toByte()) {
                        nalEnd = j
                        break
                    }
                    j++
                }

                val nalUnit = configData.copyOfRange(nalStart, nalEnd)
                when (nalType) {
                    7 -> sps = nalUnit
                    8 -> pps = nalUnit
                }
                i = nalEnd
            } else {
                i++
            }
        }
    }

    
    fun requestKeyFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 1) // Corrected from 0 to 1 to force immediate I-frame generation
            }
            codec?.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request keyframe", e)
        }
    }
    

    fun setBitrate(bps: Int) {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            }
            codec?.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set bitrate", e)
        }
    }

    fun stop() {
        isRunning = false
    }

    fun unregisterCallbacks() {
        try {
            // no-op
            // MediaCodec.setCallback(null)는 Running 상태에서
            // Exynos CCodec에서 "Invalid to call at Running state"를 유발할 수 있음.
            // late callback은 callback 내부의 released flag로 무시한다.            
            // codec?.setCallback(null)
        } catch (_: Exception) {}
    }

    fun join() {
        val threadToJoin = encoderThread
        if (threadToJoin != null) {
            try {
                threadToJoin.quitSafely()
                threadToJoin.join(2000L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed joining encoder thread", e)
            }
            encoderThread = null
            encoderHandler = null
        }
    }

    fun stopCodecOnly() {
        try {
            codec?.stop()
        } catch (_: Exception) {}
    }

    fun releaseCodecOnly() {
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        lastAppliedBitrate = null
        lastAppliedTextMode = null
        lastAppliedQpOffset = null
    }

    fun release() {
        if (released.compareAndSet(false, true)) {
            stop()
            join()            
            unregisterCallbacks()
            stopCodecOnly()
            releaseCodecOnly()
        }
    }
}

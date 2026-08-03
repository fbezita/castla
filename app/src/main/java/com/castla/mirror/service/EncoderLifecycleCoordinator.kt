package com.castla.mirror.service

import android.util.Log
import android.view.Surface
import com.castla.mirror.capture.JpegEncoder
import com.castla.mirror.capture.VideoEncoder
import com.castla.mirror.diagnostics.ResourceTracker
import java.util.concurrent.atomic.AtomicLong

/** Owns encoder creation, callbacks, start guards, and release sequencing for one pipeline. */
internal class EncoderLifecycleCoordinator(private val pipeline: MirroringPipeline) {
    companion object { private const val TAG = "MirrorForegroundService" }

    private val host get() = pipeline.hostService
    fun nextSessionId(): Long = pipeline.nextEncoderSessionId()

    suspend fun release(sessionId: Long? = null, reason: String = "release") {
        val hadEncoder = pipeline.videoEncoder != null || pipeline.jpegEncoder != null
        if (hadEncoder) pipeline.debugEncoderReleases += 1
        val codec = host.currentCodecMode
        pipeline.videoEncoder?.release()
        pipeline.videoEncoder = null
        pipeline.jpegEncoder?.release()
        pipeline.jpegEncoder = null
        pipeline.currentEncoderSurface?.let { surface ->
            ResourceTracker.trackSurfaceRelease(surface.hashCode(), "VideoEncoderInputSurface@${surface.hashCode()}")
            try { surface.release() } catch (_: Exception) {}
        }
        pipeline.currentEncoderSurface = null
        if (sessionId != null) {
            Log.i(TAG, "[${pipeline.name} Pipeline] encoderLifecycle phase=release session=$sessionId codec=$codec " +
                "displayId=${pipeline.displayId} target=${pipeline.width}x${pipeline.height} reason=$reason")
        }
    }

    fun prepare(
        sessionId: Long,
        width: Int,
        height: Int,
        bitrate: Int,
        targetFps: Int,
        rebuildStartedAtMs: Long,
    ): (() -> Unit) {
        val codec = host.currentCodecMode
        return if (codec == "mjpeg") {
            prepareJpeg(sessionId, width, height, rebuildStartedAtMs)
        } else {
            prepareVideo(sessionId, width, height, bitrate, targetFps, rebuildStartedAtMs)
        }
    }

    private fun prepareJpeg(
        sessionId: Long,
        width: Int,
        height: Int,
        rebuildStartedAtMs: Long,
    ): (() -> Unit) {
        val encoder = JpegEncoder(width, height, fps = 15, quality = 65)
        val surface = encoder.createInputSurface()
        pipeline.jpegEncoder = encoder
        pipeline.currentEncoderSurface = surface
        pipeline.debugEncoderCreates += 1
        encoder.onCaptureEvent = { detail ->
            host.logStreamBootstrapInfo(
                "pane=${pipeline.name} session=$sessionId phase=jpeg_encoder $detail displayId=${pipeline.displayId} " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
            )
        }
        host.mirrorServer?.setKeyframeRequester(pipeline.name) { force ->
            pipeline.requestThrottledKeyframe(force, "frame_watchdog_primary")
        }
        return start@{
            if (!pipeline.isCurrentEncoderSession(sessionId) || pipeline.jpegEncoder !== encoder || pipeline.currentEncoderSurface !== surface) {
                host.logStreamBootstrapInfo(
                    "pane=${pipeline.name} session=$sessionId phase=encoder_start_skipped reason=stale codec=mjpeg " +
                        "displayId=${pipeline.displayId} elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
                )
                Log.i(TAG, "[${pipeline.name} Pipeline] Skipping stale JPEG encoder start for session=$sessionId")
                return@start
            }
            host.logStreamBootstrapInfo(
                "pane=${pipeline.name} session=$sessionId phase=encoder_start codec=mjpeg displayId=${pipeline.displayId} " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
            )
            encoder.start { data, key -> pipeline.publishEncodedFrame(data, key, sessionId, width, height, rebuildStartedAtMs) }
        }
    }

    private fun prepareVideo(
        sessionId: Long,
        width: Int,
        height: Int,
        bitrate: Int,
        targetFps: Int,
        rebuildStartedAtMs: Long,
    ): (() -> Unit) {
        val preferredProfile = host.mirrorServer?.getPreferredProfile(pipeline.name) ?: "High"
        val encoder = VideoEncoder(width, height, bitrate, host.thermalFpsOverride ?: targetFps, preferredProfile)
        val surface = encoder.createInputSurface()
        pipeline.videoEncoder = encoder
        pipeline.currentEncoderSurface = surface
        pipeline.debugEncoderCreates += 1
        Log.i(TAG, "[${pipeline.name} Pipeline] encoderLifecycle phase=created session=$sessionId codec=h264 " +
            "displayId=${pipeline.displayId} target=${width}x${height}")
        encoder.onCodecEvent = { detail ->
            host.logStreamBootstrapInfo(
                "pane=${pipeline.name} session=$sessionId phase=video_encoder $detail displayId=${pipeline.displayId} " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
            )
        }
        encoder.onSpsPps = { data ->
            host.logStreamBootstrapInfo(
                "pane=${pipeline.name} session=$sessionId phase=sps_pps_ready size=${data.size} displayId=${pipeline.displayId} " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
            )
            host.mirrorServer?.broadcastSpsPps(data, pipeline.name)
        }
        host.mirrorServer?.setKeyframeRequester(pipeline.name) { force ->
            pipeline.requestThrottledKeyframe(force, "frame_watchdog_secondary")
        }
        return start@{
            if (!pipeline.isCurrentEncoderSession(sessionId) || pipeline.videoEncoder !== encoder || pipeline.currentEncoderSurface !== surface) {
                host.logStreamBootstrapInfo(
                    "pane=${pipeline.name} session=$sessionId phase=encoder_start_skipped reason=stale codec=h264 " +
                        "displayId=${pipeline.displayId} elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
                )
                Log.i(TAG, "[${pipeline.name} Pipeline] Skipping stale video encoder start for session=$sessionId")
                return@start
            }
            host.logStreamBootstrapInfo(
                "pane=${pipeline.name} session=$sessionId phase=encoder_start codec=h264 displayId=${pipeline.displayId} " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - rebuildStartedAtMs}"
            )
            encoder.start { data, key -> pipeline.publishEncodedFrame(data, key, sessionId, width, height, rebuildStartedAtMs) }
        }
    }
}

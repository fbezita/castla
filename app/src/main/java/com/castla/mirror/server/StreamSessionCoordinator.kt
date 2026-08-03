package com.castla.mirror.server

import android.util.Log
import com.castla.mirror.diagnostics.FileLogger
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class StreamSessionCoordinator(
    private val broadcastControl: (String) -> Unit,
    private val dispatchSessionReady: (String, Int, Int, Int, Int) -> Unit,
) {
    private val generations = ConcurrentHashMap<String, AtomicInteger>()
    private val firstFrameReady = ConcurrentHashMap<String, Boolean>()
    private val latestMetadata = ConcurrentHashMap<String, String>()

    fun begin(channel: String, vdId: Int, width: Int, height: Int): Int {
        val normalized = normalize(channel)
        val generation = generations.getOrPut(normalized) { AtomicInteger(0) }.incrementAndGet()
        firstFrameReady[normalized] = false
        FileLogger.i("FRAME_DEBUG", "beginStreamGeneration channel=$normalized vdId=$vdId generation=$generation ${width}x$height")
        FileLogger.i("STREAM_GENERATION", "begin channel=$normalized vdId=$vdId generation=$generation width=$width height=$height")
        publishMetadata(normalized, vdId, generation, width, height, streamReady = true, firstFrame = false)
        dispatchSessionReady(normalized, vdId, generation, width, height)
        return generation
    }

    fun markFirstFrameReady(channel: String, vdId: Int, width: Int, height: Int) {
        val normalized = normalize(channel)
        if (firstFrameReady.put(normalized, true) == true) return
        val generation = currentGeneration(normalized)
        FileLogger.i("FRAME_DEBUG", "firstFrameReady channel=$normalized vdId=$vdId generation=$generation ${width}x$height")
        FileLogger.i("VD_FRAME", "firstFrameReady channel=$normalized vdId=$vdId generation=$generation width=$width height=$height")
        publishMetadata(normalized, vdId, generation, width, height, streamReady = true, firstFrame = true)
    }

    fun pause(channel: String, vdId: Int, width: Int, height: Int) {
        val normalized = normalize(channel)
        firstFrameReady[normalized] = false
        publishMetadata(normalized, vdId, currentGeneration(normalized), width, height, streamReady = false, firstFrame = false)
    }

    fun currentGeneration(channel: String): Int = generations[normalize(channel)]?.get() ?: 0

    fun replayMetadata(send: (String) -> Unit): Int {
        latestMetadata.values.forEach(send)
        return latestMetadata.size
    }

    private fun publishMetadata(
        channel: String,
        vdId: Int,
        generation: Int,
        width: Int,
        height: Int,
        streamReady: Boolean,
        firstFrame: Boolean,
    ) {
        val payload = JSONObject().apply {
            put("type", "streamMetadata")
            put("sessionId", channel)
            put("vdId", vdId)
            put("generation", generation)
            put("width", width)
            put("height", height)
            put("streamReady", streamReady)
            put("firstFrameReady", firstFrame)
        }.toString()
        latestMetadata[channel] = payload
        Log.i(TAG, "Stream metadata: channel=$channel vdId=$vdId generation=$generation ${width}x$height streamReady=$streamReady firstFrame=$firstFrame")
        broadcastControl(payload)
    }

    companion object {
        private const val TAG = "MirrorServer"
        fun normalize(channel: String): String = if (channel == "secondary") "secondary" else "primary"
    }
}

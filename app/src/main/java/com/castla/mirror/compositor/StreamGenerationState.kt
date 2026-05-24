package com.castla.mirror.compositor

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StreamGenerationState(
    private val sessionId: DisplaySessionId
) {
    private val generationRef = AtomicInteger(0)
    private val lastFrameAtRef = AtomicLong(0)
    @Volatile var streamReady: Boolean = false
        private set
    @Volatile var firstFrameReady: Boolean = false
        private set
    @Volatile var width: Int = 0
        private set
    @Volatile var height: Int = 0
        private set

    val generation: Int
        get() = generationRef.get()

    val lastFrameTimestampMs: Long
        get() = lastFrameAtRef.get()

    fun begin(width: Int, height: Int): Int {
        this.width = width
        this.height = height
        streamReady = true
        firstFrameReady = false
        lastFrameAtRef.set(0)
        return generationRef.incrementAndGet()
    }

    fun markFirstFrame() {
        firstFrameReady = true
        lastFrameAtRef.set(android.os.SystemClock.elapsedRealtime())
    }

    fun markFrame() {
        lastFrameAtRef.set(android.os.SystemClock.elapsedRealtime())
    }

    fun pause() {
        streamReady = false
        firstFrameReady = false
    }

    fun toJson(vdId: Int): JSONObject = JSONObject().apply {
        put("type", "streamMetadata")
        put("sessionId", sessionId.value)
        put("vdId", vdId)
        put("generation", generation)
        put("width", width)
        put("height", height)
        put("streamReady", streamReady)
        put("firstFrameReady", firstFrameReady)
    }
}

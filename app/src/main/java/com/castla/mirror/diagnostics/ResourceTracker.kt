package com.castla.mirror.diagnostics

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object ResourceTracker {
    private const val TAG = "ResourceTracker"

    private val activeCodecs = AtomicInteger(0)
    private val activeVirtualDisplays = AtomicInteger(0)
    private val activeSurfaces = AtomicInteger(0)
    private val activeImageReaders = AtomicInteger(0)
    private val activeProjectionSessions = AtomicInteger(0)
    private val activeWebSockets = AtomicInteger(0)
    private val activeThreads = AtomicInteger(0)

    private val codecMap = ConcurrentHashMap<Int, String>()
    private val virtualDisplayMap = ConcurrentHashMap<Int, String>()
    private val surfaceMap = ConcurrentHashMap<Int, String>()
    private val imageReaderMap = ConcurrentHashMap<Int, String>()
    private val projectionMap = ConcurrentHashMap<Int, String>()
    private val webSocketMap = ConcurrentHashMap<Int, String>()
    private val threadMap = ConcurrentHashMap<Int, String>()

    fun trackCodecCreate(id: Int, name: String) {
        if (codecMap.putIfAbsent(id, name) == null) {
            activeCodecs.incrementAndGet()
            Log.i(TAG, "[RESOURCE_CREATE]")
            Log.i(TAG, "type=MediaCodec id=$name")
            logState()
        }
    }

    fun trackCodecRelease(id: Int, name: String) {
        if (codecMap.remove(id) != null) {
            activeCodecs.updateAndGet { (it - 1).coerceAtLeast(0) }
            Log.i(TAG, "[RESOURCE_RELEASE]")
            Log.i(TAG, "type=MediaCodec id=$name")
            logState()
        }
    }

    fun trackVirtualDisplayCreate(id: Int, name: String) {
        if (virtualDisplayMap.putIfAbsent(id, name) == null) {
            activeVirtualDisplays.incrementAndGet()
            Log.i(TAG, "[RESOURCE_CREATE]")
            Log.i(TAG, "type=VirtualDisplay id=$name")
            logState()
        }
    }

    fun trackVirtualDisplayRelease(id: Int, name: String) {
        if (virtualDisplayMap.remove(id) != null) {
            activeVirtualDisplays.updateAndGet { (it - 1).coerceAtLeast(0) }
            Log.i(TAG, "[RESOURCE_RELEASE]")
            Log.i(TAG, "type=VirtualDisplay id=$name")
            logState()
        }
    }

    fun trackSurfaceCreate(id: Int, name: String) {
        if (surfaceMap.putIfAbsent(id, name) == null) {
            activeSurfaces.incrementAndGet()
            Log.i(TAG, "[RESOURCE_CREATE]")
            Log.i(TAG, "type=Surface id=$name")
            logState()
        }
    }

    fun trackSurfaceRelease(id: Int, name: String) {
        if (surfaceMap.remove(id) != null) {
            activeSurfaces.updateAndGet { (it - 1).coerceAtLeast(0) }
            Log.i(TAG, "[RESOURCE_RELEASE]")
            Log.i(TAG, "type=Surface id=$name")
            logState()
        }
    }

    fun trackImageReaderCreate(id: Int, name: String) {
        if (imageReaderMap.putIfAbsent(id, name) == null) {
            activeImageReaders.incrementAndGet()
            Log.i(TAG, "[RESOURCE_CREATE]")
            Log.i(TAG, "type=ImageReader id=$name")
            logState()
        }
    }

    fun trackImageReaderRelease(id: Int, name: String) {
        if (imageReaderMap.remove(id) != null) {
            activeImageReaders.updateAndGet { (it - 1).coerceAtLeast(0) }
            Log.i(TAG, "[RESOURCE_RELEASE]")
            Log.i(TAG, "type=ImageReader id=$name")
            logState()
        }
    }

    fun trackProjectionCreate(id: Int, name: String) {
        if (projectionMap.putIfAbsent(id, name) == null) {
            activeProjectionSessions.incrementAndGet()
            Log.i(TAG, "[RESOURCE_CREATE]")
            Log.i(TAG, "type=MediaProjection id=$name")
            logState()
        }
    }

    fun trackProjectionRelease(id: Int, name: String) {
        if (projectionMap.remove(id) != null) {
            activeProjectionSessions.updateAndGet { (it - 1).coerceAtLeast(0) }
            Log.i(TAG, "[RESOURCE_RELEASE]")
            Log.i(TAG, "type=MediaProjection id=$name")
            logState()
        }
    }

    fun trackWebSocketCreate(id: Int, name: String) {
        if (webSocketMap.putIfAbsent(id, name) == null) {
            activeWebSockets.incrementAndGet()
            Log.i(TAG, "[RESOURCE_CREATE]")
            Log.i(TAG, "type=WebSocket id=$name")
            logState()
        }
    }

    fun trackWebSocketRelease(id: Int, name: String) {
        if (webSocketMap.remove(id) != null) {
            activeWebSockets.updateAndGet { (it - 1).coerceAtLeast(0) }
            Log.i(TAG, "[RESOURCE_RELEASE]")
            Log.i(TAG, "type=WebSocket id=$name")
            logState()
        }
    }

    fun trackThreadCreate(id: Int, name: String) {
        if (threadMap.putIfAbsent(id, name) == null) {
            activeThreads.incrementAndGet()
            Log.i(TAG, "[RESOURCE_CREATE]")
            Log.i(TAG, "type=Thread id=$name")
            logState()
        }
    }

    fun trackThreadRelease(id: Int, name: String) {
        if (threadMap.remove(id) != null) {
            activeThreads.updateAndGet { (it - 1).coerceAtLeast(0) }
            Log.i(TAG, "[RESOURCE_RELEASE]")
            Log.i(TAG, "type=Thread id=$name")
            logState()
        }
    }

    fun logState() {
        Log.i(TAG, "[RESOURCE_STATE]")
        Log.i(TAG, "activeCodecs=${activeCodecs.get()}")
        Log.i(TAG, "activeVirtualDisplays=${activeVirtualDisplays.get()}")
        Log.i(TAG, "activeSurfaces=${activeSurfaces.get()}")
        Log.i(TAG, "activeImageReaders=${activeImageReaders.get()}")
        Log.i(TAG, "activeProjectionSessions=${activeProjectionSessions.get()}")
        Log.i(TAG, "activeWebSockets=${activeWebSockets.get()}")
        Log.i(TAG, "activeThreads=${activeThreads.get()}")
    }
}

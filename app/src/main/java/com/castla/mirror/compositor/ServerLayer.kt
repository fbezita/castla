package com.castla.mirror.compositor

import com.castla.mirror.server.MirrorServer

class ServerLayer(private val mirrorServer: MirrorServer) {
    val staticAssetServer = StaticAssetServer()
    val apiServer = ApiServer()
    val webSocketControlServer = WebSocketControlServer()
    val videoStreamServer = VideoStreamServer()

    fun broadcastDiagnostics(registry: DisplaySessionRegistry) {
        val json = org.json.JSONObject().apply {
            put("type", "diagnostics")
            put("displays", org.json.JSONArray(registry.diagnostics().map {
                org.json.JSONObject().apply {
                    put("sessionId", it.sessionId.value)
                    put("vdId", it.vdId)
                    put("tier", it.tier.name)
                    put("generation", it.generation)
                    put("width", it.width)
                    put("height", it.height)
                    put("encoderRunning", it.encoderRunning)
                    put("streamReady", it.streamReady)
                    put("firstFrameReady", it.firstFrameReady)
                    put("reconnectCount", it.reconnectCount)
                    put("lastFrameTimestampMs", it.lastFrameTimestampMs)
                    put("droppedFrames", it.droppedFrames)
                    put("generationMismatch", it.generationMismatchCount)
                }
            }))
        }
        mirrorServer.broadcastControlMessage(json.toString())
    }
}

class StaticAssetServer
class ApiServer
class WebSocketControlServer
class VideoStreamServer

package com.castla.mirror.server

import android.os.SystemClock
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ControlSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val server: MirrorServer
) : NanoWSD.WebSocket(handshake) {

    companion object {
        private const val TAG = "ControlSocket"
        private const val DECODER_TAG = "CastlaDecoder"
        private var nextSocketId = 1
        private val QUIET_DECODER_EVENTS = setOf(
            "metadata",
            "configFrame",
            "keyFrame",
            "frameSummary",
            "jmuxerReady",
            "jmuxerConfig",
            "jmuxerCreated",
            "jmuxerMseReady",
            "jmuxerQueue",
            "jmuxerFeedSummary",
            "videoLoadedMetadata",
            "videoLoadedData",
            "videoCanPlay",
            "videoPlaying",
            "videoHasCurrentData"
        )
    }

    val debugId: Int = nextSocketId++
    @Volatile var sessionId: Int = 0
        private set
    @Volatile var openTimeElapsedMs: Long = 0L
        private set
    @Volatile var closeTimeElapsedMs: Long = 0L
        private set
    @Volatile var active: Boolean = false
        private set
    @Volatile var registered: Boolean = false
        private set
    private var messageCount = 0
    private var touchMessageCount = 0
    private var staleCloseRequested = false

    override fun onOpen() {
        openTimeElapsedMs = SystemClock.elapsedRealtime()
        val assignedSessionId = server.registerControlSocket(this)
        // Removed [InputDebug] onOpen log
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        closeTimeElapsedMs = SystemClock.elapsedRealtime()
        // Removed [InputDebug] onClose log
        server.unregisterControlSocket(this)
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        try {
            messageCount += 1
            if (!server.shouldAcceptControlMessage(this) && !server.ensureActiveControlSocket(this)) {
                server.logStaleControlMessage(this, messageCount)
                if (!staleCloseRequested) {
                    staleCloseRequested = true
                    try {
                        close(
                            NanoWSD.WebSocketFrame.CloseCode.NormalClosure,
                            "Stale control socket",
                            false
                        )
                    } catch (_: Exception) {}
                }
                return
            }
            // Binary frames: 10-byte touch protocol [action:u8][id:u8][x:f32LE][y:f32LE]
            if (message.opCode == NanoWSD.WebSocketFrame.OpCode.Binary) {
                touchMessageCount += 1
                //Log.d(TAG, "Binary touch received: ${message.binaryPayload.size} bytes")
                handleBinaryTouch(message.binaryPayload)
                return
            }
//            Log.d(TAG, "Text message received: ${message.textPayload?.take(50)}")
            val json = JSONObject(message.textPayload)
            val type = json.optString("type", "")

            when (type) {
                "touch" -> {
                    touchMessageCount += 1
                    val event = TouchEvent(
                        action = json.getString("action"),
                        x = json.getDouble("x").toFloat(),
                        y = json.getDouble("y").toFloat(),
                        pointerId = json.optInt("id", 0),
                        pane = json.optString("pane", "primary"),
                        clientTsMs = json.optLong("clientTs", 0L),
                        receivedAtElapsedMs = SystemClock.elapsedRealtime()
                    )
                    server.onTouchEvent(event)
                }
                "touchReset" -> {
                    server.onTouchReset()
                }
                "debugBrowserRearm" -> {
                    server.onBrowserRearmRequest()
                }
                "debugBrowserTeardown" -> {
                    server.onBrowserTeardownRequest()
                }
                "debugSocketCycle" -> {
                    server.debugCycleSockets()
                }
                "requestKeyframe" -> {
                    val pane = json.optString("pane", "primary")
                    server.onKeyframeRequest(pane, "controlSocket#$debugId")
                }
                "ping" -> {
                    send(JSONObject().apply {
                        put("type", "pong")
                        put("ts", json.optLong("ts", System.currentTimeMillis()))
                    }.toString())
                }
                "codec" -> {
                    val mode = json.optString("mode", "h264")
                    val profile = json.optString("profile", "High")
                    val pane = json.optString("pane", "primary")
                    server.onCodecModeRequest(mode, profile, pane)
                }
                "layout_update" -> {
                    val pipelinesArray = json.optJSONArray("pipelines")
                    if (pipelinesArray != null) {
                        Log.i(TAG, "layout_update received: ${pipelinesArray.toString()}")
                        server.onLayoutUpdate(pipelinesArray)
                    }
                }
                "textInput" -> {
                    val text = json.optString("text", "")
                    if (text.isNotEmpty()) {
                        server.onTextInput(text)
                    }
                }
                "keyEvent" -> {
                    val keyCode = json.optInt("keyCode", -1)
                    if (keyCode >= 0) {
                        server.onKeyEvent(keyCode)
                    }
                }
                "compositionUpdate" -> {
                    val backspaces = json.optInt("backspaces", 0)
                    val text = json.optString("text", "")
                    server.onCompositionUpdate(backspaces, text)
                }
                "ime" -> {
                    when (json.optString("op", "")) {
                        "commitText" -> {
                            val text = json.optString("text", "")
                            if (text.isNotEmpty()) server.onTextInput(text)
                        }
                        "setComposingText" -> {
                            val replaceChars = json.optInt("replaceChars", 0)
                            val text = json.optString("text", "")
                            server.onCompositionUpdate(replaceChars, text)
                        }
                        "deleteSurroundingText" -> {
                            val beforeLength = json.optInt("beforeLength", 1).coerceAtLeast(0)
                            repeat(beforeLength) { server.onKeyEvent(67) }
                        }
                        "sendKeyEvent" -> {
                            val keyCode = json.optInt("keyCode", -1)
                            if (keyCode >= 0) {
                                server.onKeyEvent(keyCode)
                            }
                        }                        
                        "finishComposingText" -> server.onCompositionUpdate(0, "")
                        "tapOutside" -> {
                            server.onTapOutside()
                        }
                    }
                }
                "goHome" -> {
                    server.onGoHomeRequest()
                }
                "audioCodec" -> {
                    val codec = json.optString("codec", "")
                    server.onAudioCodecRequest(codec)
                }
                "launchApp" -> {
                    val pkg = json.optString("pkg", "")
                    val pane = json.optString("pane", "primary")
                    val componentName = json.optString("componentName", "")
                        .takeIf { it.isNotEmpty() }
                    val isVideoApp = json.optBoolean("isVideoApp", false)
                    if (pkg.isNotEmpty()) {
                        server.onAppLaunchRequest(pkg, componentName, pane, isVideoApp)
                    }
                }

                "displayDensity" -> {
                    val scale = json.optDouble("scale", 1.0).toFloat()
                    if (scale in 0.4f..1.5f) {
                        server.onDisplayDensityChange(scale)
                    }
                }
                "qualityReport" -> {
                    server.onQualityReport(
                        droppedFrames = json.optInt("droppedFrames", 0),
                        avgDelayMs = json.optDouble("avgDelayMs", 0.0),
                        backlogDrops = json.optInt("backlogDrops", 0)
                    )
                }
                "decoderStatus" -> {
                    val pane = json.optString("pane", "primary")
                    val event = json.optString("event", "")
                    val detail = json.optString("detail", "")
                    if (!QUIET_DECODER_EVENTS.contains(event)) {
                        Log.w(DECODER_TAG, "[$pane] $event ${detail.take(180)}")
                    }
                }
                "bubbleClosed" -> {
                    server.onBubbleClosed()
                }
                "debugDump" -> {
                    val logsArray = json.optJSONArray("logs")
                    if (logsArray != null) {
                        handleDebugDump(logsArray)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse control message", e)
        }
    }
    
    private fun handleDebugDump(logs: org.json.JSONArray) {
        val count = logs.length()
        Log.i(TAG, "⚡ [debugDump] Received $count frontend logs from Tesla browser.")
        
        val boundary = "========================\nTESLA FRONTEND DEBUG DUMP\ntimestamp=${System.currentTimeMillis()}\n========================\n"
        
        val sb = java.lang.StringBuilder()
        sb.append("\n").append(boundary)
        
        for (i in 0 until count) {
            val entry = logs.optJSONObject(i) ?: continue
            val ts = entry.optLong("ts", 0L)
            val msg = entry.optString("message", "")
            val dataObj = entry.opt("data")
            val data = if (dataObj != null && dataObj != org.json.JSONObject.NULL) dataObj.toString() else ""
            
            val date = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(ts))
            sb.append("[$date] $msg")
            if (data.isNotEmpty()) {
                sb.append(" | data=").append(data)
            }
            sb.append("\n")
        }
        
        sb.append("========================\nEND OF TESLA FRONTEND DEBUG DUMP\n========================\n")
        
        val fullDump = sb.toString()
        
        // 1. Append frontend logs to existing diagnostic file using FileLogger
        com.castla.mirror.diagnostics.FileLogger.writeRaw("TeslaFrontend", fullDump)
        
        // 2. Append frontend logs to existing MirrorServer log stream (Logcat)
        Log.w(TAG, fullDump)
    }

    private fun handleBinaryTouch(data: ByteArray) {
        if (data.size < 10) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val actionByte = buf.get().toInt() and 0xFF
        val action = when (actionByte) {
            0 -> "down"
            1 -> "up"
            2 -> "move"
            else -> return
        }
        val id = buf.get().toInt() and 0xFF
        val x = buf.float
        val y = buf.float
        val pane = if (data.size >= 11) {
            when (data[10].toInt() and 0xFF) {
                1 -> "secondary"
                else -> "primary"
            }
        } else "primary"
//        if (action != "move") Log.i(TAG, "Touch[$pane]: $action id=$id x=${"%.3f".format(x)} y=${"%.3f".format(y)}")
        server.onTouchEvent(
            TouchEvent(
                action = action,
                x = x,
                y = y,
                pointerId = id,
                pane = pane,
                receivedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        )
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

    override fun onException(exception: IOException?) {
        closeTimeElapsedMs = SystemClock.elapsedRealtime()
        if (exception is java.net.SocketException || exception?.message?.contains("Socket closed") == true) {
            Log.i(TAG, "Control socket#$debugId sessionId=$sessionId closed cleanly")
        } else {
            Log.w(TAG, "Control socket#$debugId sessionId=$sessionId exception", exception)
        }
        server.unregisterControlSocket(this)
    }

    fun attachSession(sessionId: Int) {
        this.sessionId = sessionId
        this.registered = true
        this.active = true
        this.closeTimeElapsedMs = 0L
        this.staleCloseRequested = false
    }

    fun markInactive(reason: String) {
        active = false
    }

    fun markUnregistered(reason: String) {
        registered = false
        active = false
        if (closeTimeElapsedMs == 0L) {
            closeTimeElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun debugSummary(): String {
        return "socketId=$debugId sessionId=$sessionId active=$active registered=$registered " +
            "openTime=$openTimeElapsedMs closeTime=$closeTimeElapsedMs messages=$messageCount touchMessages=$touchMessageCount"
    }
}

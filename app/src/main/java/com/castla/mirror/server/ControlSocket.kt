package com.castla.mirror.server

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
    }

    override fun onOpen() {
        server.registerControlSocket(this)
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        server.unregisterControlSocket(this)
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        try {
            // Binary frames: 10-byte touch protocol [action:u8][id:u8][x:f32LE][y:f32LE]
            if (message.opCode == NanoWSD.WebSocketFrame.OpCode.Binary) {
                //Log.d(TAG, "Binary touch received: ${message.binaryPayload.size} bytes")
                handleBinaryTouch(message.binaryPayload)
                return
            }
//            Log.d(TAG, "Text message received: ${message.textPayload?.take(50)}")
            Log.d(TAG, "Text message received: ${message.textPayload}")

            val json = JSONObject(message.textPayload)
            val type = json.optString("type", "")

            when (type) {
                "touch" -> {
                    val event = TouchEvent(
                        action = json.getString("action"),
                        x = json.getDouble("x").toFloat(),
                        y = json.getDouble("y").toFloat(),
                        pointerId = json.optInt("id", 0),
                        pane = json.optString("pane", "primary")
                    )
                    server.onTouchEvent(event)
                }
                "requestKeyframe" -> {
                    val pane = json.optString("pane", "primary")
                    server.onKeyframeRequest(pane)
                }
                /* ### 수정 시작 ### */
                "codec" -> {
                    val mode = json.optString("mode", "h264")
                    val profile = json.optString("profile", "High")
                    val pane = json.optString("pane", "primary")
                    server.onCodecModeRequest(mode, profile, pane)
                }
                /* ### 수정 끝 ### */
                /* ### 수정 시작 ### */
                "layout_update" -> {
                    val pipelinesArray = json.optJSONArray("pipelines")
                    if (pipelinesArray != null) {
                        server.onLayoutUpdate(pipelinesArray)
                    }
                }
                /* ### 수정 끝 ### */
                /* ### 수정 시작 ### */
                // Legacy "viewport" protocol has been completely removed.
                /* ### 수정 끝 ### */
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

                /* ### 수정 시작 ### */
                // Legacy "closeSecondary" and "closeSplit" protocols have been completely removed.
                /* ### 수정 끝 ### */
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
                "bubbleClosed" -> {
                    server.onBubbleClosed()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse control message", e)
        }
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
        server.onTouchEvent(TouchEvent(action, x, y, id, pane))
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

    override fun onException(exception: IOException?) {
        if (exception is java.net.SocketException || exception?.message?.contains("Socket closed") == true) {
            Log.i(TAG, "Control socket closed cleanly")
        } else {
            Log.w(TAG, "Control socket exception", exception)
        }
        server.unregisterControlSocket(this)
    }
}

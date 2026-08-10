package com.castla.mirror.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import org.json.JSONObject

class AudioStreamSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val server: MirrorServer
) : NanoWSD.WebSocket(handshake) {

    companion object { private const val TAG = "AudioStreamSocket" }

    override fun onOpen() {
        server.registerAudioSocket(this)
        com.castla.mirror.diagnostics.ResourceTracker.trackWebSocketCreate(this.hashCode(), "AudioStreamSocket")
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        server.unregisterAudioSocket(this)
        com.castla.mirror.diagnostics.ResourceTracker.trackWebSocketRelease(this.hashCode(), "AudioStreamSocket")
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        val text = message.textPayload ?: return
        if (text == "requestPcm") {
            server.onAudioCodecRequest("pcm")
            return
        }
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "audioCapabilities" -> {
                    val codec = if (json.optBoolean("opus", false)) "opus" else "pcm"
                    Log.i(TAG, "Browser audio capabilities selectedCodec=$codec")
                    server.onAudioCodecRequest(codec)
                }
                "requestPcm" -> {
                    Log.i(TAG, "Client requested PCM streamId=${json.optLong("streamId", -1)} reason=${json.optString("reason")}")
                    server.onAudioCodecRequest("pcm")
                }
                "audioDiagnostics" -> {
                    Log.i(TAG, "Browser audio diagnostics: $json")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid audio control message", e)
        }
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

    override fun onException(exception: IOException?) {
        if (exception is java.net.SocketException || exception?.message?.contains("Socket closed") == true) {
            Log.i(TAG, "Audio WebSocket closed cleanly")
        } else {
            Log.w(TAG, "WebSocket exception", exception)
        }
        server.unregisterAudioSocket(this)
        com.castla.mirror.diagnostics.ResourceTracker.trackWebSocketRelease(this.hashCode(), "AudioStreamSocket")
    }

    @Synchronized
    fun sendBinary(data: ByteArray) {
        try {
            send(data)
        } catch (e: IOException) {
            if (e is java.net.SocketException || e.message?.contains("Socket closed") == true) {
                Log.i(TAG, "Send failed: socket closed cleanly")
            } else {
                Log.w(TAG, "Send failed", e)
            }
            throw e
        }
    }

    @Synchronized
    fun sendText(data: String) {
        send(data)
    }
}

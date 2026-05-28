package com.castla.mirror.server

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.MirrorDiagnostics
import com.castla.mirror.utils.AppCategoryClassifier
import com.castla.mirror.ott.OttCatalog

import com.castla.mirror.network.DeviceRelayDnsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

data class TouchEvent(
    val action: String,
    val x: Float,
    val y: Float,
    val pointerId: Int,
    val pane: String = "primary",
    val clientTsMs: Long = 0L,
    val receivedAtElapsedMs: Long = 0L
)

class MirrorServer(private val context: Context, hostname: String? = null) : NanoWSD(hostname, DEFAULT_PORT) {

    val instanceId: String = java.util.UUID.randomUUID().toString()

    private var relayPublishIp: String = "0.0.0.0"    

    private val primaryVideoSockets = mutableSetOf<VideoStreamSocket>()
    private val secondaryVideoSockets = mutableSetOf<VideoStreamSocket>()
    private val controlSockets = mutableSetOf<ControlSocket>()
    private val audioSockets = mutableSetOf<AudioStreamSocket>()
    private val controlSocketLock = Any()
    private val activeControlSessionId = AtomicInteger(0)
    private val browserConnectionEpoch = AtomicInteger(0)
    private val keyframeRequestCount = AtomicInteger(0)
    private val lastSpsPpsReplayLogAtByChannel = ConcurrentHashMap<String, Long>()
    @Volatile private var activeControlSocket: ControlSocket? = null
    private val staleControlLogTimes = ConcurrentHashMap<Int, Long>()
    @Volatile private var lastSkippedBroadcastLogAt = 0L
    private val layoutUpdateReceivedCount = AtomicInteger(0)
    private val layoutUpdateRelayedCount = AtomicInteger(0)
    private val layoutUpdateDedupedCount = AtomicInteger(0)
    @Volatile private var lastLayoutUpdateSignature: String = ""

    private val dnsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relayDnsManager = DeviceRelayDnsManager(
        context = context,
        scope = dnsScope,
        relayUpdateToken = CASTLA_CERT_TOKEN
    )


    companion object {
        private const val TAG = "MirrorServer"
        const val DEFAULT_PORT = 9090

        private const val CERT_API_URL = "https://car.fbezita.com/api/castla/cert"
        private const val CASTLA_CERT_TOKEN = "5f8c7d3a91e24f0bb4d7e6c2a8f91b6d"
        private const val CERT_PASSWORD = "castla4864"
    }    

    init {
        // [Legacy Code] Filtering NanoHTTPD socket closed exception logs
        try {
            val nanoLogger = java.util.logging.Logger.getLogger("fi.iki.elonen.NanoHTTPD")
            nanoLogger.filter = object : java.util.logging.Filter {
                override fun isLoggable(record: java.util.logging.LogRecord): Boolean {
                    val thrown = record.thrown
                    val msg = record.message ?: ""
                    val isTargetError = msg.contains("Could not send response to the client") ||
                            thrown is java.net.SocketException ||
                            thrown?.message?.contains("Socket is closed") == true ||
                            thrown?.cause is java.net.SocketException
                    return !isTargetError
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure NanoHTTPD log filter", e)
        }

        Log.i(TAG, "MirrorServer init: relay DNS publish will wait for WebCodecs mode and a valid serverIp")

        configureSecureContext()
        publishRelayDnsIfReady("init_after_secure_context")
    }

    private fun configureSecureContext() {
        // Trigger certificate download in background
        downloadCertIfAvailableBlocking(context)

        // Load settings to check if WebCodecs hardware accelerated decoding is enabled
        val settings = com.castla.mirror.ui.StreamSettings.load(context)

        // ✅ Only enable SSL/HTTPS socket binding if WebCodecs option is enabled
        if (settings.webCodecsEnabled) {
            try {
                val password = CERT_PASSWORD.toCharArray()
                val keyStore = KeyStore.getInstance("PKCS12")
                val dynamicKeyStoreFile = File(context.filesDir, "dynamic_castla.p12")
                
                val isDynamic = dynamicKeyStoreFile.exists() && dynamicKeyStoreFile.length() > 0
                val keystoreStream: InputStream = if (isDynamic) {
                    Log.i(TAG, "🔓 [Success] SSL Cert source: dynamic_castla.p12 loaded from local app storage")
                    FileInputStream(dynamicKeyStoreFile)
                } else {
                    Log.i(TAG, "🔓 [Success] SSL Cert source: castla.p12 loaded from bundled assets fallback")
                    context.assets.open("castla.p12")
                }

                keystoreStream.use { stream -> keyStore.load(stream, password) }
                val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keyManagerFactory.init(keyStore, password)

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(keyManagerFactory.keyManagers, null, null)

                // ✅ SSL socket binding (HTTPS) with advanced decorator logging
                makeSecure(sslContext.serverSocketFactory, null)
                val originalFactory = serverSocketFactory
                if (originalFactory != null) {
                    val loggingFactory = object : fi.iki.elonen.NanoHTTPD.ServerSocketFactory {
                        override fun create(): java.net.ServerSocket {
                            val originalServerSocket = originalFactory.create()
                            return LoggingServerSocket(originalServerSocket)
                        }
                    }
                    serverSocketFactory = loggingFactory
                }
                Log.i(TAG, "🚀 HTTPS server started")
                Log.i(TAG, "🌐 Public URL = ${relayDnsManager.getPublicEntryUrl()}")
                Log.i(TAG, "🔗 Device relay URL = ${relayDnsManager.getDeviceRelayUrl()}")
                return
            } catch (e: Exception) {
                Log.e(TAG, "❌ SSL load failed, falling back to HTTP mode", e)
            }
        }

        // 🌐 When WebCodecs is disabled, makeSecure() is bypassed so the server automatically runs in HTTP mode.
        Log.w(TAG, "⚠️ [HTTP Mode] WebCodecs is disabled; running as a standard HTTP server.")
    }

    /**
     * Function to download the latest .p12 certificate from a remote cloud or NAS server
     */
    fun downloadCertIfAvailableBlocking(context: Context): Boolean {
        val targetFile = File(context.filesDir, "dynamic_castla.p12")
        val tempFile = File(context.filesDir, "dynamic_castla.p12.tmp")

        return try {
            val connection = (URL(CERT_API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $CASTLA_CERT_TOKEN")
            }

            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        connection.inputStream.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        if (!tempFile.exists() || tempFile.length() <= 0L) {
                            tempFile.delete()
                            Log.w(TAG, "[Certificate Sync] Empty p12 downloaded. Keeping existing certificate.")
                            return false
                        }

                        val password = CERT_PASSWORD.toCharArray()
                        val keyStore = KeyStore.getInstance("PKCS12")
                        FileInputStream(tempFile).use { stream ->
                            keyStore.load(stream, password)
                        }

                        if (targetFile.exists()) {
                            targetFile.delete()
                        }

                        if (!tempFile.renameTo(targetFile)) {
                            tempFile.copyTo(targetFile, overwrite = true)
                            tempFile.delete()
                        }

                        Log.i(TAG, "[Certificate Sync] Downloaded and verified castla.p12 from authenticated API.")
                        true
                    }

                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN -> {
                        Log.e(TAG, "[Certificate Sync] Unauthorized. Check CASTLA_CERT_TOKEN.")
                        false
                    }

                    HttpURLConnection.HTTP_NOT_FOUND -> {
                        Log.e(TAG, "[Certificate Sync] Certificate API returned 404. Check server cert path.")
                        false
                    }

                    else -> {
                        Log.w(TAG, "[Certificate Sync] Server returned HTTP ${connection.responseCode}. Keeping existing certificate.")
                        false
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            tempFile.delete()
            Log.w(TAG, "[Certificate Sync] Network or validation error. Keeping existing certificate.", e)
            false
        }
    }

    fun setRelayPublishIp(ip: String) {
        val nextIp = ip.takeIf { it.isNotBlank() } ?: "0.0.0.0"

        if (relayPublishIp == nextIp) {
            Log.i(TAG, "setRelayPublishIp unchanged: relayPublishIp=$relayPublishIp")
        } else {
            Log.i(TAG, "setRelayPublishIp: relayPublishIp $relayPublishIp -> $nextIp")
            relayPublishIp = nextIp
        }

        publishRelayDnsIfReady("setRelayPublishIp")
    } 

    private fun publishRelayDnsIfReady(reason: String) {
        val settings = com.castla.mirror.ui.StreamSettings.load(context)

        if (!settings.webCodecsEnabled) {
            Log.i(TAG, "Relay DNS publish skipped: WebCodecs is disabled (reason=$reason)")
            return
        }

        if (relayPublishIp.isBlank() || relayPublishIp == "0.0.0.0") {
            Log.i(TAG, "Relay DNS publish skipped: relayPublishIp is not ready ($relayPublishIp, reason=$reason)")
            return
        }

        Log.i(
            TAG,
            "Publishing relay DNS: ip=$relayPublishIp public=${relayDnsManager.getPublicEntryUrl()} relay=${relayDnsManager.getDeviceRelayUrl()} reason=$reason"
        )

        relayDnsManager.publishCurrentIpIfNeeded(
            force = true,
            preferredIp = relayPublishIp
        ) { success, publicUrl, relayUrl, ip ->
            Log.i(
                TAG,
                "Relay publish result success=$success publicUrl=$publicUrl relayUrl=$relayUrl ip=$ip reason=$reason"
            )

            if (success) {
                Log.i(TAG, "🚀 Public URL = $publicUrl")
                Log.i(TAG, "🔗 Device relay URL = $relayUrl")
            }
        }
    }

    // NanoWSD WebSocket.send() writes to a socket OutputStream. Never call it
    // from the Android main thread, otherwise StrictMode can throw
    // NetworkOnMainThreadException. Keep control messages serialized to preserve
    // ordering for the browser frontend.
    private val controlSendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MirrorControlSender").apply { isDaemon = true }
    }

    private fun sendControlSocketAsync(
        socket: ControlSocket,
        json: String,
        reason: String = "control_message"
    ) {
        controlSendExecutor.execute {
            try {
                if (shouldAcceptControlMessage(socket)) {
                    socket.send(json)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send $reason to control socket ${socket.debugId}", e)
                unregisterControlSocket(socket)
            }
        }
    }

    private var onTouchListener: ((TouchEvent) -> Unit)? = null
    private var onTouchResetListener: (() -> Unit)? = null
    private var onCodecModeListener: ((String) -> Unit)? = null
    private var onTextInputListener: ((String) -> Unit)? = null
    private var onKeyEventListener: ((Int) -> Unit)? = null
    private var onCompositionUpdateListener: ((Int, String) -> Unit)? = null
    private var onAudioCodecListener: ((String) -> Unit)? = null
    private var onLayoutUpdateListener: ((org.json.JSONArray) -> Unit)? = null
    private var onTapOutsideListener: (() -> Unit)? = null
    private var onPrimaryKeyframeRequest: ((Boolean) -> Unit)? = null
    private var onSecondaryKeyframeRequest: ((Boolean) -> Unit)? = null
    private var networkCongestionListener: (() -> Unit)? = null
    
    // Web Launcher specific listeners
    private var onGoHomeListener: (() -> Unit)? = null
    private var onAppLaunchListener: ((String, String?, String, Boolean) -> Unit)? = null
    private var onDisplayDensityListener: ((Float) -> Unit)? = null
    private var onQualityReportListener: ((Int, Double, Int) -> Unit)? = null
    private var onBubbleClosedListener: (() -> Unit)? = null

    // Track active connection status
    private var isBrowserConnected = false
    private var onBrowserConnectionListener: ((Boolean) -> Unit)? = null
    private var onBrowserRearmListener: (() -> Unit)? = null
    private var onBrowserTeardownListener: (() -> Unit)? = null
    private var onAudioSocketConnectedListener: (() -> Unit)? = null

    // Cached thermal status JSON — sent immediately to new control sockets
    // to prevent race where browser connects before thermal broadcast arrives.
    @Volatile private var cachedThermalJson: String? = null

    @Volatile private var primaryCodecMode: String = "h264"
    @Volatile private var secondaryCodecMode: String = "h264"

    private var cachedSpsPps: ByteArray? = null
    private val streamGenerations = ConcurrentHashMap<String, AtomicInteger>()
    private val firstFrameReady = ConcurrentHashMap<String, Boolean>()
    private val latestStreamMetadata = ConcurrentHashMap<String, String>()

    fun setLayoutUpdateListener(listener: (org.json.JSONArray) -> Unit) {
        onLayoutUpdateListener = listener
    }

    fun setTouchListener(listener: (TouchEvent) -> Unit) {
        onTouchListener = listener
    }

    fun setTouchResetListener(listener: () -> Unit) {
        onTouchResetListener = listener
    }

    fun setCodecModeListener(listener: (String) -> Unit) {
        onCodecModeListener = listener
    }

    fun setTextInputListener(listener: (String) -> Unit) {
        onTextInputListener = listener
    }

    fun setOnTapOutsideListener(listener: () -> Unit) {
        onTapOutsideListener = listener
    }

    fun setKeyEventListener(listener: (Int) -> Unit) {
        onKeyEventListener = listener
    }

    fun setCompositionUpdateListener(listener: (Int, String) -> Unit) {
        onCompositionUpdateListener = listener
    }

    fun setAudioCodecListener(listener: (String) -> Unit) {
        onAudioCodecListener = listener
    }

    fun setKeyframeRequester(channel: String = "primary", requester: (Boolean) -> Unit) {
        if (channel == "secondary") onSecondaryKeyframeRequest = requester else onPrimaryKeyframeRequest = requester
    }
    
    fun setNetworkCongestionListener(listener: () -> Unit) {
        networkCongestionListener = listener
    }

    fun setBrowserConnectionListener(listener: ((Boolean) -> Unit)?) {
        onBrowserConnectionListener = listener
        // Fire immediately if already connected
        if (isBrowserConnected) listener?.invoke(true)
    }

    fun setBrowserRearmListener(listener: (() -> Unit)?) {
        onBrowserRearmListener = listener
    }

    fun setBrowserTeardownListener(listener: (() -> Unit)?) {
        onBrowserTeardownListener = listener
    }

    fun setAudioSocketConnectedListener(listener: (() -> Unit)?) {
        onAudioSocketConnectedListener = listener
    }

    fun setGoHomeListener(listener: () -> Unit) {
        onGoHomeListener = listener
    }
    
    fun setAppLaunchListener(listener: (String, String?, String, Boolean) -> Unit) {
        onAppLaunchListener = listener
    }

    fun setDisplayDensityListener(listener: (Float) -> Unit) {
        onDisplayDensityListener = listener
    }

    fun setQualityReportListener(listener: (Int, Double, Int) -> Unit) {
        onQualityReportListener = listener
    }

    fun setBubbleClosedListener(listener: () -> Unit) {
        onBubbleClosedListener = listener
    }

    fun isBrowserConnected(): Boolean = isBrowserConnected


    fun onDisplayDensityChange(scale: Float) {
        onDisplayDensityListener?.invoke(scale)
    }

    private fun updateConnectionState() {
        val connected = primaryVideoSockets.isNotEmpty() || secondaryVideoSockets.isNotEmpty() || controlSocketCount() > 0
        if (connected != isBrowserConnected) {
            isBrowserConnected = connected
            if (!connected) {
                MirrorDiagnostics.log(DiagnosticEvent.SOCKET_DISCONNECTED,
                    "all browser sockets closed")
            }
            onBrowserConnectionListener?.invoke(connected)
        }
    }

    fun registerVideoSocket(channel: String, socket: VideoStreamSocket) {
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        sockets.add(socket)

        // Playback cached H.264 SPS/PPS parameters ONLY if the display channel is not configured for MJPEG fallback mode.
        val codecMode = if (channel == "secondary") secondaryCodecMode else primaryCodecMode
        if (!codecMode.equals("mjpeg", ignoreCase = true)) {
            val cached = if (channel == "secondary") cachedSecondarySpsPps else cachedPrimarySpsPps
            cached?.let {
                socket.sendBinary(it)
                Log.i(TAG, "Sent cached SPS/PPS to new $channel video client")
            }
        } else {
            Log.i(TAG, "Skipped playback of cached H.264 SPS/PPS for $channel channel due to active MJPEG mode")
        }

        updateConnectionState()
        onKeyframeRequest(channel, "video_open")
    }

    fun unregisterVideoSocket(channel: String, socket: VideoStreamSocket) {
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        sockets.remove(socket)
        updateConnectionState()
    }

    fun registerControlSocket(socket: ControlSocket): Int {
        val staleSockets = mutableListOf<ControlSocket>()
        val sessionId: Int
        val epoch: Int
        synchronized(controlSocketLock) {
            sessionId = activeControlSessionId.incrementAndGet()
            epoch = browserConnectionEpoch.incrementAndGet()
            socket.attachSession(sessionId)
            controlSockets.remove(socket)
            controlSockets.add(socket)
            staleSockets += controlSockets.filter { it !== socket }
            staleSockets.forEach { stale ->
                stale.markInactive("superseded_by_session_$sessionId")
            }
            controlSockets.retainAll(setOf(socket))
            activeControlSocket = socket
        }
        staleSockets.forEach { stale ->
            try {
                stale.close(
                    NanoWSD.WebSocketFrame.CloseCode.NormalClosure,
                    "Superseded by control session $sessionId",
                    false
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close stale control socket ${stale.debugId}", e)
            }
        }
        Log.i(
            TAG,
            "Control client connected total=${controlSocketCount()} primaryVideo=${primaryVideoSockets.size} secondaryVideo=${secondaryVideoSockets.size} audio=${audioSockets.size}"
        )

        // Send serverInit greeting with unique instanceId. WebSocket.send() is
        // always dispatched off the main thread.
        val initMsg = JSONObject().apply {
            put("type", "serverInit")
            put("instanceId", instanceId)
            put("controlSessionId", sessionId)
        }
        sendControlSocketAsync(socket, initMsg.toString(), "serverInit")

        // Replay cached thermal status to new client immediately
        cachedThermalJson?.let { json ->
            sendControlSocketAsync(socket, json, "cached thermal status")
        }

        var replayedMetadata = 0
        latestStreamMetadata.values.forEach { json ->
            sendControlSocketAsync(socket, json, "stream metadata replay")
            replayedMetadata++
        }
        if (replayedMetadata > 0) {
            Log.i(TAG, "Replayed $replayedMetadata cached stream metadata message(s) to new control socket")
        }

        updateConnectionState()
        return sessionId
    }

    fun unregisterControlSocket(socket: ControlSocket) {
        synchronized(controlSocketLock) {
            controlSockets.remove(socket)
            if (activeControlSocket === socket) {
                activeControlSocket = null
            }
            socket.markUnregistered("unregister")
        }
        Log.i(
            TAG,
            "Control client disconnected total=${controlSocketCount()} primaryVideo=${primaryVideoSockets.size} secondaryVideo=${secondaryVideoSockets.size} audio=${audioSockets.size}"
        )
        updateConnectionState()
    }

    fun registerAudioSocket(socket: AudioStreamSocket) {
        audioSockets.add(socket)
        onAudioSocketConnectedListener?.invoke()
        cachedAudioConfig?.let {
            socket.sendBinary(it)
            Log.i(TAG, "Replayed audio config to new client (${it.size} bytes)")
        }
    }

    fun unregisterAudioSocket(socket: AudioStreamSocket) {
        audioSockets.remove(socket)
    }

    private var primaryFrameSeqNum: Int = 0
    private var secondaryFrameSeqNum: Int = 0

    fun beginStreamGeneration(channel: String = "primary", vdId: Int, width: Int, height: Int): Int {
        val normalized = normalizeChannel(channel)
        val generation = streamGenerations
            .getOrPut(normalized) { AtomicInteger(0) }
            .incrementAndGet()
        firstFrameReady[normalized] = false
        broadcastStreamMetadata(normalized, vdId, generation, width, height, streamReady = true, firstFrame = false)
        return generation
    }

    fun markFirstFrameReady(channel: String = "primary", vdId: Int, width: Int, height: Int) {
        val normalized = normalizeChannel(channel)
        if (firstFrameReady[normalized] == true) return
        firstFrameReady[normalized] = true
        val generation = streamGenerations[normalized]?.get() ?: 0
        broadcastStreamMetadata(normalized, vdId, generation, width, height, streamReady = true, firstFrame = true)
    }

    fun pauseStream(channel: String = "primary", vdId: Int, width: Int, height: Int) {
        val normalized = normalizeChannel(channel)
        firstFrameReady[normalized] = false
        val generation = streamGenerations[normalized]?.get() ?: 0
        broadcastStreamMetadata(normalized, vdId, generation, width, height, streamReady = false, firstFrame = false)
    }

    private fun broadcastStreamMetadata(
        channel: String,
        vdId: Int,
        generation: Int,
        width: Int,
        height: Int,
        streamReady: Boolean,
        firstFrame: Boolean
    ) {
        val json = JSONObject().apply {
            put("type", "streamMetadata")
            put("sessionId", channel)
            put("vdId", vdId)
            put("generation", generation)
            put("width", width)
            put("height", height)
            put("streamReady", streamReady)
            put("firstFrameReady", firstFrame)
        }
        val payload = json.toString()
        latestStreamMetadata[channel] = payload
        Log.i(TAG, "Stream metadata: channel=$channel vdId=$vdId generation=$generation ${width}x$height streamReady=$streamReady firstFrame=$firstFrame")
        broadcastControlMessage(payload)
    }

    private fun fillVideoHeader(data: ByteArray, flags: Byte, seq: Int) {
        val tsMs = android.os.SystemClock.elapsedRealtime().toInt()
        data[0] = flags
        data[1] = (seq and 0xFF).toByte()
        data[2] = ((seq shr 8) and 0xFF).toByte()
        data[3] = (tsMs and 0xFF).toByte()
        data[4] = ((tsMs shr 8) and 0xFF).toByte()
        data[5] = ((tsMs shr 16) and 0xFF).toByte()
        data[6] = ((tsMs shr 24) and 0xFF).toByte()
        data[7] = 0.toByte() // reserved
    }

    private fun buildVideoHeader(flags: Byte, seq: Int): ByteArray {
        val tsMs = android.os.SystemClock.elapsedRealtime().toInt()
        val buf = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(flags)
        buf.putShort(seq.toShort())  // seqLo, seqHi (2 bytes LE)
        buf.putInt(tsMs)             // tsMs_0~3 (4 bytes LE)
        buf.put(0.toByte())          // reserved
        return buf.array()
    }

    private var cachedPrimarySpsPps: ByteArray? = null
    private var cachedSecondarySpsPps: ByteArray? = null

    fun broadcastSpsPps(data: ByteArray, channel: String = "primary") {
        val buffer = ByteArray(8 + data.size)
        fillVideoHeader(buffer, 0x02, 0)
        System.arraycopy(data, 0, buffer, 8, data.size)
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        if (channel == "secondary") cachedSecondarySpsPps = buffer else cachedPrimarySpsPps = buffer

        val deadSockets = mutableListOf<VideoStreamSocket>()
        for (socket in sockets) {
            try {
                socket.sendBinary(buffer)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterVideoSocket(channel, it) }
    }

    // Explicitly clear cached SPS/PPS buffers during codec mode switches to prevent stale H.264 packets leaking.
    fun clearCachedSpsPps(channel: String = "primary") {
        if (channel == "secondary") {
            cachedSecondarySpsPps = null
        } else {
            cachedPrimarySpsPps = null
        }
        Log.i(TAG, "Cleared cached SPS/PPS for $channel channel")
    }

    fun broadcastFrame(data: ByteArray, isKeyFrame: Boolean, channel: String = "primary") {
        val normalized = normalizeChannel(channel)
        val seq = if (normalized == "secondary") ++secondaryFrameSeqNum else ++primaryFrameSeqNum
        val flags: Byte = if (isKeyFrame) 0x01 else 0x00
        
        // Check if this is a pre-allocated array from VideoEncoder (size > 8)
        val frame = if (data.size > 8 && data[8] == 0.toByte() && data[9] == 0.toByte() && data[10] == 0.toByte() && data[11] == 1.toByte()) {
            // New VideoEncoder: The 8-byte padding is already at the start, just fill it in
            fillVideoHeader(data, flags, seq)
            data
        } else if (data.size > 8 && (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 0.toByte())) {
            // New VideoEncoder: The 8 bytes are empty. Fill them.
            fillVideoHeader(data, flags, seq)
            data
        } else {
            // Fallback for MJPEG Encoder or old pipelines that don't pre-allocate 8 bytes
            val header = buildVideoHeader(flags, seq)
            header + data
        }

        val sockets = if (normalized == "secondary") secondaryVideoSockets else primaryVideoSockets
        val deadSockets = mutableListOf<VideoStreamSocket>()
        for (socket in sockets) {
            try {
                socket.sendBinary(frame)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterVideoSocket(normalized, it) }
    }

    private fun normalizeChannel(channel: String): String {
        return if (channel == "secondary") "secondary" else "primary"
    }

    private var cachedAudioConfig: ByteArray? = null

    fun broadcastAudio(data: ByteArray) {
        if (data.isNotEmpty() && data[0] == 0x00.toByte()) {
            cachedAudioConfig = data
        }

        val deadSockets = mutableListOf<AudioStreamSocket>()
        for (socket in audioSockets) {
            try {
                socket.sendBinary(data)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterAudioSocket(it) }
    }

    @Volatile private var preferredPrimaryProfile: String = "High"
    @Volatile private var preferredSecondaryProfile: String = "High"

    fun getPreferredProfile(channel: String): String {
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        val socketProfile = sockets.firstOrNull()?.profile
        if (socketProfile != null) return socketProfile
        
        return if (channel == "secondary") preferredSecondaryProfile else preferredPrimaryProfile
    }

    fun hasVideoSocket(channel: String): Boolean {
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        return sockets.isNotEmpty()
    }

    fun controlSocketCount(): Int = synchronized(controlSocketLock) {
        controlSockets.count { it.registered && it.active && it.sessionId == activeControlSessionId.get() }
    }
    fun videoSocketCount(channel: String): Int = if (channel == "secondary") secondaryVideoSockets.size else primaryVideoSockets.size
    fun audioSocketCount(): Int = audioSockets.size
    fun controlSocketRegistrySize(): Int = synchronized(controlSocketLock) { controlSockets.size }
    fun socketDebugSummary(): String =
        "epoch=${browserConnectionEpoch.get()} controlActive=${controlSocketCount()} controlRegistry=${controlSocketRegistrySize()} " +
            "primaryVideo=${primaryVideoSockets.size} secondaryVideo=${secondaryVideoSockets.size} audio=${audioSockets.size}"
    fun layoutDebugSummary(): String =
        "layoutReceived=${layoutUpdateReceivedCount.get()} layoutRelayed=${layoutUpdateRelayedCount.get()} layoutDeduped=${layoutUpdateDedupedCount.get()}"

    fun shouldAcceptControlMessage(socket: ControlSocket): Boolean = synchronized(controlSocketLock) {
        val activeSocket = activeControlSocket
        val activeSession = activeControlSessionId.get()
        val accepted = activeSocket === socket &&
            socket.registered &&
            socket.active &&
            socket.sessionId == activeSession &&
            controlSockets.contains(socket)
        accepted
    }

    fun ensureActiveControlSocket(socket: ControlSocket): Boolean {
        synchronized(controlSocketLock) {
            val activeSocket = activeControlSocket
            val activeSession = activeControlSessionId.get()
            val alreadyAccepted = activeSocket === socket &&
                socket.registered &&
                socket.active &&
                socket.sessionId == activeSession &&
                controlSockets.contains(socket)
            if (alreadyAccepted) {
                return true
            }
            if (activeSocket == null) {
                val adoptedSessionId = activeControlSessionId.incrementAndGet()
                socket.attachSession(adoptedSessionId)
                controlSockets.remove(socket)
                controlSockets.add(socket)
                activeControlSocket = socket
                val initMsg = JSONObject().apply {
                    put("type", "serverInit")
                    put("instanceId", instanceId)
                    put("controlSessionId", adoptedSessionId)
                }
                sendControlSocketAsync(socket, initMsg.toString(), "orphan serverInit")
                updateConnectionState()
                return true
            }
        }
        return false
    }

    fun logStaleControlMessage(socket: ControlSocket, messageCount: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = staleControlLogTimes[socket.debugId] ?: 0L
        if (now - last < 2000L) return
        staleControlLogTimes[socket.debugId] = now
        val activeSocket = synchronized(controlSocketLock) { activeControlSocket }
        val activeSession = activeControlSessionId.get()
    }

    fun broadcastControlMessage(json: String) {
        // Cache thermal status so new control sockets receive it immediately
        if (json.contains("\"thermalStatus\"")) {
            cachedThermalJson = json
        }
        val socket = synchronized(controlSocketLock) { activeControlSocket }
        if (socket == null || !shouldAcceptControlMessage(socket)) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSkippedBroadcastLogAt >= 2000L) {
                lastSkippedBroadcastLogAt = now
            }
            return
        }
        sendControlSocketAsync(socket, json, "broadcast control message")
    }

    fun broadcastDiagnostics() {
        broadcastControlMessage(JSONObject().apply {
            put("type", "diagnostics")
        }.toString())
    }
    
    // Callbacks from ControlSocket
    fun onTouchEvent(event: TouchEvent) {
        onTouchListener?.invoke(event)
    }

    fun onTapOutside() {
        onTapOutsideListener?.invoke()
    }

    fun onTouchReset() {
        onTouchResetListener?.invoke()
    }
    
    fun onKeyframeRequest(channel: String = "primary", source: String = "unknown") {
        val normalized = normalizeChannel(channel)
        val requestCount = keyframeRequestCount.incrementAndGet()

        // Keep the original functional behavior: every keyframe request still reaches
        // the encoder. Only throttle noisy SPS/PPS replay logs.
        val codecMode = if (normalized == "secondary") secondaryCodecMode else primaryCodecMode
        if (!codecMode.equals("mjpeg", ignoreCase = true)) {
            val cached = if (normalized == "secondary") cachedSecondarySpsPps else cachedPrimarySpsPps
            cached?.let { spsPps ->
                val sockets = if (normalized == "secondary") secondaryVideoSockets else primaryVideoSockets
                val deadSockets = mutableListOf<VideoStreamSocket>()
                var replayed = 0

                for (socket in sockets) {
                    try {
                        socket.sendBinary(spsPps)
                        replayed++
                    } catch (e: Exception) {
                        deadSockets.add(socket)
                    }
                }

                deadSockets.forEach { unregisterVideoSocket(normalized, it) }

                val now = android.os.SystemClock.elapsedRealtime()
                val lastLogAt = lastSpsPpsReplayLogAtByChannel[normalized] ?: 0L
                if (replayed > 0 && now - lastLogAt >= 3000L) {
                    lastSpsPpsReplayLogAtByChannel[normalized] = now
                    Log.i(
                        TAG,
                        "Replayed cached SPS/PPS to $replayed $normalized video socket(s) on keyframe request source=$source count=$requestCount"
                    )
                }
            }
        }

        val force = source == "video_open" || source.contains("socket") || source.contains("reconnect")
        if (normalized == "secondary") {
            onSecondaryKeyframeRequest?.invoke(force)
        } else {
            onPrimaryKeyframeRequest?.invoke(force)
        }
    }
    
    fun onNetworkCongestion() {
        networkCongestionListener?.invoke()
    }
    
    fun onCodecModeRequest(mode: String, profile: String = "High", pane: String = "primary") {
        if (pane.equals("secondary", ignoreCase = true)) {
            preferredSecondaryProfile = profile
            secondaryCodecMode = mode
            if (mode.equals("mjpeg", ignoreCase = true)) {
                clearCachedSpsPps("secondary")
            }
        } else {
            preferredPrimaryProfile = profile
            primaryCodecMode = mode
            if (mode.equals("mjpeg", ignoreCase = true)) {
                clearCachedSpsPps("primary")
            }
        }
        onCodecModeListener?.invoke(mode)
    }
    
    fun onLayoutUpdate(pipelines: org.json.JSONArray) {
        layoutUpdateReceivedCount.incrementAndGet()
        val summary = buildString {
            append("layout_update relay")
            for (i in 0 until pipelines.length()) {
                val pane = pipelines.optJSONObject(i) ?: continue
                append(" | ")
                append(pane.optString("id", "?"))
                append("=")
                append(pane.optInt("width", 0))
                append("x")
                append(pane.optInt("height", 0))
                append(" visible=")
                append(pane.optBoolean("visible", true))
            }
        }
        if (summary == lastLayoutUpdateSignature) {
            layoutUpdateDedupedCount.incrementAndGet()
            return
        }
        lastLayoutUpdateSignature = summary
        layoutUpdateRelayedCount.incrementAndGet()
        Log.i(TAG, summary)
        onLayoutUpdateListener?.invoke(pipelines)
    }
    
    fun onTextInput(text: String) {
        onTextInputListener?.invoke(text)
    }
    
    fun onKeyEvent(keyCode: Int) {
        onKeyEventListener?.invoke(keyCode)
    }
    
    fun onCompositionUpdate(backspaces: Int, text: String) {
        onCompositionUpdateListener?.invoke(backspaces, text)
    }
    
    fun onGoHomeRequest() {
        onGoHomeListener?.invoke()
    }

    fun onBrowserRearmRequest() {
        onBrowserRearmListener?.invoke()
    }

    fun onBrowserTeardownRequest() {
        onBrowserTeardownListener?.invoke()
    }

    fun debugCycleSockets() {
        val controlToClose: ControlSocket?
        val primaryToClose: List<VideoStreamSocket>
        val secondaryToClose: List<VideoStreamSocket>
        val audioToClose: List<AudioStreamSocket>
        synchronized(controlSocketLock) {
            controlToClose = activeControlSocket
        }
        primaryToClose = primaryVideoSockets.toList()
        secondaryToClose = secondaryVideoSockets.toList()
        audioToClose = audioSockets.toList()
        primaryToClose.forEach { socket ->
            try {
                socket.close(
                    NanoWSD.WebSocketFrame.CloseCode.NormalClosure,
                    "Debug socket cycle",
                    false
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close primary video socket during debug cycle", e)
            }
        }
        secondaryToClose.forEach { socket ->
            try {
                socket.close(
                    NanoWSD.WebSocketFrame.CloseCode.NormalClosure,
                    "Debug socket cycle",
                    false
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close secondary video socket during debug cycle", e)
            }
        }
        audioToClose.forEach { socket ->
            try {
                socket.close(
                    NanoWSD.WebSocketFrame.CloseCode.NormalClosure,
                    "Debug socket cycle",
                    false
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close audio socket during debug cycle", e)
            }
        }
        try {
            controlToClose?.close(
                NanoWSD.WebSocketFrame.CloseCode.NormalClosure,
                "Debug socket cycle",
                false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close control socket during debug cycle", e)
        }
    }
    
    fun onAudioCodecRequest(codec: String) {
        onAudioCodecListener?.invoke(codec)
    }
    
    fun onAppLaunchRequest(pkg: String, componentName: String? = null, pane: String = "primary", isVideoApp: Boolean ) {
        onAppLaunchListener?.invoke(pkg, componentName, pane, isVideoApp)
    }

    fun onQualityReport(droppedFrames: Int, avgDelayMs: Double, backlogDrops: Int) {
        onQualityReportListener?.invoke(droppedFrames, avgDelayMs, backlogDrops)
    }

    fun onBubbleClosed() {
        onBubbleClosedListener?.invoke()
    }


    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        Log.i(TAG, "openWebSocket uri=${handshake.uri} params=${handshake.parameters}")

        val uri = handshake.uri
        val channel = handshake.parameters["channel"]?.firstOrNull()
            ?: if (uri.contains("secondary")) "secondary" else "primary"

        return when {
            uri.startsWith("/ws/video") -> {
                Log.i(TAG, "Creating VideoStreamSocket channel=$channel")
                VideoStreamSocket(handshake, this, channel)
            }
            uri.startsWith("/ws/control") -> {
                Log.i(TAG, "Creating ControlSocket")
                ControlSocket(handshake, this)
            }
            uri.startsWith("/ws/audio") -> {
                Log.i(TAG, "Creating AudioStreamSocket")
                AudioStreamSocket(handshake, this)
            }
            else -> {
                Log.w(TAG, "Unknown websocket uri=$uri. Falling back to video channel=$channel")
                VideoStreamSocket(handshake, this, channel)
            }
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        var uri = session.uri
        if (uri == "/") uri = "/index.html"
        
        // Handle API routes for Native Web Launcher
        if (uri == "/api/apps") {
            return serveAppList()
        } else if (uri.startsWith("/api/icon")) {
            val pkg = session.parameters["pkg"]?.firstOrNull()
            if (pkg != null) {
                return serveAppIcon(pkg)
            }
        }
        
        return serveAsset(uri)
    }
    
    private fun serveAppList(): Response {
        try {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_ALL)
            
            val jsonArray = org.json.JSONArray()
            resolveInfos.forEach { ri ->
                if (ri.activityInfo.packageName != context.packageName) {
                    val obj = JSONObject().apply {
                        val pkgName = ri.activityInfo.packageName
                        val className = ri.activityInfo.name
                        val componentName = android.content.ComponentName(pkgName, className)
                            .flattenToShortString()
                        val label = ri.loadLabel(pm).toString()
                        put("packageName", pkgName)
                        put("className", className)
                        put("componentName", componentName)
                        put("label", label)
                        put("category", AppCategoryClassifier.classify(pkgName, label))
                        
                        // Check if it's a DRM-restricted OTT app
                        val ottTarget = OttCatalog.resolve(pkgName)
                        put("isWeb", ottTarget != null)
                        put("webUrl", ottTarget?.webUrl ?: JSONObject.NULL)
                        put("launchMode", if (ottTarget != null) "EXTERNAL_BROWSER_URL" else "STANDARD_APP")
                    }
                    jsonArray.put(obj)
                }
            }
            
            val responseObj = JSONObject().apply {
                put("isPremium", true)
                put("fitMode", "contain")
                put("autoFit", true)
                put("layoutMode", "single")
                put("apps", jsonArray)
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", responseObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serve app list", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }
    
    private fun serveAppIcon(packageName: String): Response {
        try {
            val pm = context.packageManager
            val icon = pm.getApplicationIcon(packageName)
            val bmp = android.graphics.Bitmap.createBitmap(
                icon.intrinsicWidth.coerceAtLeast(1), 
                icon.intrinsicHeight.coerceAtLeast(1), 
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            icon.setBounds(0, 0, canvas.width, canvas.height)
            icon.draw(canvas)
            
            val stream = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            val bytes = stream.toByteArray()
            
            return newFixedLengthResponse(Response.Status.OK, "image/png", java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Icon not found")
        }
    }

    private fun serveAsset(uri: String): Response {
        return try {
            var path = uri.substringBefore('?').trimStart('/')
            if (path.isEmpty()) path = "index.html"
            val stream = context.assets.open("web/$path")
            val mimeType = when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".ico") -> "image/x-icon"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                else -> "application/octet-stream"
            }
            val response = newChunkedResponse(Response.Status.OK, mimeType, stream)
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Expires", "0")
            response
        } catch (e: Exception) {
            val response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Expires", "0")
            response
        }
    }

}

private class LoggingServerSocket(private val delegate: java.net.ServerSocket) : java.net.ServerSocket() {
    override fun accept(): java.net.Socket {
        android.util.Log.i("MirrorServer", "Waiting for connection...")
        val socket = delegate.accept()
        android.util.Log.i("MirrorServer", "Client connected: ${socket.inetAddress}:${socket.port}")
        if (socket is javax.net.ssl.SSLSocket) {
            android.util.Log.i("MirrorServer", "Starting TLS handshake...")
        }
        return socket
    }

    override fun bind(endpoint: java.net.SocketAddress?) = delegate.bind(endpoint)
    override fun bind(endpoint: java.net.SocketAddress?, backlog: Int) = delegate.bind(endpoint, backlog)
    override fun getInetAddress(): java.net.InetAddress = delegate.inetAddress
    override fun getLocalPort(): Int = delegate.localPort
    override fun getLocalSocketAddress(): java.net.SocketAddress = delegate.localSocketAddress
    override fun close() = delegate.close()
    override fun isClosed(): Boolean = delegate.isClosed
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
}

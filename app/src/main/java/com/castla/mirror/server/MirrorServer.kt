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
import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.MirrorDiagnostics
import com.castla.mirror.utils.AppCategoryClassifier
import com.castla.mirror.ott.OttCatalog

data class TouchEvent(val action: String, val x: Float, val y: Float, val pointerId: Int, val pane: String = "primary")

class MirrorServer(private val context: Context) : NanoWSD(DEFAULT_PORT) {

    val instanceId: String = java.util.UUID.randomUUID().toString()

    init {
        // [기존 코드] NanoHTTPD 소켓 닫힘 예외 로그 필터링
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

        // 🔐 [개선된 100% 자동화 SSL 로드 로직]
        // 주서버(NAS 등)에서 인증서를 받아오는 비동기 작업 선행 후 SSL 컨텍스트 구성
        configureSecureContext()
    }

    private var serverIp: String = "0.0.0.0"

    /**
     * 동적 인증서 파일 검증 및 SSL 설정 적용
     */
    private fun configureSecureContext() {
        // 1단계에서 구축한 오라클 백엔드 다운로드 트리거
        triggerCertDownloadInBackground()

        // 🚨 현재 폰의 핫스팟/셀룰러 IP 체크
        val currentIp = serverIp 

        if (currentIp == "192.0.0.4") {
            try {
                val password = "castla123".toCharArray() 
                val keyStore = KeyStore.getInstance("PKCS12")
                val dynamicKeyStoreFile = File(context.filesDir, "dynamic_castla.p12")
                
                val keystoreStream: InputStream = if (dynamicKeyStoreFile.exists() && dynamicKeyStoreFile.length() > 0) {
                    Log.i(TAG, "🔓 [성공] 192.0.0.4 일치: 공인 인증서 로드")
                    FileInputStream(dynamicKeyStoreFile)
                } else {
                    context.assets.open("castla.p12")
                }

                keystoreStream.use { stream -> keyStore.load(stream, password) }
                val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keyManagerFactory.init(keyStore, password)

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(keyManagerFactory.keyManagers, null, null)

                // ✅ 192.0.0.4 일 때만 SSL 소켓 바인딩 (HTTPS)
                makeSecure(sslContext.serverSocketFactory, null)
                Log.i(TAG, "🚀 [🚀 HTTPS 모드] Let's Encrypt 공인 SSL 서버 가동")
                return
            } catch (e: Exception) {
                Log.e(TAG, "❌ SSL 로드 실패, HTTP 모드로 폴백합니다.", e)
            }
        }

        // 🌐 192.0.0.4가 아니면 makeSecure()를 호출하지 않으므로 자동으로 [순수 HTTP 모드]로 동작합니다.
        Log.w(TAG, "⚠️ [🌐 HTTP 모드] IP가 $currentIp 이므로 일반 HTTP 서버로 구동합니다.")
    }
    /**
     * 외부 내 개인 서버(NAS/클라우드)에서 최신 .p12 인증서를 다운로드하는 함수
     */
    fun triggerCertDownloadInBackground() {
        Thread {
            try {
                val certUrl = "https://tesla.fbezita.com/certs/castla.p12"
                val url = URL(certUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val targetFile = File(context.filesDir, "dynamic_castla.p12")
                    
                    // 다운로드 받아서 내부 저장소에 덮어쓰기
                    connection.inputStream.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "✨ [인증서 동기화 완료] 서버로부터 최신 SSL 인증서를 다운로드했습니다.")
                } else {
                    Log.w(TAG, "서버 연결 실패 (HTTP 코드: ${connection.responseCode}). 기존 파일 유지.")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "인증서 다운로드 중 네트워크 예외 발생 (인터넷 미연결 등): ${e.message}")
            }
        }.start()
    }

    fun updateServerUrl(detectedIp: String) {
        serverIp = if (detectedIp.isNotEmpty()) detectedIp else "0.0.0.0"
    }

    companion object {
        private const val TAG = "MirrorServer"
        const val DEFAULT_PORT = 9090
    }

    private val primaryVideoSockets = mutableSetOf<VideoStreamSocket>()
    private val secondaryVideoSockets = mutableSetOf<VideoStreamSocket>()
    private val controlSockets = mutableSetOf<ControlSocket>()
    private val audioSockets = mutableSetOf<AudioStreamSocket>()

    private var onTouchListener: ((TouchEvent) -> Unit)? = null
    private var onCodecModeListener: ((String) -> Unit)? = null
    private var onViewportChangeListener: ((String, Int, Int, String) -> Unit)? = null
    private var onTextInputListener: ((String) -> Unit)? = null
    private var onKeyEventListener: ((Int) -> Unit)? = null
    private var onCompositionUpdateListener: ((Int, String) -> Unit)? = null
    private var onAudioCodecListener: ((String) -> Unit)? = null
    private var onPrimaryKeyframeRequest: (() -> Unit)? = null
    private var onSecondaryKeyframeRequest: (() -> Unit)? = null
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
    private var onAudioSocketConnectedListener: (() -> Unit)? = null

    // Cached thermal status JSON — sent immediately to new control sockets
    // to prevent race where browser connects before thermal broadcast arrives.
    @Volatile private var cachedThermalJson: String? = null

    /* ### 수정 시작 ### */
    @Volatile private var primaryCodecMode: String = "h264"
    @Volatile private var secondaryCodecMode: String = "h264"
    /* ### 수정 끝 ### */

    private var cachedSpsPps: ByteArray? = null

    fun setTouchListener(listener: (TouchEvent) -> Unit) {
        onTouchListener = listener
    }

    fun setCodecModeListener(listener: (String) -> Unit) {
        onCodecModeListener = listener
    }

    fun setViewportChangeListener(listener: (String, Int, Int, String) -> Unit) {
        onViewportChangeListener = listener
    }

    fun setTextInputListener(listener: (String) -> Unit) {
        onTextInputListener = listener
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

    fun setKeyframeRequester(channel: String = "primary", requester: () -> Unit) {
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
        val connected = primaryVideoSockets.isNotEmpty() || secondaryVideoSockets.isNotEmpty() || controlSockets.isNotEmpty()
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
        Log.i(TAG, "$channel video client connected (total: ${sockets.size})")

        /* ### 수정 시작 ### */
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
        /* ### 수정 끝 ### */

        updateConnectionState()
        onKeyframeRequest(channel)
    }

    fun unregisterVideoSocket(channel: String, socket: VideoStreamSocket) {
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        sockets.remove(socket)
        Log.i(TAG, "$channel video client disconnected (total: ${sockets.size})")
        updateConnectionState()
    }

    fun registerControlSocket(socket: ControlSocket) {
        controlSockets.add(socket)
        Log.i(TAG, "Control client connected (total: ${controlSockets.size})")

        // Send serverInit greeting with unique instanceId
        try {
            val initMsg = JSONObject().apply {
                put("type", "serverInit")
                put("instanceId", instanceId)
            }
            socket.send(initMsg.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send serverInit message", e)
        }

        // Replay cached thermal status to new client immediately
        cachedThermalJson?.let { json ->
            try { socket.send(json) }
            catch (e: Exception) { Log.w(TAG, "Failed to send cached thermal status", e) }
        }

        updateConnectionState()
    }

    fun unregisterControlSocket(socket: ControlSocket) {
        controlSockets.remove(socket)
        Log.i(TAG, "Control client disconnected (total: ${controlSockets.size})")
        updateConnectionState()
    }

    fun registerAudioSocket(socket: AudioStreamSocket) {
        audioSockets.add(socket)
        Log.i(TAG, "Audio client connected (total: ${audioSockets.size})")
        onAudioSocketConnectedListener?.invoke()
        cachedAudioConfig?.let {
            socket.sendBinary(it)
            Log.i(TAG, "Replayed audio config to new client (${it.size} bytes)")
        }
    }

    fun unregisterAudioSocket(socket: AudioStreamSocket) {
        audioSockets.remove(socket)
        Log.i(TAG, "Audio client disconnected (total: ${audioSockets.size})")
    }

    private var primaryFrameSeqNum: Int = 0
    private var secondaryFrameSeqNum: Int = 0

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

    /* ### 수정 시작 ### */
    // Explicitly clear cached SPS/PPS buffers during codec mode switches to prevent stale H.264 packets leaking.
    fun clearCachedSpsPps(channel: String = "primary") {
        if (channel == "secondary") {
            cachedSecondarySpsPps = null
        } else {
            cachedPrimarySpsPps = null
        }
        Log.i(TAG, "Cleared cached SPS/PPS for $channel channel")
    }
    /* ### 수정 끝 ### */

    fun broadcastFrame(data: ByteArray, isKeyFrame: Boolean, channel: String = "primary") {
        val seq = if (channel == "secondary") ++secondaryFrameSeqNum else ++primaryFrameSeqNum
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

        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        val deadSockets = mutableListOf<VideoStreamSocket>()
        for (socket in sockets) {
            try {
                socket.sendBinary(frame)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterVideoSocket(channel, it) }
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

    /* ### 수정 시작 ### */
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
    /* ### 수정 끝 ### */

    fun controlSocketCount(): Int = controlSockets.size

    fun broadcastControlMessage(json: String) {
        // Cache thermal status so new control sockets receive it immediately
        if (json.contains("\"thermalStatus\"")) {
            cachedThermalJson = json
        }
        val deadSockets = mutableListOf<ControlSocket>()
        for (socket in controlSockets) {
            try {
                socket.send(json)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterControlSocket(it) }
    }
    
    // Callbacks from ControlSocket
    fun onTouchEvent(event: TouchEvent) {
        onTouchListener?.invoke(event)
    }
    
    fun onKeyframeRequest(channel: String = "primary") {
        if (channel == "secondary") onSecondaryKeyframeRequest?.invoke() else onPrimaryKeyframeRequest?.invoke()
    }
    
    fun onNetworkCongestion() {
        networkCongestionListener?.invoke()
    }
    
    /* ### 수정 시작 ### */
    fun onCodecModeRequest(mode: String, profile: String = "High", pane: String = "primary") {
        /* ### 수정 시작 ### */
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
        /* ### 수정 끝 ### */
        onCodecModeListener?.invoke(mode)
    }
    /* ### 수정 끝 ### */
    
    fun onViewportChange(pane: String, width: Int, height: Int, layoutMode: String = "") {
        onViewportChangeListener?.invoke(pane, width, height, layoutMode)
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
        val uri = handshake.uri
        val channel = handshake.parameters["channel"]?.firstOrNull()
            ?: if (uri.contains("secondary")) "secondary" else "primary"

        return when {
            uri.startsWith("/ws/video") -> VideoStreamSocket(handshake, this, channel)
            uri.startsWith("/ws/control") -> ControlSocket(handshake, this)
            uri.startsWith("/ws/audio") -> AudioStreamSocket(handshake, this)
            else -> VideoStreamSocket(handshake, this, channel)
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

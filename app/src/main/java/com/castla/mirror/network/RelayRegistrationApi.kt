package com.castla.mirror.network

import android.util.Log
import android.os.SystemClock
import com.castla.mirror.diagnostics.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calls your backend instead of calling Cloudflare directly.
 *
 * Backend:
 *   POST https://car.fbezita.com/api/castla/relay
 *
 * Auth:
 *   Authorization: Bearer <CASTLA_CERT_TOKEN or dedicated relay token>
 */
class RelayRegistrationApi(
    private val endpointUrl: String = "https://car.fbezita.com/api/castla/relay",
    private val token: String
) {
    companion object {
        private const val TAG = "RelayRegistrationApi"
    }

    suspend fun updateRelay(
        deviceId: String,
        hostname: String,
        ip: String,
        relayUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        val startedAtMs = SystemClock.elapsedRealtime()
        FileLogger.i("RELAY_REGISTRATION", "request_start device=$deviceId host=$hostname ip=$ip")
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("hostname", hostname)
            put("ip", ip)
            put("relayUrl", relayUrl)
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = RelayRetryPolicy.CONNECT_TIMEOUT_MS
                readTimeout = RelayRetryPolicy.READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            val ok = code in 200..299 && JSONObject(text).optBoolean("success", false)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            if (ok) {
                Log.i(TAG, "✅ Relay registered: device=$deviceId host=$hostname ip=$ip active=$relayUrl")
                FileLogger.i("RELAY_REGISTRATION", "request_result success=true http=$code durationMs=$elapsedMs")
            } else {
                Log.e(TAG, "❌ Relay registration failed http=$code body=$text")
                FileLogger.e("RELAY_REGISTRATION", "request_result success=false http=$code durationMs=$elapsedMs")
            }
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Relay registration exception", e)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            FileLogger.e(
                "RELAY_REGISTRATION",
                "request_exception durationMs=$elapsedMs type=${e::class.java.simpleName} message=${e.message ?: ""}",
                e,
            )
            false
        } finally {
            conn?.disconnect()
        }
    }
}

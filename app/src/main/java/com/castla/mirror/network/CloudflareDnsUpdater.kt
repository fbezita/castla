package com.castla.mirror.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates Cloudflare DNS A record for relay.castla.fbezita.com.
 *
 * SECURITY NOTE:
 * Prefer calling your own backend API instead of embedding Cloudflare API token in the APK.
 * This class is usable for internal/debug builds or if token is fetched securely from your backend.
 */
class CloudflareDnsUpdater(
    private val zoneId: String,
    private val recordId: String,
    private val apiToken: String,
    private val recordName: String = "relay.castla.fbezita.com",
    private val ttl: Int = 60
) {
    companion object {
        private const val TAG = "CloudflareDnsUpdater"
    }

    suspend fun updateARecord(ip: String): Boolean = withContext(Dispatchers.IO) {
        val endpoint = "https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records/$recordId"
        val body = JSONObject().apply {
            put("type", "A")
            put("name", recordName)
            put("content", ip)
            put("ttl", ttl)
            put("proxied", false)
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiToken")
                setRequestProperty("Content-Type", "application/json")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            val responseText = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            val ok = code in 200..299 && JSONObject(responseText).optBoolean("success", false)
            if (ok) {
                Log.i(TAG, "✅ Cloudflare DNS updated: $recordName -> $ip")
            } else {
                Log.e(TAG, "❌ Cloudflare DNS update failed: http=$code body=$responseText")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "❌ Cloudflare DNS update exception", e)
            false
        } finally {
            conn?.disconnect()
        }
    }
}

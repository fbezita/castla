package com.castla.mirror.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Cloudflare DNS API helper.
 *
 * Recommended production design:
 *   Android app -> your backend -> Cloudflare
 *
 * Do not ship a global Cloudflare API token in a public APK.
 */
class CloudflareDnsApi(
    private val zoneId: String,
    private val apiToken: String
) {
    companion object {
        private const val TAG = "CloudflareDnsApi"
        private const val BASE = "https://api.cloudflare.com/client/v4"
    }

    suspend fun upsertARecord(
        hostname: String,
        ip: String,
        ttl: Int = 60,
        proxied: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val existingId = findARecordId(hostname)
            if (existingId == null) {
                createARecord(hostname, ip, ttl, proxied)
            } else {
                updateARecord(existingId, hostname, ip, ttl, proxied)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ upsert A record failed: $hostname -> $ip", e)
            false
        }
    }

    private fun findARecordId(hostname: String): String? {
        val url = "$BASE/zones/$zoneId/dns_records?type=A&name=${urlEncode(hostname)}"
        val result = request("GET", url, null)
        if (!result.success) {
            Log.e(TAG, "❌ find A record failed http=${result.code} body=${result.body}")
            return null
        }

        val arr = JSONObject(result.body).optJSONArray("result") ?: JSONArray()
        if (arr.length() == 0) return null
        return arr.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
    }

    private fun createARecord(hostname: String, ip: String, ttl: Int, proxied: Boolean): Boolean {
        val url = "$BASE/zones/$zoneId/dns_records"
        val body = dnsRecordBody(hostname, ip, ttl, proxied)
        val result = request("POST", url, body)
        val ok = result.success && JSONObject(result.body).optBoolean("success", false)
        if (ok) {
            Log.i(TAG, "✅ Created DNS A: $hostname -> $ip")
        } else {
            Log.e(TAG, "❌ Create DNS A failed http=${result.code} body=${result.body}")
        }
        return ok
    }

    private fun updateARecord(recordId: String, hostname: String, ip: String, ttl: Int, proxied: Boolean): Boolean {
        val url = "$BASE/zones/$zoneId/dns_records/$recordId"
        val body = dnsRecordBody(hostname, ip, ttl, proxied)
        val result = request("PUT", url, body)
        val ok = result.success && JSONObject(result.body).optBoolean("success", false)
        if (ok) {
            Log.i(TAG, "✅ Updated DNS A: $hostname -> $ip")
        } else {
            Log.e(TAG, "❌ Update DNS A failed http=${result.code} body=${result.body}")
        }
        return ok
    }

    private fun dnsRecordBody(hostname: String, ip: String, ttl: Int, proxied: Boolean): String {
        return JSONObject().apply {
            put("type", "A")
            put("name", hostname)
            put("content", ip)
            put("ttl", ttl)
            put("proxied", proxied)
        }.toString()
    }

    private fun request(method: String, urlString: String, body: String?): ApiResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Authorization", "Bearer $apiToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body) }
                }
            }

            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            ApiResult(code in 200..299, code, text)
        } finally {
            conn?.disconnect()
        }
    }

    private data class ApiResult(
        val success: Boolean,
        val code: Int,
        val body: String
    )

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}

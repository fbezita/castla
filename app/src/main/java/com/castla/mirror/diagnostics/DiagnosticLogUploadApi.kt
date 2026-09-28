package com.castla.mirror.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class DiagnosticLogUploadApi(
    private val endpointUrl: String = "https://car.fbezita.com/api/castla/logs",
    private val token: String,
) {
    suspend fun upload(
        deviceId: String,
        versionName: String,
        archive: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Diagnostic upload token is not configured" }

        val boundary = "castla-${UUID.randomUUID()}"
        val body = multipartBody(boundary, archive)
        var connection: HttpURLConnection? = null

        try {
            connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 30_000
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Castla-Device-Id", deviceId)
                setRequestProperty("X-Castla-Version", versionName)
            }
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            val responseText = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (status !in 200..299) throw IOException("Diagnostic upload failed: HTTP $status")

            val response = JSONObject(responseText)
            val reportId = response.optString("reportId")
            if (!response.optBoolean("success", false) || reportId.isBlank()) {
                throw IOException("Diagnostic upload returned an invalid response")
            }
            reportId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            connection?.disconnect()
        }
    }

    internal fun multipartBody(boundary: String, archive: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.write("--$boundary\r\n".toByteArray())
            output.write("Content-Disposition: form-data; name=\"log\"; filename=\"castla-logs.zip\"\r\n".toByteArray())
            output.write("Content-Type: application/zip\r\n\r\n".toByteArray())
            output.write(archive)
            output.write("\r\n--$boundary--\r\n".toByteArray())
            output.toByteArray()
        }
}

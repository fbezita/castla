package com.castla.mirror.server

import android.content.Context
import android.util.Log
import com.castla.mirror.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

internal class ServerTlsConfigurator(
    private val context: Context,
    private val updateAvailability: (MirrorServerAvailability) -> Unit,
) {
    data class SecureContext(val socketFactory: SSLServerSocketFactory, val certSource: String)

    fun prepare(): SecureContext? {
        refreshCertificateIfNeededBlocking()
        if (!com.castla.mirror.ui.StreamSettings.load(context).webCodecsEnabled) return null

        val password = certificatePasswordOrNull() ?: return null
        val dynamicKeyStoreFile = File(context.filesDir, DYNAMIC_CERT_FILE_NAME)
        val loaded = TlsKeystoreLoader.loadDynamicPkcs12WithRefresh(password, dynamicKeyStoreFile) {
            Log.w(TAG, "[TLS] dynamic_castla.p12 invalid or missing. Re-downloading certificate.")
            downloadCertIfAvailableBlocking()
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(loaded.keyStore, password)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, null, null)
        return SecureContext(sslContext.serverSocketFactory, loaded.source)
    }

    fun downloadCertIfAvailableBlocking(): Boolean {
        val password = certificatePasswordOrNull() ?: return false
        val certToken = BuildConfig.CASTLA_CERT_TOKEN.trim()
        if (certToken.isEmpty()) {
            reportError("cert_token_missing")
            Log.e(TAG, "[Certificate Sync] CASTLA_CERT_TOKEN is missing. Set it via local.properties or environment variables.")
            return false
        }
        val targetFile = File(context.filesDir, DYNAMIC_CERT_FILE_NAME)
        val tempFile = File(context.filesDir, "$DYNAMIC_CERT_FILE_NAME.tmp")
        return try {
            val connection = (URL(CERT_API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $certToken")
            }
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        connection.inputStream.use { input ->
                            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                        }
                        if (!tempFile.exists() || tempFile.length() <= 0L) {
                            tempFile.delete()
                            Log.w(TAG, "[Certificate Sync] Empty p12 downloaded. Keeping existing certificate.")
                            return false
                        }
                        val keyStore = KeyStore.getInstance("PKCS12")
                        FileInputStream(tempFile).use { keyStore.load(it, password) }
                        if (targetFile.exists()) targetFile.delete()
                        if (!tempFile.renameTo(targetFile)) {
                            tempFile.copyTo(targetFile, overwrite = true)
                            tempFile.delete()
                        }
                        Log.i(TAG, "[Certificate Sync] Downloaded and verified castla.p12 from authenticated API.")
                        true
                    }
                    HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> {
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

    private fun refreshCertificateIfNeededBlocking(): Boolean {
        val password = certificatePasswordOrNull() ?: return false
        val targetFile = File(context.filesDir, DYNAMIC_CERT_FILE_NAME)
        val nowMs = System.currentTimeMillis()
        val certificateNotAfterMs = try {
            TlsKeystoreLoader.readCertificateNotAfterMs(password, targetFile)
        } catch (_: Exception) {
            null
        }
        val lastRefreshCheckMs = readLastCertificateRefreshCheckMs()
        if (!TlsCertificateRefreshPolicy.shouldRefresh(nowMs, certificateNotAfterMs, lastRefreshCheckMs)) {
            Log.i(TAG, "[Certificate Sync] Reusing cached certificate. expiresAt=$certificateNotAfterMs lastCheckAt=$lastRefreshCheckMs")
            return false
        }
        if (BuildConfig.CASTLA_CERT_TOKEN.trim().isEmpty()) {
            reportError("cert_token_missing")
            Log.e(TAG, "[Certificate Sync] CASTLA_CERT_TOKEN is missing. Set it via local.properties or environment variables.")
            return false
        }
        val downloaded = downloadCertIfAvailableBlocking()
        if (downloaded || certificateNotAfterMs?.let { it > nowMs } == true) {
            writeLastCertificateRefreshCheckMs(nowMs)
        }
        return downloaded
    }

    private fun certificatePasswordOrNull(): CharArray? {
        val password = BuildConfig.CASTLA_CERT_PASSWORD.trim()
        if (password.isNotEmpty()) return password.toCharArray()
        reportError("cert_password_missing")
        Log.e(TAG, "[Certificate Sync] CASTLA_CERT_PASSWORD is missing. Set it via local.properties or environment variables.")
        return null
    }

    private fun readLastCertificateRefreshCheckMs(): Long? {
        val file = File(context.filesDir, DYNAMIC_CERT_LAST_CHECK_FILE_NAME)
        if (!file.exists()) return null
        return try { file.readText().trim().toLongOrNull() } catch (_: IOException) { null }
    }

    private fun writeLastCertificateRefreshCheckMs(timestampMs: Long) {
        try {
            File(context.filesDir, DYNAMIC_CERT_LAST_CHECK_FILE_NAME).writeText(timestampMs.toString())
        } catch (e: IOException) {
            Log.w(TAG, "[Certificate Sync] Failed to persist last refresh check timestamp.", e)
        }
    }

    private fun reportError(detail: String) {
        updateAvailability(MirrorServerAvailability(MirrorServerAvailabilityState.ERROR, detail))
    }

    companion object {
        private const val TAG = "MirrorServer"
        private const val CERT_API_URL = "https://car.fbezita.com/api/castla/cert"
        private const val DYNAMIC_CERT_FILE_NAME = "dynamic_castla.p12"
        private const val DYNAMIC_CERT_LAST_CHECK_FILE_NAME = "dynamic_castla.p12.last_check"
    }
}

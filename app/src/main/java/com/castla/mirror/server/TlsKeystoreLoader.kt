package com.castla.mirror.server

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.cert.X509Certificate

data class LoadedTlsKeystore(
    val keyStore: KeyStore,
    val source: String,
)

object TlsKeystoreLoader {
    fun readCertificateNotAfterMs(
        password: CharArray,
        dynamicFile: File,
    ): Long? {
        val keyStore = loadPkcs12FromFile(password, dynamicFile)
        val aliases = keyStore.aliases()

        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val certificate = keyStore.getCertificate(alias) as? X509Certificate ?: continue
            return certificate.notAfter.time
        }

        return null
    }

    fun loadDynamicPkcs12WithRefresh(
        password: CharArray,
        dynamicFile: File,
        refreshDynamicKeystore: () -> Unit,
    ): LoadedTlsKeystore {
        try {
            return LoadedTlsKeystore(
                keyStore = loadPkcs12FromFile(password, dynamicFile),
                source = "dynamic",
            )
        } catch (_: Exception) {
            if (dynamicFile.exists()) {
                dynamicFile.delete()
            }
        }

        refreshDynamicKeystore()

        return LoadedTlsKeystore(
            keyStore = loadPkcs12FromFile(password, dynamicFile),
            source = "dynamic_refreshed",
        )
    }

    private fun loadPkcs12FromFile(
        password: CharArray,
        file: File,
    ): KeyStore {
        if (!file.exists() || file.length() <= 0L) {
            throw FileNotFoundException("Dynamic PKCS12 file missing: ${file.absolutePath}")
        }

        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { stream ->
            keyStore.load(stream, password)
        }
        return keyStore
    }
}

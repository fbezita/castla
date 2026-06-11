package com.castla.mirror.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore

class TlsKeystoreLoaderTest {
    private val password = "castla4864".toCharArray()

    @Test
    fun refreshesDynamicKeystoreWhenExistingFileIsCorrupted() {
        val tempDir = Files.createTempDirectory("tls-loader-test").toFile()
        val dynamicFile = File(tempDir, "dynamic_castla.p12")
        dynamicFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        var refreshCalled = false
        val loaded = TlsKeystoreLoader.loadDynamicPkcs12WithRefresh(
            password = password,
            dynamicFile = dynamicFile,
        ) {
            refreshCalled = true
            dynamicFile.writeBytes(createPkcs12Bytes(password))
        }

        assertTrue(refreshCalled)
        assertEquals("dynamic_refreshed", loaded.source)
        assertTrue(dynamicFile.exists())
    }

    @Test
    fun keepsUsingExistingDynamicKeystoreWhenItIsValid() {
        val tempDir = Files.createTempDirectory("tls-loader-test").toFile()
        val dynamicFile = File(tempDir, "dynamic_castla.p12")
        dynamicFile.writeBytes(createPkcs12Bytes(password))

        var refreshCalled = false
        val loaded = TlsKeystoreLoader.loadDynamicPkcs12WithRefresh(
            password = password,
            dynamicFile = dynamicFile,
        ) {
            refreshCalled = true
        }

        assertFalse(refreshCalled)
        assertEquals("dynamic", loaded.source)
        assertTrue(dynamicFile.exists())
    }

    private fun createPkcs12Bytes(password: CharArray): ByteArray {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, password)
        val output = java.io.ByteArrayOutputStream()
        keyStore.store(output, password)
        return output.toByteArray()
    }
}

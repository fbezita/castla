package com.castla.mirror.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogUploadApiTest {

    @Test
    fun `multipart body includes zip headers payload and closing boundary`() {
        val api = DiagnosticLogUploadApi(token = "test-token")
        val archive = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x01, 0x02)

        val body = api.multipartBody("test-boundary", archive)
        val headerEnd = body.indexOfSubsequence("\r\n\r\n".toByteArray()) + 4
        val trailer = "\r\n--test-boundary--\r\n".toByteArray()

        assertTrue(String(body, Charsets.ISO_8859_1).contains("Content-Type: application/zip"))
        assertArrayEquals(archive, body.copyOfRange(headerEnd, headerEnd + archive.size))
        assertArrayEquals(trailer, body.copyOfRange(body.size - trailer.size, body.size))
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        for (index in 0..size - needle.size) {
            if (needle.indices.all { offset -> this[index + offset] == needle[offset] }) return index
        }
        return -1
    }
}

package com.castla.mirror.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipInputStream

class DiagnosticLogArchiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `build creates zip containing every log file`() {
        val current = tempFolder.newFile("mirror.log").apply { writeText("current-log") }
        val rotated = tempFolder.newFile("mirror.log.1").apply { writeText("rotated-log") }

        val archive = DiagnosticLogArchive.build(listOf(current, rotated))
        val entries = linkedMapOf<String, ByteArray>()

        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }

        assertEquals(listOf("mirror.log", "mirror.log.1"), entries.keys.toList())
        assertArrayEquals("current-log".toByteArray(), entries.getValue("mirror.log"))
        assertArrayEquals("rotated-log".toByteArray(), entries.getValue("mirror.log.1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `build rejects empty file list`() {
        DiagnosticLogArchive.build(emptyList())
    }
}

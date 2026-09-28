package com.castla.mirror.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticLogArchive {
    fun build(files: List<File>): ByteArray {
        require(files.isNotEmpty()) { "At least one log file is required" }

        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }
}

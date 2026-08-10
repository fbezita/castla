package com.castla.mirror.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class PackageUidParserTest {
    @Test
    fun `parses uid from package manager list output`() {
        val output = "package:com.google.android.youtube uid:10234"

        assertEquals(10234, PackageUidParser.parse(output, "com.google.android.youtube"))
    }

    @Test
    fun `ignores a different package and malformed uid`() {
        val output = """
            package:com.example.other uid:10001
            package:com.google.android.youtube uid:not-a-number
        """.trimIndent()

        assertEquals(-1, PackageUidParser.parse(output, "com.google.android.youtube"))
    }
}

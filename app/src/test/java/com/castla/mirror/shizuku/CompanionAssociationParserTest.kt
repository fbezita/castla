package com.castla.mirror.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionAssociationParserTest {

    @Test
    fun `parses detailed association format used by newer one ui`() {
        val output = """
            Association{mId=12, mUserId=0, mPackageName='com.android.shell', mDeviceProfile='android.app.role.COMPANION_DEVICE_APP_STREAMING'}
        """.trimIndent()

        assertEquals(12, CompanionAssociationParser.findShellAppStreamingId(output))
    }

    @Test
    fun `parses table format used by older one ui`() {
        val output = """
            Max ID: 10
            Association ID | Package Name | Mac Address
            7 | com.samsung.android.smartmirroring | null
            8 | com.android.shell | 02:ca:57:1a:00:01
            10 | com.android.shell | 02:ca:57:1a:00:01
        """.trimIndent()

        assertEquals(10, CompanionAssociationParser.findShellAppStreamingId(output))
    }

    @Test
    fun `does not accept unrelated shell association`() {
        val output = """
            Association ID | Package Name | Mac Address
            11 | com.android.shell | 02:00:00:00:00:02
        """.trimIndent()

        assertNull(CompanionAssociationParser.findShellAppStreamingId(output))
    }
}

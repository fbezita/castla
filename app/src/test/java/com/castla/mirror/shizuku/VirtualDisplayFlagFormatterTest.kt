package com.castla.mirror.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualDisplayFlagFormatterTest {
    @Test
    fun `describes known flags in stable order`() {
        val flags = VirtualDisplayFlagFormatter.PUBLIC or
            VirtualDisplayFlagFormatter.OWN_CONTENT_ONLY or
            VirtualDisplayFlagFormatter.TRUSTED

        assertEquals("PUBLIC|OWN_CONTENT_ONLY|TRUSTED", VirtualDisplayFlagFormatter.describe(flags))
    }

    @Test
    fun `returns none when no known flags are set`() {
        assertEquals("none", VirtualDisplayFlagFormatter.describe(0))
    }
}

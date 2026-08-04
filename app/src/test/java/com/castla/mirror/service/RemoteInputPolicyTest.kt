package com.castla.mirror.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputPolicyTest {
    @Test
    fun `native VD IME bypasses Castla proxy handling`() {
        assertFalse(RemoteInputPolicy.shouldHandleProxyInput(proxyEnabled = false))
    }

    @Test
    fun `fallback IME mode enables Castla proxy handling`() {
        assertTrue(RemoteInputPolicy.shouldHandleProxyInput(proxyEnabled = true))
    }
}

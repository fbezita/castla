package com.castla.mirror.service

object RemoteInputPolicy {
    fun shouldHandleProxyInput(proxyEnabled: Boolean): Boolean = proxyEnabled
}

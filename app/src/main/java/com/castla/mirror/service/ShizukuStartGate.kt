package com.castla.mirror.service

/** Mandatory service-entry gate; Castla has no supported unprivileged mode. */
object ShizukuStartGate {
    fun canStart(
        available: Boolean,
        permitted: Boolean,
        connected: Boolean,
        binding: Boolean,
    ): Boolean = available && permitted && (connected || binding)
}

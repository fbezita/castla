package com.castla.mirror.shizuku

internal object VirtualDisplayFlagFormatter {
    const val PUBLIC = 1 shl 0
    const val PRESENTATION = 1 shl 1
    const val OWN_CONTENT_ONLY = 1 shl 3
    const val DESTROY_CONTENT = 1 shl 8
    const val TRUSTED = 1 shl 10
    const val OWN_DISPLAY_GROUP = 1 shl 11
    const val ALWAYS_UNLOCKED = 1 shl 12

    private val names = listOf(
        PUBLIC to "PUBLIC",
        PRESENTATION to "PRESENTATION",
        OWN_CONTENT_ONLY to "OWN_CONTENT_ONLY",
        DESTROY_CONTENT to "DESTROY_CONTENT",
        OWN_DISPLAY_GROUP to "OWN_DISPLAY_GROUP",
        TRUSTED to "TRUSTED",
        ALWAYS_UNLOCKED to "ALWAYS_UNLOCKED",
    )

    fun describe(flags: Int): String = names
        .filter { (flag, _) -> flags and flag != 0 }
        .joinToString("|") { (_, name) -> name }
        .ifEmpty { "none" }
}

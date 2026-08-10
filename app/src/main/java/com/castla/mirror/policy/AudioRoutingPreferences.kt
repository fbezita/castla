package com.castla.mirror.policy

import com.castla.mirror.utils.AppCategoryClassifier

enum class AudioCodecPreference {
    OPUS_FIRST,
    PCM_FIRST;

    fun resolve(browserSupportedCodec: AudioCodec): AudioCodec = when (this) {
        PCM_FIRST -> AudioCodec.PCM_S16LE
        OPUS_FIRST -> browserSupportedCodec
    }
}

object SamsungSeparateSoundPolicy {
    /** null means the Samsung setting is unavailable; empty means explicitly disabled. */
    fun parse(state: String?, packages: String?): Set<String>? {
        if (state == null && packages == null) return null
        if (state != "1") return emptySet()
        return packages.orEmpty()
            .split(':')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
    }
}

object AudioAppRoutePreference {
    fun outputFor(
        packageName: String,
        separateNavigationToPhone: Boolean,
        systemSeparatedPackages: Set<String>?,
    ): AppAudioOutput {
        if (!separateNavigationToPhone) return AppAudioOutput.BROWSER_ONLY
        val isNavigation = AppCategoryClassifier.classify(packageName, "") == "NAVIGATION"
        if (!isNavigation) return AppAudioOutput.BROWSER_ONLY
        val directToPhone = systemSeparatedPackages?.contains(packageName) ?: true
        return if (directToPhone) AppAudioOutput.PHONE_DIRECT else AppAudioOutput.BROWSER_ONLY
    }
}

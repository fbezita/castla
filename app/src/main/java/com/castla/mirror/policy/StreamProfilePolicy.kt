package com.castla.mirror.policy

import com.castla.mirror.ui.StreamSettings

enum class StreamProfile { STABILITY, BALANCED, QUALITY, CUSTOM }

object StreamProfilePolicy {
    fun apply(profile: StreamProfile, current: StreamSettings): StreamSettings = when (profile) {
        StreamProfile.STABILITY -> current.copy(
            maxResolution = StreamSettings.Resolution.RES_720,
            fps = 30,
        )
        StreamProfile.BALANCED -> current.copy(
            maxResolution = StreamSettings.Resolution.RES_720,
            fps = StreamSettings.FPS_AUTO,
        )
        StreamProfile.QUALITY -> current.copy(
            maxResolution = StreamSettings.Resolution.RES_1080,
            fps = 60,
        )
        StreamProfile.CUSTOM -> current
    }

    fun detect(settings: StreamSettings): StreamProfile = when {
        settings.maxResolution == StreamSettings.Resolution.RES_720 && settings.fps == 30 -> StreamProfile.STABILITY
        settings.maxResolution == StreamSettings.Resolution.RES_720 && settings.fps == StreamSettings.FPS_AUTO -> StreamProfile.BALANCED
        settings.maxResolution == StreamSettings.Resolution.RES_1080 && settings.fps == 60 -> StreamProfile.QUALITY
        else -> StreamProfile.CUSTOM
    }
}

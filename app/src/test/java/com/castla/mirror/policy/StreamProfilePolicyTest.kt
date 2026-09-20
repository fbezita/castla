package com.castla.mirror.policy

import com.castla.mirror.ui.StreamSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamProfilePolicyTest {
    private val current = StreamSettings(audioEnabled = true)

    @Test
    fun `stability profile uses 720p and 30 fps without changing audio`() {
        val result = StreamProfilePolicy.apply(StreamProfile.STABILITY, current)
        assertEquals(StreamSettings.Resolution.RES_720, result.maxResolution)
        assertEquals(30, result.fps)
        assertEquals(true, result.audioEnabled)
    }

    @Test
    fun `balanced profile uses 720p and automatic fps`() {
        val result = StreamProfilePolicy.apply(StreamProfile.BALANCED, current)
        assertEquals(StreamSettings.Resolution.RES_720, result.maxResolution)
        assertEquals(StreamSettings.FPS_AUTO, result.fps)
    }

    @Test
    fun `quality profile uses 1080p and 60 fps`() {
        val result = StreamProfilePolicy.apply(StreamProfile.QUALITY, current)
        assertEquals(StreamSettings.Resolution.RES_1080, result.maxResolution)
        assertEquals(60, result.fps)
    }

    @Test
    fun `detect identifies known profiles and custom settings`() {
        assertEquals(StreamProfile.STABILITY, StreamProfilePolicy.detect(StreamSettings(maxResolution = StreamSettings.Resolution.RES_720, fps = 30)))
        assertEquals(StreamProfile.BALANCED, StreamProfilePolicy.detect(StreamSettings(maxResolution = StreamSettings.Resolution.RES_720, fps = StreamSettings.FPS_AUTO)))
        assertEquals(StreamProfile.QUALITY, StreamProfilePolicy.detect(StreamSettings(maxResolution = StreamSettings.Resolution.RES_1080, fps = 60)))
        assertEquals(StreamProfile.CUSTOM, StreamProfilePolicy.detect(StreamSettings(maxResolution = StreamSettings.Resolution.RES_1080, fps = 30)))
    }
}

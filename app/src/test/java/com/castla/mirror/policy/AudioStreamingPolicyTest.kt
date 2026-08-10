package com.castla.mirror.policy

import org.junit.Assert.*
import org.junit.Test

class AudioStreamingPolicyTest {
    private val youtubePersonal = AppAudioTarget("com.google.android.youtube", 0, 10123)
    private val youtubeWork = AppAudioTarget("com.google.android.youtube", 10, 1010123)
    private val tmap = AppAudioTarget("com.skt.tmap.ku", 0, 10124)

    @Test
    fun `browser-only UID is included and phone-direct UID is excluded`() {
        val selection = AudioRoutePolicy.select(
            listOf(
                AppAudioRoute(youtubePersonal, AppAudioOutput.BROWSER_ONLY),
                AppAudioRoute(tmap, AppAudioOutput.PHONE_DIRECT),
            )
        )
        assertEquals(setOf(10123), selection.includedUids)
        assertEquals(setOf(10124), selection.excludedUids)
        assertEquals(AudioRouteMode.LOOPBACK_ONLY, selection.routeMode)
    }

    @Test
    fun `adding phone-direct navigation does not change effective capture route`() {
        val youtubeOnly = AudioRoutePolicy.select(
            listOf(AppAudioRoute(youtubePersonal, AppAudioOutput.BROWSER_ONLY))
        )
        val youtubeWithPhoneNavigation = AudioRoutePolicy.select(
            listOf(
                AppAudioRoute(youtubePersonal, AppAudioOutput.BROWSER_ONLY),
                AppAudioRoute(tmap, AppAudioOutput.PHONE_DIRECT),
            )
        )

        assertEquals(
            AudioCaptureRouteKey.from(youtubeOnly),
            AudioCaptureRouteKey.from(youtubeWithPhoneNavigation),
        )
    }

    @Test
    fun `adding another browser-routed app changes effective capture route`() {
        val youtubeOnly = AudioRoutePolicy.select(
            listOf(AppAudioRoute(youtubePersonal, AppAudioOutput.BROWSER_ONLY))
        )
        val withAnotherBrowserApp = AudioRoutePolicy.select(
            listOf(
                AppAudioRoute(youtubePersonal, AppAudioOutput.BROWSER_ONLY),
                AppAudioRoute(tmap, AppAudioOutput.BROWSER_ONLY),
            )
        )

        assertNotEquals(
            AudioCaptureRouteKey.from(youtubeOnly),
            AudioCaptureRouteKey.from(withAnotherBrowserApp),
        )
    }

    @Test
    fun `launched target registry retains background media when navigation replaces pane`() {
        val registry = AudioTargetRegistry()

        registry.remember(youtubePersonal)
        registry.remember(tmap)

        assertEquals(listOf(youtubePersonal, tmap), registry.snapshot())
    }

    @Test
    fun `launched target registry updates UID for the same app instance`() {
        val registry = AudioTargetRegistry()
        registry.remember(youtubePersonal)

        val restartedYoutube = youtubePersonal.copy(uid = 10999)
        registry.remember(restartedYoutube)

        assertEquals(listOf(restartedYoutube), registry.snapshot())
    }

    @Test
    fun `same package in different users remains distinct`() {
        val selection = AudioRoutePolicy.select(
            listOf(
                AppAudioRoute(youtubePersonal, AppAudioOutput.PHONE_DIRECT),
                AppAudioRoute(youtubeWork, AppAudioOutput.BROWSER_ONLY),
            )
        )
        assertEquals(setOf(1010123), selection.includedUids)
        assertEquals(listOf(youtubeWork), selection.includedApps)
    }

    @Test
    fun `duplicate output maps to loopback render`() {
        val selection = AudioRoutePolicy.select(
            listOf(AppAudioRoute(youtubePersonal, AppAudioOutput.DUPLICATE))
        )
        assertEquals(AudioRouteMode.LOOPBACK_RENDER, selection.routeMode)
        assertEquals(3, selection.routeMode.platformRouteFlags)
    }

    @Test
    fun `audio disabled never selects a codec`() {
        assertEquals(
            AudioCaptureDecision.Disabled,
            AudioCodecPolicy.select(false, null, AudioCodecCapabilities(true, true))
        )
    }

    @Test
    fun `opus is default and pcm is fallback or forced`() {
        assertEquals(AudioCodec.OPUS, enabledCodec(AudioCodecPolicy.select(true, null, AudioCodecCapabilities(true, true))))
        assertEquals(AudioCodec.PCM_S16LE, enabledCodec(AudioCodecPolicy.select(true, null, AudioCodecCapabilities(false, true))))
        assertEquals(AudioCodec.PCM_S16LE, enabledCodec(AudioCodecPolicy.select(true, null, AudioCodecCapabilities(true, false))))
        assertEquals(AudioCodec.PCM_S16LE, enabledCodec(AudioCodecPolicy.select(true, AudioCodec.PCM_S16LE, AudioCodecCapabilities(true, true))))
    }

    @Test
    fun `pcm accumulator emits exact 20ms frames across partial reads`() {
        val accumulator = PcmFrameAccumulator(frameBytes = 8)
        assertTrue(accumulator.append(byteArrayOf(1, 2, 3)).isEmpty())
        val frames = accumulator.append(byteArrayOf(4, 5, 6, 7, 8, 9, 10))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), frames.single())
        assertEquals(2, accumulator.bufferedBytes)
    }

    @Test
    fun `sample timestamps increase by 20ms`() {
        val clock = AudioSampleClock(48_000, 960)
        assertEquals(0L, clock.nextTimestampUs())
        assertEquals(20_000L, clock.nextTimestampUs())
        assertEquals(40_000L, clock.nextTimestampUs())
    }

    @Test
    fun `opus encoder watchdog detects queued input without output`() {
        val watchdog = EncoderOutputWatchdog(maxInputFramesWithoutOutput = 3)
        assertFalse(watchdog.onInputQueued())
        assertFalse(watchdog.onInputQueued())
        assertTrue(watchdog.onInputQueued())

        watchdog.onOutputProduced()
        assertFalse(watchdog.onInputQueued())
    }

    private fun enabledCodec(decision: AudioCaptureDecision): AudioCodec =
        (decision as AudioCaptureDecision.Enabled).codec
}

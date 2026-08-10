package com.castla.mirror.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutingPreferencesTest {
    @Test
    fun `all non-navigation apps stream to browser`() {
        assertEquals(
            AppAudioOutput.BROWSER_ONLY,
            AudioAppRoutePreference.outputFor("com.google.android.youtube", separateNavigationToPhone = true, systemSeparatedPackages = null),
        )
    }

    @Test
    fun `navigation goes direct only when separation is enabled`() {
        assertEquals(
            AppAudioOutput.PHONE_DIRECT,
            AudioAppRoutePreference.outputFor("com.skt.tmap.ku", separateNavigationToPhone = true, systemSeparatedPackages = null),
        )
        assertEquals(
            AppAudioOutput.BROWSER_ONLY,
            AudioAppRoutePreference.outputFor("com.skt.tmap.ku", separateNavigationToPhone = false, systemSeparatedPackages = null),
        )
    }

    @Test
    fun `readable Samsung package list narrows phone-direct navigation apps`() {
        val separated = setOf("com.skt.tmap.ku")
        assertEquals(
            AppAudioOutput.PHONE_DIRECT,
            AudioAppRoutePreference.outputFor("com.skt.tmap.ku", true, separated),
        )
        assertEquals(
            AppAudioOutput.BROWSER_ONLY,
            AudioAppRoutePreference.outputFor("com.nhn.android.nmap", true, separated),
        )
    }

    @Test
    fun `Samsung separate sound settings distinguish unavailable disabled and enabled`() {
        assertNull(SamsungSeparateSoundPolicy.parse(null, null))
        assertEquals(emptySet<String>(), SamsungSeparateSoundPolicy.parse("0", "com.skt.tmap.ku"))
        assertEquals(
            setOf("com.skt.tmap.ku", "com.nhn.android.nmap"),
            SamsungSeparateSoundPolicy.parse("1", "com.skt.tmap.ku:com.nhn.android.nmap"),
        )
    }

    @Test
    fun `codec preference chooses pcm first or browser-supported opus`() {
        assertEquals(AudioCodec.PCM_S16LE, AudioCodecPreference.PCM_FIRST.resolve(AudioCodec.OPUS))
        assertEquals(AudioCodec.OPUS, AudioCodecPreference.OPUS_FIRST.resolve(AudioCodec.OPUS))
        assertEquals(AudioCodec.PCM_S16LE, AudioCodecPreference.OPUS_FIRST.resolve(AudioCodec.PCM_S16LE))
    }
}

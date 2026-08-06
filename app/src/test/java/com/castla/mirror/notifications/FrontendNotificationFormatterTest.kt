package com.castla.mirror.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendNotificationFormatterTest {

    @Test
    fun `build returns payload for kakao notification`() {
        val payload = FrontendNotificationFormatter.build(
            packageName = "com.kakao.talk",
            appLabel = "KakaoTalk",
            title = "Alice",
            text = "Where are you?",
            isOngoing = false,
            isGroupSummary = false,
        )

        requireNotNull(payload)
        assertEquals("com.kakao.talk", payload.packageName)
        assertEquals("KakaoTalk", payload.appLabel)
        assertEquals("Alice", payload.title)
        assertEquals("Where are you?", payload.text)
    }

    @Test
    fun `build returns payload for packages selected by the frontend`() {
        val payload = FrontendNotificationFormatter.build(
            packageName = "com.example.random",
            appLabel = "Random",
            title = "Promo",
            text = "Buy now",
            isOngoing = false,
            isGroupSummary = false,
        )

        requireNotNull(payload)
        assertEquals("com.example.random", payload.packageName)
    }

    @Test
    fun `build drops ongoing and summary notifications`() {
        assertNull(
            FrontendNotificationFormatter.build(
                packageName = "com.kakao.talk",
                appLabel = "KakaoTalk",
                title = "Alice",
                text = "Typing",
                isOngoing = true,
                isGroupSummary = false,
            )
        )
        assertNull(
            FrontendNotificationFormatter.build(
                packageName = "com.kakao.talk",
                appLabel = "KakaoTalk",
                title = "2 messages",
                text = "summary",
                isOngoing = false,
                isGroupSummary = true,
            )
        )
    }


    @Test
    fun `build replaces image content with photo placeholder`() {
        val photoPayload = FrontendNotificationFormatter.build(
            packageName = "com.example.photos",
            appLabel = "Photos",
            title = "Alice",
            text = "private image caption",
            isOngoing = false,
            isGroupSummary = false,
            hasImage = true,
        )
        val photoOnlyPayload = FrontendNotificationFormatter.build(
            packageName = "com.example.photos",
            appLabel = "Photos",
            title = "Alice",
            text = "",
            isOngoing = false,
            isGroupSummary = false,
            hasImage = true,
        )
        val emptyPayload = FrontendNotificationFormatter.build(
            packageName = "com.example.photos",
            appLabel = "Photos",
            title = "Alice",
            text = "",
            isOngoing = false,
            isGroupSummary = false,
            hasImage = false,
        )

        requireNotNull(photoPayload)
        assertEquals("private image caption", photoPayload.text)
        assertTrue(photoPayload.hasImage)
        requireNotNull(photoOnlyPayload)
        assertEquals("", photoOnlyPayload.text)
        assertTrue(photoOnlyPayload.hasImage)
        assertNull(emptyPayload)
    }

    @Test
    fun `build trims whitespace and truncates long text`() {
        val payload = FrontendNotificationFormatter.build(
            packageName = "org.telegram.messenger",
            appLabel = "Telegram",
            title = "  Bob   ",
            text = "  " + "A".repeat(240) + "  ",
            isOngoing = false,
            isGroupSummary = false,
        )

        requireNotNull(payload)
        assertEquals("Bob", payload.title)
        assertTrue(payload.text.length <= FrontendNotificationFormatter.MAX_TEXT_LENGTH + 1)
        assertTrue(payload.text.endsWith("…"))
    }

    @Test
    fun `build preserves sender separately from conversation title`() {
        val payload = FrontendNotificationFormatter.build(
            packageName = "org.telegram.messenger",
            appLabel = "Telegram",
            title = "Family chat",
            text = "Hello",
            sender = " Alice ",
            isOngoing = false,
            isGroupSummary = false,
        )

        requireNotNull(payload)
        assertEquals("Family chat", payload.title)
        assertEquals("Alice", payload.sender)
    }

    @Test
    fun `build falls back title to app label when title missing`() {
        val payload = FrontendNotificationFormatter.build(
            packageName = "com.facebook.orca",
            appLabel = "Messenger",
            title = "",
            text = "Ping",
            isOngoing = false,
            isGroupSummary = false,
        )

        requireNotNull(payload)
        assertEquals("Messenger", payload.title)
    }
}

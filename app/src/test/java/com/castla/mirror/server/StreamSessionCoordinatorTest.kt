package com.castla.mirror.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamSessionCoordinatorTest {
    @Test
    fun `begin increments generation independently per normalized channel`() {
        val coordinator = StreamSessionCoordinator({}, { _, _, _, _, _ -> })

        assertEquals(1, coordinator.begin("primary", 10, 1280, 720))
        assertEquals(2, coordinator.begin("unknown", 10, 1280, 720))
        assertEquals(1, coordinator.begin("secondary", 11, 640, 720))
        assertEquals(2, coordinator.currentGeneration("primary"))
        assertEquals(1, coordinator.currentGeneration("secondary"))
    }

    @Test
    fun `first frame metadata is emitted only once per generation`() {
        val messages = mutableListOf<String>()
        val coordinator = StreamSessionCoordinator(messages::add, { _, _, _, _, _ -> })
        coordinator.begin("primary", 10, 1280, 720)

        coordinator.markFirstFrameReady("primary", 10, 1280, 720)
        coordinator.markFirstFrameReady("primary", 10, 1280, 720)

        assertEquals(2, messages.size)
        val metadata = JSONObject(messages.last())
        assertTrue(metadata.getBoolean("streamReady"))
        assertTrue(metadata.getBoolean("firstFrameReady"))
        assertEquals(1, metadata.getInt("generation"))
    }

    @Test
    fun `pause replaces replay metadata with not-ready state`() {
        val coordinator = StreamSessionCoordinator({}, { _, _, _, _, _ -> })
        coordinator.begin("primary", 10, 1280, 720)
        coordinator.markFirstFrameReady("primary", 10, 1280, 720)
        coordinator.pause("primary", 10, 1280, 720)
        val replayed = mutableListOf<String>()

        assertEquals(1, coordinator.replayMetadata(replayed::add))
        val metadata = JSONObject(replayed.single())
        assertFalse(metadata.getBoolean("streamReady"))
        assertFalse(metadata.getBoolean("firstFrameReady"))
        assertEquals(1, metadata.getInt("generation"))
    }
}

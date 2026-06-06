package com.castla.mirror.policy

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScreenOffPolicyTest {

    private lateinit var policy: ScreenOffPolicy

    @Before
    fun setup() {
        policy = ScreenOffPolicy()
    }

    @Test
    fun `initial state is ACTIVE`() {
        assertEquals(ScreenOffState.ACTIVE, policy.state)
        assertFalse(policy.isScreenOff)
    }

    @Test
    fun `screen off transitions ACTIVE to BLACKOUT_PENDING`() {
        val next = policy.transition(ScreenOffEvent.SCREEN_OFF)
        assertEquals(ScreenOffState.BLACKOUT_PENDING, next)
        assertTrue(policy.isScreenOff)
    }

    @Test
    fun `blackout ready transitions BLACKOUT_PENDING to BLACKOUT_ACTIVE`() {
        policy.transition(ScreenOffEvent.SCREEN_OFF)
        val next = policy.transition(ScreenOffEvent.ON_BLACKOUT_READY)
        assertEquals(ScreenOffState.BLACKOUT_ACTIVE, next)
        assertTrue(policy.isScreenOff)
    }

    @Test
    fun `restore request transitions BLACKOUT_PENDING to ACTIVE`() {
        policy.transition(ScreenOffEvent.SCREEN_OFF)
        val next = policy.transition(ScreenOffEvent.RESTORE_REQUEST)
        assertEquals(ScreenOffState.ACTIVE, next)
        assertFalse(policy.isScreenOff)
    }

    @Test
    fun `restore request transitions BLACKOUT_ACTIVE to ACTIVE`() {
        policy.transition(ScreenOffEvent.SCREEN_OFF)
        policy.transition(ScreenOffEvent.ON_BLACKOUT_READY)
        val next = policy.transition(ScreenOffEvent.RESTORE_REQUEST)
        assertEquals(ScreenOffState.ACTIVE, next)
        assertFalse(policy.isScreenOff)
    }

    @Test
    fun `screen on transitions BLACKOUT_ACTIVE to ACTIVE`() {
        policy.transition(ScreenOffEvent.SCREEN_OFF)
        policy.transition(ScreenOffEvent.ON_BLACKOUT_READY)
        val next = policy.transition(ScreenOffEvent.SCREEN_ON)
        assertEquals(ScreenOffState.ACTIVE, next)
        assertFalse(policy.isScreenOff)
    }

    @Test
    fun `invalid events are ignored in states`() {
        // SCREEN_ON in ACTIVE should be ignored
        val next1 = policy.transition(ScreenOffEvent.SCREEN_ON)
        assertEquals(ScreenOffState.ACTIVE, next1)

        // ON_BLACKOUT_READY in ACTIVE should be ignored
        val next2 = policy.transition(ScreenOffEvent.ON_BLACKOUT_READY)
        assertEquals(ScreenOffState.ACTIVE, next2)
    }

    @Test
    fun `reset returns to ACTIVE`() {
        policy.transition(ScreenOffEvent.SCREEN_OFF)
        policy.transition(ScreenOffEvent.ON_BLACKOUT_READY)
        policy.reset()
        assertEquals(ScreenOffState.ACTIVE, policy.state)
        assertTrue(policy.isPanelOffSupported)
    }
}


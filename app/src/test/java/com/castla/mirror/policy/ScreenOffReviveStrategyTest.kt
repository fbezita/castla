package com.castla.mirror.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOffReviveStrategyTest {

    @Test
    fun `samsung manufacturer uses blackout keep alive`() {
        assertEquals(
            ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
            ScreenOffReviveStrategy.select(manufacturer = "samsung", brand = "generic"),
        )
    }

    @Test
    fun `samsung brand uses blackout keep alive`() {
        assertEquals(
            ScreenOffReviveStrategy.BLACKOUT_KEEP_ALIVE,
            ScreenOffReviveStrategy.select(manufacturer = "other", brand = "Samsung"),
        )
    }

    @Test
    fun `non samsung device uses panel off`() {
        assertEquals(
            ScreenOffReviveStrategy.PANEL_OFF,
            ScreenOffReviveStrategy.select(manufacturer = "google", brand = "pixel"),
        )
    }
}

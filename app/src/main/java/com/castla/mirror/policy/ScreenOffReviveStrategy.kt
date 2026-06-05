package com.castla.mirror.policy

enum class ScreenOffReviveStrategy {
    PANEL_OFF,
    BLACKOUT_KEEP_ALIVE;

    companion object {
        fun select(manufacturer: String?, brand: String?): ScreenOffReviveStrategy {
            val normalizedManufacturer = manufacturer?.trim()?.lowercase().orEmpty()
            val normalizedBrand = brand?.trim()?.lowercase().orEmpty()
            return if (normalizedManufacturer == "samsung" || normalizedBrand == "samsung") {
                BLACKOUT_KEEP_ALIVE
            } else {
                PANEL_OFF
            }
        }
    }
}

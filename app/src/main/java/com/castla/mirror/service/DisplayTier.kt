package com.castla.mirror.service

/** Runtime resource tier used by the active mirroring pipeline. */
enum class DisplayTier {
    ACTIVE,
    VISIBLE,
    SUSPENDED,
    PARKED,
}

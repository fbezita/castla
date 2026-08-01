package com.castla.mirror.service

data class DisplayLaunchSession(
    val targetWidth: Int,
    val targetHeight: Int,
    val alignedWidth: Int,
    val alignedHeight: Int,
    val encoderReady: Boolean,
)

package com.castla.mirror.policy

/**
 * Pure decision for whether a client codec-mode request should trigger a
 * pipeline rebuild.
 *
 * Encapsulates the guard used by `MirrorForegroundService.onCodecModeRequest`
 * so it can be unit-tested without spinning up an Android Service. Keeps the
 * orchestration (mutex, encoder tear-down, VD swap) in the service while the
 * branching logic lives here.
 */
object CodecModeTransition {

    const val MODE_H264 = "h264"
    const val MODE_MJPEG = "mjpeg"

    /* ### 수정 시작 ### */
    /**
     * Decides whether a client codec-mode request should trigger a pipeline rebuild.
     * Supports symmetric bi-directional transitions between H.264 and MJPEG.
     *
     * @param requestedMode mode string carried by the client control message (h264 or mjpeg)
     * @param currentMode the service's currently active codec mode
     * @param jpegEncoderActive whether a JpegEncoder is already live
     * @return true if the service should apply the switch (set mode + rebuild)
     */
    fun shouldApply(
        requestedMode: String,
        currentMode: String,
        jpegEncoderActive: Boolean
    ): Boolean {
        if (requestedMode != MODE_MJPEG && requestedMode != MODE_H264) return false
        
        // If requesting MJPEG, only skip rebuild if already in MJPEG mode and JpegEncoder is active
        if (requestedMode == MODE_MJPEG) {
            return !(currentMode == MODE_MJPEG && jpegEncoderActive)
        }
        
        // If requesting H.264, only skip rebuild if already in H.264 mode and JpegEncoder is inactive
        if (requestedMode == MODE_H264) {
            return !(currentMode == MODE_H264 && !jpegEncoderActive)
        }
        
        return true
    }
    /* ### 수정 끝 ### */
}

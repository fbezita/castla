package com.castla.mirror.policy

enum class AudioRouteMode(val aidlValue: Int, val platformRouteFlags: Int) {
    LOOPBACK_ONLY(0, 2),
    LOOPBACK_RENDER(1, 3);

    companion object {
        fun fromAidl(value: Int): AudioRouteMode = entries.firstOrNull { it.aidlValue == value } ?: LOOPBACK_ONLY
    }
}

data class AppInstanceKey(val packageName: String, val userId: Int)

data class AppAudioTarget(val packageName: String, val userId: Int, val uid: Int) {
    val key: AppInstanceKey get() = AppInstanceKey(packageName, userId)
}

class AudioTargetRegistry {
    private val targets = LinkedHashMap<AppInstanceKey, AppAudioTarget>()

    @Synchronized
    fun remember(target: AppAudioTarget) {
        targets[target.key] = target
    }

    @Synchronized
    fun snapshot(): List<AppAudioTarget> = targets.values.toList()

    @Synchronized
    fun clear() {
        targets.clear()
    }
}

enum class AppAudioOutput { BROWSER_ONLY, PHONE_DIRECT, DUPLICATE }

data class AppAudioRoute(val target: AppAudioTarget, val output: AppAudioOutput)

data class AudioCaptureSelection(
    val includedUids: Set<Int>,
    val excludedUids: Set<Int>,
    val routeMode: AudioRouteMode,
    val includedApps: List<AppAudioTarget>,
    val excludedApps: List<AppAudioTarget>,
)

data class AudioCaptureRouteKey(
    val includedUids: Set<Int>,
    val routeMode: AudioRouteMode,
) {
    companion object {
        fun from(selection: AudioCaptureSelection) = AudioCaptureRouteKey(
            includedUids = selection.includedUids.toSet(),
            routeMode = selection.routeMode,
        )
    }
}

object AudioRoutePolicy {
    fun select(routes: List<AppAudioRoute>): AudioCaptureSelection {
        val duplicate = routes.any { it.output == AppAudioOutput.DUPLICATE }
        val included = routes.filter { it.output != AppAudioOutput.PHONE_DIRECT }.map { it.target }.distinctBy { it.key }
        val excluded = routes.filter { it.output == AppAudioOutput.PHONE_DIRECT }.map { it.target }.distinctBy { it.key }
        return AudioCaptureSelection(
            includedUids = included.mapTo(linkedSetOf()) { it.uid },
            excludedUids = excluded.mapTo(linkedSetOf()) { it.uid },
            routeMode = if (duplicate) AudioRouteMode.LOOPBACK_RENDER else AudioRouteMode.LOOPBACK_ONLY,
            includedApps = included,
            excludedApps = excluded,
        )
    }
}

enum class AudioCodec(val wireName: String) {
    OPUS("opus"),
    PCM_S16LE("pcm_s16le");

    companion object {
        fun fromWireName(value: String?): AudioCodec? = when (value?.lowercase()) {
            "opus" -> OPUS
            "pcm", "pcm_s16le" -> PCM_S16LE
            else -> null
        }
    }
}

data class AudioCodecCapabilities(
    val androidOpusEncoderSupported: Boolean,
    val browserOpusDecoderSupported: Boolean,
)

sealed interface AudioCaptureDecision {
    data object Disabled : AudioCaptureDecision
    data class Enabled(val codec: AudioCodec, val fallbackReason: String? = null) : AudioCaptureDecision
}

object AudioCodecPolicy {
    fun select(
        audioEnabled: Boolean,
        requestedCodec: AudioCodec?,
        capabilities: AudioCodecCapabilities,
    ): AudioCaptureDecision {
        if (!audioEnabled) return AudioCaptureDecision.Disabled
        if (requestedCodec == AudioCodec.PCM_S16LE) return AudioCaptureDecision.Enabled(AudioCodec.PCM_S16LE)
        if (!capabilities.androidOpusEncoderSupported) {
            return AudioCaptureDecision.Enabled(AudioCodec.PCM_S16LE, "android-opus-encoder-unsupported")
        }
        if (!capabilities.browserOpusDecoderSupported) {
            return AudioCaptureDecision.Enabled(AudioCodec.PCM_S16LE, "browser-opus-decoder-unsupported")
        }
        return AudioCaptureDecision.Enabled(AudioCodec.OPUS)
    }
}

class PcmFrameAccumulator(private val frameBytes: Int) {
    private var pending = ByteArray(0)
    val bufferedBytes: Int get() = pending.size

    fun append(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
        require(length in 0..bytes.size)
        if (length == 0) return emptyList()
        val combined = ByteArray(pending.size + length)
        pending.copyInto(combined)
        bytes.copyInto(combined, pending.size, 0, length)
        val frames = ArrayList<ByteArray>(combined.size / frameBytes)
        var offset = 0
        while (combined.size - offset >= frameBytes) {
            frames += combined.copyOfRange(offset, offset + frameBytes)
            offset += frameBytes
        }
        pending = combined.copyOfRange(offset, combined.size)
        return frames
    }

    fun clear() { pending = ByteArray(0) }
}

class AudioSampleClock(private val sampleRate: Int, private val samplesPerFrame: Int) {
    private var submittedSamples = 0L
    fun nextTimestampUs(): Long {
        val timestamp = submittedSamples * 1_000_000L / sampleRate
        submittedSamples += samplesPerFrame
        return timestamp
    }
}

class EncoderOutputWatchdog(private val maxInputFramesWithoutOutput: Int) {
    private var inputFrames = 0
    private var outputFrames = 0

    init { require(maxInputFramesWithoutOutput > 0) }

    fun onInputQueued(): Boolean {
        inputFrames += 1
        return isStalled
    }

    fun onOutputProduced() {
        outputFrames += 1
    }

    val isStalled: Boolean
        get() = inputFrames >= maxInputFramesWithoutOutput && outputFrames == 0
}

package com.castla.mirror.compositor

import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.castla.mirror.capture.VideoEncoder
import com.castla.mirror.capture.VirtualDisplayController
import com.castla.mirror.input.TouchInjector
import com.castla.mirror.server.MirrorServer
import com.castla.mirror.shizuku.IPrivilegedService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

class PersistentVirtualDisplaySession(
    val sessionId: DisplaySessionId,
    private val virtualDisplayController: VirtualDisplayController,
    private val mirrorServer: MirrorServer,
    private var touchInjector: TouchInjector?,
    private val initialSpec: DisplaySpec
) {
    companion object {
        private const val TAG = "PersistentVDSession"
    }

    private val mutex = Mutex()
    private val lifecycle = LifecycleStateMachine()
    private val streamGeneration = StreamGenerationState(sessionId)
    private val generationMismatchRef = AtomicInteger(0)
    private val droppedFramesRef = AtomicInteger(0)

    private var encoder: VideoEncoder? = null
    private var surface: Surface? = null
    private var spec: DisplaySpec = initialSpec.aligned()
    @Volatile private var vdId: Int = -1
    @Volatile var tier: DisplayTier = DisplayTier.PARKED
        private set
    @Volatile var isViewportVisible: Boolean = true
        private set
    @Volatile private var reconnectCount: Int = 0

    fun attachPrivilegedService(service: IPrivilegedService?) {
        virtualDisplayController.attachPrivilegedService(service)
        if (service == null) {
            touchInjector?.detachController("persistent_session_privileged_service_null")
        }
    }

    suspend fun ensureDisplay() = mutex.withLock {
        if (virtualDisplayController.hasVirtualDisplay()) {
            vdId = virtualDisplayController.getDisplayId()
            lifecycle.transitionTo(SessionLifecycleState.VD_READY)
            return@withLock
        }
        ensureEncoderLocked()
        val inputSurface = surface ?: return@withLock
        virtualDisplayController.createVirtualDisplay(spec.width, spec.height, spec.dpi, inputSurface)
        vdId = virtualDisplayController.getDisplayId()
        if (vdId >= 0) {
            lifecycle.transitionTo(SessionLifecycleState.VD_READY)
            touchInjector?.updateController { _, event ->
                virtualDisplayController.injectMotionEvent(event)
                true
            }
        }
    }

    suspend fun resize(nextSpec: DisplaySpec) = mutex.withLock {
        spec = nextSpec.aligned()
        streamGeneration.begin(spec.width, spec.height)
        mirrorServer.broadcastControlMessage(streamGeneration.toJson(vdId).toString())
        surface?.let { currentSurface ->
            if (vdId >= 0) {
                try {
                    virtualDisplayController.setSurface(currentSurface)
                    virtualDisplayController.resizeDisplay(spec.width, spec.height, spec.dpi)
                } catch (e: Exception) {
                    lifecycle.transitionTo(SessionLifecycleState.RECOVERING)
                    reconnectCount++
                    Log.w(TAG, "Resize failed for ${sessionId.value}; display will recover independently", e)
                }
            }
        }
        touchInjector?.updateDimensions(spec.width, spec.height)
    }

    suspend fun setTier(nextTier: DisplayTier) = mutex.withLock {
        if (tier == nextTier) return@withLock
        tier = nextTier
        val profile = StreamProfile.forTier(spec.baseBitrate, spec.baseFps, nextTier)
        if (profile.encoderRunning) {
            ensureEncoderLocked()
            encoder?.setBitrate(profile.bitrate)
            encoder?.requestKeyFrame()
            lifecycle.transitionTo(SessionLifecycleState.WAITING_FIRST_FRAME)
        } else {
            streamGeneration.pause()
            encoder?.release()
            encoder = null
            surface = null
            lifecycle.transitionTo(SessionLifecycleState.SUSPENDED)
        }
        mirrorServer.broadcastControlMessage(streamGeneration.toJson(vdId).toString())
        mirrorServer.broadcastDiagnostics()
    }

    fun setViewportVisible(visible: Boolean) {
        isViewportVisible = visible
    }

    fun injectTouch(action: Int, x: Float, y: Float, pointerId: Int) {
        if (vdId < 0) return
        virtualDisplayController.injectInput(action, x, y, pointerId)
        if (action == MotionEvent.ACTION_UP) {
            touchInjector?.release()
            touchInjector = null
        }
    }

    fun diagnostics(): DisplaySessionDiagnostics {
        return DisplaySessionDiagnostics(
            sessionId = sessionId,
            vdId = vdId,
            tier = tier,
            generation = streamGeneration.generation,
            width = spec.width,
            height = spec.height,
            encoderRunning = encoder != null,
            streamReady = streamGeneration.streamReady,
            firstFrameReady = streamGeneration.firstFrameReady,
            reconnectCount = reconnectCount,
            lastFrameTimestampMs = streamGeneration.lastFrameTimestampMs,
            droppedFrames = droppedFramesRef.get(),
            generationMismatchCount = generationMismatchRef.get()
        )
    }

    suspend fun release() = mutex.withLock {
        touchInjector?.detachController("persistent_session_release")
        encoder?.release()
        encoder = null
        surface = null
        virtualDisplayController.releaseVirtualDisplay()
        lifecycle.transitionTo(SessionLifecycleState.RELEASED)
    }

    private fun ensureEncoderLocked() {
        if (encoder != null) return
        val nextGeneration = streamGeneration.begin(spec.width, spec.height)
        val videoEncoder = VideoEncoder(spec.width, spec.height, spec.baseBitrate, spec.baseFps)
        val inputSurface = videoEncoder.createInputSurface()
        surface = inputSurface
        encoder = videoEncoder
        lifecycle.transitionTo(SessionLifecycleState.SURFACE_READY)
        videoEncoder.onSpsPps = { spsPps ->
            mirrorServer.broadcastSpsPps(spsPps, sessionId.value)
        }
        videoEncoder.start { data, isKeyFrame ->
            if (!streamGeneration.firstFrameReady) {
                streamGeneration.markFirstFrame()
                mirrorServer.broadcastControlMessage(streamGeneration.toJson(vdId).toString())
            } else {
                streamGeneration.markFrame()
            }
            mirrorServer.broadcastFrame(data, isKeyFrame, sessionId.value)
        }
        mirrorServer.setKeyframeRequester(sessionId.value) { videoEncoder.requestKeyFrame() }
        lifecycle.transitionTo(SessionLifecycleState.WAITING_FIRST_FRAME)
        Log.i(TAG, "Encoder started for ${sessionId.value} generation=$nextGeneration")
        mirrorServer.broadcastControlMessage(streamGeneration.toJson(vdId).toString())
    }
}

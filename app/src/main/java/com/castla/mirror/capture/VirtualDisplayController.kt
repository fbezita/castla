package com.castla.mirror.capture

import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import com.castla.mirror.diagnostics.FileLogger
import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.MirrorDiagnostics
import com.castla.mirror.shizuku.IPrivilegedService

/**
 * Owns the per-session virtual display lifecycle and exposes VD-scoped privileged
 * operations (input injection, app launch, surface attachment) with a fully symmetric, 
 * independent, and highly encapsulated architecture.
 */
class VirtualDisplayController(private val displayName: String) {
    private val vdImeLogPrefix = "[VDIME]"

    companion object {
        private const val TAG = "VirtualDisplayController"
        private val liveInstanceCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val activeVirtualDisplayCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val totalCreateCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val totalReleaseVirtualDisplayCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val totalReleaseCount = java.util.concurrent.atomic.AtomicInteger(0)

        fun debugSummary(): String =
            "controllerInstances=${liveInstanceCount.get()} activeVirtualDisplays=${activeVirtualDisplayCount.get()} " +
                "totalCreates=${totalCreateCount.get()} totalReleaseVirtualDisplays=${totalReleaseVirtualDisplayCount.get()} " +
                "totalReleases=${totalReleaseCount.get()}"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var privilegedService: IPrivilegedService? = null
    @Volatile private var displayId: Int = -1
    @Volatile private var isBound = false

    init {
        liveInstanceCount.incrementAndGet()
        Log.i(TAG, "[$displayName] Controller created ${debugSummary()}")
    }

    /**
     * Mirror the latest IPrivilegedService reference owned by ShizukuSetup.
     * Propagated dynamically from the service level when the Shizuku binding state changes.
     */
    fun attachPrivilegedService(svc: IPrivilegedService?) {
        privilegedService = svc
        isBound = svc != null
        if (svc == null) {
            virtualDisplay = null
            displayId = -1
        }
    }

    /** Expose the privileged service for external callers (e.g. IME checks) */
    fun getPrivilegedService(): IPrivilegedService? = privilegedService

    /**
     * Create a virtual display via Shizuku's elevated privileges.
     * Automatically registers and tracks the displayId within this controller instance.
     */
    fun createVirtualDisplay(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ): VirtualDisplay? {
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "[$displayName] Invalid dimensions: ${width}x${height}")
            return null
        }

        val service = privilegedService
        if (service == null) {
            Log.i(TAG, "[$displayName] Shizuku service not bound, cannot create virtual display")
            return null
        }

        return try {
            val id = service.createVirtualDisplay(width, height, dpi, displayName)
            if (id >= 0) {
                try {
                    service.setSurface(id, surface)
                } catch (e: Exception) {
                    Log.e(TAG, "[$displayName] setSurface failed, releasing VD", e)
                    service.releaseVirtualDisplay(id)
                    displayId = -1
                    return null
                }
                displayId = id
                totalCreateCount.incrementAndGet()
                activeVirtualDisplayCount.incrementAndGet()
                Log.i(TAG, "[$displayName] Virtual display created via Shizuku: id=$id, ${width}x${height}, surface attached")
                Log.i(TAG, "$vdImeLogPrefix [VD] name=$displayName displayId=$id width=$width height=$height dpi=$dpi event=create")
                FileLogger.i("VD", "$vdImeLogPrefix name=$displayName displayId=$id width=$width height=$height dpi=$dpi event=create")
                MirrorDiagnostics.log(DiagnosticEvent.VD_CREATED, "id=$id ${width}x${height}")
                null
            } else {
                Log.e(TAG, "[$displayName] Shizuku returned invalid display ID")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$displayName] Failed to create virtual display via Shizuku", e)
            displayId = -1
            null
        }
    }

    /**
     * Update surface on this specific virtual display.
     */
    fun setSurface(surface: Surface?) {
        val id = displayId
        if (id >= 0 && privilegedService != null) {
            try {
                privilegedService?.setSurface(id, surface)
                Log.i(TAG, "[$displayName] Surface updated on Virtual Display $id")
                Log.i(TAG, "$vdImeLogPrefix [VD] name=$displayName displayId=$id event=setSurface surfacePresent=${surface != null}")
                FileLogger.i("VD", "$vdImeLogPrefix name=$displayName displayId=$id event=setSurface surfacePresent=${surface != null}")
            } catch (e: Exception) {
                Log.e(TAG, "[$displayName] Failed to update surface on VD $id", e)
            }
        }
    }

    /**
     * Force the managed virtual display to stay awake/unlocked.
     */
    fun keepDisplayAwake() {
        val id = displayId
        if (id < 0) return
        try {
            privilegedService?.wakeUpDisplay(id)
            // Log.i(TAG, "[$displayName] Forced VD $id display state to ON")
        } catch (e: Exception) {
            Log.w(TAG, "[$displayName] Failed to force VD $id awake", e)
        }
    }

    /**
     * Turn the physical display panel on/off via SurfaceControl.
     */
    fun setPhysicalDisplayPower(on: Boolean): Boolean {
        return try {
            val svc = privilegedService ?: run {
                Log.w(TAG, "[$displayName] setPhysicalDisplayPower: no privileged service")
                return false
            }
            svc.setPhysicalDisplayPower(on)
            Log.i(TAG, "[$displayName] Physical display power: ${if (on) "ON" else "OFF"}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[$displayName] setPhysicalDisplayPower failed", e)
            false
        }
    }

    /** Returns true if the privileged service mirror is set. */
    fun isBound(): Boolean = isBound && privilegedService != null

    /** Display ID of the Shizuku-created virtual display, or -1. */
    fun getDisplayId(): Int = displayId

    /** Returns true if this virtual display is active. */
    fun hasVirtualDisplay(): Boolean = displayId >= 0 && privilegedService != null

    /** Resize the virtual display by ID without destroying it. */
    fun resizeDisplay(width: Int, height: Int, dpi: Int): Boolean {
        val id = displayId
        if (id < 0) return false
        return try {
            privilegedService?.resizeVirtualDisplay(id, width, height, dpi)
            true
        } catch (e: Exception) {
            Log.e(TAG, "[$displayName] Failed to resize VD $id", e)
            false
        }
    }

    /** Inject a touch event on this virtual display. */
    fun injectInput(action: Int, x: Float, y: Float, pointerId: Int) {
        val id = displayId
        if (id < 0) {
            Log.w(TAG, "[$displayName] injectInput skipped: displayId=$id")
            return
        }
        val svc = privilegedService
        if (svc == null) {
            Log.w(TAG, "[$displayName] injectInput skipped: privilegedService is null")
            return
        }
        try {
            
            // Wake up display instantly upon touch down to prevent black screens during drag-and-drop without ACTION_MOVE overhead.
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                svc.wakeUpDisplay(id)
            }
            
            svc.injectInput(id, action, x, y, pointerId)
        } catch (e: Exception) {
            Log.e(TAG, "[$displayName] Failed to inject input on display $id", e)
        }
    }

    /** Inject a multi-touch MotionEvent on this virtual display. */
    fun injectMotionEvent(event: android.view.MotionEvent) {
        injectMotionEventWithResult(event)
    }

    fun injectMotionEventWithResult(event: android.view.MotionEvent): Boolean {
        val id = displayId
        if (id < 0) {
            Log.w(TAG, "[$displayName] injectMotionEvent skipped: displayId=$id")
            return false
        }
        val svc = privilegedService
        if (svc == null) {
            Log.w(TAG, "[$displayName] injectMotionEvent skipped: privilegedService is null")
            return false
        }
        try {
            val action = event.actionMasked
            val pointerCount = event.pointerCount
            if (action != android.view.MotionEvent.ACTION_MOVE) {
                // Removed [InputTrace] inject_controller log for optimization
            }

            // Wake up display instantly upon touch down to strictly guarantee wake state while preventing ACTION_MOVE bottleneck.
            if (action == android.view.MotionEvent.ACTION_DOWN || 
                action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                svc.wakeUpDisplay(id)
            }
            return svc.injectMotionEventWithResult(id, event)
        } catch (e: Exception) {
            Log.e(TAG, "[$displayName] Failed to inject motion event on display $id", e)
            return false
        }
    }


    /** Launch the home screen on this managed virtual display. */
    fun launchHomeOnDisplay(): Boolean {
        val id = displayId
        if (id < 0) return false
        return try {
            privilegedService?.launchHomeOnDisplay(id)
            Log.i(TAG, "[$displayName] Launched HOME on virtual display $id")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[$displayName] Failed to launch HOME on virtual display $id", e)
            false
        }
    }

    /** Launch an app on this managed virtual display. */
    fun launchAppOnDisplay(packageName: String): Boolean {
        val id = displayId
        if (id < 0 || packageName.isEmpty()) return false
        return try {
            privilegedService?.launchAppOnDisplay(id, packageName)
            Log.i(TAG, "[$displayName] Launched $packageName on virtual display $id")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "[$displayName] Failed to launch $packageName on display $id (display not found?)", e)
            displayId = -1
            false
        } catch (e: Exception) {
            Log.e(TAG, "[$displayName] Failed to launch $packageName on display $id", e)
            false
        }
    }

    /** Launch an app on this managed virtual display with explicit cold start/forceStop control. */
    fun launchAppOnDisplayV2(packageName: String, forceStop: Boolean): Boolean {
        val id = displayId
        if (id < 0 || packageName.isEmpty()) return false
        return try {
            privilegedService?.launchAppOnDisplayV2(id, packageName, forceStop)
            Log.i(TAG, "[$displayName] Launched $packageName on virtual display $id via launchAppOnDisplayV2 (forceStop=$forceStop)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "[$displayName] Failed to launch $packageName on display $id via launchAppOnDisplayV2 (display not found?)", e)
            displayId = -1
            false
        } catch (e: Exception) {
            Log.e(TAG, "[$displayName] Failed to launch $packageName on display $id via launchAppOnDisplayV2", e)
            false
        }
    }

    /** Move an active task to this managed virtual display natively via system activity task manager binder. */
    fun moveTaskToDisplayNative(taskId: Int): Boolean {
        val id = displayId
        if (id < 0 || taskId < 0) return false
        return try {
            val success = privilegedService?.moveTaskToDisplayNative(taskId, id) ?: false
            if (success) {
                Log.i(TAG, "[$displayName] Natively moved task $taskId to virtual display $id")
            }
            success
        } catch (e: Exception) {
            Log.w(TAG, "[$displayName] Failed to natively move task $taskId to virtual display $id", e)
            false
        }
    }

    /** Launch an app on this virtual display with a string intent extra. */
    fun launchAppWithExtraOnDisplay(packageName: String, extraKey: String, extraValue: String): Boolean {
        val id = displayId
        if (id < 0 || packageName.isEmpty()) return false
        return try {
            privilegedService?.launchAppWithExtraOnDisplay(id, packageName, extraKey, extraValue)
            Log.i(TAG, "[$displayName] Launched $packageName with extra on virtual display $id")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "[$displayName] Failed to launch $packageName with extra on display $id (display not found?)", e)
            displayId = -1
            false
        } catch (e: Exception) {
            Log.e(TAG, "[$displayName] Failed to launch $packageName on display $id", e)
            false
        }
    }

    /**
     * Release just the virtual display, keeping the privileged service mirror.
     */
    fun releaseVirtualDisplay() {
        val releasedId = displayId
        if (releasedId >= 0) {
            try {
                privilegedService?.releaseVirtualDisplay(releasedId)
            } catch (e: Exception) {
                Log.w(TAG, "[$displayName] Failed to release virtual display", e)
            }
            totalReleaseVirtualDisplayCount.incrementAndGet()
            activeVirtualDisplayCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            Log.i(TAG, "$vdImeLogPrefix [VD] name=$displayName displayId=$releasedId event=releaseVirtualDisplay")
            FileLogger.i("VD", "$vdImeLogPrefix name=$displayName displayId=$releasedId event=releaseVirtualDisplay")
            MirrorDiagnostics.log(DiagnosticEvent.VD_STOPPED, "id=$releasedId")
        }
        virtualDisplay?.release()
        virtualDisplay = null
        displayId = -1
    }

    /**
     * Local-state release.
     */
    fun release() {
        val releasedId = displayId
        if (releasedId >= 0) {
            try {
                privilegedService?.releaseVirtualDisplay(releasedId)
            } catch (e: Exception) {
                Log.w(TAG, "[$displayName] Failed to release virtual display", e)
            }
            activeVirtualDisplayCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            Log.i(TAG, "$vdImeLogPrefix [VD] name=$displayName displayId=$releasedId event=release")
            FileLogger.i("VD", "$vdImeLogPrefix name=$displayName displayId=$releasedId event=release")
            MirrorDiagnostics.log(DiagnosticEvent.VD_STOPPED, "id=$releasedId (full release)")
        }
        totalReleaseCount.incrementAndGet()
        privilegedService = null
        isBound = false
        virtualDisplay?.release()
        virtualDisplay = null
        displayId = -1
    }
}

package com.castla.mirror.input

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.castla.mirror.server.TouchEvent
import kotlinx.coroutines.delay

class TouchInjector(private var displayWidth: Int, private var displayHeight: Int) {

    companion object {
        private const val TAG = "TouchInjector"
        private const val MAX_POINTERS = 10
        private const val MOVE_THROTTLE_MS = 12L
        private const val MOVE_POSITION_EPSILON_PX = 2.0f
        private var nextInjectorId = 1
    }

    private var inputManagerInstance: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null

    // Replaces setVirtualDisplayInjector().
    // This can be updated whenever controller changes without counting as a re-bind.
    private var controllerInjector: ((TouchEvent, MotionEvent) -> Boolean)? = null

    private val injectorId = nextInjectorId++
    private var inputSessionId = 0
    private var totalEvents = 0
    private var downEvents = 0
    private var moveEvents = 0
    private var upEvents = 0
    private var cancelEvents = 0
    private var orphanMoveEvents = 0
    private var orphanUpEvents = 0
    private var controllerUpdateCount = 0
    private var releaseCount = 0
    private var debugLaunchSeq = 0
    private var launchEvents = 0
    private var launchDownEvents = 0
    private var launchMoveEvents = 0
    private var launchUpEvents = 0
    private var launchCancelEvents = 0
    private var launchOrphanMoveEvents = 0
    private var launchOrphanUpEvents = 0
    private var launchQueueLagSamples = 0
    private var launchQueueLagTotalMs = 0L
    private var launchQueueLagMaxMs = 0L
    private var activeGestureDownTimeMs = 0L
    private var moveTimingSamples = 0
    private var droppedMoveSamples = 0
    private var lastInjectedMoveAtMs = 0L

    private data class PointerState(var x: Float, var y: Float)

    private val activePointers = mutableMapOf<Int, PointerState>()
    private val pointerOrder = mutableListOf<Int>()
    private val lastInjectedMovePositions = mutableMapOf<Int, PointerState>()
    private val browserToAndroidPointerId = mutableMapOf<Int, Int>()
    private val androidPointerIdsInUse = mutableSetOf<Int>()

    private val pointerProperties = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val pointerCoords = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }

    init {
        // Initialize Shizuku binder connection
        tryInitShizuku()
    }

    fun updateController(injector: ((TouchEvent, MotionEvent) -> Boolean)?) {
        controllerInjector = injector
        controllerUpdateCount += 1
    }

    fun detachController(reason: String = "manual") {
        controllerInjector = null
    }

    fun updateDimensions(width: Int, height: Int) {
        injectCancelForActivePointers(forceFallback = false)
        displayWidth = width
        displayHeight = height
        activePointers.clear()
        pointerOrder.clear()
        clearPointerIdMappings()
        activeGestureDownTimeMs = 0L
        droppedMoveSamples = 0
        lastInjectedMoveAtMs = 0L
        lastInjectedMovePositions.clear()
    }

    private fun tryInitShizuku() {
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getMethod("getInstance")
            inputManagerInstance = getInstance.invoke(null)
            injectMethod = imClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            Log.i(TAG, "Shizuku InputManager initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku InputManager unavailable", e)
        }
    }

    fun markDebugLaunch(launchSeq: Int) {
        debugLaunchSeq = launchSeq
        launchEvents = 0
        launchDownEvents = 0
        launchMoveEvents = 0
        launchUpEvents = 0
        launchCancelEvents = 0
        launchOrphanMoveEvents = 0
        launchOrphanUpEvents = 0
        launchQueueLagSamples = 0
        launchQueueLagTotalMs = 0L
        launchQueueLagMaxMs = 0L
        moveTimingSamples = 0
        droppedMoveSamples = 0
        lastInjectedMoveAtMs = 0L
        lastInjectedMovePositions.clear()
    }

    fun onTouchEvent(event: TouchEvent) {
        totalEvents += 1
        launchEvents += 1

        when (event.action) {
            "down" -> {
                downEvents += 1
                launchDownEvents += 1
            }
            "move" -> {
                moveEvents += 1
                launchMoveEvents += 1
            }
            "up" -> {
                upEvents += 1
                launchUpEvents += 1
            }
            "cancel" -> {
                cancelEvents += 1
                launchCancelEvents += 1
            }
        }

        if (event.action == "cancel") {
            injectCancelForActivePointers(forceFallback = false)
            activePointers.clear()
            pointerOrder.clear()
            clearPointerIdMappings()
            activeGestureDownTimeMs = 0L
            return
        }

        val injectStartElapsed = SystemClock.elapsedRealtime()
        val queueLagMs =
            if (event.receivedAtElapsedMs > 0L) injectStartElapsed - event.receivedAtElapsedMs else -1L
        if (queueLagMs >= 0L) {
            launchQueueLagSamples += 1
            launchQueueLagTotalMs += queueLagMs
            if (queueLagMs > launchQueueLagMaxMs) {
                launchQueueLagMaxMs = queueLagMs
            }
        }

        val effectiveDimensions = TouchInjectionMath.resolveDimensions(
            fallbackWidth = displayWidth,
            fallbackHeight = displayHeight,
            mappedWidth = event.mappedWidth,
            mappedHeight = event.mappedHeight,
        )
        val effectiveWidth = effectiveDimensions.width
        val effectiveHeight = effectiveDimensions.height
        val absX = event.x * effectiveWidth
        val absY = event.y * effectiveHeight
        val browserPointerId = event.pointerId

        if (event.action != "move") {
            Log.i(
                "TOUCH_TRACE",
                "[backend] action=${event.action} pane=${event.pane} id=$browserPointerId " +
                    "norm=${"%.4f".format(event.x)},${"%.4f".format(event.y)} " +
                    "display=${displayWidth}x${displayHeight} " +
                    "mapped=${event.mappedWidth}x${event.mappedHeight} " +
                    "effective=${effectiveWidth}x${effectiveHeight} " +
                    "abs=${"%.1f".format(absX)},${"%.1f".format(absY)}"
            )
        }

        val beforeCount = activePointers.size
        var androidPointerId = browserToAndroidPointerId[browserPointerId] ?: -1

        when (event.action) {
            "down" -> {
                if (activePointers.containsKey(browserPointerId)) {
                    injectCancelForActivePointers(forceFallback = false)
                    activePointers.clear()
                    pointerOrder.clear()
                    clearPointerIdMappings()
                    activeGestureDownTimeMs = 0L
                    Log.w(TAG, "Cleared stale touch state before DOWN for pointerId=$browserPointerId")
                }

                if (activePointers.size >= MAX_POINTERS) {
                    injectCancelForActivePointers(forceFallback = false)
                    activePointers.clear()
                    pointerOrder.clear()
                    clearPointerIdMappings()
                    activeGestureDownTimeMs = 0L
                }

                if (activePointers.isEmpty()) {
                    activeGestureDownTimeMs = SystemClock.uptimeMillis()
                }

                androidPointerId = allocateAndroidPointerId(browserPointerId)
                if (androidPointerId < 0) {
                    Log.w(TAG, "Failed to allocate android pointer id for browserPointerId=$browserPointerId")
                    return
                }

                activePointers[browserPointerId] = PointerState(absX, absY)
                lastInjectedMovePositions.remove(browserPointerId)

                if (!pointerOrder.contains(browserPointerId)) {
                    pointerOrder.add(browserPointerId)
                }
            }

            "move" -> {
                val state = activePointers[browserPointerId]
                if (state != null) {
                    state.x = absX
                    state.y = absY
                    androidPointerId = browserToAndroidPointerId[browserPointerId] ?: -1
                    val nowUptime = SystemClock.uptimeMillis()
                    val lastInjected = lastInjectedMovePositions[browserPointerId]
                    val duplicateMove =
                        lastInjected != null &&
                            kotlin.math.abs(lastInjected.x - absX) < MOVE_POSITION_EPSILON_PX &&
                            kotlin.math.abs(lastInjected.y - absY) < MOVE_POSITION_EPSILON_PX
                    val throttledMove = lastInjectedMoveAtMs > 0L && (nowUptime - lastInjectedMoveAtMs) < MOVE_THROTTLE_MS
                    if (duplicateMove || throttledMove) {
                        droppedMoveSamples += 1
                        return
                    }
                } else {
                    // Ignore orphan MOVE.
                    // Creating implicit DOWN here can leave stale pointer state.
                    orphanMoveEvents += 1
                    launchOrphanMoveEvents += 1
                    return
                }
            }

            "up" -> {
                val state = activePointers[browserPointerId]
                if (state != null) {
                    state.x = absX
                    state.y = absY
                    androidPointerId = browserToAndroidPointerId[browserPointerId] ?: -1
                } else {
                    orphanUpEvents += 1
                    launchOrphanUpEvents += 1
                    return
                }
            }

            else -> return
        }

        val pointerCount = activePointers.size.coerceAtMost(MAX_POINTERS)
        if (pointerCount <= 0) return

        val eventTime = SystemClock.uptimeMillis()
        val downTime = when {
            activeGestureDownTimeMs > 0L -> activeGestureDownTimeMs
            event.action == "down" -> eventTime
            else -> eventTime
        }

        var targetIndex = -1

        for (i in 0 until pointerCount) {
            val pid = pointerOrder.getOrNull(i) ?: continue
            val state = activePointers[pid] ?: continue
            val mappedAndroidPointerId = browserToAndroidPointerId[pid] ?: continue

            pointerProperties[i].apply {
                id = mappedAndroidPointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }

            pointerCoords[i].apply {
                x = state.x
                y = state.y
                pressure = if (event.action == "up" && pid == browserPointerId) 0.0f else 1.0f
                size = 1.0f
            }

            if (pid == browserPointerId) {
                targetIndex = i
            }
        }

        if (targetIndex < 0) return

        val hasOtherPointers = activePointers.keys.any { it != browserPointerId }

        val actionCode = when (event.action) {
            "down" -> {
                if (!hasOtherPointers) {
                    MotionEvent.ACTION_DOWN
                } else {
                    MotionEvent.ACTION_POINTER_DOWN or
                        (targetIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
            }

            "up" -> {
                if (!hasOtherPointers) {
                    MotionEvent.ACTION_UP
                } else {
                    MotionEvent.ACTION_POINTER_UP or
                        (targetIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
            }

            "move" -> MotionEvent.ACTION_MOVE
            else -> return
        }

        val motionEvent = MotionEvent.obtain(
            downTime,
            eventTime,
            actionCode,
            pointerCount,
            pointerProperties,
            pointerCoords,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        var accepted = false
        try {
            accepted = injectMotionEvent(event, motionEvent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject event", e)
            activePointers.clear()
            pointerOrder.clear()
        } finally {
            motionEvent.recycle()
        }

        if (event.action == "up") {
            activePointers.remove(browserPointerId)
            pointerOrder.remove(browserPointerId)
            lastInjectedMovePositions.remove(browserPointerId)
            releaseAndroidPointerId(browserPointerId)

            if (!hasOtherPointers) {
                activePointers.clear()
                pointerOrder.clear()
                clearPointerIdMappings()
                activeGestureDownTimeMs = 0L
            }
        }

        if (event.action == "move") {
            lastInjectedMoveAtMs = SystemClock.uptimeMillis()
            val injectedState = lastInjectedMovePositions.getOrPut(browserPointerId) { PointerState(absX, absY) }
            injectedState.x = absX
            injectedState.y = absY
            moveTimingSamples += 1
        }
    }

    fun release(forceFallbackCancel: Boolean = false, reason: String = "manual") {
        releaseCount += 1
        injectCancelForActivePointers(forceFallback = forceFallbackCancel)
        activePointers.clear()
        pointerOrder.clear()
        clearPointerIdMappings()
        activeGestureDownTimeMs = 0L
        moveTimingSamples = 0
        droppedMoveSamples = 0
        lastInjectedMoveAtMs = 0L
        lastInjectedMovePositions.clear()
        inputSessionId += 1
    }

    fun hasTrackedPointers(): Boolean {
        return activePointers.isNotEmpty() || pointerOrder.isNotEmpty() || activeGestureDownTimeMs > 0L
    }

    private fun injectCancelForActivePointers(forceFallback: Boolean) {
        val pointerCount = activePointers.size.coerceAtMost(MAX_POINTERS)

        if (pointerCount <= 0 && !forceFallback) return

        cancelEvents += 1

        val eventTime = SystemClock.uptimeMillis()
        val downTime = if (activeGestureDownTimeMs > 0L) activeGestureDownTimeMs else eventTime
        val cancelPointerCount = if (pointerCount > 0) pointerCount else 1

        for (i in 0 until cancelPointerCount) {
            val pid = pointerOrder.getOrNull(i) ?: 0
            val state = activePointers[pid]
                ?: PointerState(displayWidth / 2f, displayHeight / 2f)

            pointerProperties[i].apply {
                id = pid
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }

            pointerCoords[i].apply {
                x = state.x
                y = state.y
                pressure = 0.0f
                size = 1.0f
            }
        }

        val motionEvent = MotionEvent.obtain(
            downTime,
            eventTime,
            MotionEvent.ACTION_CANCEL,
            cancelPointerCount,
            pointerProperties,
            pointerCoords,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        try {
            injectMotionEvent(
                TouchEvent(
                    action = "cancel",
                    x = 0f,
                    y = 0f,
                    pointerId = 0,
                    pane = "primary"
                ),
                motionEvent
            )
            Log.i(
                TAG,
                "Injected ACTION_CANCEL for $cancelPointerCount pointer(s), " +
                    "tracked=$pointerCount fallback=$forceFallback"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject touch cancel", e)
        } finally {
            motionEvent.recycle()
        }
    }

    private fun injectMotionEvent(event: TouchEvent, motionEvent: MotionEvent): Boolean {
        val injector = controllerInjector

        return if (injector != null) {
            injector.invoke(event, motionEvent)
        } else {
            (injectMethod?.invoke(inputManagerInstance, motionEvent, 0) as? Boolean) ?: true
        }
    }

    private fun allocateAndroidPointerId(browserPointerId: Int): Int {
        browserToAndroidPointerId[browserPointerId]?.let { return it }
        for (candidate in 0 until MAX_POINTERS) {
            if (androidPointerIdsInUse.add(candidate)) {
                browserToAndroidPointerId[browserPointerId] = candidate
                return candidate
            }
        }
        return -1
    }

    private fun releaseAndroidPointerId(browserPointerId: Int) {
        val androidPointerId = browserToAndroidPointerId.remove(browserPointerId) ?: return
        androidPointerIdsInUse.remove(androidPointerId)
    }

    private fun clearPointerIdMappings() {
        browserToAndroidPointerId.clear()
        androidPointerIdsInUse.clear()
    }

    fun debugState(): String {
        val avgLaunchQueueLagMs =
            if (launchQueueLagSamples > 0) launchQueueLagTotalMs / launchQueueLagSamples else 0L
        return "session=$inputSessionId activePointers=${activePointers.size} trackedOrder=${pointerOrder.size} " +
            "events=$totalEvents down=$downEvents move=$moveEvents up=$upEvents cancel=$cancelEvents " +
            "orphanMove=$orphanMoveEvents orphanUp=$orphanUpEvents " +
            "launchSeq=$debugLaunchSeq launchEvents=$launchEvents launchDown=$launchDownEvents launchMove=$launchMoveEvents " +
            "launchUp=$launchUpEvents launchCancel=$launchCancelEvents launchOrphanMove=$launchOrphanMoveEvents " +
            "launchOrphanUp=$launchOrphanUpEvents launchQueueLagAvgMs=$avgLaunchQueueLagMs launchQueueLagMaxMs=$launchQueueLagMaxMs " +
            "pointerMaps=${browserToAndroidPointerId.size} " +
            "controllerUpdates=$controllerUpdateCount releases=$releaseCount " +
            "controllerAttached=${controllerInjector != null}"
    }
}

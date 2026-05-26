package com.castla.mirror.input

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.castla.mirror.server.TouchEvent

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
    private var controllerInjector: ((MotionEvent) -> Unit)? = null

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

    private val pointerProperties = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val pointerCoords = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }

    init {
        tryInitShizuku()
    }

    fun updateController(injector: ((MotionEvent) -> Unit)?) {
        controllerInjector = injector
        controllerUpdateCount += 1
        Log.i(
            TAG,
            "[InputDebug] injector#$injectorId updateController count=$controllerUpdateCount " +
                "attached=${injector != null} state=${debugState()}"
        )
    }

    fun detachController(reason: String = "manual") {
        controllerInjector = null
        Log.i(
            TAG,
            "[InputDebug] injector#$injectorId detachController reason=$reason state=${debugState()}"
        )
    }

    fun updateDimensions(width: Int, height: Int) {
        injectCancelForActivePointers(forceFallback = false)
        displayWidth = width
        displayHeight = height
        activePointers.clear()
        pointerOrder.clear()
        activeGestureDownTimeMs = 0L
        droppedMoveSamples = 0
        lastInjectedMoveAtMs = 0L
        lastInjectedMovePositions.clear()
        Log.i(TAG, "[InputDebug] injector#$injectorId updateDimensions ${width}x${height} state=${debugState()}")
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
        Log.i(TAG, "[InputDebug] injector#$injectorId markDebugLaunch launchSeq=$launchSeq state=${debugState()}")
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

        val absX = event.x * displayWidth
        val absY = event.y * displayHeight
        val pointerId = event.pointerId

        val beforeCount = activePointers.size

        when (event.action) {
            "down" -> {
                if (activePointers.containsKey(pointerId)) {
                    injectCancelForActivePointers(forceFallback = false)
                    activePointers.clear()
                    pointerOrder.clear()
                    activeGestureDownTimeMs = 0L
                    Log.w(TAG, "Cleared stale touch state before DOWN for pointerId=$pointerId")
                }

                if (activePointers.size >= MAX_POINTERS) {
                    injectCancelForActivePointers(forceFallback = false)
                    activePointers.clear()
                    pointerOrder.clear()
                    activeGestureDownTimeMs = 0L
                }

                if (activePointers.isEmpty()) {
                    activeGestureDownTimeMs = SystemClock.uptimeMillis()
                }

                activePointers[pointerId] = PointerState(absX, absY)
                lastInjectedMovePositions.remove(pointerId)

                if (!pointerOrder.contains(pointerId)) {
                    pointerOrder.add(pointerId)
                }
            }

            "move" -> {
                val state = activePointers[pointerId]
                if (state != null) {
                    state.x = absX
                    state.y = absY
                    val nowUptime = SystemClock.uptimeMillis()
                    val lastInjected = lastInjectedMovePositions[pointerId]
                    val duplicateMove =
                        lastInjected != null &&
                            kotlin.math.abs(lastInjected.x - absX) < MOVE_POSITION_EPSILON_PX &&
                            kotlin.math.abs(lastInjected.y - absY) < MOVE_POSITION_EPSILON_PX
                    val throttledMove = lastInjectedMoveAtMs > 0L && (nowUptime - lastInjectedMoveAtMs) < MOVE_THROTTLE_MS
                    if (duplicateMove || throttledMove) {
                        droppedMoveSamples += 1
                        if (droppedMoveSamples % 120 == 0) {
                            Log.i(
                                TAG,
                                "[InputTrace] move_drop_android pane=${event.pane} pointerId=$pointerId reason=${if (duplicateMove) "dedup" else "throttle"} " +
                                    "queueLagMs=$queueLagMs droppedMoves=$droppedMoveSamples sinceLastMoveMs=${if (lastInjectedMoveAtMs > 0L) nowUptime - lastInjectedMoveAtMs else -1L}"
                            )
                        }
                        return
                    }
                } else {
                    // Ignore orphan MOVE.
                    // Creating implicit DOWN here can leave stale pointer state.
                    orphanMoveEvents += 1
                    launchOrphanMoveEvents += 1
                    if (queueLagMs >= 120L) {
                        Log.w(
                            TAG,
                            "[InputDebug] injector#$injectorId ignored orphan MOVE pointerId=$pointerId state=${debugState()}"
                        )
                    }
                    return
                }
            }

            "up" -> {
                val state = activePointers[pointerId]
                if (state != null) {
                    state.x = absX
                    state.y = absY
                } else {
                    orphanUpEvents += 1
                    launchOrphanUpEvents += 1
                    Log.w(
                        TAG,
                        "[InputDebug] injector#$injectorId ignored orphan UP pointerId=$pointerId state=${debugState()}"
                    )
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
            else -> {
                Log.w(
                    TAG,
                    "[InputDebug] injector#$injectorId missing gesture downTime for action=${event.action} pointerId=$pointerId state=${debugState()}"
                )
                eventTime
            }
        }

        var targetIndex = -1

        for (i in 0 until pointerCount) {
            val pid = pointerOrder.getOrNull(i) ?: continue
            val state = activePointers[pid] ?: continue

            pointerProperties[i].apply {
                id = pid
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }

            pointerCoords[i].apply {
                x = state.x
                y = state.y
                pressure = if (event.action == "up" && pid == pointerId) 0.0f else 1.0f
                size = 1.0f
            }

            if (pid == pointerId) {
                targetIndex = i
            }
        }

        if (targetIndex < 0) {
            Log.w(
                TAG,
                "[InputDebug] injector#$injectorId target pointer missing pointerId=$pointerId state=${debugState()}"
            )
            return
        }

        val hasOtherPointers = activePointers.keys.any { it != pointerId }

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

        val injectStartNs = SystemClock.elapsedRealtimeNanos()
        try {
            injectMotionEvent(motionEvent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject event", e)
            activePointers.clear()
            pointerOrder.clear()
        } finally {
            motionEvent.recycle()
        }
        val injectDurationMs = (SystemClock.elapsedRealtimeNanos() - injectStartNs) / 1_000_000.0

        if (event.action == "up") {
            activePointers.remove(pointerId)
            pointerOrder.remove(pointerId)

            if (!hasOtherPointers) {
                activePointers.clear()
                pointerOrder.clear()
                activeGestureDownTimeMs = 0L
            }
        }

        if (event.action == "move") {
            lastInjectedMoveAtMs = SystemClock.uptimeMillis()
            val injectedState = lastInjectedMovePositions.getOrPut(pointerId) { PointerState(absX, absY) }
            injectedState.x = absX
            injectedState.y = absY
            moveTimingSamples += 1
            val totalSinceReceiveMs =
                if (event.receivedAtElapsedMs > 0L) SystemClock.elapsedRealtime() - event.receivedAtElapsedMs else -1L
            if (injectDurationMs >= 8.0 || queueLagMs >= 32L || moveTimingSamples % 120 == 0) {
                Log.i(
                    TAG,
                    "[InputTrace] move_path pane=${event.pane} pointerId=$pointerId queueLagMs=$queueLagMs injectMs=${"%.2f".format(java.util.Locale.US, injectDurationMs)} totalSinceReceiveMs=$totalSinceReceiveMs before=$beforeCount after=${activePointers.size} session=$inputSessionId"
                )
            }
        }

        if (event.action != "move" || queueLagMs >= 120L) {
            Log.i(
                TAG,
                "[InputDebug] injector#$injectorId onTouch action=${event.action} pane=${event.pane} " +
                    "pointerId=$pointerId queueLagMs=$queueLagMs before=$beforeCount after=${activePointers.size} " +
                    "session=$inputSessionId state=${debugState()}"
            )
        }
    }

    fun release(forceFallbackCancel: Boolean = false, reason: String = "manual") {
        releaseCount += 1
        injectCancelForActivePointers(forceFallback = forceFallbackCancel)
        activePointers.clear()
        pointerOrder.clear()
        activeGestureDownTimeMs = 0L
        moveTimingSamples = 0
        droppedMoveSamples = 0
        lastInjectedMoveAtMs = 0L
        lastInjectedMovePositions.clear()
        inputSessionId += 1

        Log.i(
            TAG,
            "[InputDebug] injector#$injectorId release releaseCount=$releaseCount " +
                "nextSession=$inputSessionId reason=$reason forceFallbackCancel=$forceFallbackCancel state=${debugState()}"
        )
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
            injectMotionEvent(motionEvent)
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

    private fun injectMotionEvent(motionEvent: MotionEvent) {
        val injector = controllerInjector

        if (injector != null) {
            injector.invoke(motionEvent)
        } else {
            injectMethod?.invoke(inputManagerInstance, motionEvent, 0)
        }
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
            "controllerUpdates=$controllerUpdateCount releases=$releaseCount " +
            "controllerAttached=${controllerInjector != null}"
    }
}

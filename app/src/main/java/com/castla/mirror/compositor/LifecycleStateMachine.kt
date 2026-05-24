package com.castla.mirror.compositor

import java.util.concurrent.atomic.AtomicReference

enum class SessionLifecycleState {
    NEW,
    VD_READY,
    SURFACE_READY,
    ENCODER_READY,
    STREAM_READY,
    WAITING_FIRST_FRAME,
    RUNNING,
    SUSPENDED,
    RECOVERING,
    RELEASED
}

class LifecycleStateMachine(initial: SessionLifecycleState = SessionLifecycleState.NEW) {
    private val stateRef = AtomicReference(initial)

    val state: SessionLifecycleState
        get() = stateRef.get()

    fun transitionTo(next: SessionLifecycleState): Boolean {
        while (true) {
            val current = stateRef.get()
            if (!isAllowed(current, next)) return false
            if (stateRef.compareAndSet(current, next)) return true
        }
    }

    private fun isAllowed(current: SessionLifecycleState, next: SessionLifecycleState): Boolean {
        if (current == next) return true
        if (current == SessionLifecycleState.RELEASED) return false
        if (next == SessionLifecycleState.RELEASED || next == SessionLifecycleState.RECOVERING) return true
        return when (current) {
            SessionLifecycleState.NEW -> next == SessionLifecycleState.VD_READY
            SessionLifecycleState.VD_READY -> next == SessionLifecycleState.SURFACE_READY || next == SessionLifecycleState.SUSPENDED
            SessionLifecycleState.SURFACE_READY -> next == SessionLifecycleState.ENCODER_READY || next == SessionLifecycleState.SUSPENDED
            SessionLifecycleState.ENCODER_READY -> next == SessionLifecycleState.STREAM_READY || next == SessionLifecycleState.SUSPENDED
            SessionLifecycleState.STREAM_READY -> next == SessionLifecycleState.WAITING_FIRST_FRAME || next == SessionLifecycleState.SUSPENDED
            SessionLifecycleState.WAITING_FIRST_FRAME -> next == SessionLifecycleState.RUNNING || next == SessionLifecycleState.SUSPENDED
            SessionLifecycleState.RUNNING -> next == SessionLifecycleState.SUSPENDED
            SessionLifecycleState.SUSPENDED -> next == SessionLifecycleState.SURFACE_READY || next == SessionLifecycleState.ENCODER_READY
            SessionLifecycleState.RECOVERING -> next != SessionLifecycleState.NEW
            SessionLifecycleState.RELEASED -> false
        }
    }
}

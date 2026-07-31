package com.castla.mirror.service

/** Flags used when creating or reusing an app task on a managed virtual display. */
object MultiDisplayLaunchPolicy {
    const val NEW_TASK = 0x10000000
    const val MULTIPLE_TASK = 0x08000000
    const val REORDER_TO_FRONT = 0x00020000

    /** Create a separate task unless the caller explicitly wants to reuse a warm task. */
    fun flags(reorderToFront: Boolean): Int =
        if (reorderToFront) NEW_TASK or REORDER_TO_FRONT else NEW_TASK or MULTIPLE_TASK

    fun shellFlags(reorderToFront: Boolean): String =
        "0x%08x".format(flags(reorderToFront))
    /** Reuse a warm task only when it is already resident on the requested display. */
    fun shouldReuseWarmTask(
        hasMatchingTasks: Boolean,
        existingTaskDisplayId: Int,
        targetDisplayId: Int,
        forceColdStart: Boolean,
    ): Boolean =
        hasMatchingTasks && !forceColdStart &&
            existingTaskDisplayId >= 0 && existingTaskDisplayId == targetDisplayId
}

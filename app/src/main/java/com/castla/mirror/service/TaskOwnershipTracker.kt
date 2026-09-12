package com.castla.mirror.service

import java.util.concurrent.ConcurrentHashMap

/** Tracks tasks temporarily borrowed from a physical display during one mirroring session. */
internal class TaskOwnershipTracker {
    private val borrowedOrigins = ConcurrentHashMap<Int, Int>()

    fun recordBorrowed(taskId: Int, originDisplayId: Int, targetDisplayId: Int) {
        if (taskId >= 0 && originDisplayId >= 0 && originDisplayId != targetDisplayId) {
            borrowedOrigins[taskId] = originDisplayId
        }
    }

    fun borrowedTasks(): Map<Int, Int> = borrowedOrigins.toMap()

    fun clear() {
        borrowedOrigins.clear()
    }
}

package com.castla.mirror.shizuku

/** Builds the primitive arguments used by framework getTasks overloads. */
internal object GetTasksQueryPolicy {
    fun intArguments(intParameterCount: Int, displayId: Int?): IntArray {
        require(intParameterCount >= 1)
        return IntArray(intParameterCount) { index ->
            when {
                index == 0 -> 100
                displayId != null && intParameterCount >= 2 && index == intParameterCount - 1 -> displayId
                displayId == null && intParameterCount >= 2 && index == intParameterCount - 1 -> -1
                else -> 0
            }
        }
    }
}


package com.castla.mirror.service

internal data class VirtualDisplayHomeTarget(
    val packageName: String,
    val className: String,
) {
    fun shellCommand(displayId: Int): String = "am start --display $displayId -n $packageName/$className"

    companion object {
        private const val HOME_CLASS = "com.castla.mirror.ui.VirtualDisplayHomeActivity"

        fun forApplication(applicationId: String): VirtualDisplayHomeTarget =
            VirtualDisplayHomeTarget(applicationId, HOME_CLASS)
    }
}

internal object VirtualDisplayHomePolicy {
    fun shouldPrimeBeforeRestore(isNewVirtualDisplay: Boolean, currentApp: String): Boolean =
        isNewVirtualDisplay && currentApp.isNotBlank() && currentApp != "HOME"

    fun shouldLaunchForEmptyDisplay(currentApp: String, activeTasks: List<String>): Boolean =
        activeTasks.isEmpty() &&
            currentApp.isNotBlank() &&
            currentApp != "HOME" &&
            currentApp != "com.android.settings"
}

internal enum class VirtualDisplayHomeAction {
    NONE,
    LAUNCH_HOME,
    REPORT_HOME,
}

internal class VirtualDisplayHomeMonitor {
    private val armedDisplays = mutableSetOf<Int>()
    private val reportedHomeDisplays = mutableSetOf<Int>()

    fun evaluate(
        displayId: Int,
        currentApp: String,
        activeTasks: List<String>,
    ): VirtualDisplayHomeAction {
        val homeComponents = activeTasks.filter(::isStandbyHomeTask)
        val homePackages = homeComponents.mapTo(mutableSetOf()) { it.substringBefore('/') }
        val hasHome = homeComponents.isNotEmpty()
        val hasApp = activeTasks.any { task ->
            !isStandbyHomeTask(task) && task.substringBefore('/') !in homePackages
        }

        if (hasApp) {
            armedDisplays += displayId
            reportedHomeDisplays -= displayId
            return VirtualDisplayHomeAction.NONE
        }

        if (hasHome) {
            val wasArmed = armedDisplays.remove(displayId)
            return if (wasArmed && reportedHomeDisplays.add(displayId)) {
                VirtualDisplayHomeAction.REPORT_HOME
            } else {
                VirtualDisplayHomeAction.NONE
            }
        }

        return if (
            displayId !in reportedHomeDisplays &&
            VirtualDisplayHomePolicy.shouldLaunchForEmptyDisplay(currentApp, activeTasks)
        ) {
            VirtualDisplayHomeAction.LAUNCH_HOME
        } else {
            VirtualDisplayHomeAction.NONE
        }
    }

    fun markHomeReported(displayId: Int) {
        armedDisplays -= displayId
        reportedHomeDisplays += displayId
    }

    private fun isStandbyHomeTask(task: String): Boolean =
        task.contains("VirtualDisplayHomeActivity")
}

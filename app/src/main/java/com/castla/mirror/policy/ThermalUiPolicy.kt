package com.castla.mirror.policy

enum class ThermalUiSeverity { NONE, INFO, WARNING, CRITICAL }

object ThermalUiPolicy {
    fun severity(status: Int): ThermalUiSeverity = when {
        status >= 3 -> ThermalUiSeverity.CRITICAL
        status == 2 -> ThermalUiSeverity.WARNING
        status == 1 -> ThermalUiSeverity.INFO
        else -> ThermalUiSeverity.NONE
    }

    fun isUrgent(status: Int): Boolean = status >= 2
}

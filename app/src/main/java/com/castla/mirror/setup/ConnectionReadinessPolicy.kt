package com.castla.mirror.setup

enum class ReadinessItemKind {
    SHIZUKU,
    BATTERY,
    LOCAL_NETWORK,
    APP_NOTIFICATIONS,
    NOTIFICATION_ACCESS,
}

enum class ReadinessState {
    COMPLETE,
    ACTION_REQUIRED,
    OPTIONAL,
}

data class ReadinessItem(
    val kind: ReadinessItemKind,
    val state: ReadinessState,
    val required: Boolean,
)

data class ConnectionReadiness(
    val items: List<ReadinessItem>,
) {
    val requiredTotal: Int = items.count { it.required }
    val requiredComplete: Int = items.count { it.required && it.state == ReadinessState.COMPLETE }
    val optionalTotal: Int = items.count { !it.required }
    val optionalComplete: Int = items.count { !it.required && it.state == ReadinessState.COMPLETE }
    val requiredReady: Boolean = requiredComplete == requiredTotal
}

object ConnectionReadinessPolicy {
    fun evaluate(
        shizukuConnected: Boolean,
        batteryUnrestricted: Boolean,
        notificationAccess: Boolean,
        localNetworkRequired: Boolean = false,
        localNetworkGranted: Boolean = true,
        appNotificationsRequired: Boolean = false,
        appNotificationsGranted: Boolean = true,
    ): ConnectionReadiness {
        val items = mutableListOf(
            required(ReadinessItemKind.SHIZUKU, shizukuConnected),
            required(ReadinessItemKind.BATTERY, batteryUnrestricted),
        )
        if (localNetworkRequired) {
            items += required(ReadinessItemKind.LOCAL_NETWORK, localNetworkGranted)
        }
        if (appNotificationsRequired) {
            items += optional(ReadinessItemKind.APP_NOTIFICATIONS, appNotificationsGranted)
        }
        items += optional(ReadinessItemKind.NOTIFICATION_ACCESS, notificationAccess)
        return ConnectionReadiness(items)
    }

    private fun required(kind: ReadinessItemKind, complete: Boolean) = ReadinessItem(
        kind = kind,
        state = if (complete) ReadinessState.COMPLETE else ReadinessState.ACTION_REQUIRED,
        required = true,
    )

    private fun optional(kind: ReadinessItemKind, complete: Boolean) = ReadinessItem(
        kind = kind,
        state = if (complete) ReadinessState.COMPLETE else ReadinessState.OPTIONAL,
        required = false,
    )
}

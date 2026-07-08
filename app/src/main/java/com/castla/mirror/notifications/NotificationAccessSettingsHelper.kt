package com.castla.mirror.notifications

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object NotificationAccessSettingsHelper {
    fun isNotificationAccessEnabled(
        enabledListenersValue: String?,
        packageName: String,
        className: String,
    ): Boolean {
        if (enabledListenersValue.isNullOrBlank()) return false

        val fullComponent = "$packageName/$className"
        val shortClassName = if (className.startsWith(packageName)) {
            className.removePrefix(packageName)
        } else {
            className
        }
        val shortComponent = "$packageName/$shortClassName"

        return enabledListenersValue
            .split(':')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { entry ->
                entry.equals(fullComponent, ignoreCase = true) ||
                    entry.equals(shortComponent, ignoreCase = true)
            }
    }

    fun isNotificationAccessEnabled(
        context: Context,
        serviceClass: Class<*>,
    ): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        )
        val componentName = ComponentName(context, serviceClass)
        return isNotificationAccessEnabled(
            enabledListenersValue = enabledListeners,
            packageName = componentName.packageName,
            className = componentName.className,
        )
    }

    fun shouldAutoOpenSettings(
        hasAccess: Boolean,
        hasPromptedBefore: Boolean,
    ): Boolean = !hasAccess && !hasPromptedBefore
}

package com.castla.mirror.service

/** Builds shell commands used by the privileged display launch fallback paths. */
object ShellLaunchCommandBuilder {
    fun escapeShellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun buildExternalBrowserCommand(displayId: Int, url: String, browserComponent: String): String =
        "am start --display $displayId -f 0x18000000 -a android.intent.action.VIEW -d ${escapeShellArg(url)} -n ${escapeShellArg(browserComponent)}".trim()

    fun buildAppLaunchCommand(
        displayId: Int,
        packageOrComponent: String,
        resolvedComponent: String?,
        flags: String,
        extraKey: String? = null,
        extraValue: String? = null,
    ): String {
        val launchTarget = resolvedComponent ?: packageOrComponent
        return buildString {
            append("am start --display $displayId -f $flags ")
            if (resolvedComponent != null) {
                append("-n ${escapeShellArg(resolvedComponent)} ")
            } else {
                append("-a android.intent.action.MAIN -c android.intent.category.LAUNCHER ")
                append("-p ${escapeShellArg(launchTarget)} ")
            }
            if (!extraKey.isNullOrEmpty() && extraValue != null) {
                append("--es $extraKey ${escapeShellArg(extraValue)} ")
            }
        }.trim()
    }
}
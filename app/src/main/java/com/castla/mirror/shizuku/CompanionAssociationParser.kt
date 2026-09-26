package com.castla.mirror.shizuku

/** Handles both detailed and table output from `cmd companiondevice list`. */
internal object CompanionAssociationParser {
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val APP_STREAMING_PROFILE = "android.app.role.COMPANION_DEVICE_APP_STREAMING"
    private const val CASTLA_DEVICE_ADDRESS = "02:CA:57:1A:00:01"

    fun findShellAppStreamingId(output: String): Int? {
        val detailedId = output.lineSequence()
            .filter { line ->
                line.contains("mPackageName='$SHELL_PACKAGE'") &&
                    line.contains("mDeviceProfile='$APP_STREAMING_PROFILE'")
            }
            .mapNotNull { line ->
                Regex("mId=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull()
        if (detailedId != null) return detailedId

        return output.lineSequence()
            .mapNotNull { line ->
                val columns = line.split('|').map(String::trim)
                if (columns.size < 3 ||
                    columns[1] != SHELL_PACKAGE ||
                    !columns[2].equals(CASTLA_DEVICE_ADDRESS, ignoreCase = true)
                ) {
                    return@mapNotNull null
                }
                columns[0].toIntOrNull()
            }
            .maxOrNull()
    }
}

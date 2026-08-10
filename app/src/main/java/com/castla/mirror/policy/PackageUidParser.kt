package com.castla.mirror.policy

object PackageUidParser {
    private val linePattern = Regex("^package:(\\S+)\\s+uid:(\\d+)$")

    fun parse(output: String, packageName: String): Int = output.lineSequence()
        .map(String::trim)
        .mapNotNull(linePattern::matchEntire)
        .firstOrNull { it.groupValues[1] == packageName }
        ?.groupValues
        ?.get(2)
        ?.toIntOrNull()
        ?: -1
}

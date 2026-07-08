package com.castla.mirror.notifications

data class FrontendNotificationPayload(
    val id: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAtMs: Long,
)

object FrontendNotificationFormatter {
    const val MAX_TITLE_LENGTH = 48
    const val MAX_TEXT_LENGTH = 140

    fun build(
        packageName: String,
        appLabel: String?,
        title: CharSequence?,
        text: CharSequence?,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        postedAtMs: Long = System.currentTimeMillis(),
        notificationKey: String = packageName,
    ): FrontendNotificationPayload? {
        if (isOngoing || isGroupSummary) return null

        val sanitizedAppLabel = sanitize(appLabel, MAX_TITLE_LENGTH).ifBlank { packageName }
        val sanitizedTitle = sanitize(title, MAX_TITLE_LENGTH).ifBlank { sanitizedAppLabel }
        val sanitizedText = sanitize(text, MAX_TEXT_LENGTH)
        if (sanitizedText.isBlank()) return null

        return FrontendNotificationPayload(
            id = notificationKey,
            packageName = packageName,
            appLabel = sanitizedAppLabel,
            title = sanitizedTitle,
            text = sanitizedText,
            postedAtMs = postedAtMs,
        )
    }

    private fun sanitize(value: CharSequence?, maxLength: Int): String {
        val normalized = value
            ?.toString()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        if (normalized.length <= maxLength) return normalized
        return normalized.take(maxLength).trimEnd() + "…"
    }
}

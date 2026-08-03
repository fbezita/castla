package com.castla.mirror.service

data class ContentRestoreRequest(
    val currentApp: String,
    val currentWebUrl: String?,
    val activeDisplayId: Int,
    val isVirtualDisplayCurrent: () -> Boolean,
)

/** Restores the content selected for a pipeline after its virtual display is recreated. */
class ContentRestoreCoordinator(
    private val markMutation: (String) -> Unit,
    private val setCurrentApp: (String) -> Unit,
    private val launchHome: () -> Unit,
    private val resolveBrowserComponent: (String) -> String?,
    private val launchExternalBrowser: suspend (String, String) -> Boolean,
    private val launchOwnActivity: suspend (String, String) -> Unit,
    private val launchComponent: suspend (String) -> Unit,
) {
    suspend fun restore(request: ContentRestoreRequest) {
        if (!request.isVirtualDisplayCurrent()) return
        markMutation("restore_content_begin(activeId=${request.activeDisplayId})")

        when (request.currentApp) {
            "HOME", "", "com.android.settings" -> {
                setCurrentApp("HOME")
                markMutation("restore_content_home")
                launchHome()
            }
            else -> {
                val webUrl = request.currentWebUrl
                if (webUrl != null && !request.currentApp.contains("WebBrowserActivity")) {
                    val browserComponent = resolveBrowserComponent(webUrl)
                    val launched = browserComponent?.let {
                        launchExternalBrowser(webUrl, it)
                    } ?: false
                    if (!launched) launchOwnActivity("com.castla.mirror.ui.WebBrowserActivity", webUrl)
                } else if (request.currentApp.contains("WebBrowserActivity")) {
                    markMutation("restore_content_browser_activity")
                    launchOwnActivity(
                        request.currentApp.substringAfter('/'),
                        webUrl ?: "https://m.youtube.com",
                    )
                } else {
                    markMutation("restore_content_launch_component")
                    launchComponent(request.currentApp)
                }
            }
        }
    }
}

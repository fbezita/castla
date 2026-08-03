package com.castla.mirror.service

data class BrowserLaunchContext(
    val displayId: Int,
    val lastValidWidth: Int,
    val lastValidHeight: Int,
    val requestedWidth: Int,
    val requestedHeight: Int,
)

/** Coordinates external-browser selection and the embedded browser fallback. */
class PipelineBrowserLaunchCoordinator(
    private val resolveBrowser: (String) -> String?,
    private val forceEmbeddedBrowser: (String?) -> Boolean,
    private val embeddedComponent: () -> String,
    private val isSameActivePage: (String, String) -> Boolean,
    private val updateState: (String, String, Boolean) -> Unit,
    private val requestMissingDisplayRecovery: suspend (String, String?, Int, Int) -> Unit,
    private val launchExternalBrowser: suspend (String, String) -> Boolean,
    private val launchEmbeddedBrowser: suspend (String, String) -> Unit,
    private val restartStream: () -> Unit,
    private val rebalanceBitrates: () -> Unit,
) {
    suspend fun launch(
        url: String,
        sourceAppPackage: String?,
        allowFallback: Boolean,
        forceEmbedded: Boolean,
        context: BrowserLaunchContext,
    ) {
        val browserComponent = if (forceEmbedded || forceEmbeddedBrowser(sourceAppPackage)) {
            null
        } else {
            resolveBrowser(url)
        }
        val targetComponent = browserComponent ?: embeddedComponent()
        if (context.displayId >= 0 && isSameActivePage(url, targetComponent)) {
            restartStream()
            rebalanceBitrates()
            return
        }
        if (context.displayId < 0) {
            updateState(targetComponent, url, browserComponent != null)
            val fallbackW = if (context.lastValidWidth > 0) context.lastValidWidth else 720
            val fallbackH = if (context.lastValidHeight > 0) context.lastValidHeight else 720
            requestMissingDisplayRecovery(
                url,
                browserComponent,
                if (context.requestedWidth > 0) context.requestedWidth else fallbackW,
                if (context.requestedHeight > 0) context.requestedHeight else fallbackH,
            )
            return
        }
        if (browserComponent != null && launchExternalBrowser(url, browserComponent)) {
            updateState(browserComponent, url, true)
            restartStream()
            rebalanceBitrates()
            return
        }
        if (allowFallback) {
            launchEmbeddedBrowser(url, targetComponent)
            updateState(targetComponent, url, false)
            restartStream()
            rebalanceBitrates()
        }
    }
}

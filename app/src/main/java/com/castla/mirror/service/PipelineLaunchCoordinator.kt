package com.castla.mirror.service

data class StandardLaunchContext(
    val displayId: Int,
    val requestedWidth: Int,
    val requestedHeight: Int,
    val lastValidWidth: Int,
    val lastValidHeight: Int,
)

/** Coordinates standard-app launch entry points while leaving pipeline state ownership in the pipeline. */
class PipelineLaunchCoordinator(
    private val normalizeTarget: (String) -> String,
    private val launchComponent: suspend (String, Boolean) -> Boolean,
    private val logRecovery: (String) -> Unit,
    private val updateState: (String) -> Unit,
    private val requestRecovery: suspend (String, Boolean, Int, Int) -> Unit,
    private val rebalanceBitrates: () -> Unit,
) {
    suspend fun launchStandard(
        launchTarget: String,
        forceDisplayId: Boolean,
        context: StandardLaunchContext,
    ) {
        val resolvedTarget = normalizeTarget(launchTarget)
        val launched = if (context.displayId >= 0) {
            launchComponent(resolvedTarget, forceDisplayId)
        } else {
            false
        }
        if (!launched) {
            logRecovery(
                "launch_standard_defer pkg=$resolvedTarget displayId=${context.displayId} " +
                    "requested=${context.requestedWidth}x${context.requestedHeight} " +
                    "lastValid=${context.lastValidWidth}x${context.lastValidHeight}"
            )
            updateState(resolvedTarget)
            val fallbackW = if (context.lastValidWidth > 0) context.lastValidWidth else 720
            val fallbackH = if (context.lastValidHeight > 0) context.lastValidHeight else 720
            requestRecovery(
                resolvedTarget,
                forceDisplayId,
                if (context.requestedWidth > 0) context.requestedWidth else fallbackW,
                if (context.requestedHeight > 0) context.requestedHeight else fallbackH,
            )
        } else {
            updateState(resolvedTarget)
            rebalanceBitrates()
        }
    }
}

package com.castla.mirror.service

/**
 * Keeps latency-sensitive app launch work ahead of optional post-launch work.
 * The scheduler must enqueue [postLaunch] without running it inline.
 */
class NonBlockingPostLaunchCoordinator(
    private val schedule: (() -> Unit) -> Unit,
) {
    suspend fun execute(
        launch: suspend () -> Unit,
        postLaunch: () -> Unit,
    ) {
        launch()
        schedule(postLaunch)
    }
}

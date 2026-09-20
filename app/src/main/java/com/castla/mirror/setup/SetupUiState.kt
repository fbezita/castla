package com.castla.mirror.setup

data class SetupSnapshot(
    val installed: Boolean,
    val running: Boolean = false,
    val permitted: Boolean = false,
    val serviceConnected: Boolean = false,
    val failure: String? = null,
)

sealed interface SetupUiState {
    data object NotInstalled : SetupUiState
    data object NotRunning : SetupUiState
    data object PermissionRequired : SetupUiState
    data object Connecting : SetupUiState
    data object Ready : SetupUiState
    data class Failed(val reason: String) : SetupUiState

    companion object {
        fun resolve(snapshot: SetupSnapshot): SetupUiState = when {
            !snapshot.installed -> NotInstalled
            snapshot.failure != null -> Failed(snapshot.failure)
            !snapshot.running -> NotRunning
            !snapshot.permitted -> PermissionRequired
            !snapshot.serviceConnected -> Connecting
            else -> Ready
        }
    }
}

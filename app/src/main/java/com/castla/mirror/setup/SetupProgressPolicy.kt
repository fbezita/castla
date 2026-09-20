package com.castla.mirror.setup

internal object SetupProgressPolicy {
    fun activeStep(state: SetupUiState): Int = when (state) {
        SetupUiState.NotInstalled -> 0
        SetupUiState.NotRunning -> 1
        SetupUiState.PermissionRequired -> 2
        SetupUiState.Connecting,
        is SetupUiState.Failed -> 3
        SetupUiState.Ready -> 4
    }
}

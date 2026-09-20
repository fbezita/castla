package com.castla.mirror.setup

import android.content.Context
import android.content.pm.PackageManager
import com.castla.mirror.shizuku.ShizukuSetup
import com.castla.mirror.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Single source of truth for the mandatory Shizuku runtime prerequisite. */
class SetupCoordinator(
    context: Context,
    private val shizukuSetup: ShizukuSetup,
    scope: CoroutineScope,
) {
    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }

    private val appContext = context.applicationContext
    private val installed = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SetupUiState> = combine(
        installed,
        shizukuSetup.state,
        shizukuSetup.serviceConnected,
        failure,
    ) { isInstalled, shizukuState, serviceConnected, failureReason ->
        val runningState = shizukuState as? ShizukuState.Running
        SetupUiState.resolve(
            SetupSnapshot(
                installed = isInstalled,
                running = runningState != null,
                permitted = runningState?.permitted == true,
                serviceConnected = serviceConnected,
                failure = failureReason,
            )
        )
    }.stateIn(scope, SharingStarted.Eagerly, SetupUiState.NotInstalled)

    init {
        scope.launch {
            uiState.collectLatest { state ->
                if (state != SetupUiState.Connecting) return@collectLatest
                delay(8_000L)
                if (uiState.value == SetupUiState.Connecting) {
                    reportFailure("Privileged service connection timed out")
                }
            }
        }
    }

    fun refreshInstallation(): Boolean {
        val detected = try {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        installed.value = detected
        if (!detected) failure.value = null
        return detected
    }

    fun isInstalled(): Boolean = installed.value

    fun reportFailure(reason: String) {
        failure.value = reason
    }

    fun clearFailure() {
        failure.value = null
    }
}

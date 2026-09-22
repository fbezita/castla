package com.castla.mirror

import android.Manifest
import com.castla.mirror.BuildConfig
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.castla.mirror.server.MirrorServer
import com.castla.mirror.server.MirrorServerAvailability
import com.castla.mirror.server.MirrorServerAvailabilityState
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.castla.mirror.network.NetworkMonitor
import com.castla.mirror.network.NetworkState
import com.castla.mirror.network.CastlaDeviceId
import com.castla.mirror.network.RelayRetryPolicy
import com.castla.mirror.automation.RoutineAutomationPolicy
import com.castla.mirror.notifications.CastlaNotificationListenerService
import com.castla.mirror.notifications.NotificationAccessSettingsHelper
import com.castla.mirror.service.HotspotClientDetector
import com.castla.mirror.service.MirrorForegroundService
import com.castla.mirror.service.TeslaBleScanner
import com.castla.mirror.service.TeslaDetectNotifier
import com.castla.mirror.setup.SetupCoordinator
import com.castla.mirror.setup.SetupUiState
import com.castla.mirror.shizuku.ShizukuSetup
import com.castla.mirror.shizuku.ShizukuInstallLinks
import com.castla.mirror.ui.SettingsScreen
import com.castla.mirror.ui.ShizukuSetupScreen
import com.castla.mirror.ui.MirroringMode
import com.castla.mirror.ui.StreamSettings
import com.castla.mirror.ui.MeshGradientBackground
import com.castla.mirror.ui.glassCard
import com.castla.mirror.update.ForceUpdateDialog
import com.castla.mirror.update.UpdateManager
import com.castla.mirror.update.UpdateManagerFactory
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val USB_CONFIG_PREFS = "usb_config_advisory"
        private const val KEY_SUPPRESS_USB_CONFIG_WARNING = "suppress_warning"
        private const val CASTLA_DOMAIN = "castla.fbezita.com"
        // User-facing stable entrypoint. The backend redirects this to the active
        // per-device relay URL: https://c-{deviceId}.castla.fbezita.com:9090
        private const val CASTLA_PUBLIC_URL = "https://castla.fbezita.com"
    }

    private var isStreaming by mutableStateOf(false)
    private var isPreparing by mutableStateOf(false)
    private var serverUrl by mutableStateOf("")
    private var serverAvailability by mutableStateOf(MirrorServerAvailability.IDLE)
    private var currentIp by mutableStateOf("0.0.0.0")
    private var showSettings by mutableStateOf(false)
    private var streamSettings by mutableStateOf(StreamSettings())
    private var setupUiState by mutableStateOf<SetupUiState>(SetupUiState.NotInstalled)
    private var isShizukuOnPowerAllowlist by mutableStateOf(false)
    private var showHotspotOffDialog by mutableStateOf(false)
    private var showUsbConfigWarningDialog by mutableStateOf(false)
    private var teslaAutoDetectEnabled by mutableStateOf(false)
    private var hotspotEnabledByApp = false
    private var isHotspotActive by mutableStateOf(false)
    private var teslaBleScanner: TeslaBleScanner? = null

    // Text Input & IME states
    private var isImeEnabled by mutableStateOf(false)
    private var isImeSelected by mutableStateOf(false)
    private var isCastlaImeActive by mutableStateOf(false)
    private var isNotificationAccessEnabled by mutableStateOf(false)

    // Shizuku download state

    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var shizukuSetup: ShizukuSetup
    private lateinit var setupCoordinator: SetupCoordinator
    private lateinit var updateManager: UpdateManager
    private var mirrorService: MirrorForegroundService? = null
    private var serviceBound = false
    private var bindRequested = false
    private var isCleanupInProgress by mutableStateOf(false)
    private var pendingStartAfterCleanup = false
    private var pendingAutomationStart = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as MirrorForegroundService.LocalBinder
            mirrorService = localBinder.service
            if (!isStreaming) {
                isStreaming = localBinder.service.isRunning
            }
            serverAvailability = localBinder.service.getMirrorServer()?.getAvailability()
                ?: MirrorServerAvailability.IDLE
            val serviceWasAlreadyRunning = localBinder.service.isRunning
            if (streamSettings.mirroringMode == MirroringMode.FULL_SCREEN && !serviceWasAlreadyRunning) {
                localBinder.service.setBrowserConnectionListener { connected ->
                    if (connected) runOnUiThread {
//                        moveTaskToBack(true)
                    }
                }
            }

            updateServerUrl()
            serviceBound = true
            bindRequested = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mirrorService = null
            serviceBound = false
            serverAvailability = MirrorServerAvailability.IDLE
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        startMirrorService()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        proceedAfterNotificationPermission()
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.i(TAG, "Bluetooth permissions: $results")
        if (hasBleScanPermissions()) {
            startAutoDetect()
        } else {
            teslaAutoDetectEnabled = false
            getSharedPreferences("castla_settings", MODE_PRIVATE).edit()
                .putBoolean("auto_detect_enabled", false).apply()
        }
    }

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginMirroringStartFlow("local_network_granted")
        } else {
            clearPreparingState(getString(R.string.toast_error, "local network permission is required"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updateManager = UpdateManagerFactory.create()
        updateManager.checkForUpdate(this)

        networkMonitor = NetworkMonitor(this)
        networkMonitor.startMonitoring()
        streamSettings = StreamSettings.load(this)

        shizukuSetup = (application as CastlaApp).shizukuSetup
        setupCoordinator = SetupCoordinator(this, shizukuSetup, lifecycleScope)
        if (setupCoordinator.refreshInstallation()) {
            (application as CastlaApp).ensureShizukuInitialized()
        }

        loadAutoDetectState()
        refreshNotificationAccessState()
        refreshShizukuBatteryOptimizationState()

        // Handle intent extra to open settings (e.g. from screenshot automation)
        if (intent?.getBooleanExtra("open_settings", false) == true) {
            showSettings = true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.state.collect { state ->
                    when (state) {
                        is NetworkState.Connected -> {
                            currentIp = state.ip
                            updateServerUrl()
                            (mirrorService ?: MirrorForegroundService.instance)?.refreshRelayRegistration(
                                resolveReachableMirrorIp(),
                                "network_connected_or_changed",
                            )
                            // WebCodecs now uses the public Castla entrypoint. The backend
                            // redirects to the active per-device local relay.
                        }
                        is NetworkState.Disconnected -> {
                            currentIp = "0.0.0.0"
                            updateServerUrl()
                        }
                    }
                }
            }
        }

        // Sync UI streaming state when service stops externally
        // (e.g. notification action, thermal auto-stop, crash)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorForegroundService.serviceRunningFlow.collect { running ->
                    if (!running && isStreaming) {
                        isStreaming = false
                        serverAvailability = MirrorServerAvailability.IDLE
                        if (!pendingStartAfterCleanup) {
                            isPreparing = false
                        }
                        mirrorService = null
                        if (serviceBound || bindRequested) {
                            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
                            serviceBound = false
                            bindRequested = false
                        }
                        updateServerUrl()
                        // Clean up hotspot that was auto-enabled by the app
                        if (!pendingStartAfterCleanup && hotspotEnabledByApp && shizukuSetup.serviceConnected.value) {
                            hotspotEnabledByApp = false
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                disableHotspot()
                                Log.i(TAG, "Auto-disabled hotspot after external service stop")
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorForegroundService.serverAvailabilityFlow.collect { availability ->
                    serverAvailability = availability
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorForegroundService.cleanupInProgressFlow.collect { cleanupInProgress ->
                    val wasCleanupInProgress = isCleanupInProgress
                    isCleanupInProgress = cleanupInProgress
                    if (wasCleanupInProgress && !cleanupInProgress && pendingStartAfterCleanup) {
                        pendingStartAfterCleanup = false
                        Log.i(TAG, "Cleanup finished — continuing queued mirroring start")
                        beginMirroringStartFlow("cleanup_completed")
                    }
                    if (wasCleanupInProgress && !cleanupInProgress) {
                        maybeStartFromAutomation()
                    }
                }
            }
        }


        lifecycleScope.launch {
            setupCoordinator.uiState.collect { state ->
                val previous = setupUiState
                setupUiState = state
                Log.i(TAG, "Setup state: $state")
                if (previous == SetupUiState.NotRunning && state != SetupUiState.NotRunning) {
                    refreshShizukuBatteryOptimizationState()
                }
                if (previous != SetupUiState.Ready && state == SetupUiState.Ready) {
                    requestBatteryOptimizationExemption()
                }
                if (state == SetupUiState.Ready) {
                    maybeStartFromAutomation()
                }
            }
        }

        lifecycleScope.launch {
            shizukuSetup.serviceConnected.collect { connected ->
                Log.i(TAG, "Shizuku PrivilegedService connected: $connected")
                if (connected) {
                    launch(kotlinx.coroutines.Dispatchers.IO) {
                        val ok = shizukuSetup.ensureShizukuHardened()
                        Log.i(TAG, "ensureShizukuHardened (MainActivity): $ok")
                        evaluateUsbConfigAdvisory()
                    }
                }
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                updateManager.ForceUpdateOverlay(this@MainActivity)

                val thermalStatus by (mirrorService?.thermalStatus
                    ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()
                if (setupUiState != SetupUiState.Ready) {
                    ShizukuSetupScreen(
                        state = setupUiState,
                        onInstall = { openShizukuDownloadPage() },
                        onOpenShizuku = { openShizukuApp() },
                        onOpenGuide = {
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://website-ten-sigma-42.vercel.app/setup"),
                                )
                            )
                        },
                        onGrantPermission = { shizukuSetup.requestPermission() },
                        onRetry = {
                            setupCoordinator.clearFailure()
                            if (shizukuSetup.isAvailable() && shizukuSetup.hasPermission()) {
                                shizukuSetup.bindPrivilegedService()
                            } else {
                                openShizukuApp()
                            }
                        },
                    )
                } else if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        settings = streamSettings,
                        isStreaming = isStreaming,
                        thermalStatus = thermalStatus,
                        onSettingsChanged = { newSettings ->
                            streamSettings = newSettings
                            StreamSettings.save(this@MainActivity, newSettings)
                            mirrorService?.updateVideoLatencySettings(
                                newSettings.teslaBluetoothVideoLatencyMs,
                                newSettings.streamedAudioVideoLatencyMs,
                            )
                        },
                        onBackClick = { showSettings = false },
                        onCheckUpdate = {
                            updateManager.checkForUpdate(this@MainActivity, force = true)
                        }
                    )
                } else {
                    CastlaScreen(
                        isStreaming = isStreaming,
                        isPreparing = isPreparing,
                        serverUrl = serverUrl,
                        serverAvailability = serverAvailability,
                        streamSettings = streamSettings,
                        reachableMirrorIp = resolveReachableMirrorIp(),
                        isImeEnabled = isImeEnabled,
                        isImeSelected = isImeSelected,
                        isCastlaImeActive = isCastlaImeActive,
                        isNotificationAccessEnabled = isNotificationAccessEnabled,
                        onRestoreIme = {
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    com.castla.mirror.input.ImeSwitchManager.restorePreviousIme(this@MainActivity) { cmd ->
                                        shizukuSetup.exec(cmd)
                                    }
                                    Log.i(TAG, "User manually requested keyboard restore.")
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        refreshTextInputPermissions()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed user manually restoring keyboard", e)
                                }
                            }
                        },
                        onStartClick = { onStartMirroring() },
                        onStopClick = { stopMirrorService() },
                        onSettingsClick = { showSettings = true },
                        onEnableIme = {
                            com.castla.mirror.input.TextInputSettingsHelper.navigateToEnableImeSettings(this@MainActivity)
                        },
                        onSelectIme = {
                            com.castla.mirror.input.TextInputSettingsHelper.showInputMethodPicker(this@MainActivity)
                        },
                        onOpenNotificationAccessSettings = {
                            openNotificationAccessSettings()
                        },
                        isHotspotActive = isHotspotActive,
                        onToggleHotspot = { toggleHotspot() },
                        autoHotspot = streamSettings.autoHotspot,
                        onAutoHotspotChanged = { enabled ->
                            Log.i(TAG, "Auto-hotspot changed: $enabled")
                            streamSettings = streamSettings.copy(autoHotspot = enabled)
                            StreamSettings.save(this@MainActivity, streamSettings)
                        },
                        currentVersion = updateManager.currentVersion,
                        latestVersion = updateManager.latestVersion,
                        updateAvailable = updateManager.updateAvailable,
                        onUpdateClick = { updateManager.startUpdate(this@MainActivity) },
                        isShizukuOnPowerAllowlist = isShizukuOnPowerAllowlist,
                        onOpenShizukuBatterySettings = {
                            try {
                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to open battery optimization settings", e)
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.toast_battery_settings_fallback),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }

                // Hotspot turn-off dialog
                if (showHotspotOffDialog) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF1A1A2E))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                                .padding(24.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(id = R.string.dialog_hotspot_off_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(id = R.string.dialog_hotspot_off_message),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            showHotspotOffDialog = false
                                            hotspotEnabledByApp = false
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                                    ) {
                                        Text(
                                            text = stringResource(id = R.string.dialog_hotspot_off_no),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            showHotspotOffDialog = false
                                            hotspotEnabledByApp = false
                                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                disableHotspot()
                                                runOnUiThread {
                                                    Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_disabled), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFFF5252)
                                        )
                                    ) {
                                        Text(
                                            text = stringResource(id = R.string.dialog_hotspot_off_yes),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (showUsbConfigWarningDialog) {
                    UsbConfigWarningDialog(
                        onOpenDevOptions = {
                            showUsbConfigWarningDialog = false
                            openDeveloperOptions()
                        },
                        onDismiss = { showUsbConfigWarningDialog = false },
                        onDontShowAgain = {
                            suppressUsbConfigWarning()
                            showUsbConfigWarningDialog = false
                        }
                    )
                }
            }
        }

        handleNewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNewIntent(intent)
    }
    
    private fun handleNewIntent(intent: Intent?) {
        if (intent == null) return

        if (RoutineAutomationPolicy.isServerStopAction(intent.action)) {
            intent.action = Intent.ACTION_MAIN
            requestStopFromAutomation()
            return
        }
        
        val startRequested =
            RoutineAutomationPolicy.isServerStartAction(intent.action) ||
                intent.getBooleanExtra("start_mirroring", false)
        if (startRequested) {
            pendingAutomationStart = true
            intent.action = Intent.ACTION_MAIN
            intent.removeExtra("start_mirroring")
            Log.i(TAG, "Server start requested from an explicit automation entry point")
            maybeStartFromAutomation()
        }
        if (intent.getBooleanExtra("open_settings", false)) {
            showSettings = true
        }
    }

    private fun requestStopFromAutomation() {
        pendingAutomationStart = false
        pendingStartAfterCleanup = false
        Log.i(TAG, "Server stop requested from an explicit automation entry point")

        if (MirrorForegroundService.isServiceRunning) {
            startService(
                Intent(this, MirrorForegroundService::class.java).apply {
                    action = MirrorForegroundService.ACTION_STOP
                },
            )
        }
        finishAndRemoveTask()
    }

    private fun maybeStartFromAutomation() {
        if (!pendingAutomationStart) return

        if (isCleanupInProgress || setupUiState != SetupUiState.Ready) {
            Log.i(
                TAG,
                "Automation start deferred: setup=$setupUiState cleanup=$isCleanupInProgress",
            )
            return
        }

        if (MirrorForegroundService.isServiceRunning || isStreaming || isPreparing) {
            pendingAutomationStart = false
            Log.i(TAG, "Automation start ignored because mirroring is already active or starting")
            return
        }

        if (
            RoutineAutomationPolicy.canStartNow(
                startRequested = pendingAutomationStart,
                setupReady = true,
                serviceRunning = false,
                startInProgress = false,
            )
        ) {
            pendingAutomationStart = false
            onStartMirroring()
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateManager.onResume(this)
        refreshShizukuBatteryOptimizationState()
        refreshTextInputPermissions()
        refreshNotificationAccessState()
    }

    private fun refreshTextInputPermissions() {
        isImeEnabled = runCatching {
            com.castla.mirror.input.TextInputSettingsHelper.isImeEnabled(this)
        }.getOrDefault(true)

        isImeSelected = runCatching {
            com.castla.mirror.input.TextInputSettingsHelper.isImeSelected(this)
        }.getOrDefault(false)

        isCastlaImeActive = runCatching {
            com.castla.mirror.input.ImeSwitchManager.isCastlaImeActive(this)
        }.getOrDefault(false)
    }

    private fun refreshShizukuBatteryOptimizationState() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        isShizukuOnPowerAllowlist = try {
            pm.isIgnoringBatteryOptimizations(SetupCoordinator.SHIZUKU_PACKAGE)
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshNotificationAccessState() {
        isNotificationAccessEnabled = runCatching {
            NotificationAccessSettingsHelper.isNotificationAccessEnabled(
                this,
                CastlaNotificationListenerService::class.java,
            )
        }.getOrDefault(false)
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open notification listener settings", e)
            Toast.makeText(
                this,
                getString(R.string.toast_notification_access_settings_fallback),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Called on the IO thread once the Shizuku privileged service is connected.
     * Reads the persistent USB configuration via privileged getprop and, on
     * Samsung devices where MTP/PTP is the default, surfaces the advisory
     * dialog so the user can swap to "Charging only" in Developer Options.
     * No-op if the user previously dismissed via "Don't show again".
     */
    private fun evaluateUsbConfigAdvisory() {
        if (isUsbConfigWarningSuppressed()) return
        val advisory = try {
            shizukuSetup.classifyUsbConfig(Build.MANUFACTURER ?: "")
        } catch (e: Exception) {
            Log.w(TAG, "classifyUsbConfig threw", e)
            return
        }
        Log.i(TAG, "USB config advisory: $advisory")
        if (advisory == com.castla.mirror.shizuku.UsbConfigChecker.Advisory.RiskyUsbConfig) {
            runOnUiThread { showUsbConfigWarningDialog = true }
        }
    }

    private fun isUsbConfigWarningSuppressed(): Boolean =
        getSharedPreferences(USB_CONFIG_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SUPPRESS_USB_CONFIG_WARNING, false)

    private fun suppressUsbConfigWarning() {
        getSharedPreferences(USB_CONFIG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SUPPRESS_USB_CONFIG_WARNING, true)
            .apply()
    }

    private fun openDeveloperOptions() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "openDeveloperOptions: no matching Settings activity", e)
                Toast.makeText(
                    this,
                    getString(R.string.toast_dev_options_unavailable),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val wasInstalled = setupCoordinator.isInstalled()
        val isInstalled = setupCoordinator.refreshInstallation()
        if (isInstalled && !wasInstalled) {
            (application as CastlaApp).ensureShizukuInitialized()
        }
        loadAutoDetectState()

        if (!serviceBound && !bindRequested) {
            val intent = Intent(this, MirrorForegroundService::class.java)
            bindRequested = bindService(intent, serviceConnection, 0)
        }

        // Recover Shizuku if it died while we were backgrounded (typically from a
        // USB-unplug adbd respawn that wiped shell-UID processes). startActivity
        // from here is allowed because we're in the foreground — the same call
        // from binderDeadListener gets hit by BAL_BLOCK while the screen is
        // sleeping, which is why we also rely on this onStart hook.
        if (isInstalled) {
            shizukuSetup.launchShizukuManagerIfLostSinceBoot()
        }
    }

    override fun onStop() {
        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        updateManager.destroy()
        networkMonitor.stopMonitoring()
        stopAutoDetect()
        super.onDestroy()
    }

    private fun updateServerUrl() {
        val ip = resolveReachableMirrorIp()

        if (streamSettings.webCodecsEnabled) {
            // Final Castla UX: users always type only this public entry URL.
            // The manager backend redirects it to the active per-device local relay.
            serverUrl = CASTLA_PUBLIC_URL
            return
        }

        // Non-WebCodecs mode intentionally remains direct HTTP to the phone IP.
        // Browser will then fall back to MSE/JMuxer because secure context is not available.
        serverUrl = "http://${ip}:${MirrorServer.DEFAULT_PORT}"
    }

    private fun resolveReachableMirrorIp(): String {
        val hotspotIp = currentIp

        return when {
            hotspotIp != "0.0.0.0" && hotspotIp.isNotEmpty() -> hotspotIp
            else -> "0.0.0.0"
        }
    }

    /**
     * Enable WiFi tethering (hotspot) via Shizuku's privileged service.
     * Uses TetheringManager/ConnectivityManager Java API (most reliable).
     */
    private suspend fun enableHotspot(): Boolean {
        if (!shizukuSetup.serviceConnected.value) {
            Log.w(TAG, "enableHotspot: Shizuku service not connected")
            return false
        }
        val success = shizukuSetup.startWifiTethering()
        Log.i(TAG, "enableHotspot: startWifiTethering returned $success")
        if (success) {
            // Give tethering time to initialize
            kotlinx.coroutines.delay(2000)
        }
        return success
    }

    private fun disableHotspot() {
        if (!shizukuSetup.serviceConnected.value) {
            Log.w(TAG, "disableHotspot: Shizuku service not connected")
            return
        }
        val success = shizukuSetup.stopWifiTethering()
        Log.i(TAG, "disableHotspot: stopWifiTethering returned $success")
    }

    private fun refreshHotspotStatus() {
        // Check hotspot status without requiring Shizuku by inspecting network interfaces directly
        try {
            val hotspotNames = listOf("swlan0", "wlan1", "ap0", "softap0")
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val active = interfaces.any { iface ->
                iface.isUp && hotspotNames.any { name -> iface.name.contains(name) }
            }
            isHotspotActive = active
            Log.d(TAG, "refreshHotspotStatus: active=$active (interfaces=${interfaces.map { it.name }})")
        } catch (e: Exception) {
            Log.w(TAG, "refreshHotspotStatus failed", e)
            isHotspotActive = false
        }
    }

    private fun toggleHotspot() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // If privileged service is not connected, try to bind and wait
            if (!shizukuSetup.serviceConnected.value) {
                if (shizukuSetup.isAvailable() && shizukuSetup.hasPermission()) {
                    shizukuSetup.bindPrivilegedService()
                    // Wait up to 3 seconds for connection
                    var waited = 0
                    while (!shizukuSetup.serviceConnected.value && waited < 3000) {
                        kotlinx.coroutines.delay(200)
                        waited += 200
                    }
                }
                if (!shizukuSetup.serviceConnected.value) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_failed), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
            }

            if (isHotspotActive) {
                disableHotspot()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_disabled), Toast.LENGTH_SHORT).show()
                    isHotspotActive = false
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_enabling), Toast.LENGTH_SHORT).show()
                }
                val success = enableHotspot()
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_enabled), Toast.LENGTH_SHORT).show()
                        isHotspotActive = true
                        hotspotEnabledByApp = true
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_failed), Toast.LENGTH_SHORT).show()
                    }
                    updateServerUrl()
                }
            }
        }
    }

    private fun findHotspotInterface(): String? {
        val candidates = listOf("swlan0", "wlan1", "ap0", "softap0")
        val result = shizukuSetup.exec("ip -o addr show") ?: return null
        for (name in candidates) {
            if (result.contains(name)) return name
        }
        return null
    }


    private fun openShizukuDownloadPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuInstallLinks.PLAY_STORE_APP)))
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(ShizukuInstallLinks.OFFICIAL_DOWNLOAD_PAGE),
                    )
                )
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Failed to open official Shizuku download page", fallbackError)
                Toast.makeText(this, "Could not open the Shizuku download page.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage(SetupCoordinator.SHIZUKU_PACKAGE)
        if (intent != null) {
            startActivity(intent)
        }
    }

    // ── Tesla Auto-Detect (Hotspot client + BLE) ──────────────────────

    private fun loadAutoDetectState() {
        teslaAutoDetectEnabled = getSharedPreferences("castla_settings", MODE_PRIVATE)
            .getBoolean("auto_detect_enabled", false)
        if (teslaAutoDetectEnabled) {
            if (hasBleScanPermissions()) {
                startAutoDetect()
            } else {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                bluetoothPermissionLauncher.launch(permissions)
            }
        }
    }

    private fun toggleAutoDetect() {
        teslaAutoDetectEnabled = !teslaAutoDetectEnabled
        getSharedPreferences("castla_settings", MODE_PRIVATE).edit()
            .putBoolean("auto_detect_enabled", teslaAutoDetectEnabled).apply()
        if (teslaAutoDetectEnabled) {
            startAutoDetect()
            Toast.makeText(this, getString(R.string.toast_tesla_paired_auto_detect), Toast.LENGTH_SHORT).show()
        } else {
            stopAutoDetect()
            Toast.makeText(this, getString(R.string.toast_tesla_auto_detect_disabled), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAutoDetect() {
        // BLE scanner — detects Tesla BLE advertisement
        if (teslaBleScanner == null && hasBleScanPermissions()) {
            startBleScanner()
        }
    }

    private fun stopAutoDetect() {
        stopBleScanner()
    }

    private fun startBleScanner() {
        if (teslaBleScanner != null) return
        teslaBleScanner = TeslaBleScanner(this).also {
            it.start {
                TeslaDetectNotifier.showTeslaDetectedNotification(this)
            }
        }
        Log.i(TAG, "BLE scanner started")
    }

    private fun stopBleScanner() {
        teslaBleScanner?.stop()
        teslaBleScanner = null
    }

    private fun hasBleScanPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "Requesting battery optimization exemption")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to request battery optimization exemption", e)
            }
        }
    }

    private fun clearPreparingState(message: String? = null, stopServiceIfNeeded: Boolean = false) {
        isPreparing = false
        if (stopServiceIfNeeded) {
            com.castla.mirror.diagnostics.FileLogger.i(
                "SERVICE_LIFECYCLE",
                "stop_requested source=main_activity_start_timeout message=${message ?: ""}",
            )
            try { stopService(Intent(this, MirrorForegroundService::class.java)) } catch (_: Exception) {}
            mirrorService = null
            isStreaming = false
            if (serviceBound || bindRequested) {
                try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
                serviceBound = false
                bindRequested = false
            }
        }
        if (!message.isNullOrBlank()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun queueStartAfterCleanup(reason: String) {
        pendingStartAfterCleanup = true
        isPreparing = true
        Log.i(TAG, "Queued mirroring start until cleanup completes: $reason")
    }

    private fun beginMirroringStartFlow(reason: String) {
        Log.i(TAG, "Beginning mirroring start flow: $reason")
        isPreparing = true
        Log.i(TAG, "isPreparing=true (starting Shizuku mirror flow)")

        if (setupUiState != SetupUiState.Ready) {
            Log.w(TAG, "Mirroring start blocked by mandatory setup state=$setupUiState")
            isPreparing = false
            return
        }

        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Requesting POST_NOTIFICATIONS permission")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        proceedAfterNotificationPermission()
    }

    private fun onStartMirroring() {
        Log.i(TAG, "onStartMirroring called")

        // The Tesla/browser UI can update the next-session stream profile while
        // this activity is already alive, so refresh persisted settings here.
        streamSettings = StreamSettings.load(this)

        if (isCleanupInProgress || MirrorForegroundService.isCleanupInProgress) {
            queueStartAfterCleanup("cleanup_in_progress")
            return
        }

        if (MirrorForegroundService.isServiceRunning || mirrorService?.isRunning == true) {
            queueStartAfterCleanup("service_still_running")
            stopMirrorService(askHotspot = false, preservePreparingState = true)
            return
        }

        beginMirroringStartFlow("user_request")
    }

    private fun proceedAfterNotificationPermission() {
        if (streamSettings.audioEnabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Requesting RECORD_AUDIO permission")
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        startMirrorService()
    }

    private fun startMirrorService() {
        // Auto-enable hotspot if setting is on, Shizuku is available, and hotspot is not already active
        val needHotspot = streamSettings.autoHotspot && shizukuSetup.serviceConnected.value
        if (needHotspot) {
            val alreadyActive = findHotspotInterface() != null
            if (alreadyActive) {
                Log.i(TAG, "Hotspot already active — skipping enableHotspot, starting service directly")
                isHotspotActive = true
                launchMirrorService()
            } else {
                Toast.makeText(this, getString(R.string.toast_hotspot_enabling), Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val success = enableHotspot()
                    hotspotEnabledByApp = success
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_enabled), Toast.LENGTH_SHORT).show()
                            isHotspotActive = true
                        } else {
                            Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_failed), Toast.LENGTH_SHORT).show()
                        }
                        updateServerUrl()
                        launchMirrorService()
                    }
                }
            }
        } else {
            launchMirrorService()
        }
    }

    private fun launchMirrorService() {
        val reachableMirrorIp = resolveReachableMirrorIp()
        
        val intent = Intent(this, MirrorForegroundService::class.java).apply {
            putExtra(
                MirrorForegroundService.EXTRA_MAX_RESOLUTION,
                if (streamSettings.isAutoResolution) 0 else streamSettings.maxResolution.maxHeight
            )
            putExtra(MirrorForegroundService.EXTRA_FPS, streamSettings.fps) // FPS_AUTO is already 0
            putExtra(MirrorForegroundService.EXTRA_AUDIO, streamSettings.audioEnabled)
            putExtra(MirrorForegroundService.EXTRA_TESLA_BT_VIDEO_LATENCY_MS, streamSettings.teslaBluetoothVideoLatencyMs)
            putExtra(MirrorForegroundService.EXTRA_STREAMED_AUDIO_VIDEO_LATENCY_MS, streamSettings.streamedAudioVideoLatencyMs)
            putExtra(MirrorForegroundService.EXTRA_MIRRORING_MODE, streamSettings.mirroringMode.name)
            putExtra(MirrorForegroundService.EXTRA_TARGET_PACKAGE, streamSettings.targetAppPackage)

            putExtra("EXTRA_HOST_IP", currentIp)
            putExtra("EXTRA_CASTLA_DOMAIN", CASTLA_DOMAIN)
            putExtra(MirrorForegroundService.EXTRA_RELAY_PUBLISH_IP, reachableMirrorIp)
        }

        startMirrorServiceDirect(
            intent = intent,
            reason = if (streamSettings.webCodecsEnabled) "webcodecs_public_entry" else "plain_mse"
        )
    }

    private fun startMirrorServiceDirect(intent: Intent, reason: String) {
        isPreparing = true

        startForegroundService(intent)

        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }

        bindRequested = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isStreaming = true
        refreshHotspotStatus()
        Log.i(TAG, "isStreaming=true, isPreparing=$isPreparing (service started, reason=$reason)")

        waitForServiceReady()
    }

    private fun waitForServiceReady() {
        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            while (mirrorService?.isRunning != true && isPreparing) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= RelayRetryPolicy.SERVICE_START_TIMEOUT_MS) {
                    Log.w(TAG, "Mirror service start timed out after ${elapsed}ms")
                    clearPreparingState(
                        message = getString(R.string.toast_error, "mirroring start timed out"),
                        stopServiceIfNeeded = true
                    )
                    return@launch
                }
                kotlinx.coroutines.delay(100)
            }
            val elapsed = System.currentTimeMillis() - startTime
            val remaining = 2000L - elapsed
            if (remaining > 0) {
                kotlinx.coroutines.delay(remaining)
            }
            if (isPreparing) {
                isPreparing = false
                Log.i(TAG, "isPreparing=false (service ready, elapsed=${System.currentTimeMillis() - startTime}ms)")
            }
        }
    }

    private fun stopMirrorService(askHotspot: Boolean = true, preservePreparingState: Boolean = false) {
        com.castla.mirror.diagnostics.FileLogger.i(
            "SERVICE_LIFECYCLE",
            "stop_requested source=main_activity_user_or_restart preservePreparingState=$preservePreparingState",
        )
        val shouldAskHotspot = askHotspot && hotspotEnabledByApp

        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        stopService(Intent(this, MirrorForegroundService::class.java))
        mirrorService = null
        isStreaming = false
        if (!preservePreparingState) {
            isPreparing = false
        }
        isHotspotActive = false
        updateServerUrl()
        com.castla.mirror.widget.MirrorWidgetProvider.updateAllWidgets(this)

        // Ask user whether to turn off hotspot
        if (shouldAskHotspot) {
            showHotspotOffDialog = true
        }
    }
}

private fun MirrorServerAvailability.toStatusTextRes(): Int = when (state) {
    MirrorServerAvailabilityState.STARTING -> R.string.status_server_starting
    MirrorServerAvailabilityState.WAITING_RELAY -> R.string.status_waiting_for_secure_relay
    MirrorServerAvailabilityState.ERROR -> R.string.status_server_setup_failed
    MirrorServerAvailabilityState.READY_HTTP,
    MirrorServerAvailabilityState.READY_HTTPS -> R.string.status_streaming_active
    MirrorServerAvailabilityState.IDLE -> R.string.status_ready_to_stream
}

@Composable
fun CastlaScreen(
    isStreaming: Boolean,
    isPreparing: Boolean = false,
    serverUrl: String,
    serverAvailability: MirrorServerAvailability = MirrorServerAvailability.IDLE,
    streamSettings: StreamSettings = StreamSettings(),
    reachableMirrorIp: String = "0.0.0.0",
    isImeEnabled: Boolean,
    isImeSelected: Boolean,
    isCastlaImeActive: Boolean = false,
    isNotificationAccessEnabled: Boolean = false,
    onRestoreIme: () -> Unit = {},
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEnableIme: () -> Unit,
    onSelectIme: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit = {},
    isHotspotActive: Boolean = false,
    onToggleHotspot: () -> Unit = {},
    autoHotspot: Boolean = false,
    onAutoHotspotChanged: (Boolean) -> Unit = {},
    currentVersion: String = "",
    latestVersion: String? = null,
    updateAvailable: Boolean = false,
    onUpdateClick: () -> Unit = {},
    isShizukuOnPowerAllowlist: Boolean = true,
    onOpenShizukuBatterySettings: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val serverReady = serverAvailability.isReady
    val serverStatusColor = when {
        isPreparing || serverAvailability.state == MirrorServerAvailabilityState.STARTING -> Color(0xFFFFB300)
        serverReady -> Color(0xFF69F0AE)
        isStreaming && serverAvailability.state == MirrorServerAvailabilityState.ERROR -> Color(0xFFFF5252)
        else -> Color.White.copy(alpha = 0.7f)
    }
    MeshGradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(id = R.string.app_name),
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Text(
                text = stringResource(id = R.string.subtitle_tesla_screen_mirroring),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )

            // Version info
            if (currentVersion.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "v$currentVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    if (updateAvailable && latestVersion != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFF6B35).copy(alpha = 0.9f))
                                .clickable { onUpdateClick() }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "v$latestVersion ${stringResource(id = R.string.version_update_available)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (latestVersion != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = R.string.version_latest),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF69F0AE).copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable { onSettingsClick() }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(id = R.string.btn_settings), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            androidx.compose.animation.AnimatedVisibility(visible = isCastlaImeActive) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF2E1A1A).copy(alpha = 0.85f))
                            .border(BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)), RoundedCornerShape(24.dp))
                            .padding(16.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF5252))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Remote Keyboard Active",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF5252),
                                    fontSize = 15.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onRestoreIme,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = "Restore Phone Keyboard",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isPreparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color(0xFFFFB300),
                        strokeWidth = 2.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    serverReady -> Color(0xFF69F0AE)
                                    isStreaming && serverAvailability.state == MirrorServerAvailabilityState.ERROR -> Color(0xFFFF5252)
                                    else -> Color.White.copy(alpha = 0.5f)
                                }
                            )
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = when {
                        isPreparing -> stringResource(id = R.string.status_preparing)
                        isStreaming -> stringResource(id = serverAvailability.toStatusTextRes())
                        else -> stringResource(id = R.string.status_ready_to_stream)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = serverStatusColor,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            AnimatedVisibility(visible = isStreaming && serverReady) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(id = R.string.title_open_tesla_browser),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = serverUrl,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF69F0AE),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val orgDeviceId = CastlaDeviceId.getDeviceId(context)
                        val mixedDeviceId = CastlaDeviceId.getDeviceId(context, reachableMirrorIp)
                        
                        Text(
                            text = "Device ID: $orgDeviceId / Active: c-$mixedDeviceId ($reachableMirrorIp)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(id = R.string.desc_tesla_favorite_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StreamStatusChip(
                                stringResource(R.string.stream_status_resolution),
                                when (streamSettings.maxResolution) {
                                    StreamSettings.Resolution.AUTO -> stringResource(R.string.stream_status_auto)
                                    StreamSettings.Resolution.RES_720 -> "720p"
                                    StreamSettings.Resolution.RES_1080 -> "1080p"
                                },
                            )
                            StreamStatusChip(
                                stringResource(R.string.stream_status_fps),
                                if (streamSettings.isAutoFps) stringResource(R.string.stream_status_auto) else "${streamSettings.fps} FPS",
                            )
                            StreamStatusChip(
                                stringResource(R.string.stream_status_audio),
                                stringResource(if (streamSettings.audioEnabled) R.string.stream_status_on else R.string.stream_status_off),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = isStreaming && serverAvailability.state == MirrorServerAvailabilityState.ERROR,
            ) {
                Text(
                    text = stringResource(R.string.server_recovery_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    color = Color(0xFFFFAB91),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            // Hotspot toggle button + auto-hotspot switch — only visible when streaming
            AnimatedVisibility(visible = isStreaming && serverReady) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onToggleHotspot,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = if (isHotspotActive) {
                                ButtonDefaults.buttonColors(containerColor = Color(0xFF69F0AE))
                            } else {
                                ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f))
                            },
                            border = if (!isHotspotActive) BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)) else null
                        ) {
                            Text(
                                text = if (isHotspotActive)
                                    stringResource(id = R.string.btn_hotspot_on)
                                else
                                    stringResource(id = R.string.btn_hotspot_off),
                                fontWeight = FontWeight.Bold,
                                color = if (isHotspotActive) Color.Black else Color.White
                            )
                        }
                        // Auto-hotspot toggle
                        Box(
                            modifier = Modifier
                                .height(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(id = R.string.btn_auto_hotspot),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Switch(
                                    checked = autoHotspot,
                                    onCheckedChange = onAutoHotspotChanged,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF69F0AE),
                                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                                        uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (isStreaming) {
                Spacer(modifier = Modifier.height(24.dp))
            }
            AnimatedVisibility(visible = !isNotificationAccessEnabled) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF0D2438).copy(alpha = 0.82f))
                            .border(1.dp, Color(0xFF4FC3F7).copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                            .padding(20.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(id = R.string.title_notification_overlay_setup),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF81D4FA)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(id = R.string.desc_notification_overlay_setup),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFB3E5FC),
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onOpenNotificationAccessSettings,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF0288D1)
                                )
                            ) {
                                Text(
                                    stringResource(id = R.string.btn_enable_notification_access),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            AnimatedVisibility(visible = !isShizukuOnPowerAllowlist) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF1A2D00).copy(alpha = 0.8f))
                            .border(1.dp, Color(0xFF8BC34A).copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                            .padding(20.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(id = R.string.desc_shizuku_battery_optimization),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCCFF90),
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onOpenShizukuBatterySettings,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF8BC34A)
                                ),
                                border = BorderStroke(1.dp, Color(0xFF8BC34A))
                            ) {
                                Text(stringResource(id = R.string.btn_shizuku_battery_settings), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            if (!isStreaming) {
                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .padding(20.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(id = R.string.title_how_to_use),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(id = R.string.desc_how_to_use),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            lineHeight = 24.sp
                        )
                    }
                }
            }


                Spacer(modifier = Modifier.height(20.dp))
            }

            MirrorActionButton(
                isStreaming = isStreaming,
                isPreparing = isPreparing,
                onStartClick = onStartClick,
                onStopClick = onStopClick,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 20.dp),
            )

        }
    }
}

@Composable
private fun StreamStatusChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.58f), fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MirrorActionButton(
    isStreaming: Boolean,
    isPreparing: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = if (isStreaming) onStopClick else onStartClick,
        enabled = !isPreparing || isStreaming,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(20.dp),
        colors = when {
            isPreparing && !isStreaming -> ButtonDefaults.buttonColors(
                disabledContainerColor = Color.White.copy(alpha = 0.3f),
                disabledContentColor = Color.Black.copy(alpha = 0.5f),
            )
            isStreaming -> ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
            else -> ButtonDefaults.buttonColors(containerColor = Color.White)
        },
    ) {
        if (isPreparing && !isStreaming) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.Black.copy(alpha = 0.5f),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = when {
                isPreparing && !isStreaming -> stringResource(id = R.string.status_preparing)
                isStreaming -> stringResource(id = R.string.btn_stop_mirroring)
                else -> stringResource(id = R.string.btn_start_mirroring)
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (isStreaming) Color.White else Color.Black,
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 16.dp)
        )
    }
}

@Composable
private fun UsbConfigWarningDialog(
    onOpenDevOptions: () -> Unit,
    onDismiss: () -> Unit,
    onDontShowAgain: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1A1A2E))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(id = R.string.dialog_usb_config_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(id = R.string.dialog_usb_config_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Start
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onOpenDevOptions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text(
                        text = stringResource(id = R.string.dialog_usb_config_open_dev_options),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = stringResource(id = R.string.dialog_usb_config_dismiss),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = onDontShowAgain,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = stringResource(id = R.string.dialog_usb_config_dont_show),
                            color = Color.White.copy(alpha = 0.75f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

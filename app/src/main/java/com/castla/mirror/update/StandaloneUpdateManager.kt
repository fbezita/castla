package com.castla.mirror.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.widget.Toast
import com.castla.mirror.R

/**
 * Standalone flavor: checks a remote JSON endpoint for the latest version.
 * When an update is required, downloads the APK in-app and launches the installer.
 */
class StandaloneUpdateManager : UpdateManager {

    companion object {
        private const val TAG = "StandaloneUpdate"
        private const val GITHUB_LATEST_RELEASE_URL =
            "https://api.github.com/repos/fbezita/castla/releases/latest"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
        private const val APK_FILE_NAME = "castla-update.apk"
    }

    private val showForceUpdate = mutableStateOf(false)
    private val latestVersionName = mutableStateOf("")
    private val _currentVersion = mutableStateOf("")
    private val _updateAvailable = mutableStateOf(false)
    private val downloadUrl = mutableStateOf("")
    private val isDownloading = mutableStateOf(false)
    private val downloadProgress = mutableFloatStateOf(0f)
    private val downloadFailed = mutableStateOf(false)

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null

    override val currentVersion: String get() = _currentVersion.value
    override val latestVersion: String? get() = latestVersionName.value.ifEmpty { null }
    override val updateAvailable: Boolean get() = _updateAvailable.value

    override fun startUpdate(activity: ComponentActivity) {
        startDownloadAndInstall(activity)
    }

    override fun checkForUpdate(
        activity: ComponentActivity,
        force: Boolean,
        onResult: ((Boolean) -> Unit)?
    ) {
        // Set current version
        try {
            _currentVersion.value = activity.packageManager
                .getPackageInfo(activity.packageName, 0).versionName ?: ""
        } catch (_: Exception) {}

        // 24-hour rate limit guard for non-authenticated GitHub API requests
        val prefs = activity.getSharedPreferences("castla_update_prefs", Context.MODE_PRIVATE)
        val lastCheckTime = prefs.getLong("last_check_timestamp", 0L)
        val currentTime = System.currentTimeMillis()
        if (!force && currentTime - lastCheckTime < 86400000L) { // 24 hours
            Log.i(TAG, "Skipping update check: last check was less than 24 hours ago.")
            onResult?.invoke(false)
            return
        }

        if (force) {
            Toast.makeText(activity, activity.getString(R.string.settings_checking_update), Toast.LENGTH_SHORT).show()
        }

        activity.lifecycleScope.launch {
            try {
                val result = fetchLatestRelease()
                if (result != null) {
                    val tagName = result.optString("tag_name", "")
                    val remoteVersion = tagName.removePrefix("v")
                    latestVersionName.value = remoteVersion

                    // Find APK asset download URL
                    val assets = result.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                downloadUrl.value = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    val available = isNewerVersion(
                        current = _currentVersion.value,
                        remote = remoteVersion
                    )
                    _updateAvailable.value = available

                    if (available) {
                        showForceUpdate.value = true
                    } else if (force) {
                        Toast.makeText(activity, activity.getString(R.string.toast_no_update_available), Toast.LENGTH_SHORT).show()
                    }

                    Log.i(TAG, "Version check: current=${_currentVersion.value}, latest=$remoteVersion, updateAvailable=${_updateAvailable.value}")
                    
                    // Update last check timestamp on successful check
                    prefs.edit().putLong("last_check_timestamp", currentTime).apply()
                    onResult?.invoke(available)
                } else {
                    if (force) {
                        Toast.makeText(activity, "Failed to connect to update server", Toast.LENGTH_SHORT).show()
                    }
                    onResult?.invoke(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Version check failed, skipping", e)
                if (force) {
                    Toast.makeText(activity, "Error checking update: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                onResult?.invoke(false)
            }
        }
    }

    /**
     * Compares semantic version strings (e.g. "1.0.9" vs "1.1.0").
     * Returns true if [remote] is newer than [current].
     */
    private fun isNewerVersion(current: String, remote: String): Boolean {
        val cleanCurrent = current.takeWhile { it.isDigit() || it == '.' }.trimEnd('.')
        val cleanRemote = remote.takeWhile { it.isDigit() || it == '.' }.trimEnd('.')

        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val length = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until length) {
            val c = currentParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    @Composable
    override fun ForceUpdateOverlay(activity: ComponentActivity) {
        if (showForceUpdate.value) {
            ForceUpdateDialog(
                latestVersion = latestVersionName.value,
                isDownloading = isDownloading.value,
                downloadProgress = downloadProgress.floatValue,
                downloadFailed = downloadFailed.value,
                onUpdate = { startDownloadAndInstall(activity) }
            )
        }
    }

    private fun startDownloadAndInstall(activity: ComponentActivity) {
        if (isDownloading.value) return

        val url = downloadUrl.value.ifEmpty { return }

        isDownloading.value = true
        downloadFailed.value = false
        downloadProgress.floatValue = 0f

        // Clean up old APK if exists
        val apkDir = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "")
        val apkFile = File(apkDir, APK_FILE_NAME)
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("Castla Update")
            setDescription("Downloading v${latestVersionName.value}")
            setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        }

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)

        // Register receiver for download complete
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    handleDownloadComplete(activity, dm)
                }
            }
        }
        activity.registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED
        )

        // Track progress
        activity.lifecycleScope.launch {
            trackDownloadProgress(dm)
        }
    }

    private suspend fun trackDownloadProgress(dm: DownloadManager) = withContext(Dispatchers.IO) {
        while (isDownloading.value) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val bytesDownloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                )
                val bytesTotal = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                )
                if (bytesTotal > 0) {
                    downloadProgress.floatValue = bytesDownloaded.toFloat() / bytesTotal.toFloat()
                }
                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                cursor.close()
                if (status == DownloadManager.STATUS_FAILED) {
                    isDownloading.value = false
                    downloadFailed.value = true
                    return@withContext
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    return@withContext
                }
            } else {
                cursor?.close()
            }
            delay(200)
        }
    }

    private fun handleDownloadComplete(activity: ComponentActivity, dm: DownloadManager) {
        isDownloading.value = false
        downloadProgress.floatValue = 1f

        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)
        if (cursor != null && cursor.moveToFirst()) {
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            cursor.close()
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                installApk(activity)
            } else {
                Log.e(TAG, "Download failed with status: $status")
                downloadFailed.value = true
            }
        } else {
            cursor?.close()
            downloadFailed.value = true
        }
    }

    private fun installApk(activity: ComponentActivity) {
        val apkFile = File(
            activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILE_NAME
        )
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found: ${apkFile.absolutePath}")
            downloadFailed.value = true
            return
        }

        val apkUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    override fun destroy() {
        downloadReceiver = null
    }

    private suspend fun fetchLatestRelease(): JSONObject? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(GITHUB_LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().readText()
                JSONObject(body)
            } else {
                Log.w(TAG, "GitHub release check HTTP ${connection.responseCode}")
                null
            }
        } finally {
            connection?.disconnect()
        }
    }
}

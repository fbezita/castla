package com.castla.mirror

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.castla.mirror.automation.RoutineAutomationPolicy
import com.castla.mirror.diagnostics.FileLogger
import com.castla.mirror.shizuku.ShizukuSetup

class CastlaApp : Application() {
    val shizukuSetup: ShizukuSetup by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ShizukuSetup()
    }

    @Volatile
    private var shizukuInitialized = false

    @Synchronized
    fun ensureShizukuInitialized(): ShizukuSetup {
        if (!shizukuInitialized) {
            shizukuSetup.init(this, bindService = true)
            shizukuInitialized = true
        }
        return shizukuSetup
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        registerAutomationShortcut()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.e("UEH", "Uncaught on ${thread.name}", throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun registerAutomationShortcut() {
        val startShortcut = ShortcutInfoCompat.Builder(this, "start_server")
            .setShortLabel(getString(R.string.shortcut_start_server_short))
            .setLongLabel(getString(R.string.shortcut_start_server_long))
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setRank(0)
            .setIntent(
                Intent(this, MainActivity::class.java).apply {
                    action = RoutineAutomationPolicy.ACTION_START_SERVER
                },
            )
            .build()

        val stopShortcut = ShortcutInfoCompat.Builder(this, "stop_server")
            .setShortLabel(getString(R.string.shortcut_stop_server_short))
            .setLongLabel(getString(R.string.shortcut_stop_server_long))
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setRank(1)
            .setIntent(
                Intent(this, MainActivity::class.java).apply {
                    action = RoutineAutomationPolicy.ACTION_STOP_SERVER
                },
            )
            .build()

        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(this, listOf(startShortcut, stopShortcut))
        }.onFailure { error ->
            Log.w("CastlaApp", "Failed to register automation shortcuts", error)
        }
    }
}

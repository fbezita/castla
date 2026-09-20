package com.castla.mirror

import android.app.Application
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
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.e("UEH", "Uncaught on ${thread.name}", throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}

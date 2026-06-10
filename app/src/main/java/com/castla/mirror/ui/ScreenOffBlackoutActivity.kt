package com.castla.mirror.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

class ScreenOffBlackoutActivity : Activity() {

    private lateinit var gestureDetector: GestureDetector
    private var readyNotified = false

    companion object {
        const val ACTION_START = "com.castla.mirror.action.SCREEN_OFF_BLACKOUT_START"
        const val ACTION_STOP = "com.castla.mirror.action.SCREEN_OFF_BLACKOUT_STOP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                try {
                    com.castla.mirror.service.MirrorForegroundService.instance?.onUserRequestRestoreFromBlackout()
                } catch (_: Exception) {}
                return true
            }
        })
        applyBlackoutWindow()
        handleIntent(intent)
        notifyBlackoutReadyOnce()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        notifyBlackoutReadyOnce()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_STOP) {
            finish()
            return
        }

        val content = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
        setContentView(content)
    }

    private fun applyBlackoutWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.attributes = window.attributes.apply {
            screenBrightness = 0f
        }
    }

    private fun notifyBlackoutReadyOnce() {
        if (readyNotified) return
        readyNotified = true
        try {
            com.castla.mirror.service.MirrorForegroundService.instance?.onBlackoutActivityReady()
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        notifyBlackoutReadyOnce()
    }
}

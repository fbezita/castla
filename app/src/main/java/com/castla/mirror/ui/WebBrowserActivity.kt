package com.castla.mirror.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.widget.FrameLayout
import android.util.Log
import android.content.pm.ActivityInfo
import android.view.Gravity
import android.content.res.Configuration

class WebBrowserActivity : Activity() {

    companion object {
        private const val TAG = "WebBrowserActivity"
    }

    private lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullScreenContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "WebBrowserActivity created")

        var url = intent.getStringExtra("url") ?: "https://m.youtube.com"
        
        val pane = intent.getStringExtra("pane") ?: "primary"
        var shouldFollowDisplayShape = pane == "secondary"
        if (url.contains("#pane=secondary")) {
            shouldFollowDisplayShape = true
            url = url.replace("#pane=secondary", "")
        }

        // Secondary VD is already sized by the browser pane, so let the Activity follow that display.
        requestedOrientation = if (shouldFollowDisplayShape) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        
        if (shouldFollowDisplayShape) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = window.attributes
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            window.attributes = params

            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }

        fullScreenContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setupWebView(this, shouldFollowDisplayShape)
        }

        root.addView(webView)
        root.addView(fullScreenContainer)
        setContentView(root)

        // Restore WebView state if activity was recreated (e.g. VD resize
        // that wasn't caught by configChanges)
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
            Log.i(TAG, "Restored WebView state (URL: ${webView.url})")
        } else {
            Log.i(TAG, "Loading URL: $url (pane=$pane followDisplayShape=$shouldFollowDisplayShape)")
            webView.loadUrl(url)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp} dpi=${newConfig.densityDpi}")
    }

    private fun setupWebView(webView: WebView, followDisplayShape: Boolean) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = !followDisplayShape
            loadWithOverviewMode = !followDisplayShape
            setSupportZoom(!followDisplayShape)
            builtInZoomControls = !followDisplayShape
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = BrowserUserAgentPolicy.resolve(
                url = intent.getStringExtra("url") ?: "https://m.youtube.com",
                followDisplayShape = followDisplayShape,
            )
        }
        if (followDisplayShape) {
            webView.setInitialScale(100)
        }

        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false 
                }
                
                Log.i(TAG, "Blocked app link redirect: $url")
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Log.e(TAG, "WebView Error: ${error?.errorCode} - ${error?.description} (URL: ${request?.url})")
                super.onReceivedError(view, request, error)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // HTML5 movie Full Screen
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                Log.i(TAG, "Entering full screen video mode")
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                webView.visibility = View.GONE
                fullScreenContainer.visibility = View.VISIBLE
                fullScreenContainer.addView(view)
            }

            // close fullscreen
            override fun onHideCustomView() {
                Log.i(TAG, "Exiting full screen video mode")
                if (customView == null) return
                fullScreenContainer.visibility = View.GONE
                fullScreenContainer.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
        Log.i(TAG, "WebBrowserActivity paused — media playback stopped")
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        Log.i(TAG, "WebBrowserActivity resumed")
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}

package com.castla.mirror.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.graphics.Color

/**
 * A very simple, empty activity that serves as the HOME for virtual displays.
 * By declaring CATEGORY_SECONDARY_HOME in the manifest, this activity prevents
 * other apps on the virtual display from being reparented to the main display
 * when the HOME action is triggered.
 */
class VirtualDisplayHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Use a solid black background to hide any background activity fragments
        // and provide a clean "Home" experience on the virtual display.
        val view = View(this)
        view.setBackgroundColor(Color.BLACK)
        setContentView(view)
    }

    override fun onBackPressed() {
        // Disable back button on the home screen
    }
}

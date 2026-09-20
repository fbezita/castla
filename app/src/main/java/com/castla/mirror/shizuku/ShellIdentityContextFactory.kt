package com.castla.mirror.shizuku

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.annotation.RequiresApi

internal object ShellIdentityContextFactory {
    fun create(base: Context, attribution: Any?): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createApi31(base, attribution)
        } else {
            createLegacy(base)
        }

    private fun createLegacy(base: Context): Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createApi31(base: Context, attribution: Any?): Context =
        object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = SHELL_PACKAGE
            override fun getOpPackageName(): String = SHELL_PACKAGE
            override fun getAttributionTag(): String? = null
            override fun getAttributionSource(): android.content.AttributionSource =
                attribution as? android.content.AttributionSource ?: super.getAttributionSource()
        }

    private const val SHELL_PACKAGE = "com.android.shell"
}

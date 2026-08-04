package com.castla.mirror.shizuku

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import com.castla.mirror.ui.StreamSettings
import com.castla.mirror.service.MultiDisplayLaunchPolicy
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs in Shizuku's elevated process — has system-level access.
 * Creates virtual displays and injects input events for the mirroring pipeline.
 */
class PrivilegedService : IPrivilegedService.Stub() {
    private val tetheringExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val verboseScreenOffLogging: Boolean by lazy {
        runCatching {
            val context = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
            context != null && StreamSettings.load(context).verboseDiagnosticsEnabled
        }.getOrDefault(false)
    }

    private fun logScreenOffInfo(message: String) {
        if (verboseScreenOffLogging) {
            Log.i(TAG, message)
        }
    }

    private fun logScreenOffWarn(message: String) {
        if (verboseScreenOffLogging) {
            Log.w(TAG, message)
        }
    }

    companion object {
        private const val TAG = "PrivilegedService"
        private const val VDIME_PREFIX = "[VDIME]"
        // FLAG_PUBLIC ensures the virtual display behaves like a real display and allows home/launcher to render
        private const val DISPLAY_FLAG_PUBLIC = 1 shl 0
        // FLAG_OWN_CONTENT_ONLY prevents the main display's content from leaking into the VD
        private const val DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
        // FLAG_PRESENTATION tells the system this is a presentation display, which helps keeping it alive
        private const val DISPLAY_FLAG_PRESENTATION = 1 shl 1
        // FLAG_ALWAYS_UNLOCKED (API 33+) prevents the VD from locking when the physical screen locks
        private const val DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
        // FLAG_TRUSTED makes the system treat this VD as a trusted display (needed for some system UI)
        private const val DISPLAY_FLAG_TRUSTED = 1 shl 10
        // FLAG_OWN_DISPLAY_GROUP puts the VD in a separate display group so Keyguard does NOT show on it
        private const val DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        // FLAG_DESTROY_CONTENT_ON_REMOVAL destroys tasks instead of reparenting to main display
        private const val DISPLAY_FLAG_DESTROY_CONTENT = 1 shl 8
        private const val DISPLAY_IME_POLICY_LOCAL = 0
        private const val SHELL_APP_STREAMING_PROFILE =
            "android.app.role.COMPANION_DEVICE_APP_STREAMING"
        // Stable, locally administered identity for the synthetic shell companion association.
        // It is not a hardware or Wi-Fi MAC address.
        private const val SHELL_ASSOCIATION_DEVICE_ADDRESS = "02:CA:57:1A:00:01"
    }

    private fun describeVirtualDisplayFlags(flags: Int): String {
        val parts = mutableListOf<String>()
        if ((flags and DISPLAY_FLAG_PUBLIC) != 0) parts += "PUBLIC"
        if ((flags and DISPLAY_FLAG_PRESENTATION) != 0) parts += "PRESENTATION"
        if ((flags and DISPLAY_FLAG_OWN_CONTENT_ONLY) != 0) parts += "OWN_CONTENT_ONLY"
        if ((flags and DISPLAY_FLAG_DESTROY_CONTENT) != 0) parts += "DESTROY_CONTENT"
        if ((flags and DISPLAY_FLAG_OWN_DISPLAY_GROUP) != 0) parts += "OWN_DISPLAY_GROUP"
        if ((flags and DISPLAY_FLAG_TRUSTED) != 0) parts += "TRUSTED"
        if ((flags and DISPLAY_FLAG_ALWAYS_UNLOCKED) != 0) parts += "ALWAYS_UNLOCKED"
        return if (parts.isEmpty()) "none" else parts.joinToString("|")
    }

    private val virtualDisplays = mutableMapOf<Int, VirtualDisplay>()
    private val virtualDisplayNames = mutableMapOf<Int, String>()
    private val virtualDevicesByDisplayId = mutableMapOf<Int, Any>()

    // Cache map to throttle heavy dumpsys shell commands for each displayId
    private val lastWakeUpTimeMap = ConcurrentHashMap<Int, Long>()

    // Fields for Direct Binder API reflection caching
    private var activityManagerInstance: Any? = null
    private var windowManagerInstance: Any? = null
    private var forceStopPackageMethod: Method? = null
    private var setForcedDisplaySizeMethod: Method? = null
    private var setForcedDisplayDensityForUserMethod: Method? = null
    private var clearForcedDisplaySizeMethod: Method? = null
    private var clearForcedDisplayDensityForUserMethod: Method? = null
    private var setShouldShowSystemDecorsMethod: Method? = null
    private var setDisplayImePolicyMethod: Method? = null
    private var getDisplayImePolicyMethod: Method? = null
    private var syncInputTransactionsMethod: Method? = null
    private var registerDisplayWindowListenerMethod: Method? = null
    private var unregisterDisplayWindowListenerMethod: Method? = null
    private var displayWindowListenerProxy: Any? = null
    private var displayWindowListenerDescriptor: String = "android.view.IDisplayWindowListener"
    @Volatile private var displayWindowListenerRegistered = false

    private var activityTaskManagerInstance: Any? = null
    private var startActivityMethod: Method? = null
    private var moveTaskToDisplayMethod: Method? = null
    private var moveTaskToFrontMethod: Method? = null

    private var inputManagerInstance: Any? = null
    private var injectMethod: Method? = null
    private var shellContext: android.content.Context? = null

    // Cached objects for injectInput — avoids allocation per touch event
    private val cachedProps = arrayOf(
        MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_FINGER }
    )
    private val cachedCoords = arrayOf(
        MotionEvent.PointerCoords().apply { pressure = 1.0f; size = 1.0f }
    )
    private var setDisplayIdMethod: Method? = null

    private var setKeyEventDisplayIdMethod: Method? = null
    private val displayTopActivityCache = ConcurrentHashMap<Int, String>()
    private val displayTopActivityUpdatedAt = ConcurrentHashMap<Int, Long>()
    private val displayWindowListenerTransactions = ConcurrentHashMap<Int, String>()
    private val displayFocusExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    init {
        // Bypass Hidden API limits immediately before initializing any system service binders
        bypassHiddenApiRestrictions()
        tryInitInputManager()
        tryInitShellContext()

        // Pre-initialize system binders for activity manager and window manager
        tryInitSystemServices()

        // Must run AFTER shell context init so ActivityThread state is prepared. This makes
        // any subsequent AudioRecord/AudioTrack use packageName="com.android.shell"
        // matching our shell uid 2000 — required for AudioFlinger's attribution validator.
        fillShellAppInfo()
    }

    /**
     * scrcpy-style workaround: AudioRecord pulls its AttributionSource packageName from
     * ActivityThread.mBoundApplication.appInfo.packageName. Inside Shizuku's shell
     * process that defaults to "com.castla.mirror" (the client APK that was loaded),
     * which mismatches our actual runtime uid 2000 — AudioFlinger's ValidatedAttributionSource
     * check rejects the combination and AudioRecord.state stays STATE_UNINITIALIZED.
     * Overwriting the package name to "com.android.shell" (owned by uid 2000) fixes it.
     *
     * Reference: genymobile/scrcpy server/src/.../Workarounds.java#fillAppInfo
     */
    // Bypass Android Hidden API constraints to allow stable system binder reflections
    private fun bypassHiddenApiRestrictions() {
        try {
            val getRuntime = Class.forName("dalvik.system.VMRuntime").getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val vmRuntime = getRuntime.invoke(null)
            val setHiddenApiExemptions = Class.forName("dalvik.system.VMRuntime")
                .getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            setHiddenApiExemptions.isAccessible = true
            setHiddenApiExemptions.invoke(vmRuntime, arrayOf("L"))
            Log.i(TAG, "Successfully bypassed Android Hidden API restrictions via VMRuntime exemption")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bypass Android Hidden API restrictions", e)
        }
    }

    private fun fillShellAppInfo() {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getDeclaredMethod("currentActivityThread").also { it.isAccessible = true }
            val activityThread = currentAt.invoke(null) ?: return

            val appBindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val appBindDataCtor = appBindDataClass.getDeclaredConstructor().also { it.isAccessible = true }
            val appBindData = appBindDataCtor.newInstance()

            val appInfo = android.content.pm.ApplicationInfo().apply {
                packageName = "com.android.shell"
                uid = 2000
            }

            val appInfoField = appBindDataClass.getDeclaredField("appInfo").also { it.isAccessible = true }
            appInfoField.set(appBindData, appInfo)

            val mBoundApp = atClass.getDeclaredField("mBoundApplication").also { it.isAccessible = true }
            mBoundApp.set(activityThread, appBindData)

            Log.i(TAG, "fillShellAppInfo: ActivityThread.mBoundApplication.appInfo.packageName=com.android.shell")
        } catch (e: Exception) {
            Log.w(TAG, "fillShellAppInfo failed", e)
        }
    }

    /**
     * Temporarily swap the current Application's base Context for our shellContext.
     * Returns the original base (to be restored via [restoreApplicationBase]).
     *
     * This is the path AudioRecord ultimately uses to build its AttributionSource:
     *   AttributionSource.myAttributionSource()
     *     → ActivityThread.currentOpPackageName()
     *     → ActivityThread.currentApplication().getOpPackageName()
     *     → Application.mBase (ContextWrapper).getOpPackageName()
     *
     * Rebasing globally at init time crashes Shizuku's ServiceStarter post-init, so we
     * apply it only around the AudioRecord construction.
     */
    private fun rebaseApplicationToShellContext(): android.content.Context? {
        val shell = shellContext ?: return null
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").also { it.isAccessible = true }
                .invoke(null) as? android.app.Application ?: return null
            val mBaseField = android.content.ContextWrapper::class.java.getDeclaredField("mBase")
                .also { it.isAccessible = true }
            val prev = mBaseField.get(app) as? android.content.Context
            mBaseField.set(app, shell)
            prev
        } catch (e: Exception) {
            Log.w(TAG, "rebaseApplicationToShellContext failed", e)
            null
        }
    }

    private fun restoreApplicationBase(prev: android.content.Context?) {
        if (prev == null) return
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").also { it.isAccessible = true }
                .invoke(null) as? android.app.Application ?: return
            val mBaseField = android.content.ContextWrapper::class.java.getDeclaredField("mBase")
                .also { it.isAccessible = true }
            mBaseField.set(app, prev)
        } catch (e: Exception) {
            Log.w(TAG, "restoreApplicationBase failed", e)
        }
    }

    private fun tryInitShellContext() {
        try {
            if (android.os.Looper.myLooper() == null) {
                android.os.Looper.prepare()
            }
            val atClass = Class.forName("android.app.ActivityThread")
            val at = try {
                atClass.getMethod("currentActivityThread").invoke(null)
            } catch (_: Exception) {
                atClass.getMethod("systemMain").invoke(null)
            }
            val systemContext = atClass.getMethod("getSystemContext").invoke(at) as android.content.Context

            // Pre-build an AttributionSource matching shell (uid 2000, pkg com.android.shell)
            // so AudioFlinger's ValidatedAttributionSourceState accepts the combination.
            // The default systemContext.getAttributionSource() reports packageName="android"
            // with uid=1000, which mismatches our actual runtime uid 2000 → rejected.
            val shellAttribution: Any? = try {
                val builderClass = Class.forName("android.content.AttributionSource\$Builder")
                val builder = builderClass
                    .getConstructor(Int::class.javaPrimitiveType)
                    .newInstance(2000)
                builderClass.getMethod("setPackageName", String::class.java)
                    .invoke(builder, "com.android.shell")
                builderClass.getMethod("build").invoke(builder)
            } catch (e: Exception) {
                Log.w(TAG, "Could not build shell AttributionSource", e)
                null
            }


            // Create a proper package context for "com.android.shell" to align our identity
            // with shell UID 2000, resolving SecurityException when starting activities.
            val rawShellContext = try {
                systemContext.createPackageContext("com.android.shell", 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create package context for com.android.shell, falling back to systemContext", e)
                systemContext
            }

            // Wrap with "com.android.shell" package name to match Shizuku uid 2000
            shellContext = object : android.content.ContextWrapper(rawShellContext) {
                override fun getApplicationContext(): android.content.Context = this
                override fun getPackageName(): String = "com.android.shell"
                override fun getOpPackageName(): String = "com.android.shell"
                override fun getAttributionTag(): String? = null
                override fun getAttributionSource(): android.content.AttributionSource {
                    if (shellAttribution is android.content.AttributionSource) return shellAttribution
                    return super.getAttributionSource()
                }
            }

            Log.i(TAG, "Shell context initialized: pkg=${shellContext?.packageName}, attr=${shellAttribution != null}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init shell context", e)
        }
    }

    private fun tryInitInputManager() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as android.os.IBinder

            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getMethod("asInterface", android.os.IBinder::class.java)

            inputManagerInstance = asInterface.invoke(null, binder)

            val injectCandidates = listOf(
                arrayOf(
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType
                ),
                arrayOf(
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            )

            injectMethod = inputManagerInstance!!
                .javaClass.methods
                .firstOrNull { method ->
                    method.name == "injectInputEvent" &&
                    injectCandidates.any { candidate ->
                        method.parameterTypes.contentEquals(candidate)
                    }
                }

            Log.i(TAG,
                "inject method=${injectMethod}, params=${injectMethod?.parameterTypes?.contentToString()}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to init InputManager via Binder", e)
        }
    }

    private fun invokeInjectInputEvent(
        event: InputEvent,
        mode: Int
    ): Boolean {
        val manager = inputManagerInstance ?: return false
        val method = injectMethod ?: return false

        return try {
            val result = when (method.parameterTypes.size) {
                2 -> {
                    method.invoke(
                        manager,
                        event,
                        mode
                    )
                }

                3 -> {
                    when (method.parameterTypes[2]) {
                        Int::class.javaPrimitiveType -> {
                            method.invoke(
                                manager,
                                event,
                                mode,
                                0
                            )
                        }

                        Boolean::class.javaPrimitiveType -> {
                            method.invoke(
                                manager,
                                event,
                                mode,
                                false
                            )
                        }

                        else -> return false
                    }
                }

                else -> return false
            }

            result as? Boolean ?: false

        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent failed", e)
            false
        }
    }


    // Initialize standard system services natively via reflection to bypass shell
    private fun tryInitSystemServices() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            // Cache ActivityManager binder interface
            try {
                val amBinder = getService.invoke(null, "activity") as? android.os.IBinder
                if (amBinder != null) {
                    val amClass = Class.forName("android.app.IActivityManager\$Stub")
                    val asInterface = amClass.getMethod("asInterface", android.os.IBinder::class.java)
                    activityManagerInstance = asInterface.invoke(null, amBinder)
                    forceStopPackageMethod = activityManagerInstance?.javaClass?.getMethod(
                        "forceStopPackage",
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                    Log.i(TAG, "IActivityManager reflection binder successfully prepared")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prepare IActivityManager binder interface", e)
            }


            // Cache ActivityTaskManager binder interface
            try {
                val atmBinder = getService.invoke(null, "activity_task") as? android.os.IBinder
                if (atmBinder != null) {
                    val atmClass = Class.forName("android.app.IActivityTaskManager\$Stub")
                    val asInterface = atmClass.getMethod("asInterface", android.os.IBinder::class.java)
                    activityTaskManagerInstance = asInterface.invoke(null, atmBinder)

                    val atmInterface = activityTaskManagerInstance?.javaClass
                    startActivityMethod = atmInterface?.methods?.find { m ->
                        m.name == "startActivity" && m.parameterTypes.size in 10..12
                    }
                    val moveToFrontCandidates = (atmInterface?.methods?.toList().orEmpty() + runCatching {
                        Class.forName("android.app.IActivityTaskManager").methods.toList()
                    }.getOrDefault(emptyList())).filter { method ->
                        method.name == "moveTaskToFront" && method.parameterTypes.count { it == Int::class.javaPrimitiveType } >= 1
                    }.distinctBy { method -> method.parameterTypes.joinToString(",") }
                    moveTaskToFrontMethod = moveToFrontCandidates.minByOrNull { method -> method.parameterTypes.size }
                    Log.i(TAG, "IActivityTaskManager.moveTaskToFront resolved=${moveTaskToFrontMethod != null} candidates=${moveToFrontCandidates.size} signature=${moveTaskToFrontMethod?.parameterTypes?.joinToString { it.simpleName }}")
                    if (startActivityMethod != null) {
                        Log.i(TAG, "IActivityTaskManager reflection binder successfully prepared (params=${startActivityMethod?.parameterTypes?.size})")
                    } else {
                        Log.w(TAG, "IActivityTaskManager.startActivity method not found")
                    }

                    // Search for moveTaskToDisplay in IActivityTaskManager first
                    try {
                        val atmTargetClass = Class.forName("android.app.IActivityTaskManager")
                        moveTaskToDisplayMethod = atmTargetClass.declaredMethods.find { m ->
                            m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2 &&
                            m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            m.parameterTypes[1] == Int::class.javaPrimitiveType
                        } ?: atmTargetClass.methods.find { m ->
                            m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2 &&
                            m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            m.parameterTypes[1] == Int::class.javaPrimitiveType
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve moveTaskToDisplay on IActivityTaskManager class, trying proxy instance", e)
                    }

                    if (moveTaskToDisplayMethod == null) {
                        // Fallback to proxy instance methods
                        moveTaskToDisplayMethod = atmInterface?.methods?.find { m ->
                            m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2 &&
                            m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            m.parameterTypes[1] == Int::class.javaPrimitiveType
                        } ?: atmInterface?.declaredMethods?.find { m ->
                            m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2 &&
                            m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            m.parameterTypes[1] == Int::class.javaPrimitiveType
                        }
                    }

                    if (moveTaskToDisplayMethod != null) {
                        Log.i(TAG, "IActivityTaskManager.moveTaskToDisplay method successfully cached")
                    } else {
                        Log.w(TAG, "IActivityTaskManager.moveTaskToDisplay method not found. Checking IActivityManager fallback.")
                        // Fallback to IActivityManager
                        try {
                            val amClass = Class.forName("android.app.IActivityManager")
                            moveTaskToDisplayMethod = amClass.declaredMethods.find { m ->
                                m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2 &&
                                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                                m.parameterTypes[1] == Int::class.javaPrimitiveType
                            } ?: amClass.methods.find { m ->
                                m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2 &&
                                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                                m.parameterTypes[1] == Int::class.javaPrimitiveType
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to resolve moveTaskToDisplay on IActivityManager class, trying proxy instance", e)
                        }

                        if (moveTaskToDisplayMethod == null) {
                            val amInterface = activityManagerInstance?.javaClass
                            moveTaskToDisplayMethod = amInterface?.methods?.find { m ->
                                m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2 &&
                                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                                m.parameterTypes[1] == Int::class.javaPrimitiveType
                            } ?: amInterface?.declaredMethods?.find { m ->
                                m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2 &&
                                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                                m.parameterTypes[1] == Int::class.javaPrimitiveType
                            }
                        }

                        if (moveTaskToDisplayMethod != null) {
                            Log.i(TAG, "IActivityManager.moveTaskToDisplay method successfully cached as fallback")
                        } else {
                            // Ultimate fallback: loose parameters check (name and size only)
                            try {
                                val atmTargetClass = Class.forName("android.app.IActivityTaskManager")
                                moveTaskToDisplayMethod = atmTargetClass.declaredMethods.find { m ->
                                    m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2
                                } ?: atmTargetClass.methods.find { m ->
                                    m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2
                                }
                            } catch (_: Exception) {}

                            if (moveTaskToDisplayMethod == null) {
                                try {
                                    val amClass = Class.forName("android.app.IActivityManager")
                                    moveTaskToDisplayMethod = amClass.declaredMethods.find { m ->
                                        m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2
                                    } ?: amClass.methods.find { m ->
                                        m.name == "moveTaskToDisplay" && m.parameterTypes.size == 2
                                    }
                                } catch (_: Exception) {}
                            }

                            if (moveTaskToDisplayMethod != null) {
                                Log.i(TAG, "moveTaskToDisplay cached via loose parameters match")
                            } else {
                                Log.e(TAG, "Failed to resolve moveTaskToDisplay method in both IActivityTaskManager and IActivityManager")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prepare IActivityTaskManager binder interface", e)
            }


            // Cache WindowManager binder interface
            try {
                val wmBinder = getService.invoke(null, "window") as? android.os.IBinder
                if (wmBinder != null) {
                    val wmClass = Class.forName("android.view.IWindowManager\$Stub")
                    val asInterface = wmClass.getMethod("asInterface", android.os.IBinder::class.java)
                    windowManagerInstance = asInterface.invoke(null, wmBinder)

                    val wmInterface = windowManagerInstance?.javaClass
                    setForcedDisplaySizeMethod = wmInterface?.getMethod(
                        "setForcedDisplaySize",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    setForcedDisplayDensityForUserMethod = wmInterface?.getMethod(
                        "setForcedDisplayDensityForUser",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    clearForcedDisplaySizeMethod = wmInterface?.getMethod(
                        "clearForcedDisplaySize",
                        Int::class.javaPrimitiveType
                    )
                    clearForcedDisplayDensityForUserMethod = wmInterface?.getMethod(
                        "clearForcedDisplayDensityForUser",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    setShouldShowSystemDecorsMethod = wmInterface?.methods?.firstOrNull {
                        it.name == "setShouldShowSystemDecors" && it.parameterTypes.size == 2
                    }
                    setDisplayImePolicyMethod = wmInterface?.methods?.firstOrNull {
                        it.name == "setDisplayImePolicy" && it.parameterTypes.size == 2
                    }
                    getDisplayImePolicyMethod = wmInterface?.methods?.firstOrNull {
                        it.name == "getDisplayImePolicy" && it.parameterTypes.size == 1
                    }
                    syncInputTransactionsMethod = wmInterface?.methods?.firstOrNull {
                        it.name == "syncInputTransactions" && it.parameterTypes.size == 1
                    }
                    registerDisplayWindowListenerMethod = wmInterface?.methods?.firstOrNull {
                        it.name == "registerDisplayWindowListener"
                    }
                    unregisterDisplayWindowListenerMethod = wmInterface?.methods?.firstOrNull {
                        it.name == "unregisterDisplayWindowListener"
                    }
                    Log.i(TAG, "IWindowManager reflection binder successfully prepared")
                    ensureDisplayWindowListenerRegistered()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prepare IWindowManager binder interface", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize system services reflection cache", e)
        }
    }

    private fun configureImePolicyForDisplay(displayId: Int, reason: String) {
        val wm = windowManagerInstance ?: run {
            Log.w(TAG, "$VDIME_PREFIX [IME_POLICY] skipped displayId=$displayId reason=$reason wm=null")
            return
        }
        try {
            setShouldShowSystemDecorsMethod?.invoke(wm, displayId, true)
        } catch (e: Exception) {
            Log.w(TAG, "$VDIME_PREFIX [IME_POLICY] setShouldShowSystemDecors failed displayId=$displayId reason=$reason", e)
        }
        try {
            setDisplayImePolicyMethod?.invoke(wm, displayId, DISPLAY_IME_POLICY_LOCAL)
        } catch (e: Exception) {
            Log.w(TAG, "$VDIME_PREFIX [IME_POLICY] setDisplayImePolicy failed displayId=$displayId reason=$reason", e)
        }
        try {
            syncInputTransactionsMethod?.invoke(wm, false)
        } catch (_: Exception) {}
        val policy = try {
            (getDisplayImePolicyMethod?.invoke(wm, displayId) as? Int) ?: -1
        } catch (_: Exception) {
            -1
        }
        Log.i(TAG, "$VDIME_PREFIX [IME_POLICY] displayId=$displayId reason=$reason policy=$policy local=$DISPLAY_IME_POLICY_LOCAL trusted=true")
    }

    // Force stop package natively using IActivityManager to achieve 0ms latency
    private fun nativeForceStop(pkg: String) {
        if (pkg.isEmpty() || pkg == "com.castla.mirror" || pkg == "com.castla.mirror.debug" || pkg.startsWith("com.castla.mirror")) {
            return
        }
        try {
            if (activityManagerInstance != null && forceStopPackageMethod != null) {
                forceStopPackageMethod?.invoke(activityManagerInstance, pkg, 0)
                Log.i(TAG, "Natively force-stopped package $pkg via binder")
            } else {
                execCommand("am force-stop $pkg")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native forceStopPackage failed, falling back to shell command", e)
            try { execCommand("am force-stop $pkg") } catch (_: Exception) {}
        }
    }


    // Start activity natively via IActivityTaskManager to bypass OS package matching restrictions
    private fun nativeStartActivity(intent: Intent, options: android.os.Bundle?): Boolean {
        val atm = activityTaskManagerInstance ?: return false
        val method = startActivityMethod ?: return false
        return try {
            val resolvedType = shellContext?.contentResolver?.let { intent.resolveTypeIfNeeded(it) }
            val paramTypes = method.parameterTypes
            val args = arrayOfNulls<Any>(paramTypes.size)
            for (i in paramTypes.indices) {
                val type = paramTypes[i]
                when {
                    type == android.content.Intent::class.java -> args[i] = intent
                    type == android.os.Bundle::class.java -> args[i] = options
                    type == String::class.java -> {
                        args[i] = null
                    }
                    type == Int::class.javaPrimitiveType -> args[i] = 0
                }
            }
            if (paramTypes.size == 10) {
                args[1] = "com.android.shell"
                args[3] = resolvedType
            } else if (paramTypes.size >= 11) {
                args[1] = "com.android.shell"
                args[4] = resolvedType
            }
            method.invoke(atm, *args)
            Log.i(TAG, "Natively started activity via IActivityTaskManager with com.android.shell")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start activity natively", e)
            false
        }
    }


    private fun createVirtualDeviceManager(context: android.content.Context): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val serviceBinder = serviceManager
            .getMethod("getService", String::class.java)
            .invoke(null, "virtualdevice") as? android.os.IBinder
            ?: error("VirtualDeviceManager binder unavailable")
        val serviceInterfaceClass =
            Class.forName("android.companion.virtual.IVirtualDeviceManager")
        val serviceStubClass =
            Class.forName("android.companion.virtual.IVirtualDeviceManager\$Stub")
        val service = serviceStubClass
            .getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, serviceBinder)
        val managerClass = Class.forName("android.companion.virtual.VirtualDeviceManager")
        return managerClass.getDeclaredConstructor(
            serviceInterfaceClass,
            android.content.Context::class.java,
        ).also { it.isAccessible = true }.newInstance(service, context)
    }

    private fun findShellAppStreamingAssociationId(output: String): Int? {
        return output.lineSequence()
            .firstOrNull { line ->
                line.contains("mPackageName='com.android.shell'") &&
                    line.contains("mDeviceProfile='$SHELL_APP_STREAMING_PROFILE'")
            }
            ?.let { line -> Regex("mId=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun ensureShellAppStreamingAssociationId(): Int {
        val existing = findShellAppStreamingAssociationId(
            execCommand("cmd companiondevice list 0")
        )
        if (existing != null) return existing

        execCommand(
            "cmd companiondevice associate 0 com.android.shell " +
                "$SHELL_ASSOCIATION_DEVICE_ADDRESS $SHELL_APP_STREAMING_PROFILE"
        )
        return findShellAppStreamingAssociationId(
            execCommand("cmd companiondevice list 0")
        ) ?: error("Unable to create shell APP_STREAMING association")
    }

    private fun closeVirtualDevice(virtualDevice: Any?, displayId: Int) {
        if (virtualDevice == null) return
        try {
            virtualDevice.javaClass.getMethod("close").invoke(virtualDevice)
            Log.i(TAG, "VirtualDevice closed for displayId=$displayId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close VirtualDevice for displayId=$displayId", e)
        }
    }

    private fun createVirtualDeviceBackedDisplay(
        context: android.content.Context,
        width: Int,
        height: Int,
        dpi: Int,
        name: String,
    ): Pair<VirtualDisplay, Any> {
        val manager = createVirtualDeviceManager(context)
        val associationId = ensureShellAppStreamingAssociationId()

        val paramsClass = Class.forName("android.companion.virtual.VirtualDeviceParams")
        val paramsBuilderClass =
            Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
        val paramsBuilder = paramsBuilderClass.getConstructor().newInstance()
        paramsBuilderClass.methods
            .firstOrNull { it.name == "setName" && it.parameterCount == 1 }
            ?.invoke(paramsBuilder, "Castla:$name")
        val params = paramsBuilderClass.getMethod("build").invoke(paramsBuilder)
        val createDevice = manager.javaClass.methods.first {
            it.name == "createVirtualDevice" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == paramsClass
        }
        val virtualDevice = createDevice.invoke(manager, associationId, params) ?: error("VirtualDevice creation returned null")

        try {
            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val configBuilderClass =
                Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val configBuilder = configBuilderClass.getConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).newInstance(name, width, height, dpi)

            var flags = DISPLAY_FLAG_PUBLIC or DISPLAY_FLAG_PRESENTATION or
                DISPLAY_FLAG_OWN_CONTENT_ONLY or DISPLAY_FLAG_DESTROY_CONTENT or
                DISPLAY_FLAG_TRUSTED
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                flags = flags or DISPLAY_FLAG_ALWAYS_UNLOCKED
            }
            configBuilderClass.getMethod("setFlags", Int::class.javaPrimitiveType)
                .invoke(configBuilder, flags)
            val config = configBuilderClass.getMethod("build").invoke(configBuilder)
            val createDisplay = virtualDevice.javaClass.methods.first {
                it.name == "createVirtualDisplay" &&
                    it.parameterCount == 3 &&
                    it.parameterTypes[0] == configClass
            }
            val display = createDisplay.invoke(virtualDevice, config, null, null) as VirtualDisplay
            Log.i(
                TAG,
                "[VD_POWER_GROUP] source=virtual_device associationId=$associationId " +
                    "displayId=${display.display.displayId} flags=$flags " +
                    "flagNames=${describeVirtualDisplayFlags(flags)}"
            )
            return display to virtualDevice
        } catch (e: Exception) {
            closeVirtualDevice(virtualDevice, -1)
            throw e
        }
    }
    private fun createLegacyVirtualDisplay(
        context: android.content.Context,
        width: Int,
        height: Int,
        dpi: Int,
        name: String,
    ): VirtualDisplay {
        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val flags = DISPLAY_FLAG_PUBLIC or DISPLAY_FLAG_PRESENTATION or
            DISPLAY_FLAG_OWN_CONTENT_ONLY or DISPLAY_FLAG_DESTROY_CONTENT
        val builder = builderClass.getConstructor(
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).newInstance(name, width, height, dpi)
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType)
            .invoke(builder, flags)
        val config = builderClass.getMethod("build").invoke(builder)
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        val createMethod = globalClass.declaredMethods.first { method ->
            method.name == "createVirtualDisplay" &&
                method.parameterTypes.any { it == configClass }
        }.also { it.isAccessible = true }
        val args = arrayOfNulls<Any>(createMethod.parameterTypes.size)
        createMethod.parameterTypes.forEachIndexed { index, type ->
            when {
                type == configClass -> args[index] = config
                type == android.content.Context::class.java -> args[index] = context
            }
        }
        return createMethod.invoke(global, *args) as? VirtualDisplay
            ?: error("Legacy virtual display creation returned null")
    }
    override fun createVirtualDisplay(width: Int, height: Int, dpi: Int, name: String): Int {
        val existingDisplayIds = virtualDisplayNames
            .filterValues { it == name }
            .keys
            .toList()

        existingDisplayIds.forEach { displayId ->
            Log.i(TAG, "Releasing existing VD displayId=$displayId name=$name before recreating")
            virtualDisplays.remove(displayId)?.let { vd ->
                try {
                    vd.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to release displayId=$displayId", e)
                }
            }
            virtualDisplayNames.remove(displayId)
            closeVirtualDevice(virtualDevicesByDisplayId.remove(displayId), displayId)
        }

        return try {
            val context = shellContext ?: error("Shell context not initialized")
            val (display, virtualDevice) = if (android.os.Build.VERSION.SDK_INT >= 33) {
                createVirtualDeviceBackedDisplay(
                    context = context,
                    width = width,
                    height = height,
                    dpi = dpi,
                    name = name,
                )
            } else {
                createLegacyVirtualDisplay(context, width, height, dpi, name) to null
            }
            val displayId = display.display.displayId
            virtualDisplays[displayId] = display
            virtualDisplayNames[displayId] = name
            if (virtualDevice != null) {
                virtualDevicesByDisplayId[displayId] = virtualDevice
            }
            Log.i(
                TAG,
                "[FocusTrace] vd_created displayId=$displayId size=${width}x${height} " +
                    "source=virtual_device"
            )
            Log.i(
                TAG,
                "$VDIME_PREFIX [VD] source=virtual_device name=$name displayId=$displayId " +
                    "ownerUid=${android.os.Process.myUid()}"
            )
            configureImePolicyForDisplay(displayId, "createVirtualDisplay")
            scheduleDisplayFocusRefresh(displayId, "createVirtualDisplay")
            displayId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDevice-backed display", e)
            -1
        }
    }
    override fun setSurface(displayId: Int, surface: Surface?) {
        val display = virtualDisplays[displayId]
        if (display == null) {
            Log.w(TAG, "setSurface: no display with id=$displayId")
            return
        }
        display.surface = surface
        configureImePolicyForDisplay(displayId, "setSurface")
//        Log.i(TAG, "Surface attached to virtual display $displayId")
        if (surface != null) {
            tetheringExecutor.execute {
                try {
                    execCommand("dumpsys power set-display-state $displayId ON")
                    wakeUpDisplay(displayId)
//                    Log.i(TAG, "Wedge power and wakeup injected during setSurface for displayId=$displayId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply setSurface power activation for displayId=$displayId", e)
                }
            }
        }
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        val virtualDevice = virtualDevicesByDisplayId.remove(displayId)
        virtualDisplays.remove(displayId)?.let {
            virtualDisplayNames.remove(displayId)
            cleanupVirtualDisplayResources(displayId, it, virtualDevice)
            Log.i(TAG, "Virtual display released: id=$displayId")
        } ?: closeVirtualDevice(virtualDevice, displayId)
    }

    override fun injectInput(displayId: Int, action: Int, x: Float, y: Float, pointerId: Int) {
        val now = SystemClock.uptimeMillis()

        cachedProps[0].id = pointerId
        cachedCoords[0].x = x
        cachedCoords[0].y = y

        val event = MotionEvent.obtain(
            now, now, action, 1,
            cachedProps, cachedCoords,
            0, 0, 1.0f, 1.0f,
            0, 0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        try {
            if (setDisplayIdMethod == null) {
                setDisplayIdMethod = MotionEvent::class.java.getMethod(
                    "setDisplayId", Int::class.javaPrimitiveType
                )
            }
            setDisplayIdMethod?.invoke(event, displayId)
        } catch (_: Exception) {}

        try {
            invokeInjectInputEvent(event, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Input injection failed on display $displayId", e)
        } finally {
            event.recycle()
        }
    }

    override fun injectMotionEvent(displayId: Int, event: MotionEvent) {
        injectMotionEventWithResult(displayId, event)
    }

    override fun injectMotionEventWithResult(displayId: Int, event: MotionEvent): Boolean {
        // Inject motion event into the input subsystem natively on the specific virtual display without hidden API warnings and trace logging
        try {
            if (setDisplayIdMethod == null) {
                setDisplayIdMethod = MotionEvent::class.java.getMethod(
                    "setDisplayId", Int::class.javaPrimitiveType
                )
            }
            setDisplayIdMethod?.invoke(event, displayId)
        } catch (_: Exception) {}

        return try {
            invokeInjectInputEvent(event, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Input event injection failed on display $displayId", e)
            false
        }
    }

    private fun ensureDisplayWindowListenerRegistered() {
        if (displayWindowListenerRegistered) return
        val wm = windowManagerInstance ?: return
        val registerMethod = registerDisplayWindowListenerMethod ?: run {
            Log.w(TAG, "[FocusTrace] listener_register_missing_method wmImpl=${wm.javaClass.name}")
            return
        }
        var proxyClassName = "uninitialized"
        try {
            val listenerClass = Class.forName("android.view.IDisplayWindowListener")
            primeDisplayWindowListenerMetadata(listenerClass)
            val listenerBinder = createDisplayWindowListenerBinder()
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass, android.os.IInterface::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "asBinder" -> listenerBinder
                    "toString" -> "CastlaDisplayWindowListenerProxy(displayBinder=${listenerBinder.hashCode()})"
                    "hashCode" -> System.identityHashCode(listenerBinder)
                    "equals" -> args?.firstOrNull() === listenerBinder
                    else -> handleDisplayWindowListenerCallback(method.name, args)
                }
            }
            proxyClassName = proxy.javaClass.name
            val result = registerMethod.invoke(wm, proxy)
            displayWindowListenerProxy = proxy
            displayWindowListenerRegistered = true
            Log.i(TAG, "[FocusTrace] listener_registered wmImpl=${wm.javaClass.name} listenerProxy=$proxyClassName")
            when (result) {
                is IntArray -> result.forEach { scheduleDisplayFocusRefresh(it, "wm_register_seed") }
                is Array<*> -> result.filterIsInstance<Int>().forEach { scheduleDisplayFocusRefresh(it, "wm_register_seed") }
            }
        } catch (e: Exception) {
            val signature = buildString {
                append(registerMethod.name)
                append("(")
                append(registerMethod.parameterTypes.joinToString(",") { it.name })
                append("):")
                append(registerMethod.returnType.name)
            }
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.targetException ?: e.cause else e.cause
            Log.w(
                TAG,
                "[FocusTrace] listener_register_failed method=$signature wmImpl=${wm.javaClass.name} listenerProxy=$proxyClassName cause=${cause?.javaClass?.name}:${cause?.message}",
                e
            )
        }
    }

    private fun primeDisplayWindowListenerMetadata(listenerClass: Class<*>) {
        try {
            val stubClass = Class.forName("android.view.IDisplayWindowListener\$Stub")
            try {
                val descriptorField = stubClass.getDeclaredField("DESCRIPTOR")
                descriptorField.isAccessible = true
                (descriptorField.get(null) as? String)?.takeIf { it.isNotBlank() }?.let {
                    displayWindowListenerDescriptor = it
                }
            } catch (_: Exception) {}

            stubClass.declaredFields
                .filter { it.name.startsWith("TRANSACTION_") }
                .forEach { field ->
                    try {
                        field.isAccessible = true
                        val code = field.getInt(null)
                        val methodName = field.name.removePrefix("TRANSACTION_")
                        displayWindowListenerTransactions[code] = methodName
                    } catch (_: Exception) {}
                }

            if (displayWindowListenerTransactions.isEmpty()) {
                listenerClass.methods.forEachIndexed { index, method ->
                    displayWindowListenerTransactions[index + 1] = method.name
                }
            }
            Log.i(
                TAG,
                "[FocusTrace] listener_metadata descriptor=$displayWindowListenerDescriptor transactions=$displayWindowListenerTransactions"
            )
        } catch (e: Exception) {
            Log.w(TAG, "[FocusTrace] listener_metadata_failed", e)
        }
    }

    private fun createDisplayWindowListenerBinder(): Binder {
        val descriptor = displayWindowListenerDescriptor
        val transactionMap = java.util.HashMap(displayWindowListenerTransactions)
        return object : Binder() {
            override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(descriptor)
                    return true
                }
                val methodName = transactionMap[code]
                if (methodName != null) {
                    return try {
                        data.enforceInterface(descriptor)
                        val displayId = try { data.readInt() } catch (_: Exception) { -1 }
                        handleDisplayWindowListenerCallback(methodName, arrayOf(displayId))
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "[FocusTrace] listener_onTransact_failed code=$code method=$methodName", e)
                        false
                    }
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
    }

    private fun handleDisplayWindowListenerCallback(methodName: String, args: Array<out Any?>?): Any? {
        try {
            val displayId = args?.firstOrNull() as? Int ?: -1
            if (displayId >= 0) {
                Log.i(TAG, "[FocusTrace] listener_callback method=$methodName displayId=$displayId args=${args?.joinToString() ?: "none"}")
            } else {
                Log.i(TAG, "[FocusTrace] listener_callback method=$methodName args=${args?.joinToString() ?: "none"}")
            }
            when (methodName) {
                "onDisplayRemoved" -> {
                    if (displayId >= 0) {
                        displayTopActivityCache.remove(displayId)
                        displayTopActivityUpdatedAt.remove(displayId)
                        Log.i(TAG, "[FocusTrace] cache_cleared displayId=$displayId reason=display_removed")
                    }
                }
                else -> {
                    if (displayId >= 0) {
                        scheduleDisplayFocusRefresh(displayId, methodName)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed handling display window listener callback method=$methodName", e)
        }
        return null
    }

    private fun scheduleDisplayFocusRefresh(displayId: Int, reason: String) {
        if (displayId < 0) return
        displayFocusExecutor.execute {
            try {
                val top = queryTopActivityForDisplayRaw(displayId)
                if (top.isNotBlank()) {
                    displayTopActivityCache[displayId] = top
                    displayTopActivityUpdatedAt[displayId] = SystemClock.elapsedRealtime()
                    Log.i(TAG, "[FocusTrace] cache_update displayId=$displayId reason=$reason top=$top")
                } else {
                    displayTopActivityCache.remove(displayId)
                    displayTopActivityUpdatedAt[displayId] = SystemClock.elapsedRealtime()
                    Log.i(TAG, "[FocusTrace] cache_cleared displayId=$displayId reason=$reason")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[FocusTrace] cache_refresh_failed displayId=$displayId reason=$reason", e)
            }
        }
    }

    private fun doStartWifiTethering(): String {
        val log = StringBuilder()
        log.appendLine("=== startWifiTethering ===")

        // Pre-step: Disable carrier DUN provisioning check
        try {
            val r = execCommand("settings put global tether_dun_required 0")
            log.appendLine("DUN bypass: $r")
        } catch (_: Exception) {}

        // Method 1: TetheringManager via new instance with correct package name
        // getSystemService("tethering") caches a TetheringManager with pkg="android",
        // but TetheringService requires pkg to match UID 2000 ("com.android.shell").
        // So we create a fresh TetheringManager via its constructor.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val ctx = shellContext
                if (ctx == null) {
                    log.appendLine("shellContext is null!")
                } else {
                    val tmObj = createTetheringManager()
                    log.appendLine("TetheringManager created: ${tmObj?.javaClass?.name}")

                    if (tmObj != null) {
                        val tmClass = tmObj.javaClass

                        // Build TetheringRequest with entitlement bypass
                        val requestBuilderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                        val builder = requestBuilderClass
                            .getConstructor(Int::class.javaPrimitiveType)
                            .newInstance(0) // TETHERING_WIFI = 0
                        try {
                            requestBuilderClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType)
                                .invoke(builder, true)
                            log.appendLine("setExemptFromEntitlementCheck: OK")
                        } catch (e: Exception) {
                            log.appendLine("setExemptFromEntitlementCheck: ${e.message}")
                        }
                        try {
                            requestBuilderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType)
                                .invoke(builder, false)
                            log.appendLine("setShouldShowEntitlementUi: OK")
                        } catch (e: Exception) {
                            log.appendLine("setShouldShowEntitlementUi: ${e.message}")
                        }
                        val request = requestBuilderClass.getMethod("build").invoke(builder)
                        log.appendLine("TetheringRequest built: ${request.javaClass.name}")

                        // Create StartTetheringCallback proxy
                        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                        val callback = java.lang.reflect.Proxy.newProxyInstance(
                            callbackClass.classLoader,
                            arrayOf(callbackClass)
                        ) { _, method, args ->
                            when (method.name) {
                                "onTetheringStarted" -> Log.i(TAG, "HOTSPOT: started successfully!")
                                "onTetheringFailed" -> Log.e(TAG, "HOTSPOT: failed, error=${args?.getOrNull(0)}")
                            }
                            null
                        }

                        // Try TetheringRequest overload first
                        val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
                        val startMethod = tmClass.methods.find { m ->
                            m.name == "startTethering" && m.parameterTypes.size == 3 &&
                                m.parameterTypes[0] == requestClass
                        }
                        if (startMethod != null) {
                            val executor = tetheringExecutor
                            startMethod.invoke(tmObj, request, executor, callback)
                            log.appendLine("SUCCESS: startTethering(TetheringRequest) invoked")
                            Log.i(TAG, log.toString())
                            return "OK\n$log"
                        }

                        // Fallback: int overload
                        val intMethod = tmClass.methods.find { m ->
                            m.name == "startTethering" && m.parameterTypes.size == 3 &&
                                m.parameterTypes[0] == Int::class.javaPrimitiveType
                        }
                        if (intMethod != null) {
                            val executor = tetheringExecutor
                            intMethod.invoke(tmObj, 0, executor, callback)
                            log.appendLine("SUCCESS: startTethering(int) invoked")
                            Log.i(TAG, log.toString())
                            return "OK\n$log"
                        }

                        log.appendLine("FAIL: no startTethering method found")
                    }
                }
            } catch (e: Exception) {
                log.appendLine("TetheringManager EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                val cause = e.cause
                if (cause != null) log.appendLine("  cause: ${cause.javaClass.simpleName}: ${cause.message}")
            }
        }

        log.appendLine("ALL METHODS FAILED")
        Log.e(TAG, log.toString())
        return "FAIL\n$log"
    }

    /**
     * Create a TetheringManager with correct caller package name (com.android.shell)
     * instead of using the cached one from getSystemService which reports "android".
     */
    private fun createTetheringManager(): Any? {
        val ctx = shellContext ?: return null
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val tmClass = Class.forName("android.net.TetheringManager")
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "tethering") as? android.os.IBinder ?: return null

        val constructor = tmClass.declaredConstructors.find { c ->
            c.parameterTypes.size == 2 && c.parameterTypes[0] == android.content.Context::class.java
        }
        return if (constructor != null) {
            constructor.isAccessible = true
            constructor.newInstance(ctx, java.util.function.Supplier<android.os.IBinder> { binder })
        } else {
            // Fallback: patch the cached instance
            val tm = ctx.getSystemService("tethering") ?: return null
            try {
                val field = tmClass.getDeclaredField("mCallerPackageName")
                field.isAccessible = true
                field.set(tm, "com.android.shell")
            } catch (_: Exception) {}
            tm
        }
    }

    private fun doStopWifiTethering(): String {
        tetheringExecutor.execute {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    val tmObj = createTetheringManager()
                    if (tmObj != null) {
                        val stopMethod = tmObj.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                        stopMethod.invoke(tmObj, 0)
                        Log.i(TAG, "HOTSPOT OFF (Async): TetheringManager.stopTethering() called")
                        return@execute
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "stopTethering TetheringManager failed", e)
                }
            }

            try {
                val ctx = shellContext
                if (ctx != null) {
                    val cm = ctx.getSystemService("connectivity")
                    if (cm != null) {
                        val stopMethod = cm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                        stopMethod.invoke(cm, 0)
                        Log.i(TAG, "HOTSPOT OFF (Async): ConnectivityManager.stopTethering() called")
                        return@execute
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "All stopTethering methods failed in async task", e)
            }
        }
        return "OK"
    }

    private fun escapeShellArg(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun resolveLaunchComponent(packageOrComponent: String): String? {
        if (packageOrComponent.contains('/')) return packageOrComponent

        val ctx = shellContext ?: return null
        return try {
            val pm = ctx.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageOrComponent)
            val component = launchIntent?.component ?: run {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    `package` = packageOrComponent
                }
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    .firstOrNull()
                    ?.activityInfo
                    ?.let { ComponentName(it.packageName, it.name) }
            }
            component?.flattenToShortString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve launcher component for $packageOrComponent", e)
            null
        }
    }

    private fun buildLaunchCommand(
        displayId: Int,
        packageOrComponent: String,
        extraKey: String? = null,
        extraValue: String? = null
    ): String {
        val resolvedComponent = resolveLaunchComponent(packageOrComponent)
        return buildString {
            append("am start --display $displayId -f ${MultiDisplayLaunchPolicy.shellFlags(reorderToFront = false)} ")
            append("-a android.intent.action.MAIN -c android.intent.category.LAUNCHER ")
            if (resolvedComponent != null) {
                append("-n ${escapeShellArg(resolvedComponent)} ")
            } else {
                append("-p ${escapeShellArg(packageOrComponent)} ")
            }
            if (!extraKey.isNullOrEmpty() && extraValue != null) {
                append("--es $extraKey ${escapeShellArg(extraValue)} ")
            }
        }.trim()
    }


    override fun launchAppOnDisplay(displayId: Int, packageName: String) {
        launchAppOnDisplayV2(displayId, packageName, true)
    }

    override fun launchAppOnDisplayV2(displayId: Int, packageName: String, forceStop: Boolean) {
        try {
            configureImePolicyForDisplay(displayId, "launchAppOnDisplayV2")
            Log.i(TAG, "$VDIME_PREFIX [APP_LAUNCH] package=$packageName displayId=$displayId method=native_launch_on_display_v2 forceStop=$forceStop")
            val pkg = if (packageName.contains("/")) packageName.substringBefore("/") else packageName
            if (forceStop) {
                nativeForceStop(pkg)
            }

            val resolvedComponent = resolveLaunchComponent(packageName)
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                if (resolvedComponent != null) {
                    component = ComponentName.unflattenFromString(resolvedComponent)
                } else {
                    `package` = packageName
                }
                addFlags(MultiDisplayLaunchPolicy.flags(reorderToFront = false))
            }

            val options = android.app.ActivityOptions.makeBasic()
            try {
                val setLaunchDisplayId = options.javaClass.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                setLaunchDisplayId.invoke(options, displayId)
            } catch (e: Exception) {
                Log.w(TAG, "setLaunchDisplayId option failed to apply", e)
            }

            val started = nativeStartActivity(intent, options.toBundle())
            if (started) {
                Log.i(TAG, "Natively launched app $packageName on display $displayId via IActivityTaskManager")
            } else {
                try {
                    shellContext?.startActivity(intent, options.toBundle())
                    Log.i(TAG, "Natively launched app $packageName on display $displayId with 0ms delay")
                } catch (e: Exception) {
                    Log.w(TAG, "Native launchAppOnDisplay failed, falling back to shell executor", e)
                    Log.i(TAG, "$VDIME_PREFIX [APP_LAUNCH] package=$packageName displayId=$displayId method=shell_am_start_display fallback=true")
                    val cmd = buildLaunchCommand(displayId, packageName)
                    execCommand(cmd)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app $packageName on display $displayId", e)
        }
    }

    override fun moveTaskToFrontNative(taskId: Int): Boolean {
        val atm = activityTaskManagerInstance ?: return false
        val method = moveTaskToFrontMethod ?: return false
        return try {
            val args = Array<Any?>(method.parameterTypes.size) { null }
            var intOrdinal = 0
            method.parameterTypes.forEachIndexed { index, type ->
                when {
                    type == Int::class.javaPrimitiveType -> {
                        args[index] = if (intOrdinal++ == 0) taskId else 0
                    }
                    type == String::class.java -> args[index] = "com.android.shell"
                    type == Boolean::class.javaPrimitiveType -> args[index] = false
                    else -> args[index] = null
                }
            }
            val result = method.invoke(atm, *args)
            val success = result as? Boolean ?: true
            Log.i(TAG, "Native moveTaskToFront taskId=$taskId success=$success")
            success
        } catch (e: Exception) {
            Log.w(TAG, "Native moveTaskToFront failed taskId=$taskId", e)
            false
        }
    }
    override fun moveTaskToDisplayNative(taskId: Int, displayId: Int): Boolean {
        val atm = activityTaskManagerInstance ?: return false
        val method = moveTaskToDisplayMethod ?: return false
        return try {
            method.invoke(atm, taskId, displayId)
            Log.i(TAG, "Natively moved task $taskId to display $displayId via IActivityTaskManager")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to move task natively via IActivityTaskManager", e)
            false
        }
    }

    override fun launchAppWithExtraOnDisplay(displayId: Int, packageName: String, extraKey: String, extraValue: String) {
        try {
            configureImePolicyForDisplay(displayId, "launchAppWithExtraOnDisplay")
            Log.i(TAG, "$VDIME_PREFIX [APP_LAUNCH] package=$packageName displayId=$displayId method=native_launch_with_extra extraKey=$extraKey")
            val pkg = if (packageName.contains("/")) packageName.substringBefore("/") else packageName
            nativeForceStop(pkg)

            val resolvedComponent = resolveLaunchComponent(packageName)
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                if (resolvedComponent != null) {
                    component = ComponentName.unflattenFromString(resolvedComponent)
                } else {
                    `package` = packageName
                }
                addFlags(MultiDisplayLaunchPolicy.flags(reorderToFront = false))
                putExtra(extraKey, extraValue)
            }

            val options = android.app.ActivityOptions.makeBasic()
            try {
                val setLaunchDisplayId = options.javaClass.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                setLaunchDisplayId.invoke(options, displayId)
            } catch (e: Exception) {
                Log.w(TAG, "setLaunchDisplayId option failed to apply", e)
            }


            val started = nativeStartActivity(intent, options.toBundle())
            if (started) {
                Log.i(TAG, "Natively launched app $packageName with extras on display $displayId via IActivityTaskManager")
            } else {
                try {
                    shellContext?.startActivity(intent, options.toBundle())
                    Log.i(TAG, "Natively launched app $packageName with extras on display $displayId with 0ms delay")
                } catch (e: Exception) {
                    Log.w(TAG, "Native launchAppWithExtraOnDisplay failed, falling back to shell executor", e)
                    Log.i(TAG, "$VDIME_PREFIX [APP_LAUNCH] package=$packageName displayId=$displayId method=shell_am_start_display fallback=true extraKey=$extraKey")
                    val cmd = buildLaunchCommand(displayId, packageName, extraKey, extraValue)
                    execCommand(cmd)
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to launch $packageName with extra on display $displayId (display not found?)", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName on display $displayId", e)
        }
    }

    override fun launchHomeOnDisplay(displayId: Int) {
        try {
            configureImePolicyForDisplay(displayId, "launchHomeOnDisplay")
            Log.i(TAG, "$VDIME_PREFIX [APP_LAUNCH] package=com.castla.mirror/.ui.VirtualDisplayHomeActivity displayId=$displayId method=native_launch_home")
            val intent = Intent().apply {
                component = ComponentName("com.castla.mirror", "com.castla.mirror.ui.VirtualDisplayHomeActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            val options = android.app.ActivityOptions.makeBasic()
            try {
                options.javaClass.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType).invoke(options, displayId)
            } catch (_: Exception) {}


            val started = nativeStartActivity(intent, options.toBundle())
            if (started) {
                Log.i(TAG, "Natively launched VirtualDisplayHomeActivity on display $displayId via IActivityTaskManager")
            } else {
                try {
                    shellContext?.startActivity(intent, options.toBundle())
                    Log.i(TAG, "Natively launched VirtualDisplayHomeActivity on display $displayId with 0ms delay")
                } catch (e: Exception) {
                    Log.w(TAG, "Native launch home failed, falling back to shell am start", e)
                    val cmd = "am start --display $displayId -n com.castla.mirror/.ui.VirtualDisplayHomeActivity"
                    execCommand(cmd)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch custom HOME on display $displayId", e)

            // Inject KEYCODE_HOME (3) directly into the virtual display to bypass shell fork
            try {
                val now = SystemClock.uptimeMillis()
                val downEvent = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HOME, 0, 0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                val upEvent = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HOME, 0, 0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

                if (setKeyEventDisplayIdMethod == null) {
                    setKeyEventDisplayIdMethod = android.view.KeyEvent::class.java.getMethod(
                        "setDisplayId", Int::class.javaPrimitiveType
                    )
                }
                setKeyEventDisplayIdMethod?.invoke(downEvent, displayId)
                invokeInjectInputEvent(downEvent, 0)

                setKeyEventDisplayIdMethod?.invoke(upEvent, displayId)
                invokeInjectInputEvent(upEvent, 0)
                Log.i(TAG, "Injected KEYCODE_HOME (3) natively on display $displayId")
            } catch (ex: Exception) {
                Log.w(TAG, "Direct KEYCODE_HOME injection failed, falling back to legacy shell", ex)
                try { execCommand("input -d $displayId keyevent 3") } catch (_: Exception) {}
            }

        }
    }



    override fun injectText(text: String, displayId: Int) {
        if (text.isEmpty()) return
        val isAsciiOnly = text.all { it.code < 128 }
        if (isAsciiOnly) {
            try {
                val charMap = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                val events = charMap.getEvents(text.toCharArray())

                if (events != null) {
                    if (setKeyEventDisplayIdMethod == null) {
                        setKeyEventDisplayIdMethod = android.view.KeyEvent::class.java.getMethod(
                            "setDisplayId", Int::class.javaPrimitiveType
                        )
                    }
                    for (event in events) {
                        setKeyEventDisplayIdMethod?.invoke(event, displayId)
                        invokeInjectInputEvent(event, 0)
                    }
                    return
                }

            } catch (e: Exception) {
                Log.w(TAG, "KeyCharacterMap conversion failed, falling back to clipboard", e)
            }
        }

        // Native Clipboard + 0ms paste key event injection without shell interaction
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val clipBinder = getService.invoke(null, "clipboard") as android.os.IBinder
            val clipStubClass = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = clipStubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val clipService = asInterface.invoke(null, clipBinder)
            val clipData = android.content.ClipData.newPlainText("castla", text)
            val setPrimary = clipService.javaClass.methods.find { it.name == "setPrimaryClip" }
            if (setPrimary != null) {
                val paramCount = setPrimary.parameterTypes.size
                val args: Array<Any?> = when (paramCount) {
                    5 -> arrayOf(clipData, "com.android.shell", "com.android.shell", 0, 0)
                    4 -> arrayOf(clipData, "com.android.shell", null, 0)
                    3 -> arrayOf(clipData, "com.android.shell", 0)
                    2 -> arrayOf(clipData, "com.android.shell")
                    else -> arrayOf(clipData)
                }
                setPrimary.invoke(clipService, *args)
            }
            Thread.sleep(50)


            // Inject KEYCODE_PASTE (279) directly to skip expensive shell execution
            try {
                val now = SystemClock.uptimeMillis()
                val downEvent = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PASTE, 0, 0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                val upEvent = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_PASTE, 0, 0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

                if (setKeyEventDisplayIdMethod == null) {
                    setKeyEventDisplayIdMethod = android.view.KeyEvent::class.java.getMethod(
                        "setDisplayId", Int::class.javaPrimitiveType
                    )
                }
                setKeyEventDisplayIdMethod?.invoke(downEvent, displayId)
                invokeInjectInputEvent(downEvent, 0)

                setKeyEventDisplayIdMethod?.invoke(upEvent, displayId)
                invokeInjectInputEvent(upEvent, 0)
                Log.i(TAG, "Injected KEYCODE_PASTE natively on display $displayId")
            } catch (ex: Exception) {

                Log.w(TAG, "Direct KEYCODE_PASTE injection failed, falling back to shell", ex)
                val pasteCmd = if (displayId > 0) {
                    "input -d $displayId keyevent ${android.view.KeyEvent.KEYCODE_PASTE}"
                } else {
                    "input keyevent ${android.view.KeyEvent.KEYCODE_PASTE}"
                }
                execCommand(pasteCmd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard+paste injection failed", e)
            try {
                val escaped = text.replace("%", "%%").replace("'", "'\\''").replace(" ", "%s")
                val cmd = if (displayId > 0) "input -d $displayId text '$escaped'" else "input text '$escaped'"
                execCommand(cmd)
            } catch (ex: Exception) {
                Log.e(TAG, "Ultimate text fallback failed", ex)
            }
        }
    }

    override fun injectComposingText(backspaces: Int, text: String, displayId: Int) {
        try {
            if (backspaces > 0) {
                try {
                    for (i in 0 until backspaces) {
                        val now = SystemClock.uptimeMillis()
                        val downEvent = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL, 0, 0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                        val upEvent = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL, 0, 0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)


                        if (setKeyEventDisplayIdMethod == null) {
                            setKeyEventDisplayIdMethod = android.view.KeyEvent::class.java.getMethod(
                                "setDisplayId", Int::class.javaPrimitiveType
                            )
                        }
                        setKeyEventDisplayIdMethod?.invoke(downEvent, displayId)
                        invokeInjectInputEvent(downEvent, 0)

                        setKeyEventDisplayIdMethod?.invoke(upEvent, displayId)
                        invokeInjectInputEvent(upEvent, 0)
                    }
                    Log.i(TAG, "Injected $backspaces KEYCODE_DEL natively on display $displayId")

                } catch (ex: Exception) {
                    Log.w(TAG, "Direct KEYCODE_DEL injection failed, falling back to shell", ex)
                    val bsKeys = (1..backspaces).joinToString(" ") { "67" }
                    val cmd = if (displayId > 0) "input -d $displayId keyevent $bsKeys" else "input keyevent $bsKeys"
                    execCommand(cmd)
                }
            }
            if (text.isNotEmpty()) injectText(text, displayId)
        } catch (e: Exception) {
            Log.e(TAG, "injectComposingText failed", e)
        }
    }


    override fun addInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean { return true }
    override fun removeInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean { return true }
    override fun setupTeslaNetworking(ifName: String, virtualIp: String): String { return "" }
    override fun restartTetheringWithCgnat(): String { return "" }
    override fun isAlive(): Boolean = true

    // --- WiFi Tethering (Hotspot) control ---

    override fun startWifiTethering(): Boolean {
        Log.i(TAG, "startWifiTethering: attempting to enable hotspot")

        // Pre-step: Disable carrier DUN provisioning check (important for Samsung/carrier devices)
        try {
            execCommand("settings put global tether_dun_required 0")
            Log.i(TAG, "Carrier DUN check disabled")
        } catch (_: Exception) {}

        // Method 1: TetheringManager (Android 11+ / API 30+) — PREFERRED
        // StartTetheringCallback is an INTERFACE with default methods, so Proxy works.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val ctx = shellContext
                if (ctx != null) {
                    val tmObj = ctx.getSystemService("tethering")
                    if (tmObj != null) {
                        val tmClass = tmObj.javaClass

                        // Build TetheringRequest with entitlement bypass
                        val requestBuilderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                        val builder = requestBuilderClass
                            .getConstructor(Int::class.javaPrimitiveType)
                            .newInstance(0) // TETHERING_WIFI = 0

                        // Bypass carrier entitlement check (critical for Samsung/carrier-locked devices)
                        try {
                            requestBuilderClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType)
                                .invoke(builder, true)
                            Log.i(TAG, "setExemptFromEntitlementCheck(true) set")
                        } catch (_: Exception) {
                            Log.w(TAG, "setExemptFromEntitlementCheck not available on this version")
                        }
                        try {
                            requestBuilderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType)
                                .invoke(builder, false)
                            Log.i(TAG, "setShouldShowEntitlementUi(false) set")
                        } catch (_: Exception) {}

                        val request = requestBuilderClass.getMethod("build").invoke(builder)

                        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                        val callback = java.lang.reflect.Proxy.newProxyInstance(
                            callbackClass.classLoader,
                            arrayOf(callbackClass)
                        ) { _, method, args ->
                            when (method.name) {
                                "onTetheringStarted" -> Log.i(TAG, "TetheringManager: hotspot started successfully!")
                                "onTetheringFailed" -> Log.e(TAG, "TetheringManager: hotspot failed, error=${args?.getOrNull(0)}")
                            }
                            null
                        }

                        val startMethod = tmClass.methods.find { m ->
                            m.name == "startTethering" && m.parameterTypes.size == 3
                        }
                        if (startMethod != null) {
                            val executor = tetheringExecutor
                            startMethod.invoke(tmObj, request, executor, callback)
                            Log.i(TAG, "startWifiTethering: TetheringManager.startTethering() invoked")
                            return true
                        } else {
                            Log.w(TAG, "startTethering method not found on TetheringManager")
                        }
                    } else {
                        Log.w(TAG, "getSystemService('tethering') returned null")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TetheringManager approach failed", e)
            }
        }

        // Method 2: ConnectivityManager.startTethering (Android 7-10)
        // OnStartTetheringCallback is abstract class with empty method bodies — ART allows instantiation
        try {
            val ctx = shellContext
            if (ctx != null) {
                val cm = ctx.getSystemService("connectivity")
                if (cm != null) {
                    val callbackClass = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
                    val callback = callbackClass.getDeclaredConstructor().let { ctor ->
                        ctor.isAccessible = true
                        ctor.newInstance()
                    }
                    val startMethod = cm.javaClass.getMethod(
                        "startTethering",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        callbackClass
                    )
                    startMethod.invoke(cm, 0 /* TETHERING_WIFI */, false /* no provisioning UI */, callback)
                    Log.i(TAG, "startWifiTethering: ConnectivityManager.startTethering() invoked")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ConnectivityManager approach failed", e)
        }

        // Method 3: Shell command fallback (Android 11+)
        try {
            val result = execCommand("cmd connectivity tethering wifi enable")
            Log.i(TAG, "startWifiTethering: shell cmd result: $result")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Shell cmd fallback failed", e)
        }

        Log.e(TAG, "All tethering start methods exhausted")
        return false
    }

    override fun stopWifiTethering(): Boolean {
        Log.i(TAG, "stopWifiTethering: attempting to disable hotspot (Async)")
        tetheringExecutor.execute {
            // Method 1: TetheringManager.stopTethering (Android 11+)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    val ctx = shellContext
                    if (ctx != null) {
                        val tmObj = ctx.getSystemService("tethering")
                        if (tmObj != null) {
                            val stopMethod = tmObj.javaClass.getMethod(
                                "stopTethering",
                                Int::class.javaPrimitiveType
                            )
                            stopMethod.invoke(tmObj, 0) // TETHERING_WIFI = 0
                            Log.i(TAG, "stopWifiTethering (Async): TetheringManager.stopTethering() called")
                            return@execute
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "TetheringManager stop failed", e)
                }
            }

            // Method 2: ConnectivityManager.stopTethering
            try {
                val ctx = shellContext
                if (ctx != null) {
                    val cm = ctx.getSystemService("connectivity")
                    if (cm != null) {
                        val stopMethod = cm.javaClass.getMethod(
                            "stopTethering",
                            Int::class.javaPrimitiveType
                        )
                        stopMethod.invoke(cm, 0)
                        Log.i(TAG, "stopWifiTethering (Async): ConnectivityManager.stopTethering() called")
                        return@execute
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ConnectivityManager stop failed", e)
            }

            // Method 3: Shell fallback
            try {
                execCommand("cmd connectivity tethering wifi disable")
                Log.i(TAG, "stopWifiTethering (Async): shell fallback executed")
            } catch (e: Exception) {
                Log.e(TAG, "All stop tethering methods failed", e)
            }
        }
        return true
    }

    // --- System audio capture via AudioPolicy loopback (shell uid has MODIFY_AUDIO_ROUTING) ---
    //
    // Plain AudioRecord(REMOTE_SUBMIX, ...) only captures usages the platform routes to
    // REMOTE_SUBMIX by default (MEDIA/GAME/UNKNOWN). Navigation guidance and other
    // "restricted" usages are not captured that way. To grab them we register an
    // AudioPolicy with a loopback AudioMix whose MixingRule matches every usage we care
    // about, then pull frames from policy.createAudioRecordSink(mix). This is only
    // possible because the Shizuku privileged service runs as shell, which holds
    // MODIFY_AUDIO_ROUTING. On failure we fall back to plain REMOTE_SUBMIX (still
    // captures YouTube/games/etc.).

    @Volatile
    private var audioCaptureRunning = false
    private var audioCaptureThread: Thread? = null
    private var audioCaptureRecord: AudioRecord? = null
    private var registeredAudioPolicy: Any? = null

    override fun startSystemAudioCapture(sampleRate: Int, channels: Int): ParcelFileDescriptor? {
        stopSystemAudioCapture()

        // IMPORTANT: this method is an AIDL binder entry point. Binder.getCallingUid()
        // returns the *app* uid (10xxx), not shell (2000). AudioPolicy/AudioRecord's
        // permission and attribution checks (MODIFY_AUDIO_ROUTING, CAPTURE_AUDIO_OUTPUT)
        // are evaluated against the calling identity — clear it so we look like shell.
        val token = Binder.clearCallingIdentity()
        // Swap Application.mBase so AudioRecord's AttributionSource reports
        // packageName="com.android.shell" matching our uid 2000. Restore immediately
        // after init — AudioRecord has already cached its attribution by then.
        val prevBase = rebaseApplicationToShellContext()
        try {
            return doStartSystemAudioCapture(sampleRate, channels)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start system audio capture", e)
            unregisterAudioPolicy()
            return null
        } finally {
            restoreApplicationBase(prevBase)
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun doStartSystemAudioCapture(sampleRate: Int, channels: Int): ParcelFileDescriptor? {
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf * 2, 8192)

        // Try AudioPolicy-based capture first (includes navigation/assistant/alarm/etc.)
        val policyRecord = tryCreateAudioPolicyRecord(sampleRate, channels)
        val usingPolicy = policyRecord != null
        val record = policyRecord ?: buildRemoteSubmixRecord(sampleRate, channelMask, bufSize)

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio capture AudioRecord failed to initialize (state=${record?.state})")
            try { record?.release() } catch (_: Exception) {}
            unregisterAudioPolicy()
            return null
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        record.startRecording()
        audioCaptureRecord = record
        audioCaptureRunning = true

        audioCaptureThread = Thread({
            val pcmBuf = ByteArray(3840) // 20ms at 48kHz stereo 16bit
            val output = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
            try {
                while (audioCaptureRunning) {
                    val read = record.read(pcmBuf, 0, pcmBuf.size)
                    if (read > 0) {
                        output.write(pcmBuf, 0, read)
                    } else if (read < 0) {
                        Log.w(TAG, "Audio capture read error: $read")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture thread ended", e)
            } finally {
                try { output.close() } catch (_: Exception) {}
            }
        }, "SystemAudio-Capture").also { it.start() }

        val mode = if (usingPolicy) "AudioPolicy loopback" else "plain REMOTE_SUBMIX"
        Log.i(TAG, "System audio capture started via $mode: ${sampleRate}Hz, ${channels}ch")
        return readEnd
    }

    /**
     * Build a plain REMOTE_SUBMIX AudioRecord via the Builder so we can supply shellContext.
     * The default AudioRecord constructor uses ActivityThread.currentApplication() which,
     * inside a Shizuku-loaded privileged service, reports packageName=com.castla.mirror
     * while Process.myUid()=2000 (shell) — AudioFlinger rejects that combination.
     */
    private fun buildRemoteSubmixRecord(sampleRate: Int, channelMask: Int, bufSize: Int): AudioRecord? {
        return try {
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val builder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
            applyShellContextToBuilder(builder)
            builder.build()
        } catch (e: Exception) {
            Log.w(TAG, "buildRemoteSubmixRecord failed", e)
            null
        }
    }

    /** Inject shellContext into AudioRecord.Builder via reflection — setContext is @hide. */
    private fun applyShellContextToBuilder(builder: AudioRecord.Builder) {
        val ctx = shellContext ?: return
        try {
            val m = AudioRecord.Builder::class.java.getMethod("setContext", android.content.Context::class.java)
            m.invoke(builder, ctx)
        } catch (e: Exception) {
            Log.w(TAG, "setContext(shellContext) on AudioRecord.Builder failed: ${e.message}")
        }
    }

    override fun stopSystemAudioCapture() {
        audioCaptureRunning = false
        try { audioCaptureRecord?.stop() } catch (_: Exception) {}
        audioCaptureThread?.join(2000)
        audioCaptureThread = null
        try { audioCaptureRecord?.release() } catch (_: Exception) {}
        audioCaptureRecord = null
        unregisterAudioPolicy()
    }

    /**
     * Builds an AudioPolicy with a loopback AudioMix matching every usage we want to
     * relay to the browser, registers it, then builds an AudioRecord that captures the
     * REMOTE_SUBMIX loopback output from that mix. Uses reflection because the relevant
     * AudioPolicy / AudioMix / AudioAttributes.setInternalCapturePreset /
     * AudioRecord.Builder.buildAudioRecordForAudioPolicy APIs are all @SystemApi.
     *
     * We deliberately do NOT call policy.createAudioRecordSink(), which internally
     * builds an AudioRecord without a Context — the resulting AttributionSource uses
     * ActivityThread.currentApplication()'s packageName (com.castla.mirror), which
     * mismatches our shell uid (2000) and gets rejected by AudioFlinger. Building the
     * AudioRecord ourselves lets us inject shellContext so attribution reports
     * packageName=com.android.shell matching uid 2000.
     *
     * Returns null on any failure — caller falls back to plain REMOTE_SUBMIX.
     */
    private fun tryCreateAudioPolicyRecord(sampleRate: Int, channels: Int): AudioRecord? {
        val context = shellContext ?: return null
        return try {
            val audioManager = context.getSystemService(android.media.AudioManager::class.java) ?: return null

            val mixingRuleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val mixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val audioMixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy")

            val ruleMatchAttributeUsage = 1   // AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE
            val routeFlagLoopBack = 2         // AudioMix.ROUTE_FLAG_LOOP_BACK

            val ruleBuilder = mixingRuleBuilderClass.getConstructor().newInstance()
            val addRule = mixingRuleBuilderClass.getMethod(
                "addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType
            )

            val usages = intArrayOf(
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                AudioAttributes.USAGE_ASSISTANT,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
                AudioAttributes.USAGE_ALARM,
                AudioAttributes.USAGE_NOTIFICATION,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                AudioAttributes.USAGE_NOTIFICATION_EVENT,
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            )
            var matchedAny = false
            for (usage in usages) {
                try {
                    val attr = AudioAttributes.Builder().setUsage(usage).build()
                    addRule.invoke(ruleBuilder, attr, ruleMatchAttributeUsage)
                    matchedAny = true
                } catch (e: Exception) {
                    Log.w(TAG, "AudioMixingRule skip usage=$usage: ${e.message}")
                }
            }
            if (!matchedAny) return null
            val mixingRule = mixingRuleBuilderClass.getMethod("build").invoke(ruleBuilder)

            val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val mixBuilder = mixBuilderClass.getConstructor(mixingRuleClass).newInstance(mixingRule)
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, routeFlagLoopBack)
            val audioMix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

            val policyBuilder = policyBuilderClass
                .getConstructor(android.content.Context::class.java)
                .newInstance(context)
            policyBuilderClass.getMethod("addMix", audioMixClass).invoke(policyBuilder, audioMix)
            val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

            val registerResult = audioManager.javaClass
                .getMethod("registerAudioPolicy", audioPolicyClass)
                .invoke(audioManager, policy) as Int
            if (registerResult != 0) {
                Log.w(TAG, "registerAudioPolicy failed with code $registerResult")
                return null
            }
            registeredAudioPolicy = policy

            val record = audioPolicyClass
                .getMethod("createAudioRecordSink", audioMixClass)
                .invoke(policy, audioMix) as? AudioRecord
            if (record == null) {
                Log.w(TAG, "createAudioRecordSink returned null")
                unregisterAudioPolicy()
                return null
            }
            record
        } catch (e: Exception) {
            Log.w(TAG, "AudioPolicy capture setup failed, will fall back", e)
            unregisterAudioPolicy()
            null
        }
    }

    private fun unregisterAudioPolicy() {
        val policy = registeredAudioPolicy ?: return
        registeredAudioPolicy = null
        try {
            val context = shellContext ?: return
            val audioManager = context.getSystemService(android.media.AudioManager::class.java) ?: return
            val audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            audioManager.javaClass
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister audio policy", e)
        }
    }

    private fun doCleanupVirtualDisplayResourcesSync(displayId: Int, vd: android.hardware.display.VirtualDisplay) {
        try {
            Log.i(TAG, "Cleaning up resources for virtual display: id=$displayId")

            val apps = getRunningTasksOnDisplay(displayId)
            apps.forEach { app ->
                if (app.isNotEmpty() && !app.contains("/") && app != "com.castla.mirror" && app != "com.castla.mirror.debug") {
                    try {
                        nativeForceStop(app)
                        Log.i(TAG, "Successfully force-stopped app $app on display $displayId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to force-stop app $app on display $displayId", e)
                    }
                }
            }

            try {
                if (windowManagerInstance != null && clearForcedDisplaySizeMethod != null) {
                    clearForcedDisplaySizeMethod?.invoke(windowManagerInstance, displayId)
                    Log.i(TAG, "Natively reset WindowManager size for display $displayId")
                } else {
                    execCommand("wm size reset -d $displayId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native WindowManager size reset failed, falling back to shell", e)
                try { execCommand("wm size reset -d $displayId") } catch (_: Exception) {}
            }

            try {
                if (windowManagerInstance != null && clearForcedDisplayDensityForUserMethod != null) {
                    clearForcedDisplayDensityForUserMethod?.invoke(windowManagerInstance, displayId, 0)
                    Log.i(TAG, "Natively reset WindowManager density for display $displayId")
                } else {
                    execCommand("wm density reset -d $displayId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native WindowManager density reset failed, falling back to shell", e)
                try { execCommand("wm density reset -d $displayId") } catch (_: Exception) {}
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in cleanupVirtualDisplayResources for display $displayId", e)
        } finally {
            try {
                vd.release()
                Log.i(TAG, "Virtual display released successfully: id=$displayId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release VirtualDisplay object for id=$displayId", e)
            }
        }
    }

    private fun cleanupVirtualDisplayResources(
        displayId: Int,
        vd: android.hardware.display.VirtualDisplay,
        virtualDevice: Any?,
    ) {
        tetheringExecutor.execute {
            try {
                doCleanupVirtualDisplayResourcesSync(displayId, vd)
            } finally {
                closeVirtualDevice(virtualDevice, displayId)
            }
        }
    }

    override fun destroy() {
        Log.i(TAG, "[PRIVILEGED_SERVICE] release")
        stopSystemAudioCapture()
        val displaysToCleanup = virtualDisplays.toList()
        val virtualDevicesToCleanup = virtualDevicesByDisplayId.toMap()
        virtualDisplays.clear()
        virtualDisplayNames.clear()
        virtualDevicesByDisplayId.clear()
        displaysToCleanup.forEach { (displayId, vd) ->
            try {
                doCleanupVirtualDisplayResourcesSync(displayId, vd)
                closeVirtualDevice(virtualDevicesToCleanup[displayId], displayId)
            } catch (e: Exception) {
                Log.e(TAG, "Error in destroy() cleaning display $displayId", e)
            }
        }
        try {
            if (displayWindowListenerRegistered && windowManagerInstance != null && unregisterDisplayWindowListenerMethod != null) {
                unregisterDisplayWindowListenerMethod?.invoke(windowManagerInstance, displayWindowListenerProxy)
                displayWindowListenerRegistered = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister display window listener on destroy", e)
        }
        try {
            tetheringExecutor.shutdownNow()
        } catch (_: Exception) {}
        try {
            displayFocusExecutor.shutdownNow()
        } catch (_: Exception) {}
        System.exit(0)
    }

    override fun wakeUpDisplay(displayId: Int) {
        if (displayId <= 0) {
            logScreenOffWarn("[SCREEN_OFF] [VD_STATE_ON] displayId=$displayId targetPhysical=true skipped=true")
            return
        }
        val now = System.currentTimeMillis()
        val lastTime = lastWakeUpTimeMap[displayId] ?: 0L
        if (now - lastTime < 3000L) return
        lastWakeUpTimeMap[displayId] = now

        tetheringExecutor.execute {
            try {
                execCommand("dumpsys power set-display-state $displayId ON")
                logScreenOffInfo("[SCREEN_OFF] [VD_STATE_ON] displayId=$displayId targetPhysical=false wakeKey=false")
            } catch (e: Exception) {
                Log.e(TAG, "VD display-state ON failed for display $displayId", e)
            }
        }
    }

    override fun keepVirtualDisplayAlive(displayId: Int) {
        if (displayId <= 0) {
            logScreenOffWarn("[SCREEN_OFF] [VD_KEEPALIVE] displayId=$displayId command=set-display-state ON targetPhysical=true skipped=true")
            return
        }
        try {
            execCommand("dumpsys power set-display-state $displayId ON")
            logScreenOffInfo("[SCREEN_OFF] [VD_KEEPALIVE] displayId=$displayId command=set-display-state ON targetPhysical=false")
        } catch (e: Exception) {
            Log.e(TAG, "keepVirtualDisplayAlive failed for display $displayId", e)
        }
    }



    override fun resizeVirtualDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int) {
        val vd = virtualDisplays[displayId]
        if (vd == null) {
            Log.w(TAG, "resizeVirtualDisplay: no display with id=$displayId")
            throw IllegalStateException("Virtual display $displayId not found")
        }
        vd.resize(width, height, densityDpi)
        Log.i(TAG, "Resized virtual display $displayId to ${width}x${height} @ ${densityDpi}dpi")
        try {
            if (windowManagerInstance != null && setForcedDisplaySizeMethod != null && setForcedDisplayDensityForUserMethod != null) {
                setForcedDisplaySizeMethod?.invoke(windowManagerInstance, displayId, width, height)
                setForcedDisplayDensityForUserMethod?.invoke(windowManagerInstance, displayId, densityDpi, 0)
                Log.i(TAG, "Natively synchronized WindowManager size and density for display $displayId")
            } else {
                execCommand("wm size ${width}x${height} -d $displayId")
                execCommand("wm density $densityDpi -d $displayId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native WindowManager sync failed, falling back to shell", e)
            try {
                execCommand("wm size ${width}x${height} -d $displayId")
                execCommand("wm density $densityDpi -d $displayId")
            } catch (_: Exception) {}
        }
    }


    override fun registerDeathToken(token: android.os.IBinder) {
        try {
            token.linkToDeath({
                Log.w(TAG, "Client died! Cleaning up PrivilegedService and killing VDs.")
                destroy()

                // REMOVED System.exit(0) to prevent the privileged shell process from exploding immediately,
                // which was causing a race condition interrupting the asynchronous Binder release IPC transactions of VirtualDisplays.

            }, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to link to death", e)
        }
    }

    // --- Physical display power control (scrcpy approach) ---

    private val POWER_MODE_OFF = 0
    private val POWER_MODE_NORMAL = 2

    override fun setPhysicalDisplayPower(on: Boolean) {
        // Log.i(TAG, "[BUILD:screen-off-v2] setPhysicalDisplayPower($on) ENTRY")
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        try {
            val scClass = Class.forName("android.view.SurfaceControl")
            val setMethod = scClass.getMethod(
                "setDisplayPowerMode",
                android.os.IBinder::class.java, Int::class.javaPrimitiveType
            )

            val token = getPhysicalDisplayToken(scClass)
            if (token != null) {
                setMethod.invoke(null, token, mode)
                Log.i(TAG, "Physical display power set to ${if (on) "ON" else "OFF"}")
            } else {
                Log.e(TAG, "Could not get physical display token")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setPhysicalDisplayPower failed", e)
        }
    }

    private fun getPhysicalDisplayToken(scClass: Class<*>): android.os.IBinder? {
        // Try Android 10-13: SurfaceControl.getPhysicalDisplayIds() + getPhysicalDisplayToken()
        try {
            val getIds = scClass.getMethod("getPhysicalDisplayIds")
            val getToken = scClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            val ids = getIds.invoke(null) as? LongArray
            if (ids != null && ids.isNotEmpty()) {
                return getToken.invoke(null, ids[0]) as? android.os.IBinder
            }
        } catch (_: Exception) {}

        // Try Android 10+: getInternalDisplayToken()
        try {
            val m = scClass.getMethod("getInternalDisplayToken")
            return m.invoke(null) as? android.os.IBinder
        } catch (_: Exception) {}

        // Try Android 14+: DisplayControl from services.jar
        try {
            val classLoaderFactoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val createClassLoaderMethod = classLoaderFactoryClass.getDeclaredMethod(
                "createClassLoader",
                String::class.java, String::class.java, String::class.java,
                ClassLoader::class.java, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, String::class.java
            )
            val classLoader = createClassLoaderMethod.invoke(
                null, "/system/framework/services.jar", null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null
            ) as ClassLoader
            val dcClass = classLoader.loadClass("com.android.server.display.DisplayControl")
            try {
                val loadLib = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
                loadLib.isAccessible = true
                loadLib.invoke(Runtime.getRuntime(), dcClass, "android_servers")
            } catch (_: Exception) {}
            val getIds = dcClass.getMethod("getPhysicalDisplayIds")
            val getToken = dcClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            val ids = getIds.invoke(null) as? LongArray
            if (ids != null && ids.isNotEmpty()) {
                return getToken.invoke(null, ids[0]) as? android.os.IBinder
            }
        } catch (_: Exception) {}

        // Fallback: getBuiltInDisplay(0)
        try {
            val m = scClass.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
            return m.invoke(null, 0) as? android.os.IBinder
        } catch (_: Exception) {}

        return null
    }

    override fun removeTask(taskId: Int) {
        Log.i(TAG, "removeTask($taskId) ENTRY")
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val atm = atmClass.getMethod("getService").invoke(null)
            val removeTaskMethod = atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
            val success = removeTaskMethod.invoke(atm, taskId) as? Boolean
            Log.i(TAG, "removeTask($taskId) result: $success")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove task $taskId via ActivityTaskManager", e)
        }
    }

    private var previousStayAwake: String? = null
    private var previousPowerButtonLocks: String? = null
    private var previousLockTimeout: String? = null

    override fun enableStayAwakeMode() {
        Log.i(TAG, "enableStayAwakeMode ENTRY")
        try {
            val oldVal = execCommand("settings get global stay_on_while_plugged_in").trim()
            previousStayAwake = oldVal
            val newVal = "7"
            execCommand("settings put global stay_on_while_plugged_in $newVal")
            Log.i(TAG, "Stay-awake mode enabled: previous=$oldVal new=$newVal")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable stay-awake mode", e)
        }

        try {
            val oldPowerBtn = execCommand("settings get secure power_button_instantly_locks").trim()
            previousPowerButtonLocks = oldPowerBtn
            execCommand("settings put secure power_button_instantly_locks 0")
            Log.i(TAG, "Disabled power button instantly locks: previous=$oldPowerBtn new=0")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable power button instantly locks", e)
        }

        try {
            val oldTimeout = execCommand("settings get secure lock_screen_lock_after_timeout").trim()
            previousLockTimeout = oldTimeout
            execCommand("settings put secure lock_screen_lock_after_timeout 86400000")
            Log.i(TAG, "Extended lockscreen lock timeout: previous=$oldTimeout new=86400000")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extend lock screen timeout", e)
        }
    }

    override fun restoreStayAwakeMode() {
        Log.i(TAG, "restoreStayAwakeMode ENTRY")
        try {
            val oldVal = previousStayAwake
            if (oldVal != null) {
                execCommand("settings put global stay_on_while_plugged_in $oldVal")
                Log.i(TAG, "Stay-awake mode restored to $oldVal")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore stay-awake mode", e)
        }

        try {
            val oldPowerBtn = previousPowerButtonLocks
            if (oldPowerBtn != null && oldPowerBtn != "null") {
                execCommand("settings put secure power_button_instantly_locks $oldPowerBtn")
                Log.i(TAG, "Power button instantly locks restored to $oldPowerBtn")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore power button instantly locks setting", e)
        }

        try {
            val oldTimeout = previousLockTimeout
            if (oldTimeout != null && oldTimeout != "null") {
                execCommand("settings put secure lock_screen_lock_after_timeout $oldTimeout")
                Log.i(TAG, "Lock screen timeout restored to $oldTimeout")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore lock screen timeout setting", e)
        }
    }

    private fun invokeGetTasks(service: Any?, displayId: Int?): List<*> {
        if (service == null) return emptyList<Any>()
        val methods = service.javaClass.methods.filter { it.name == "getTasks" }
        val method = methods.firstOrNull { candidate ->
            candidate.parameterTypes.count { it == Int::class.javaPrimitiveType } >= 2
        } ?: throw NoSuchMethodException("No compatible getTasks method found")
        val intCount = method.parameterTypes.count { it == Int::class.javaPrimitiveType }
        val args = Array<Any?>(method.parameterTypes.size) { index ->
            val type = method.parameterTypes[index]
            if (type == Int::class.javaPrimitiveType) null
            else if (type == Boolean::class.javaPrimitiveType) false
            else null
        }
        val intArgs = GetTasksQueryPolicy.intArguments(intCount, displayId)
        var intOrdinal = 0
        method.parameterTypes.forEachIndexed { index, type ->
            if (type == Int::class.javaPrimitiveType) {
                args[index] = intArgs[intOrdinal++]
            }
        }
        val result = method.invoke(service, *args) as? List<*> ?: emptyList<Any>()
        Log.d(TAG, "getTasks signature=${method.parameterTypes.joinToString { it.simpleName }} displayId=$displayId result=${result.size}")
        return result
    }
    override fun getRunningTasksOnDisplay(displayId: Int): List<String> {
        val packages = mutableListOf<String>()
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getService = atmClass.getMethod("getService")
            val service = getService.invoke(null)

            val tasks = invokeGetTasks(service, displayId)

            for (task in tasks) {
                if (task == null) continue
                val taskClass = task.javaClass

                val displayIdField = try {
                    taskClass.getField("displayId")
                } catch (_: Exception) {
                    try {
                        taskClass.getSuperclass()?.getField("displayId")
                    } catch (_: Exception) {
                        null
                    }
                }
                val tDisplayId = displayIdField?.getInt(task) ?: -1
                if (displayId == -1 || tDisplayId == displayId) {
                    val topActField = try {
                        taskClass.getField("topActivity")
                    } catch (_: Exception) {
                        try {
                            taskClass.getSuperclass()?.getField("topActivity")
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val topActivity = topActField?.get(task) as? ComponentName
                    if (topActivity != null) {
                        packages.add(topActivity.packageName)
                        packages.add(topActivity.flattenToShortString())
                    }

                    val baseActField = try {
                        taskClass.getField("baseActivity")
                    } catch (_: Exception) {
                        try {
                            taskClass.getSuperclass()?.getField("baseActivity")
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val baseActivity = baseActField?.get(task) as? ComponentName
                    if (baseActivity != null) {
                        packages.add(baseActivity.packageName)
                        packages.add(baseActivity.flattenToShortString())
                    }

                    val baseIntentField = try {
                        taskClass.getField("baseIntent")
                    } catch (_: Exception) {
                        try {
                            taskClass.getSuperclass()?.getField("baseIntent")
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val baseIntentObj = baseIntentField?.get(task) as? android.content.Intent
                    val baseIntentPkg = baseIntentObj?.`package` ?: baseIntentObj?.component?.packageName ?: ""
                    if (baseIntentPkg.isNotEmpty()) {
                        packages.add(baseIntentPkg)
                        baseIntentObj?.component?.flattenToShortString()?.let { packages.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get running tasks on display $displayId via reflection", e)
        }
        return packages.distinct()
    }

    override fun getTaskIdsForPackage(packageName: String): IntArray {
        val taskIds = mutableListOf<Int>()
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getService = atmClass.getMethod("getService")
            val service = getService.invoke(null)

            val tasks = invokeGetTasks(service, null)

            Log.d(TAG, "getTaskIdsForPackage natively: queried ${tasks.size} tasks for package $packageName")

            for (task in tasks) {
                if (task == null) continue
                val taskClass = task.javaClass

                val topActField = try { taskClass.getField("topActivity") } catch (_: Exception) { null }
                val baseActField = try { taskClass.getField("baseActivity") } catch (_: Exception) { null }
                val realActField = try { taskClass.getField("realActivity") } catch (_: Exception) { null }
                val origActField = try { taskClass.getField("origActivity") } catch (_: Exception) { null }

                val topActivityObj = topActField?.get(task)
                val baseActivityObj = baseActField?.get(task)
                val realActivityObj = realActField?.get(task)
                val origActivityObj = origActField?.get(task)

                val topStr = topActivityObj?.toString() ?: ""
                val baseStr = baseActivityObj?.toString() ?: ""
                val realStr = realActivityObj?.toString() ?: ""
                val origStr = origActivityObj?.toString() ?: ""

                val baseIntentField = try { taskClass.getField("baseIntent") } catch (_: Exception) { null }
                val baseIntentObj = baseIntentField?.get(task) as? android.content.Intent
                val baseIntentPkg = baseIntentObj?.`package` ?: baseIntentObj?.component?.packageName ?: ""

                val matches = topStr.contains(packageName) ||
                              baseStr.contains(packageName) ||
 realStr.contains(packageName) ||
                              origStr.contains(packageName) ||
                              (baseIntentPkg.isNotEmpty() && baseIntentPkg.contains(packageName))

                if (matches) {
                    // 안드로이드 API 29+ 에서는 taskId 필드가 표준이며, 이전 버전은 id 필드를 사용함
                    val idField = try {
                        taskClass.getField("taskId")
                    } catch (_: Exception) {
                        try {
                            taskClass.getField("id")
                        } catch (_: Exception) {
                            try {
                                taskClass.getSuperclass()?.getField("taskId")
                            } catch (_: Exception) {
                                try {
                                    taskClass.getSuperclass()?.getField("id")
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                    }
                    val taskId = idField?.getInt(task) ?: -1
                    if (taskId != -1) {
                        taskIds.add(taskId)
                        Log.i(TAG, "Matched task ID $taskId for package $packageName (top=$topStr, base=$baseStr, real=$realStr, orig=$origStr)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get task IDs for package $packageName natively", e)
        }
        return taskIds.toIntArray()
    }

    private fun queryTopActivityForDisplayRaw(displayId: Int): String {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getService = atmClass.getMethod("getService")
            val service = getService.invoke(null)

            try {
                val focusedRootTaskMethod = service.javaClass.getMethod("getFocusedRootTaskInfo")
                val focusedTaskInfo = focusedRootTaskMethod.invoke(service)
                if (focusedTaskInfo != null) {
                    val focusedClass = focusedTaskInfo.javaClass
                    val focusedDisplayField = try {
                        focusedClass.getField("displayId")
                    } catch (_: Exception) {
                        focusedClass.getSuperclass()?.getField("displayId")
                    }
                    val focusedDisplayId = focusedDisplayField?.getInt(focusedTaskInfo) ?: -1
                    if (focusedDisplayId == displayId) {
                        val topActField = try {
                            focusedClass.getField("topActivity")
                        } catch (_: Exception) {
                            focusedClass.getSuperclass()?.getField("topActivity")
                        }
                        val topActivity = topActField?.get(focusedTaskInfo) as? ComponentName
                        if (topActivity != null) {
                            return topActivity.flattenToShortString()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[FocusTrace] focused_root_failed displayId=$displayId", e)
            }

            val tasks = invokeGetTasks(service, displayId)

            for (task in tasks) {
                if (task == null) continue
                val taskClass = task.javaClass
                val displayIdField = try {
                    taskClass.getField("displayId")
                } catch (_: Exception) {
                    try {
                        taskClass.getSuperclass()?.getField("displayId")
                    } catch (_: Exception) {
                        null
                    }
                }
                val taskDisplayId = displayIdField?.getInt(task) ?: -1
                if (taskDisplayId != displayId) continue

                val topActField = try {
                    taskClass.getField("topActivity")
                } catch (_: Exception) {
                    try {
                        taskClass.getSuperclass()?.getField("topActivity")
                    } catch (_: Exception) {
                        null
                    }
                }
                val topActivity = topActField?.get(task) as? ComponentName
                if (topActivity != null) {
                    return topActivity.flattenToShortString()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[FocusTrace] top_activity_failed displayId=$displayId", e)
        }
        return ""
    }

    override fun getTopActivityForDisplay(displayId: Int): String {
        if (displayId < 0) return ""
        ensureDisplayWindowListenerRegistered()

        val cached = displayTopActivityCache[displayId]?.trim().orEmpty()
        val cachedAt = displayTopActivityUpdatedAt[displayId] ?: 0L
        val cacheAgeMs = if (cachedAt > 0L) (SystemClock.elapsedRealtime() - cachedAt).coerceAtLeast(0L) else Long.MAX_VALUE
        if (cached.isNotBlank() && cacheAgeMs <= 5_000L) {
            return cached
        }

        val top = queryTopActivityForDisplayRaw(displayId)
        if (top.isNotBlank()) {
            displayTopActivityCache[displayId] = top
            displayTopActivityUpdatedAt[displayId] = SystemClock.elapsedRealtime()
        }
        return top
    }

    override fun getDisplayIdForPackage(packageName: String): Int {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getService = atmClass.getMethod("getService")
            val service = getService.invoke(null)

            val tasks = invokeGetTasks(service, null)

            for (task in tasks) {
                if (task == null) continue
                val taskClass = task.javaClass

                val topActField = try { taskClass.getField("topActivity") } catch (_: Exception) { null }
                val baseActField = try { taskClass.getField("baseActivity") } catch (_: Exception) { null }
                val realActField = try { taskClass.getField("realActivity") } catch (_: Exception) { null }
                val origActField = try { taskClass.getField("origActivity") } catch (_: Exception) { null }

                val topActivityObj = topActField?.get(task)
                val baseActivityObj = baseActField?.get(task)
                val realActivityObj = realActField?.get(task)
                val origActivityObj = origActField?.get(task)

                val topStr = topActivityObj?.toString() ?: ""
                val baseStr = baseActivityObj?.toString() ?: ""
                val realStr = realActivityObj?.toString() ?: ""
                val origStr = origActivityObj?.toString() ?: ""

                val baseIntentField = try { taskClass.getField("baseIntent") } catch (_: Exception) { null }
                val baseIntentObj = baseIntentField?.get(task) as? android.content.Intent
                val baseIntentPkg = baseIntentObj?.`package` ?: baseIntentObj?.component?.packageName ?: ""

                val matches = topStr.contains(packageName) ||
                              baseStr.contains(packageName) ||
 realStr.contains(packageName) ||
                              origStr.contains(packageName) ||
                              (baseIntentPkg.isNotEmpty() && baseIntentPkg.contains(packageName))

                if (matches) {
                    val displayIdField = try {
                        taskClass.getField("displayId")
                    } catch (_: Exception) {
                        try {
                            taskClass.getSuperclass()?.getField("displayId")
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val tDisplayId = displayIdField?.getInt(task) ?: -1
                    if (tDisplayId >= 0) {
                        Log.i(TAG, "Matched display ID $tDisplayId for package $packageName")
                        return tDisplayId
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get display ID for package $packageName", e)
        }
        return -1
    }

    /**
     * [오버로드 모호성 완벽 해결 버전]
     * 외부 앱 기동(am start) 명령어와 가상 화면 생성/제거가 단일 프로세스 내에서
     * 상호 배제되도록 락을 제어하는 유일한 execCommand 진입점입니다.
     */
    override fun execCommand(command: String): String {
        if (command == "__HOTSPOT_ON__") return doStartWifiTethering()
        if (command == "__HOTSPOT_OFF__") return doStopWifiTethering()

        // 🔴 [미러링 앱 자살 방지 가드]
        if (command.startsWith("am force-stop ") && command.contains("com.castla.mirror")) {
            Log.w(TAG, "Aborted am force-stop command targeting self application to prevent suicide: $command")
            return "Ignored self-destruction command"
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            // 두 스트림을 동시에 끝까지 읽어옵니다.
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()

            // 🔴 [핵심 교정] 에러 출력과 일반 출력을 하나로 병합하여 반환합니다.
            // 이렇게 해야 메인 앱이 Warning이나 SecurityException을 100% 인지합니다.
            val totalResult = (output + "\n" + error).trim()

            if (error.isNotEmpty()) {
                Log.w(TAG, "stderr: $error")
                if (error.contains("SecurityException") || error.contains("Permission Denial")) {
                    throw SecurityException(error.trim())
                }
            }

            totalResult
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            ""
        }
    }

    override fun getProcessPid(): Int {
        return android.os.Process.myPid()
    }
}






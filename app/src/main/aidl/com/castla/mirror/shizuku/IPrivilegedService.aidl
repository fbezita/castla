package com.castla.mirror.shizuku;

import android.os.ParcelFileDescriptor;
import android.view.Surface;
import android.view.MotionEvent;

interface IPrivilegedService {
    void destroy() = 16777114;

    /**
     * Create a virtual display with the given parameters.
     * Returns the display ID, or -1 on failure.
     */
    int createVirtualDisplay(int width, int height, int dpi, String name) = 1;

    /**
     * Attach a Surface to the virtual display so content renders onto it.
     * Must be called after createVirtualDisplay with the encoder's input surface.
     */
    void setSurface(int displayId, in Surface surface) = 5;

    /**
     * Release the virtual display with the given ID.
     */
    void releaseVirtualDisplay(int displayId) = 2;

    /**
     * Inject an input event at the given coordinates on the specified display.
     * action: MotionEvent action constant (0=DOWN, 1=UP, 2=MOVE)
     */
    @JavaPassthrough(annotation="@java.lang.Deprecated")
    void injectInput(int displayId, int action, float x, float y, int pointerId) = 3;

    /**
     * Inject a full MotionEvent on the specified display (supports multi-touch).
     */
    void injectMotionEvent(int displayId, in MotionEvent event) = 24;

    /**
     * Check if the service is alive.
     */
    boolean isAlive() = 4;

    /**
     * Execute a shell command with elevated (ADB) privileges.
     * Returns the command output (stdout), or empty string on failure.
     */
    String execCommand(String command) = 6;

    /**
     * Add an IP address to a network interface using Android's INetd binder.
     * This goes through netd (runs as root) and is allowed from ADB shell uid.
     * Returns true on success.
     */
    boolean addInterfaceAddress(String ifName, String address, int prefixLength) = 7;

    /**
     * Remove an IP address from a network interface using INetd binder.
     */
    boolean removeInterfaceAddress(String ifName, String address, int prefixLength) = 8;

    /**
     * Run full Tesla network setup: try all methods to add IP alias.
     * Returns a diagnostic log string with results of each method tried.
     */
    String setupTeslaNetworking(String ifName, String virtualIp) = 9;

    /**
     * Restart WiFi tethering with a CGNAT IP (100.64.0.1/24) so Tesla browser
     * can reach the phone. Uses TetheringManager via reflection from Shizuku's
     * elevated process (uid 2000).
     * Returns a diagnostic log string.
     */
    String restartTetheringWithCgnat() = 10;

    /**
     * Launch an app on a specific virtual display.
     * Uses am start --display to place the app on the given display.
     */
    void launchAppOnDisplay(int displayId, String packageName) = 11;

    /**
     * Launch an app on a specific virtual display with string intent extras.
     * Uses am start --display to place the app on the given display.
     */
    void launchAppWithExtraOnDisplay(int displayId, String packageName, String extraKey, String extraValue) = 15;

    /**
     * Launch the home screen on a specific virtual display.
     * Uses am start with HOME category.
     */
    void launchHomeOnDisplay(int displayId) = 12;

    /**
     * Inject text into the focused field on the specified display.
     * ASCII: shell `input text`. Non-ASCII: clipboard + paste key.
     */
    void injectText(String text, int displayId) = 13;

    /**
     * Korean/CJK composition: delete N chars (backspace) + insert text via clipboard+paste.
     * Called on each compositionupdate from browser. Serialized on a single thread.
     */
    void injectComposingText(int backspaces, String text, int displayId) = 14;

    /**
     * Register a binder token to monitor the client's lifecycle.
     * If the client process dies, the service will clean up and exit.
     */
    void registerDeathToken(in IBinder token) = 16;

    /**
     * Force a virtual display to stay awake by injecting user activity
     * and waking up the display via PowerManager internal APIs.
     * Call this when the physical screen turns off.
     */
    void wakeUpDisplay(int displayId) = 17;

    /**
     * Keep a virtual display rendering without sending wake/input events that
     * can leak onto the physical display.
     */
    void keepVirtualDisplayAlive(int displayId) = 37;

    /**
     * Resize an existing virtual display without destroying it.
     * Activities receive a configuration change instead of being killed.
     */
    void resizeVirtualDisplay(int displayId, int width, int height, int densityDpi) = 18;

    /**
     * Turn the physical display panel on/off via SurfaceControl.setDisplayPowerMode().
     * When off, the device stays awake (CPU/GPU/compositor running) but the physical
     * screen panel is dark. VirtualDisplays keep rendering normally.
     * This is the scrcpy "screen off" approach.
     */
    void setPhysicalDisplayPower(boolean on) = 19;

    /**
     * Start capturing ALL system audio via REMOTE_SUBMIX (requires shell uid).
     * Returns a ParcelFileDescriptor that streams raw PCM Int16 LE, 48kHz stereo.
     * The caller reads from this pipe. Returns null on failure.
     */
    ParcelFileDescriptor startSystemAudioCapture(int sampleRate, int channels) = 20;

    /**
     * Stop the system audio capture started by startSystemAudioCapture().
     */
    void stopSystemAudioCapture() = 21;

    /**
     * Start WiFi tethering (hotspot) using ConnectivityManager/TetheringManager
     * reflection from the privileged process (shell uid has TETHER_PRIVILEGED).
     * Returns true if the request was submitted successfully.
     */
    boolean startWifiTethering() = 22;

    /**
     * Stop WiFi tethering (hotspot).
     * Returns true if the request was submitted successfully.
     */
    boolean stopWifiTethering() = 23;

    /**
     * Remove a task by its task ID using ActivityTaskManager.
     */
    void removeTask(int taskId) = 26;

    /**
     * Enable scrcpy-style stay-awake mode by setting stay_on_while_plugged_in.
     */
    void enableStayAwakeMode() = 27;

    /**
     * Restore the original stay-awake mode setting.
     */
    void restoreStayAwakeMode() = 28;

    List<String> getRunningTasksOnDisplay(int displayId) = 29;

    /**
     * Return all task IDs belonging to the specified package name.
     * Native ATM query (under 1ms).
     */
    int[] getTaskIdsForPackage(String packageName) = 30;

    /**
     * Return the display ID where the specified package's task currently resides, or -1 if not found.
     * Native ATM query (under 1ms).
     */
    int getDisplayIdForPackage(String packageName) = 31;

    /**
     * Launch an app on a specific virtual display natively, 
     * optionally preserving the warm start task stack.
     */
    void launchAppOnDisplayV2(int displayId, String packageName, boolean forceStop) = 32;

    /**
     * Move an active task to the target virtual display natively via IActivityTaskManager.
     */
    boolean moveTaskToDisplayNative(int taskId, int displayId) = 33;

    /** Bring an existing task to the front without starting its launcher activity. */
    boolean moveTaskToFrontNative(int taskId) = 38;

    /**
     * Inject a full MotionEvent on the specified display and return whether InputManager accepted it.
     */
    boolean injectMotionEventWithResult(int displayId, in MotionEvent event) = 34;

    /**
     * Return the top activity flattened short string for the specified display, or empty string if unavailable.
     */
    String getTopActivityForDisplay(int displayId) = 35;
    int getProcessPid() = 36;
}



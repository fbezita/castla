package com.castla.mirror.input

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Helper utility to diagnose and manage permissions/activation states for
 * CastlaImeService (virtual keyboard) and CastlaFocusAccessibilityService (focus tracking).
 */
object TextInputSettingsHelper {

    /**
     * Checks if the Castla IME is enabled in the system's enabled input methods list.
     */
    fun isImeEnabled(context: Context): Boolean {
        val targetImeName = "${context.packageName}/com.castla.mirror.input.CastlaImeService"
        val enabledInputMethods = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        ) ?: return false
        return enabledInputMethods.contains(targetImeName)
    }

    /**
     * Checks if the Castla IME is currently selected as the active input method.
     */
    fun isImeSelected(context: Context): Boolean {
        val defaultInputMethod = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: return false
        return defaultInputMethod.startsWith(context.packageName)
    }

    /**
     * Checks if the Castla Focus Accessibility Service is enabled in the system settings.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val targetAccessibilityName = "${context.packageName}/com.castla.mirror.input.CastlaFocusAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(targetAccessibilityName)
    }

    /**
     * Shows the input method selection picker dialog.
     */
    fun showInputMethodPicker(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    /**
     * Navigates to the system settings screen to manage and enable virtual keyboards.
     */
    fun navigateToEnableImeSettings(context: Context) {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general settings if input method settings are not accessible directly
            val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    /**
     * Navigates to the system settings screen to manage and enable accessibility services.
     */
    fun navigateToAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    private const val PREFS_NAME = "castla_ime_restore_prefs"
    private const val KEY_PREVIOUS_IME = "previous_ime_id"
    private const val KEY_RESTORE_PENDING = "restore_pending"

    /**
     * Programmatically saves the current active IME and switches the default IME to Castla IME.
     * Works silently via Shizuku shell commands.
     */
    fun saveCurrentImeAndSwitchToCastla(context: Context, execCommand: (String) -> String?) {
        val currentIme = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: ""
        val targetIme = "${context.packageName}/com.castla.mirror.input.CastlaImeService"

        // If Castla IME is already the default, do not overwrite the saved previous IME
        if (currentIme == targetIme) {
            return
        }

        // Save the previous IME ID to preferences and mark restore as pending
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PREVIOUS_IME, currentIme)
            .putBoolean(KEY_RESTORE_PENDING, true)
            .apply()

        // Enable Castla IME if not already enabled in system settings
        val enabledMethods = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        ) ?: ""
        if (!enabledMethods.contains(targetIme)) {
            val newEnabled = if (enabledMethods.isEmpty()) targetIme else "$enabledMethods:$targetIme"
            execCommand("settings put secure enabled_input_methods $newEnabled")
        }

        // Programmatically set Castla IME as the default active keyboard
        execCommand("settings put secure default_input_method $targetIme")
    }

    /**
     * Programmatically restores the user's previous IME.
     * Works silently via Shizuku shell commands.
     */
    fun restorePreviousIme(context: Context, execCommand: (String) -> String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previousIme = prefs.getString(KEY_PREVIOUS_IME, null)
        val restorePending = prefs.getBoolean(KEY_RESTORE_PENDING, false)

        if (restorePending && previousIme != null && previousIme.isNotEmpty()) {
            execCommand("settings put secure default_input_method $previousIme")
        }

        // Clear preference state
        prefs.edit()
            .remove(KEY_PREVIOUS_IME)
            .putBoolean(KEY_RESTORE_PENDING, false)
            .apply()
    }

    /**
     * Self-healing recovery: Checks if a pending restore exists (e.g. after a crash or abnormal app exit)
     * and programmatically restores the user's previous IME. Falls back to a safe system IME if missing.
     */
    fun restorePreviousImeIfPending(context: Context, execCommand: (String) -> String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val restorePending = prefs.getBoolean(KEY_RESTORE_PENDING, false)
        val targetIme = "${context.packageName}/com.castla.mirror.input.CastlaImeService"

        // Also check if Castla is currently set as the default IME, which implies we should restore even if preference was cleared
        val currentIme = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: ""

        if (restorePending || currentIme == targetIme) {
            var previousIme = prefs.getString(KEY_PREVIOUS_IME, null)

            // Dynamic fallback: If saved IME is lost, find a suitable enabled system keyboard as fallback
            if (previousIme == null || previousIme.isEmpty() || previousIme == targetIme) {
                val enabledMethods = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_INPUT_METHODS
                ) ?: ""
                
                // Parse enabled methods and pick the first non-Castla method
                previousIme = enabledMethods.split(":")
                    .firstOrNull { it.isNotEmpty() && !it.contains(context.packageName) }
            }

            if (previousIme != null && previousIme.isNotEmpty()) {
                execCommand("settings put secure default_input_method $previousIme")
            }

            // Always clear state after attempt
            prefs.edit()
                .remove(KEY_PREVIOUS_IME)
                .putBoolean(KEY_RESTORE_PENDING, false)
                .apply()
        }
    }

    /**
     * Programmatically enables the Castla Focus Accessibility Service silently.
     * Works silently via Shizuku shell commands.
     */
    fun enableAccessibilityServiceSilently(context: Context, execCommand: (String) -> String?) {
        val targetService = "${context.packageName}/com.castla.mirror.input.CastlaFocusAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val services = enabledServices.split(":")
            .filter { it.isNotBlank() && it != "null" }
            .toMutableSet()

        services.add(targetService)

        execCommand("settings put secure enabled_accessibility_services ${services.joinToString(":")}")
        execCommand("settings put secure accessibility_enabled 1")
    }

    /**
     * Programmatically disables the Castla Focus Accessibility Service silently while preserving others.
     * Works silently via Shizuku shell commands.
     */
    fun disableAccessibilityServiceSilently(context: Context, execCommand: (String) -> String?) {
        val targetService = "${context.packageName}/com.castla.mirror.input.CastlaFocusAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val services = enabledServices.split(":")
            .filter { it.isNotBlank() && it != "null" && it != targetService }

        execCommand("settings put secure enabled_accessibility_services ${services.joinToString(":")}")

        if (services.isEmpty()) {
            execCommand("settings put secure accessibility_enabled 0")
        }
    }
}

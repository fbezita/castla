package com.castla.mirror.service

import android.util.Log
import com.castla.mirror.diagnostics.FileLogger
import com.castla.mirror.shizuku.IPrivilegedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class DisplayRoutingDiagnostics(
    private val host: MirrorForegroundService,
    private val enabled: () -> Boolean,
    private val prefix: String,
) {
    companion object { private const val TAG = "MirrorForegroundService" }

    fun schedule(
        pane: String,
        service: IPrivilegedService?,
        targetPkg: String,
        targetDisplayId: Int,
        phase: String,
        launchMode: String,
        vdDisplayId: Int,
    ) {
        if (!enabled() || service == null || targetPkg.isBlank() || targetDisplayId < 0) return
        val summary = "phase=$phase pane=$pane targetPkg=$targetPkg targetDisplayId=$targetDisplayId launchMode=$launchMode vdDisplayId=$vdDisplayId"
        Log.i(TAG, "$prefix [IME_ROUTING] $summary")
        FileLogger.i("IME_ROUTING", "$prefix $summary")
        host.serviceScope.launch(Dispatchers.IO) {
            val delays = if (phase == "prelaunch") listOf(0L) else listOf(250L, 1000L)
            for (delayMs in delays) {
                if (delayMs > 0L) delay(delayMs)
                capture(pane, service, targetPkg, targetDisplayId, phase, launchMode, vdDisplayId, delayMs)
            }
        }
    }

    private suspend fun capture(
        pane: String,
        service: IPrivilegedService,
        targetPkg: String,
        targetDisplayId: Int,
        phase: String,
        launchMode: String,
        vdDisplayId: Int,
        delayMs: Long,
    ) {
        val appDisplayId = try { host.runBinderSafe { service.getDisplayIdForPackage(targetPkg) } ?: -1 } catch (_: Exception) { -1 }
        val imeDump = try { host.runBinderSafe(1500L) { service.execCommand("dumpsys input_method") } ?: "" } catch (_: Exception) { "" }
        val windowDump = try { host.runBinderSafe(1500L) { service.execCommand("dumpsys window displays") } ?: "" } catch (_: Exception) { "" }
        val imeDisplayId = extractImeDisplayId(imeDump, windowDump)
        val imeSummary = buildImeSummary(imeDump, windowDump)
        val localIme = appDisplayId != -1 && imeDisplayId == appDisplayId && imeDisplayId == vdDisplayId

        log("VD", "pane=$pane phase=$phase vdDisplayId=$vdDisplayId targetDisplayId=$targetDisplayId launchMode=$launchMode delayMs=$delayMs")
        log("APP_DISPLAY", "pane=$pane phase=$phase pkg=$targetPkg appDisplayId=$appDisplayId targetDisplayId=$targetDisplayId vdDisplayId=$vdDisplayId delayMs=$delayMs")
        log("IME_DISPLAY", "pane=$pane phase=$phase pkg=$targetPkg imeDisplayId=$imeDisplayId targetDisplayId=$targetDisplayId vdDisplayId=$vdDisplayId delayMs=$delayMs summary=$imeSummary")
        log("IME_ROUTING", "pane=$pane phase=$phase pkg=$targetPkg localIme=$localIme targetDisplayId=$targetDisplayId appDisplayId=$appDisplayId imeDisplayId=$imeDisplayId vdDisplayId=$vdDisplayId launchMode=$launchMode delayMs=$delayMs")
    }

    private fun log(channel: String, message: String) {
        Log.i(TAG, "$prefix [$channel] $message")
        FileLogger.i(channel, "$prefix $message")
    }

    private fun extractImeDisplayId(imeDump: String, windowDump: String): Int {
        listOf(
            Regex("""mCurTokenDisplayId\s*[=:]\s*(-?\d+)"""),
            Regex("""imeDisplayId\s*[=:]\s*(-?\d+)"""),
        ).forEach { pattern ->
            pattern.find(imeDump)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        val related = buildImeRelatedText(imeDump, windowDump)
        listOf(
            Regex("""mDisplayId\s*[=:]\s*(-?\d+)"""),
            Regex("""displayId\s*[=:]\s*(-?\d+)"""),
            Regex("""display\s*[=:]\s*(-?\d+)"""),
        ).forEach { pattern ->
            pattern.find(related)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return -1
    }

    private fun buildImeRelatedText(imeDump: String, windowDump: String): String {
        val keywords = listOf(
            "InputMethod", "input method", "IME", "ime", "mIme", "mInputMethod",
            "mCurTokenDisplayId", "imeDisplayId", "imeLayeringTarget", "mInputMethodTarget",
            "mCurFocusedWindow", "mServedView",
        )
        fun isImeLine(line: String) = keywords.any { line.contains(it, ignoreCase = true) }
        return buildString {
            imeDump.lineSequence().filter(::isImeLine).forEach { appendLine(it.trim()) }
            windowDump.lineSequence().filter(::isImeLine).forEach { appendLine(it.trim()) }
        }
    }

    private fun buildImeSummary(imeDump: String, windowDump: String): String =
        buildImeRelatedText(imeDump, windowDump)
            .lineSequence()
            .filter { line ->
                listOf(
                    "mCurMethodId", "mCurTokenDisplayId", "imeDisplayId", "mCurFocusedWindow",
                    "mServedView", "displayId", "mDisplayId", "InputMethod", "imeLayeringTarget",
                    "mInputMethodTarget",
                ).any { line.contains(it, true) }
            }
            .map { it.trim() }
            .distinct()
            .take(16)
            .joinToString(" | ")
            .take(1200)
}

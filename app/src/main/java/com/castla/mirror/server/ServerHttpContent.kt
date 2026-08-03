package com.castla.mirror.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.castla.mirror.ott.OttCatalog
import com.castla.mirror.utils.AppCategoryClassifier
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal class ServerHttpContent(private val context: Context) {
    fun serve(uri: String, parameters: Map<String, List<String>>): NanoHTTPD.Response {
        if (uri == "/api/apps") return serveAppList()
        if (uri.startsWith("/api/icon")) {
            parameters["pkg"]?.firstOrNull()?.let { return serveAppIcon(it) }
        }
        return serveAsset(uri)
    }

    private fun serveAppList(): NanoHTTPD.Response = try {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = JSONArray()
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach { info ->
            if (info.activityInfo.packageName == context.packageName) return@forEach
            val packageName = info.activityInfo.packageName
            val className = info.activityInfo.name
            val label = info.loadLabel(packageManager).toString()
            val ottTarget = OttCatalog.resolve(packageName)
            apps.put(JSONObject().apply {
                put("packageName", packageName)
                put("className", className)
                put("componentName", ComponentName(packageName, className).flattenToShortString())
                put("label", label)
                put("category", AppCategoryClassifier.classify(packageName, label))
                put("isWeb", ottTarget != null)
                put("webUrl", ottTarget?.webUrl ?: JSONObject.NULL)
                put("launchMode", if (ottTarget != null) "EXTERNAL_BROWSER_URL" else "STANDARD_APP")
            })
        }
        val payload = JSONObject().apply {
            put("isPremium", true)
            put("fitMode", "contain")
            put("autoFit", true)
            put("layoutMode", "single")
            put("apps", apps)
        }
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", payload.toString())
    } catch (e: Exception) {
        Log.e(TAG, "Failed to serve app list", e)
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", e.message)
    }

    private fun serveAppIcon(packageName: String): NanoHTTPD.Response = try {
        val icon = context.packageManager.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(
            icon.intrinsicWidth.coerceAtLeast(1),
            icon.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        icon.setBounds(0, 0, canvas.width, canvas.height)
        icon.draw(canvas)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "image/png",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
    } catch (_: Exception) {
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Icon not found")
    }

    private fun serveAsset(uri: String): NanoHTTPD.Response {
        val response = try {
            val path = uri.substringBefore('?').trimStart('/').ifEmpty { "index.html" }
            val mimeType = when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".ico") -> "image/x-icon"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                else -> "application/octet-stream"
            }
            NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeType, context.assets.open("web/$path"))
        } catch (_: Exception) {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        return response
    }

    companion object { private const val TAG = "MirrorServer" }
}

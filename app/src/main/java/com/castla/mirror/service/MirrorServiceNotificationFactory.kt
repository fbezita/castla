package com.castla.mirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.castla.mirror.MainActivity

internal object MirrorServiceNotificationFactory {
    fun createChannel(context: Context, channelId: String) {
        val channel = NotificationChannel(
            channelId,
            "Mirror Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun create(
        context: Context,
        channelId: String,
        stopAction: String,
        restoreImeAction: String,
    ): Notification {
        val openPending = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getBroadcast(
            context,
            1,
            Intent(stopAction).apply { setPackage(context.packageName) },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val restoreImePending = PendingIntent.getService(
            context,
            2,
            Intent(context, MirrorForegroundService::class.java).apply { action = restoreImeAction },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channelId)
            .setContentTitle("Castla")
            .setContentText("Streaming to Tesla")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop Mirroring", stopPending)
            .addAction(android.R.drawable.ic_menu_edit, "Restore Keyboard", restoreImePending)
            .build()
    }
}

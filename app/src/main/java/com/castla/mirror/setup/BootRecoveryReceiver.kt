package com.castla.mirror.setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.castla.mirror.MainActivity
import com.castla.mirror.R
import rikka.shizuku.Shizuku

class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val setupCompleted = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETUP_COMPLETED, false)
        val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!BootRecoveryNotificationPolicy.shouldNotify(setupCompleted, shizukuRunning)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.shizuku_recovery_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val openCastla = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.shizuku_recovery_title))
            .setContentText(context.getString(R.string.shizuku_recovery_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.shizuku_recovery_text)))
            .setContentIntent(openCastla)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val PREFS_NAME = "castla_setup_state"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val CHANNEL_ID = "castla_shizuku_recovery"
        private const val NOTIFICATION_ID = 2002

        fun markSetupCompleted(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SETUP_COMPLETED, true)
                .apply()
        }
    }
}

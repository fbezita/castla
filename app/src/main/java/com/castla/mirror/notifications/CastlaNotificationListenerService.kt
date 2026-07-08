package com.castla.mirror.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.castla.mirror.service.MirrorForegroundService
import org.json.JSONObject

class CastlaNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val server = MirrorForegroundService.instance?.getMirrorServer() ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)

        val payload = FrontendNotificationFormatter.build(
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            isOngoing = sbn.isOngoing,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            postedAtMs = sbn.postTime,
            notificationKey = sbn.key ?: sbn.packageName,
        ) ?: return

        server.broadcastControlMessage(
            JSONObject().apply {
                put("type", "notification")
                put("id", payload.id)
                put("packageName", payload.packageName)
                put("appLabel", payload.appLabel)
                put("title", payload.title)
                put("text", payload.text)
                put("postedAtMs", payload.postedAtMs)
            }.toString()
        )
    }
}

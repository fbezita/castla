package com.castla.mirror.notifications

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.castla.mirror.service.MirrorForegroundService
import org.json.JSONObject

class CastlaNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "CastlaNotification"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener_connected package=$packageName")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "listener_disconnected package=$packageName")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val server = MirrorForegroundService.instance?.getMirrorServer()
        if (server == null) {
            Log.w(TAG, "notification_dropped reason=mirror_server_unavailable pkg=${sbn.packageName} key=${sbn.key}")
            return
        }
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val messages = messagingBundles(extras)
            ?.let { bundles -> Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles) }
            .orEmpty()
        val explicitConversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        val fallbackTitle = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
        val conversationTitle = FrontendNotificationFormatter.resolveConversationTitle(
            explicitConversationTitle = explicitConversationTitle,
            subText = subText,
            fallbackTitle = fallbackTitle,
            hasMessagingMessages = messages.isNotEmpty(),
        )
        val latestMessage = messages.lastOrNull()
        val text = FrontendNotificationFormatter.selectText(
            messagingText = latestMessage?.text,
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            text = extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        val sender = senderName(latestMessage)
            ?: fallbackTitle?.takeIf { fallback ->
                conversationTitle != null && fallback.toString() != conversationTitle.toString()
            }

        val payload = FrontendNotificationFormatter.build(
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = conversationTitle,
            text = text,
            sender = sender,
            isOngoing = sbn.isOngoing,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            postedAtMs = sbn.postTime,
            notificationKey = sbn.key ?: sbn.packageName,
            hasImage = hasImage(extras),
        )
        if (payload == null) {
            Log.i(
                TAG,
                "notification_filtered pkg=${sbn.packageName} key=${sbn.key} " +
                    "ongoing=${sbn.isOngoing} summary=${notification.flags and Notification.FLAG_GROUP_SUMMARY != 0} " +
                    "hasText=${!text.isNullOrBlank()} hasImage=${hasImage(extras)}",
            )
            return
        }

        val controlClients = server.controlSocketCount()
        server.broadcastControlMessage(
            JSONObject().apply {
                put("type", "notification")
                put("id", payload.id)
                put("packageName", payload.packageName)
                put("appLabel", payload.appLabel)
                put("title", payload.title)
                put("text", payload.text)
                payload.sender?.let { put("sender", it) }
                put("postedAtMs", payload.postedAtMs)
                put("hasImage", payload.hasImage)
            }.toString()
        )
        Log.i(
            TAG,
            "notification_forwarded pkg=${payload.packageName} key=${payload.id} controlClients=$controlClients",
        )
    }

    @Suppress("DEPRECATION")
    private fun messagingBundles(extras: Bundle): Array<out Parcelable>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }

    @Suppress("DEPRECATION")
    private fun senderName(message: Notification.MessagingStyle.Message?): CharSequence? {
        if (message == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            message.senderPerson?.name ?: message.sender
        } else {
            message.sender
        }
    }

    private fun hasImage(extras: Bundle): Boolean {
        if (extras.containsKey(Notification.EXTRA_PICTURE)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            extras.containsKey(Notification.EXTRA_PICTURE_ICON)
        ) {
            return true
        }

        val messageBundles = messagingBundles(extras) ?: return false
        return Notification.MessagingStyle.Message
            .getMessagesFromBundleArray(messageBundles)
            .any { message -> message.dataMimeType?.startsWith("image/", ignoreCase = true) == true }
    }
}

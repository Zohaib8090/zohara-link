package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.model.MirroredNotification

class EcosystemNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null || sbn.packageName == packageName) return // Ignore own notifications

        try {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: ""

            if (title.isBlank() && text.isBlank()) return

            val pm = packageManager
            val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
            val appName = pm.getApplicationLabel(appInfo).toString()

            // Check if notification has a quick reply action
            var hasReply = false
            var replyActionKey: String? = null

            val actions = sbn.notification.actions
            if (actions != null) {
                for (action in actions) {
                    if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                        hasReply = true
                        val key = "${sbn.key}_action"
                        replyActionMap[key] = ActionReplyHolder(action, action.remoteInputs[0])
                        replyActionKey = key
                        break
                    }
                }
            }

            val mirrored = MirroredNotification(
                id = sbn.key,
                appName = appName,
                packageName = sbn.packageName,
                title = title,
                text = text,
                timestamp = sbn.postTime,
                hasReply = hasReply,
                replyKey = replyActionKey
            )

            onNotificationReceivedCallback?.invoke(mirrored)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        replyActionMap.remove("${sbn.key}_action")
    }

    companion object {
        private const val TAG = "EcosystemNotifListener"

        data class ActionReplyHolder(
            val action: Notification.Action,
            val remoteInput: RemoteInput
        )

        private val replyActionMap = mutableMapOf<String, ActionReplyHolder>()
        var onNotificationReceivedCallback: ((MirroredNotification) -> Unit)? = null

        fun sendRemoteReply(context: android.content.Context, replyKey: String, replyText: String): Boolean {
            val holder = replyActionMap[replyKey] ?: return false
            try {
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(holder.remoteInput.resultKey, replyText)
                RemoteInput.addResultsToIntent(arrayOf(holder.remoteInput), intent, bundle)
                holder.action.actionIntent.send(context, 0, intent)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send remote reply: ${e.message}", e)
                return false
            }
        }
    }
}

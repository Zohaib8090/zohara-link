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
        // The person can switch mirroring off inside the app (Access > Phone alerts).
        val prefs = getSharedPreferences("zohara_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("mirror_alerts", true)) return
        if (shouldSkip(sbn, prefs)) return

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

    /**
     * Not everything an app posts is a message. A health tracker's step counter, a music player, a download or navigation keep
     * one notification on screen and re-post it every few seconds; mirroring each re-post made the same pop-up appear on the
     * computer again and again. So: ongoing, foreground-service, progress, media and group-summary notifications are skipped,
     * an identical re-post of the same notification is skipped, and apps the person switched off are skipped. The apps seen so
     * far are remembered so the person can switch them off in the app (Access > Choose which apps).
     */
    private fun shouldSkip(sbn: StatusBarNotification, prefs: android.content.SharedPreferences): Boolean {
        val n = sbn.notification
        val skipFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY
        if (n.flags and skipFlags != 0) return true
        if (n.category == Notification.CATEGORY_PROGRESS || n.category == Notification.CATEGORY_TRANSPORT ||
            n.category == Notification.CATEGORY_SERVICE || n.category == Notification.CATEGORY_SYSTEM
        ) return true
        if (n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return true

        rememberApp(sbn.packageName, prefs)
        if (prefs.getStringSet("muted_packages", emptySet())?.contains(sbn.packageName) == true) return true

        val signature = (n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "") + "\u0001" +
            (n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
        synchronized(lastSignature) {
            if (lastSignature[sbn.key] == signature) return true
            if (lastSignature.size > 300) lastSignature.clear()
            lastSignature[sbn.key] = signature
        }
        return false
    }

    private fun rememberApp(pkg: String, prefs: android.content.SharedPreferences) {
        val known = prefs.getStringSet("seen_apps", emptySet()) ?: emptySet()
        if (known.any { it.startsWith("$pkg\t") }) return
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        prefs.edit().putStringSet("seen_apps", known + "$pkg\t$label").apply()
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
        private val lastSignature = HashMap<String, String>()
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

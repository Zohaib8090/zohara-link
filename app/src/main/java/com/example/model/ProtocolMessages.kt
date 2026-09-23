package com.example.model

import org.json.JSONObject

/**
 * Protocol message data classes and JSON serialization helpers
 * for Zohara Link TLS/TCP socket synchronization.
 */
object ProtocolTypes {
    const val PAIR_REQUEST = "PAIR_REQUEST"
    const val PAIR_CHALLENGE = "PAIR_CHALLENGE"
    const val PAIR_VERIFY = "PAIR_VERIFY"
    const val PAIR_CONFIRMED = "PAIR_CONFIRMED"
    const val CLIPBOARD_SYNC = "CLIPBOARD_SYNC"
    const val FILE_OFFER = "FILE_OFFER"
    const val FILE_ACCEPT = "FILE_ACCEPT"
    const val FILE_CHUNK = "FILE_CHUNK"
    const val FILE_COMPLETE = "FILE_COMPLETE"
    const val FILE_CANCEL = "FILE_CANCEL"
    const val NOTIFICATION_POST = "NOTIFICATION_POST"
    const val NOTIFICATION_DISMISS = "NOTIFICATION_DISMISS"
    const val NOTIFICATION_REPLY = "NOTIFICATION_REPLY"
    const val TELEMETRY_STATUS = "TELEMETRY_STATUS"
    const val MEDIA_CONTROL = "MEDIA_CONTROL"
    const val VOLUME_CONTROL = "VOLUME_CONTROL"
    const val INPUT_EVENT = "INPUT_EVENT"
    const val PROXIMITY_HEARTBEAT = "PROXIMITY_HEARTBEAT"
}

data class DiscoveredDevice(
    val serviceName: String,
    val hostAddress: String,
    val port: Int,
    val isPaired: Boolean = false,
    val lastDiscoveredAt: Long = System.currentTimeMillis()
)

data class TelemetryData(
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val batteryHealth: String = "GOOD",
    val wifiSsid: String = "Not Connected",
    val wifiRssi: Int = -50,
    val networkType: String = "WIFI",
    val deviceModel: String = android.os.Build.MODEL
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", ProtocolTypes.TELEMETRY_STATUS)
        put("batteryLevel", batteryLevel)
        put("isCharging", isCharging)
        put("batteryHealth", batteryHealth)
        put("wifiSsid", wifiSsid)
        put("wifiRssi", wifiRssi)
        put("networkType", networkType)
        put("deviceModel", deviceModel)
    }
}

data class MediaControlState(
    val title: String = "No media playing",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val volume: Int = 70
)

data class MirroredNotification(
    val id: String,
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val hasReply: Boolean = false,
    val replyKey: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", ProtocolTypes.NOTIFICATION_POST)
        put("notifId", id)
        put("appName", appName)
        put("packageName", packageName)
        put("title", title)
        put("text", text)
        put("postTime", timestamp)
        put("hasReply", hasReply)
        if (replyKey != null) put("replyActionKey", replyKey)
    }
}

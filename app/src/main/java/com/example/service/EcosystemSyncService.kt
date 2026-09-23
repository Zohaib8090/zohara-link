package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.model.TelemetryData
import com.example.network.ConnectionState
import com.example.network.TlsSocketEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EcosystemSyncService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var telemetryJob: Job? = null
    private var clipboardManager: ClipboardManager? = null
    private var lastSyncedClipboardText = ""

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val batteryPercent = if (level >= 0 && scale > 0) (level * 100) / scale else 100
                currentBatteryLevel = batteryPercent
                currentIsCharging = isCharging
            }
        }
    }

    private var currentBatteryLevel = 100
    private var currentIsCharging = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Ready to synchronize with Arch Linux"))

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        setupClipboardListener()
        startTelemetryLoop()

        // Link notification mirroring
        EcosystemNotificationListener.onNotificationReceivedCallback = { notif ->
            socketEngineInstance?.sendNotification(notif)
        }
    }

    private fun setupClipboardListener() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                if (text.isNotBlank() && text != lastSyncedClipboardText) {
                    lastSyncedClipboardText = text
                    socketEngineInstance?.sendClipboard(text)
                }
            }
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch {
            while (isActive) {
                val engine = socketEngineInstance
                if (engine != null && engine.connectionState.value is ConnectionState.Connected) {
                    val telemetry = gatherTelemetry()
                    engine.sendTelemetry(telemetry)
                }
                delay(10000) // Send telemetry every 10 seconds
            }
        }
    }

    private fun gatherTelemetry(): TelemetryData {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo: WifiInfo? = wifiManager?.connectionInfo
        val ssid = wifiInfo?.ssid?.replace("\"", "") ?: "Wi-Fi"
        val rssi = wifiInfo?.rssi ?: -50

        return TelemetryData(
            batteryLevel = currentBatteryLevel,
            isCharging = currentIsCharging,
            wifiSsid = if (ssid == "<unknown ssid>") "Connected" else ssid,
            wifiRssi = rssi,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zohara Link Active Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps persistent TLS connection with Arch Linux companion daemon"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zohara Link Ecosystem")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        telemetryJob?.cancel()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "zohara_link_foreground_channel"
        const val NOTIFICATION_ID = 4242

        var socketEngineInstance: TlsSocketEngine? = null

        fun start(context: Context) {
            val intent = Intent(context, EcosystemSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

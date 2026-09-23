package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val hostAddress: String,
    val port: Int,
    val isPaired: Boolean = true,
    val clientCertThumbprint: String = "",
    val pairedTimestamp: Long = System.currentTimeMillis(),
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val autoConnect: Boolean = true
)

@Entity(tableName = "file_transfers")
data class TransferRecordEntity(
    @PrimaryKey val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val bytesTransferred: Long,
    val isUpload: Boolean, // true: Android -> Linux, false: Linux -> Android
    val status: String, // PENDING, TRANSFERRING, COMPLETED, FAILED, CANCELLED
    val timestamp: Long = System.currentTimeMillis(),
    val localUri: String = "",
    val mimeType: String = "application/octet-stream"
)

@Entity(tableName = "notification_records")
data class NotificationRecordEntity(
    @PrimaryKey val id: String,
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMirrored: Boolean = true
)

@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false
)

@Entity(tableName = "clipboard_records")
data class ClipboardRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val source: String, // "Android" or "Linux"
    val timestamp: Long = System.currentTimeMillis()
)

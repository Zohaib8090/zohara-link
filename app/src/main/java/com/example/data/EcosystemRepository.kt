package com.example.data

import com.example.model.ClipboardRecordEntity
import com.example.model.NotificationRecordEntity
import com.example.model.PairedDeviceEntity
import com.example.model.SyncLogEntity
import com.example.model.TransferRecordEntity
import kotlinx.coroutines.flow.Flow

class EcosystemRepository(private val db: AppDatabase) {
    val pairedDevices: Flow<List<PairedDeviceEntity>> = db.pairedDeviceDao().getAllPairedDevices()
    val transfers: Flow<List<TransferRecordEntity>> = db.transferRecordDao().getAllTransfers()
    val notifications: Flow<List<NotificationRecordEntity>> = db.notificationRecordDao().getAllNotifications()
    val clipboardHistory: Flow<List<ClipboardRecordEntity>> = db.clipboardRecordDao().getClipboardHistory()
    val logs: Flow<List<SyncLogEntity>> = db.syncLogDao().getAllLogs()

    suspend fun saveDevice(device: PairedDeviceEntity) {
        db.pairedDeviceDao().insertOrUpdateDevice(device)
    }

    suspend fun removeDevice(deviceId: String) {
        db.pairedDeviceDao().deleteDevice(deviceId)
    }

    suspend fun updateDeviceLastSeen(deviceId: String) {
        db.pairedDeviceDao().updateLastSeen(deviceId, System.currentTimeMillis())
    }

    suspend fun recordTransfer(transfer: TransferRecordEntity) {
        db.transferRecordDao().insertOrUpdateTransfer(transfer)
    }

    suspend fun updateTransferProgress(transferId: String, bytes: Long, status: String) {
        db.transferRecordDao().updateProgress(transferId, bytes, status)
    }

    suspend fun recordNotification(notification: NotificationRecordEntity) {
        db.notificationRecordDao().insertNotification(notification)
    }

    suspend fun recordClipboard(text: String, source: String) {
        db.clipboardRecordDao().insertClipboard(
            ClipboardRecordEntity(text = text, source = source)
        )
    }

    suspend fun clearClipboardHistory() {
        db.clipboardRecordDao().clearClipboardHistory()
    }

    suspend fun log(tag: String, message: String, isError: Boolean = false) {
        db.syncLogDao().insertLog(
            SyncLogEntity(tag = tag, message = message, isError = isError)
        )
    }

    suspend fun clearLogs() {
        db.syncLogDao().clearLogs()
    }
}

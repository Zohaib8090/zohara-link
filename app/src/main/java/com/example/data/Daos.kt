package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.ClipboardRecordEntity
import com.example.model.NotificationRecordEntity
import com.example.model.PairedDeviceEntity
import com.example.model.SyncLogEntity
import com.example.model.TransferRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices ORDER BY lastSeenTimestamp DESC")
    fun getAllPairedDevices(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDeviceById(deviceId: String): PairedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDevice(device: PairedDeviceEntity)

    @Query("DELETE FROM paired_devices WHERE deviceId = :deviceId")
    suspend fun deleteDevice(deviceId: String)

    @Query("UPDATE paired_devices SET lastSeenTimestamp = :timestamp WHERE deviceId = :deviceId")
    suspend fun updateLastSeen(deviceId: String, timestamp: Long)
}

@Dao
interface TransferRecordDao {
    @Query("SELECT * FROM file_transfers ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<TransferRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTransfer(transfer: TransferRecordEntity)

    @Query("UPDATE file_transfers SET bytesTransferred = :bytes, status = :status WHERE transferId = :transferId")
    suspend fun updateProgress(transferId: String, bytes: Long, status: String)

    @Query("DELETE FROM file_transfers WHERE transferId = :transferId")
    suspend fun deleteTransfer(transferId: String)

    @Query("DELETE FROM file_transfers")
    suspend fun clearAllTransfers()
}

@Dao
interface NotificationRecordDao {
    @Query("SELECT * FROM notification_records ORDER BY timestamp DESC LIMIT 50")
    fun getAllNotifications(): Flow<List<NotificationRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationRecordEntity)

    @Query("DELETE FROM notification_records WHERE id = :id")
    suspend fun deleteNotification(id: String)

    @Query("DELETE FROM notification_records")
    suspend fun clearNotifications()
}

@Dao
interface ClipboardRecordDao {
    @Query("SELECT * FROM clipboard_records ORDER BY timestamp DESC LIMIT 30")
    fun getClipboardHistory(): Flow<List<ClipboardRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClipboard(clip: ClipboardRecordEntity)

    @Query("DELETE FROM clipboard_records")
    suspend fun clearClipboardHistory()
}

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT 100")
    fun getAllLogs(): Flow<List<SyncLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SyncLogEntity)

    @Query("DELETE FROM sync_logs")
    suspend fun clearLogs()
}

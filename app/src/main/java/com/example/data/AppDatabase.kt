package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.model.ClipboardRecordEntity
import com.example.model.NotificationRecordEntity
import com.example.model.PairedDeviceEntity
import com.example.model.SyncLogEntity
import com.example.model.TransferRecordEntity

@Database(
    entities = [
        PairedDeviceEntity::class,
        TransferRecordEntity::class,
        NotificationRecordEntity::class,
        ClipboardRecordEntity::class,
        SyncLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun transferRecordDao(): TransferRecordDao
    abstract fun notificationRecordDao(): NotificationRecordDao
    abstract fun clipboardRecordDao(): ClipboardRecordDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "zohara_link.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

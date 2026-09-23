package com.example.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.EcosystemRepository
import com.example.model.ClipboardRecordEntity
import com.example.model.DiscoveredDevice
import com.example.model.MediaControlState
import com.example.model.NotificationRecordEntity
import com.example.model.PairedDeviceEntity
import com.example.model.TelemetryData
import com.example.model.TransferRecordEntity
import com.example.network.ConnectionState
import com.example.network.NsdDiscoveryManager
import com.example.network.TlsSocketEngine
import com.example.service.EcosystemNotificationListener
import com.example.service.EcosystemSyncService
import android.content.SharedPreferences
import com.example.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface UpdateStatus {
    object Idle : UpdateStatus
    object Checking : UpdateStatus
    data class UpToDate(val currentVersion: String, val checkedAt: Long = System.currentTimeMillis()) : UpdateStatus
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseTitle: String,
        val releaseNotes: String,
        val releaseUrl: String,
        val isCritical: Boolean = false,
        val releaseDate: String = "August 2026"
    ) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}

class EcosystemViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs: SharedPreferences = application.getSharedPreferences("zohara_settings", Context.MODE_PRIVATE)

    private val repository: EcosystemRepository
    private val nsdManager: NsdDiscoveryManager
    private val socketEngine: TlsSocketEngine

    val pairedDevices: StateFlow<List<PairedDeviceEntity>>
    val transfers: StateFlow<List<TransferRecordEntity>>
    val notifications: StateFlow<List<NotificationRecordEntity>>
    val clipboardHistory: StateFlow<List<ClipboardRecordEntity>>

    val connectionState: StateFlow<ConnectionState>
    val latencyMs: StateFlow<Long>
    val isScanning: StateFlow<Boolean>
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    val mediaState: StateFlow<MediaControlState>

    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()

    private val _currentClipboard = MutableStateFlow("")
    val currentClipboard: StateFlow<String> = _currentClipboard.asStateFlow()

    // Onboarding & Demo Mode State
    private val _isOnboardingCompleted = MutableStateFlow(prefs.getBoolean("onboarding_completed", false))
    val isOnboardingCompleted: StateFlow<Boolean> = _isOnboardingCompleted.asStateFlow()

    private val _isDemoMode = MutableStateFlow(prefs.getBoolean("demo_mode_active", false))
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    // Settings & Theme Preferences
    private val _isDarkTheme = MutableStateFlow(prefs.getBoolean("dark_theme_enabled", false))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _isAutoTheme = MutableStateFlow(prefs.getBoolean("auto_theme_enabled", true))
    val isAutoTheme: StateFlow<Boolean> = _isAutoTheme.asStateFlow()

    // Update Scanner State
    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    private val _githubReleaseUrl = MutableStateFlow(
        prefs.getString("github_release_url", "https://github.com/zohara-os/zohara-link/releases")
            ?: "https://github.com/zohara-os/zohara-link/releases"
    )
    val githubReleaseUrl: StateFlow<String> = _githubReleaseUrl.asStateFlow()

    init {
        val db = AppDatabase.getInstance(application)
        repository = EcosystemRepository(db)
        nsdManager = NsdDiscoveryManager(application)
        socketEngine = TlsSocketEngine(application)
        EcosystemSyncService.socketEngineInstance = socketEngine

        pairedDevices = repository.pairedDevices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        transfers = repository.transfers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        notifications = repository.notifications.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        clipboardHistory = repository.clipboardHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        connectionState = socketEngine.connectionState
        latencyMs = socketEngine.latencyMs
        isScanning = nsdManager.isScanning
        discoveredDevices = nsdManager.discoveredDevices
        mediaState = socketEngine.mediaState

        // Observe incoming clipboard from Linux daemon
        viewModelScope.launch {
            socketEngine.incomingClipboard.collect { text ->
                _currentClipboard.value = text
                repository.recordClipboard(text, "Linux")
                copyToLocalClipboard(text)
            }
        }

        // Save paired devices into database when successfully connected
        viewModelScope.launch {
            socketEngine.connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    val entity = PairedDeviceEntity(
                        deviceId = "dev_${state.host}_${state.port}",
                        deviceName = state.serverName,
                        hostAddress = state.host,
                        port = state.port,
                        isPaired = true,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    repository.saveDevice(entity)
                    repository.log("PAIRING", "Connected to ${state.serverName} at ${state.host}:${state.port}")
                }
            }
        }

        // Auto-launch demo mode if previously active
        if (_isDemoMode.value) {
            launchDemoMode(saveState = false)
        }

        // Read initial clipboard
        readLocalClipboard()
    }

    fun connectToZoharaOS(name: String, host: String, port: Int = 42424) {
        val trimmedHost = host.trim()
        val trimmedName = if (name.isBlank()) "Zohara Arch OS ($trimmedHost)" else name.trim()
        val id = "dev_${trimmedHost}_${port}"
        val entity = PairedDeviceEntity(
            deviceId = id,
            deviceName = trimmedName,
            hostAddress = trimmedHost,
            port = port,
            isPaired = false,
            pairedTimestamp = System.currentTimeMillis(),
            lastSeenTimestamp = System.currentTimeMillis(),
            autoConnect = true
        )
        viewModelScope.launch {
            repository.saveDevice(entity)
            repository.log("ZOHARA_CONNECT", "Connecting to Arch Linux node: $trimmedName ($trimmedHost:$port)")
            _isDemoMode.value = false
            _isOnboardingCompleted.value = true
            prefs.edit()
                .putBoolean("demo_mode_active", false)
                .putBoolean("onboarding_completed", true)
                .apply()
            socketEngine.disconnect()
            delay(150)
            socketEngine.connect(trimmedHost, port, isAlreadyPaired = false)
        }
    }

    fun launchDemoMode(saveState: Boolean = true) {
        _isDemoMode.value = true
        _isOnboardingCompleted.value = true
        if (saveState) {
            prefs.edit()
                .putBoolean("demo_mode_active", true)
                .putBoolean("onboarding_completed", true)
                .apply()
        }
        viewModelScope.launch {
            socketEngine.connect("127.0.0.1", 42424, isAlreadyPaired = true)
            // Seed sample clipboard history if empty
            if (clipboardHistory.value.isEmpty()) {
                repository.recordClipboard("git clone https://aur.archlinux.org/zohara-link.git", "Zohara OS")
                repository.recordClipboard("sudo pacman -Syu zohara-daemon wayland-protocols", "Zohara OS")
            }
            // Seed sample notification if empty
            if (notifications.value.isEmpty()) {
                repository.recordNotification(
                    NotificationRecordEntity(
                        id = "demo_pkg_update",
                        appName = "Pacman (Arch)",
                        packageName = "org.archlinux.pacman",
                        title = "System Update Completed",
                        text = "14 packages updated cleanly via libalpm / pacman.",
                        timestamp = System.currentTimeMillis(),
                        isMirrored = true
                    )
                )
            }
        }
    }

    fun exitDemoMode() {
        _isDemoMode.value = false
        prefs.edit().putBoolean("demo_mode_active", false).apply()
        disconnect()
    }

    fun resetToWelcomeScreen() {
        _isOnboardingCompleted.value = false
        _isDemoMode.value = false
        prefs.edit()
            .putBoolean("onboarding_completed", false)
            .putBoolean("demo_mode_active", false)
            .apply()
        disconnect()
    }

    fun startDiscovery() {
        nsdManager.startDiscovery()
    }

    fun stopDiscovery() {
        nsdManager.stopDiscovery()
    }

    fun connectToHost(host: String, port: Int = 42424, isPaired: Boolean = false) {
        socketEngine.connect(host, port, isPaired)
    }

    fun confirmPairing(pin: String) {
        socketEngine.confirmPairing(pin)
    }

    fun disconnect() {
        socketEngine.disconnect()
    }

    fun unpairDevice(deviceId: String) {
        viewModelScope.launch {
            repository.removeDevice(deviceId)
        }
    }

    fun addManualDevice(
        name: String,
        host: String,
        port: Int = 42424,
        autoConnect: Boolean = true,
        connectImmediately: Boolean = false
    ) {
        val trimmedHost = host.trim()
        val trimmedName = if (name.isBlank()) "Linux Node ($trimmedHost)" else name.trim()
        val id = "dev_${trimmedHost}_${port}"
        val entity = PairedDeviceEntity(
            deviceId = id,
            deviceName = trimmedName,
            hostAddress = trimmedHost,
            port = port,
            isPaired = true,
            pairedTimestamp = System.currentTimeMillis(),
            lastSeenTimestamp = System.currentTimeMillis(),
            autoConnect = autoConnect
        )
        viewModelScope.launch {
            repository.saveDevice(entity)
            repository.log("DEVICE_MGMT", "Registered new ecosystem node: $trimmedName ($trimmedHost:$port)")
            if (connectImmediately) {
                switchActiveDevice(entity)
            }
        }
    }

    fun switchActiveDevice(device: PairedDeviceEntity) {
        viewModelScope.launch {
            repository.updateDeviceLastSeen(device.deviceId)
            val current = connectionState.value
            if (current is ConnectionState.Connected && current.host == device.hostAddress && current.port == device.port) {
                return@launch
            }
            socketEngine.disconnect()
            delay(150)
            socketEngine.connect(device.hostAddress, device.port, isAlreadyPaired = device.isPaired)
            repository.log("DEVICE_MGMT", "Switched active link to ${device.deviceName} (${device.hostAddress}:${device.port})")
        }
    }

    fun toggleDeviceConnection(device: PairedDeviceEntity) {
        val current = connectionState.value
        if (current is ConnectionState.Connected && current.host == device.hostAddress && current.port == device.port) {
            disconnect()
            viewModelScope.launch {
                repository.log("DEVICE_MGMT", "Disconnected link with ${device.deviceName}")
            }
        } else {
            switchActiveDevice(device)
        }
    }

    fun toggleAutoConnect(deviceId: String, enabled: Boolean) {
        viewModelScope.launch {
            val dev = pairedDevices.value.find { it.deviceId == deviceId }
            if (dev != null) {
                val updated = dev.copy(autoConnect = enabled)
                repository.saveDevice(updated)
            }
        }
    }

    fun updateDevice(deviceId: String, newName: String, newHost: String, newPort: Int) {
        viewModelScope.launch {
            val dev = pairedDevices.value.find { it.deviceId == deviceId }
            if (dev != null) {
                val updated = dev.copy(
                    deviceName = newName.trim(),
                    hostAddress = newHost.trim(),
                    port = newPort
                )
                repository.saveDevice(updated)
                repository.log("DEVICE_MGMT", "Updated node settings for ${updated.deviceName}")
            }
        }
    }

    fun sendClipboard(text: String) {
        if (text.isBlank()) return
        socketEngine.sendClipboard(text)
        _currentClipboard.value = text
        viewModelScope.launch {
            repository.recordClipboard(text, "Android")
        }
        copyToLocalClipboard(text)
    }

    fun readLocalClipboard() {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotBlank()) {
                _currentClipboard.value = text
            }
        }
    }

    private fun copyToLocalClipboard(text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipData = ClipData.newPlainText("Zohara Shared Clipboard", text)
        cm?.setPrimaryClip(clipData)
    }

    fun sendFile(uri: Uri) {
        val context = getApplication<Application>()
        var fileName = "file_${System.currentTimeMillis()}"
        var fileSize = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx != -1) fileName = cursor.getString(nameIdx)
                if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
            }
        }

        val transferId = UUID.randomUUID().toString()
        val record = TransferRecordEntity(
            transferId = transferId,
            fileName = fileName,
            fileSize = fileSize,
            bytesTransferred = 0,
            isUpload = true,
            status = "TRANSFERRING",
            localUri = uri.toString()
        )

        viewModelScope.launch {
            repository.recordTransfer(record)
            socketEngine.sendFileStream(
                uri = uri,
                fileName = fileName,
                fileSize = fileSize,
                onProgress = { progress ->
                    viewModelScope.launch {
                        val bytes = (fileSize * (progress / 100.0)).toLong()
                        repository.updateTransferProgress(transferId, bytes, if (progress >= 100) "COMPLETED" else "TRANSFERRING")
                    }
                },
                onComplete = { success ->
                    viewModelScope.launch {
                        repository.updateTransferProgress(transferId, fileSize, if (success) "COMPLETED" else "FAILED")
                    }
                }
            )
        }
    }

    fun sendMediaAction(action: String) {
        socketEngine.sendMediaAction(action)
    }

    fun sendVolume(volume: Int) {
        socketEngine.sendVolume(volume)
    }

    fun sendInputMove(dx: Float, dy: Float) {
        socketEngine.sendInputMove(dx, dy)
    }

    fun sendInputClick(button: String = "LEFT") {
        socketEngine.sendInputClick(button)
    }

    fun sendLockDesktop() {
        socketEngine.sendLockDesktop()
    }

    fun replyToNotification(replyKey: String, text: String) {
        val success = EcosystemNotificationListener.sendRemoteReply(getApplication(), replyKey, text)
        if (success) {
            viewModelScope.launch {
                repository.log("NOTIFICATION_REPLY", "Sent reply: $text")
            }
        }
    }

    fun clearTransfers() {
        viewModelScope.launch {
            val db = AppDatabase.getInstance(getApplication())
            db.transferRecordDao().clearAllTransfers()
        }
    }

    fun clearClipboardHistory() {
        viewModelScope.launch {
            repository.clearClipboardHistory()
        }
    }

    fun setDarkTheme(enabled: Boolean) {
        _isDarkTheme.value = enabled
        _isAutoTheme.value = false
        prefs.edit()
            .putBoolean("dark_theme_enabled", enabled)
            .putBoolean("auto_theme_enabled", false)
            .apply()
    }

    fun setAutoTheme(enabled: Boolean) {
        _isAutoTheme.value = enabled
        prefs.edit()
            .putBoolean("auto_theme_enabled", enabled)
            .apply()
    }

    fun setCustomReleaseUrl(url: String) {
        val trimmed = url.trim()
        _githubReleaseUrl.value = trimmed
        prefs.edit().putString("github_release_url", trimmed).apply()
    }

    fun checkForUpdates(simulateNewVersion: Boolean = false) {
        viewModelScope.launch {
            _updateStatus.value = UpdateStatus.Checking
            delay(1500) // Realistic check delay

            val currentVer = getAppVersionName()
            if (simulateNewVersion) {
                _updateStatus.value = UpdateStatus.UpdateAvailable(
                    currentVersion = currentVer,
                    latestVersion = "v1.3.0-stable",
                    releaseTitle = "Zohara Mesh v1.3.0: WebRTC Multi-Node & Fast Sync",
                    releaseNotes = "• Low-latency WebRTC DataChannel file pipeline\n• Zero-trust cryptographic mTLS challenge\n• Improved Wayland/X11 clipboard sync engine\n• Memory & battery optimization for background sync",
                    releaseUrl = _githubReleaseUrl.value,
                    isCritical = false,
                    releaseDate = "August 2026"
                )
            } else {
                _updateStatus.value = UpdateStatus.UpToDate(
                    currentVersion = currentVer,
                    checkedAt = System.currentTimeMillis()
                )
            }
        }
    }

    fun resetUpdateStatus() {
        _updateStatus.value = UpdateStatus.Idle
    }

    fun getAppVersionName(): String {
        return try {
            val pInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
            pInfo.versionName ?: BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            "v1.2.0-stable"
        }
    }

    fun getAppVersionCode(): Long {
        return try {
            val pInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            1L
        }
    }

    override fun onCleared() {
        super.onCleared()
        nsdManager.stopDiscovery()
    }
}

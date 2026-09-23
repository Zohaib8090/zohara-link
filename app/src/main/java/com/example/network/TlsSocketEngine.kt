package com.example.network

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.model.MediaControlState
import com.example.model.MirroredNotification
import com.example.model.ProtocolTypes
import com.example.model.TelemetryData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class PairingRequired(val pinSas: String, val host: String) : ConnectionState()
    data class Connected(val host: String, val port: Int, val serverName: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class TlsSocketEngine(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingClipboard = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val incomingClipboard: SharedFlow<String> = _incomingClipboard.asSharedFlow()

    private val _mediaState = MutableStateFlow(MediaControlState())
    val mediaState: StateFlow<MediaControlState> = _mediaState.asStateFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val _activeTransfers = MutableStateFlow<Map<String, Int>>(emptyMap())
    val activeTransfers: StateFlow<Map<String, Int>> = _activeTransfers.asStateFlow()

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null

    private var currentHost: String = ""
    private var currentPort: Int = 42424
    private val deviceId = "android_${Build.MANUFACTURER}_${Build.MODEL}_${Build.ID}".replace(" ", "_")

    fun connect(host: String, port: Int, isAlreadyPaired: Boolean = false) {
        disconnect()
        currentHost = host
        currentPort = port

        connectionJob = scope.launch {
            _connectionState.value = ConnectionState.Connecting

            // Check if user is connecting to a simulated/demo workstation
            if (host.contains("demo", ignoreCase = true) || host == "127.0.0.1" || host == "localhost") {
                Log.d(TAG, "Starting virtual Linux daemon session for $host:$port")
                delay(300)
                _connectionState.value = ConnectionState.Connected(host, port, "Arch Linux (Workstation)")
                _latencyMs.value = 12L
                _mediaState.value = _mediaState.value.copy(
                    title = "Synthwave Symphony",
                    artist = "Master Boot Record",
                    isPlaying = true,
                    volume = 72
                )
                startHeartbeat()
                return@launch
            }

            try {
                Log.d(TAG, "Attempting TLS connection to $host:$port...")
                val sslContext = createUnsafeSslContext()
                val sslFactory: SSLSocketFactory = sslContext.socketFactory

                val rawSocket = Socket()
                rawSocket.connect(InetSocketAddress(host, port), 4000)

                val sslSocket = sslFactory.createSocket(
                    rawSocket,
                    host,
                    port,
                    true
                ) as SSLSocket
                sslSocket.startHandshake()

                socket = sslSocket
                reader = BufferedReader(InputStreamReader(sslSocket.getInputStream()))
                writer = PrintWriter(sslSocket.getOutputStream(), true)

                Log.d(TAG, "TLS Socket handshake completed with $host:$port")

                if (isAlreadyPaired) {
                    _connectionState.value = ConnectionState.Connected(host, port, host)
                    startHeartbeat()
                } else {
                    // Send pairing request
                    sendPairRequest()
                }

                listenLoop()
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "Connection timed out to $host:$port: Node unreachable or firewall blocking port")
                _connectionState.value = ConnectionState.Error("Node unreachable at $host:$port (Timed out)")
                disconnect()
            } catch (e: java.net.ConnectException) {
                Log.w(TAG, "Connection refused at $host:$port: Is zohara-daemon running on Linux?")
                _connectionState.value = ConnectionState.Error("Connection refused by $host:$port")
                disconnect()
            } catch (e: java.net.NoRouteToHostException) {
                Log.w(TAG, "No route to host $host: Ensure device is on the same local Wi-Fi subnet")
                _connectionState.value = ConnectionState.Error("No route to $host (Check Wi-Fi)")
                disconnect()
            } catch (e: java.net.UnknownHostException) {
                Log.w(TAG, "Unknown host: $host")
                _connectionState.value = ConnectionState.Error("Unknown host $host")
                disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Connection to $host:$port failed: ${e.message}")
                _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
                disconnect()
            }
        }
    }

    private fun sendPairRequest() {
        val req = JSONObject().apply {
            put("type", ProtocolTypes.PAIR_REQUEST)
            put("deviceId", deviceId)
            put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("clientThumbprint", "android_self_signed")
            put("protocolVersion", 1)
        }
        sendJson(req)
    }

    fun confirmPairing(pin: String) {
        scope.launch {
            val verify = JSONObject().apply {
                put("type", ProtocolTypes.PAIR_VERIFY)
                put("deviceId", deviceId)
                put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("pinSas", pin)
                put("approved", true)
            }
            sendJson(verify)
        }
    }

    private suspend fun listenLoop() = withContext(Dispatchers.IO) {
        val r = reader ?: return@withContext
        try {
            while (isActive) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    val json = JSONObject(line)
                    handleIncomingPacket(json)
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing incoming packet: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Socket read closed: ${e.message}")
        } finally {
            if (_connectionState.value is ConnectionState.Connected) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    private fun handleIncomingPacket(json: JSONObject) {
        when (json.optString("type")) {
            ProtocolTypes.PAIR_CHALLENGE -> {
                val pin = json.optString("pinSas", "123456")
                val serverName = json.optString("serverName", currentHost)
                _connectionState.value = ConnectionState.PairingRequired(pin, serverName)
            }
            ProtocolTypes.PAIR_CONFIRMED -> {
                val status = json.optString("status")
                val serverName = json.optString("serverName", currentHost)
                if (status == "SUCCESS") {
                    _connectionState.value = ConnectionState.Connected(currentHost, currentPort, serverName)
                    startHeartbeat()
                } else {
                    _connectionState.value = ConnectionState.Error("Pairing rejected by host")
                }
            }
            ProtocolTypes.CLIPBOARD_SYNC -> {
                val text = json.optString("text")
                if (text.isNotEmpty()) {
                    scope.launch { _incomingClipboard.emit(text) }
                }
            }
            ProtocolTypes.MEDIA_CONTROL -> {
                val title = json.optString("title", "Unknown Track")
                val artist = json.optString("artist", "")
                val isPlaying = json.optBoolean("isPlaying", false)
                _mediaState.value = _mediaState.value.copy(
                    title = title,
                    artist = artist,
                    isPlaying = isPlaying
                )
            }
            ProtocolTypes.PROXIMITY_HEARTBEAT -> {
                val sentTs = json.optLong("clientTimestamp", 0L)
                if (sentTs > 0) {
                    _latencyMs.value = System.currentTimeMillis() - sentTs
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                val hb = JSONObject().apply {
                    put("type", ProtocolTypes.PROXIMITY_HEARTBEAT)
                    put("clientTimestamp", System.currentTimeMillis())
                    put("deviceId", deviceId)
                }
                sendJson(hb)
                delay(5000)
            }
        }
    }

    fun sendClipboard(text: String) {
        val msg = JSONObject().apply {
            put("type", ProtocolTypes.CLIPBOARD_SYNC)
            put("text", text)
            put("source", "android")
            put("timestamp", System.currentTimeMillis())
        }
        sendJson(msg)
    }

    fun sendNotification(notif: MirroredNotification) {
        sendJson(notif.toJson())
    }

    fun sendTelemetry(telemetry: TelemetryData) {
        sendJson(telemetry.toJson())
    }

    fun sendMediaAction(action: String) {
        val msg = JSONObject().apply {
            put("type", ProtocolTypes.MEDIA_CONTROL)
            put("action", action)
        }
        sendJson(msg)
    }

    fun sendVolume(volumePercent: Int) {
        val msg = JSONObject().apply {
            put("type", ProtocolTypes.VOLUME_CONTROL)
            put("volumePercent", volumePercent)
        }
        sendJson(msg)
    }

    fun sendInputMove(dx: Float, dy: Float) {
        val msg = JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "MOVE")
            put("dx", dx)
            put("dy", dy)
        }
        sendJson(msg)
    }

    fun sendInputClick(button: String = "LEFT") {
        val msg = JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "CLICK")
            put("button", button)
        }
        sendJson(msg)
    }

    fun sendLockDesktop() {
        val msg = JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "LOCK_SCREEN")
        }
        sendJson(msg)
    }

    fun sendFileStream(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        scope.launch {
            val transferId = UUID.randomUUID().toString()
            try {
                // 1. Offer file
                val offer = JSONObject().apply {
                    put("type", ProtocolTypes.FILE_OFFER)
                    put("transferId", transferId)
                    put("fileName", fileName)
                    put("fileSize", fileSize)
                }
                sendJson(offer)

                // 2. Read chunks and stream
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    onComplete(false)
                    return@launch
                }

                val buffer = ByteArray(32 * 1024) // 32KB chunks
                var bytesRead: Int
                var totalSent = 0L

                inputStream.use { stream ->
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        val chunkBytes = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                        val b64 = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)

                        val chunkMsg = JSONObject().apply {
                            put("type", ProtocolTypes.FILE_CHUNK)
                            put("transferId", transferId)
                            put("payloadBase64", b64)
                        }
                        sendJson(chunkMsg)
                        totalSent += bytesRead

                        val progress = ((totalSent.toDouble() / fileSize.coerceAtLeast(1)) * 100).toInt()
                        onProgress(progress)
                        delay(5) // Avoid overflowing socket buffer
                    }
                }

                // 3. File complete
                val completeMsg = JSONObject().apply {
                    put("type", ProtocolTypes.FILE_COMPLETE)
                    put("transferId", transferId)
                    put("status", "SUCCESS")
                }
                sendJson(completeMsg)
                onProgress(100)
                onComplete(true)
            } catch (e: Exception) {
                Log.e(TAG, "File transfer error: ${e.message}", e)
                onComplete(false)
            }
        }
    }

    private fun sendJson(json: JSONObject) {
        scope.launch {
            try {
                val w = writer ?: return@launch
                val line = json.toString()
                w.println(line)
            } catch (e: Exception) {
                Log.e(TAG, "Error writing socket: ${e.message}")
            }
        }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        connectionJob?.cancel()
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.d(TAG, "Disconnect cleanup note: ${e.message}")
        }
        socket = null
        reader = null
        writer = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun createUnsafeSslContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext
    }

    companion object {
        private const val TAG = "TlsSocketEngine"
    }
}

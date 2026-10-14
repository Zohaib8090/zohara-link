package com.example.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.model.MediaControlState
import com.example.model.MirroredNotification
import com.example.model.ProtocolTypes
import com.example.model.TelemetryData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
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
    data class PairingRequired(val fingerprint: String, val host: String) : ConnectionState()
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

    /** Messages from the computer the person should see, e.g. "remote input is not allowed for this phone". */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val _activeTransfers = MutableStateFlow<Map<String, Int>>(emptyMap())
    val activeTransfers: StateFlow<Map<String, Int>> = _activeTransfers.asStateFlow()

    /** The computer's screen, newest picture (null when not watching). Shown by the remote desktop screen. */
    /** The live game stream's signalling from the computer (offer, candidates, state, errors); see GameStreamClient. */
    private val _streamMessages = kotlinx.coroutines.flow.MutableSharedFlow<JSONObject>(extraBufferCapacity = 128)
    val streamMessages: kotlinx.coroutines.flow.SharedFlow<JSONObject> = _streamMessages

    /** Sends a message as it is (the stream's answer and candidates). */
    fun sendRaw(o: JSONObject) = sendJson(o)

    private val _screenFrame = MutableStateFlow<Bitmap?>(null)
    val screenFrame: StateFlow<Bitmap?> = _screenFrame.asStateFlow()

    /** Why the screen is not showing, in the computer's words (null while pictures arrive). Shown on the remote desktop screen. */
    private val _screenProblem = MutableStateFlow<String?>(null)
    val screenProblem: StateFlow<String?> = _screenProblem.asStateFlow()

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null

    // Certificate pinning. The computer's TLS certificate is self-signed, so instead of trusting any certificate (as this
    // app used to) we remember the SHA-256 fingerprint of the one we paired with, together with the reconnect token that
    // computer gave us, and refuse every other certificate on later connections. While pairing, the fingerprint is shown
    // next to the PIN so the person can check it matches the one on the computer's screen.
    private val pinPrefs by lazy { context.getSharedPreferences("zohara_link_pins", Context.MODE_PRIVATE) }
    @Volatile private var observedFingerprint: String = ""
    @Volatile private var requirePin = false

    private var currentHost: String = ""
    private var currentPort: Int = 42424
    private val deviceId = "android_${Build.MANUFACTURER}_${Build.MODEL}_${Build.ID}".replace(" ", "_")

    // Other ways to reach the same computer (Tailscale and the like), learned from its ADDRESSES packet. When a paired computer
    // cannot be reached at the saved address (the phone is away from home Wi-Fi), they are tried one after the other. The
    // certificate is still pinned, so an address that leads to another machine is refused like any other.
    private val overlayPrefs by lazy { context.getSharedPreferences("zohara_link_overlays", Context.MODE_PRIVATE) }
    private val fallbackQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun overlayAddresses(): List<String> =
        overlayPrefs.getString("last", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    private fun saveOverlayAddresses(json: JSONObject) {
        val list = json.optJSONArray("overlays") ?: return
        val addresses = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("address") }.filter { it.isNotBlank() }
        overlayPrefs.edit().putString("last", addresses.joinToString(",")).apply()
    }

    private fun failOrFallback(isAlreadyPaired: Boolean, message: String) {
        val next = if (isAlreadyPaired) fallbackQueue.poll() else null
        if (next != null) {
            Log.d(TAG, "Trying another route to the computer: $next")
            connectInternal(next, currentPort, true)
        } else {
            _connectionState.value = ConnectionState.Error(message)
            disconnect()
        }
    }

    fun connect(host: String, port: Int, isAlreadyPaired: Boolean = false) {
        fallbackQueue.clear()
        if (isAlreadyPaired) overlayAddresses().filter { it != host }.forEach { fallbackQueue.add(it) }
        connectInternal(host, port, isAlreadyPaired)
    }

    private fun connectInternal(host: String, port: Int, isAlreadyPaired: Boolean) {
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
                requirePin = isAlreadyPaired
                observedFingerprint = ""
                val sslContext = createPinningSslContext()
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
                    // The handshake only succeeded because the certificate is a pinned one; prove who we are with its token.
                    val token = pinPrefs.getString(observedFingerprint, null)
                    if (token.isNullOrEmpty()) {
                        disconnect()
                        _connectionState.value = ConnectionState.Error("Pair with this computer again")
                        return@launch
                    }
                    sendJson(JSONObject().apply {
                        put("type", ProtocolTypes.AUTH)
                        put("deviceId", deviceId)
                        put("token", token)
                    })
                } else {
                    // Send pairing request
                    sendPairRequest()
                }

                listenLoop()
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "Connection timed out to $host:$port: Node unreachable or firewall blocking port")
                failOrFallback(isAlreadyPaired, "Node unreachable at $host:$port (Timed out)")
            } catch (e: java.net.ConnectException) {
                Log.w(TAG, "Connection refused at $host:$port: Is zohara-daemon running on Linux?")
                failOrFallback(isAlreadyPaired, "Connection refused by $host:$port")
            } catch (e: java.net.NoRouteToHostException) {
                Log.w(TAG, "No route to host $host: Ensure device is on the same local Wi-Fi subnet")
                failOrFallback(isAlreadyPaired, "No route to $host (Check Wi-Fi)")
            } catch (e: java.net.UnknownHostException) {
                Log.w(TAG, "Unknown host: $host")
                failOrFallback(isAlreadyPaired, "Unknown host $host")
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
                val serverName = json.optString("serverName", currentHost)
                // The certificate the computer says it has must be the one this connection actually presented; if not,
                // someone in the middle is relaying the pairing.
                val claimed = json.optString("certFingerprint")
                if (claimed.isEmpty() || claimed != observedFingerprint) {
                    disconnect()
                    _connectionState.value = ConnectionState.Error(
                        "The computer's certificate does not match. Update Zohara Link on the computer, or do not pair on this network."
                    )
                    return
                }
                _connectionState.value = ConnectionState.PairingRequired(shortFingerprint(observedFingerprint), serverName)
            }
            ProtocolTypes.FILE_ACCEPT -> {
                val accepted = json.optBoolean("accepted", true)
                val reason = if (accepted) "" else json.optString("error", "refused")
                pendingAccepts[json.optString("transferId")]?.complete(reason)
            }
            "ERROR" -> {
                val why = json.optString("error")
                if (why.startsWith("viewing the screen")) _screenProblem.value = "The computer has not allowed this phone to see its screen. Turn on \"Show the screen\" for this phone in Settings > Zohara Link on the computer."
                if (why.isNotEmpty()) _notices.tryEmit(why)
            }
            ProtocolTypes.AUTH_RESULT -> {
                if (json.optString("status") == "SUCCESS") {
                    val serverName = json.optString("serverName", currentHost)
                    _connectionState.value = ConnectionState.Connected(currentHost, currentPort, serverName)
                    startHeartbeat()
                } else {
                    // The computer has forgotten this phone (it was unpaired there): the old pin is useless now.
                    pinPrefs.edit().remove(observedFingerprint).apply()
                    disconnect()
                    _connectionState.value = ConnectionState.Error("This computer no longer knows this phone. Pair again.")
                }
            }
            ProtocolTypes.PAIR_CONFIRMED -> {
                val status = json.optString("status")
                val serverName = json.optString("serverName", currentHost)
                if (status == "SUCCESS") {
                    val token = json.optString("sessionToken")
                    if (token.isNotEmpty() && observedFingerprint.isNotEmpty()) {
                        pinPrefs.edit().putString(observedFingerprint, token).apply()
                    }
                    _connectionState.value = ConnectionState.Connected(currentHost, currentPort, serverName)
                    startHeartbeat()
                } else {
                    val why = json.optString("error", "Pairing rejected by host")
                    _connectionState.value = ConnectionState.Error(why)
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
            ProtocolTypes.ADDRESSES -> saveOverlayAddresses(json)
            ProtocolTypes.SCREEN_FRAME -> {
                val bytes = Base64.decode(json.optString("jpegBase64"), Base64.DEFAULT)
                _screenFrame.value = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: _screenFrame.value
                _screenProblem.value = null
            }
            ProtocolTypes.STREAM_OFFER, ProtocolTypes.STREAM_ICE, ProtocolTypes.STREAM_STATE, ProtocolTypes.STREAM_ERROR -> {
                _streamMessages.tryEmit(json)
            }
            ProtocolTypes.SCREEN_STOPPED -> {
                _screenFrame.value = null
                _screenProblem.value = json.optString("reason", "the computer ended it")
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

    // Finger movements arrive 100 times a second or more; sending each one made the pointer lag behind the finger. They are added
    // up here and sent about 60 times a second as one move.
    // The computer answers each file offer with yes or a reason for no; "" means yes.
    private val pendingAccepts = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val moveLock = Any()
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var moveJob: Job? = null

    fun sendInputMove(dx: Float, dy: Float) {
        synchronized(moveLock) {
            pendingDx += dx
            pendingDy += dy
        }
        if (moveJob?.isActive == true) return
        moveJob = scope.launch {
            while (isActive) {
                // the computer writes a move in microseconds now, so moves can go out about 120 times a second
                delay(8)
                val (x, y) = synchronized(moveLock) {
                    val taken = pendingDx to pendingDy
                    pendingDx = 0f
                    pendingDy = 0f
                    taken
                }
                if (x == 0f && y == 0f) break
                sendJson(JSONObject().apply {
                    put("type", ProtocolTypes.INPUT_EVENT)
                    put("action", "MOVE")
                    put("dx", x.toDouble())
                    put("dy", y.toDouble())
                })
            }
        }
    }

    /** Holds a mouse button down or lets it go (dragging). */
    fun sendInputButton(button: String, down: Boolean) {
        sendJson(JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "BUTTON")
            put("button", button)
            put("down", down)
        })
    }

    /** Smooth scrolling in lines (fractions allowed): positive scrolls down and right. */
    fun sendInputScrollSmooth(dx: Float, dy: Float) {
        sendJson(JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "SCROLL")
            put("dx", dx.toDouble())
            put("dy", dy.toDouble())
        })
    }

    /** A key held down or let go, by its Linux key code. */
    fun sendInputKeyCode(code: Int, down: Boolean) {
        sendJson(JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", if (down) "KEY_DOWN" else "KEY_UP")
            put("code", code)
        })
    }

    /** The phone as a game controller: its whole state. Sticks -1..1 (up is negative), triggers 0..1, buttons as a bit mask. */
    fun sendGamepad(buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, hatX: Int, hatY: Int) {
        sendJson(JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "GAMEPAD")
            put("buttons", buttons)
            put("lx", lx.toDouble())
            put("ly", ly.toDouble())
            put("rx", rx.toDouble())
            put("ry", ry.toDouble())
            put("lt", lt.toDouble())
            put("rt", rt.toDouble())
            put("hatX", hatX)
            put("hatY", hatY)
        })
    }

    fun sendGamepadOff() {
        sendJson(JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "GAMEPAD_OFF")
        })
    }

    fun sendInputClick(button: String = "LEFT") {
        val msg = JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "CLICK")
            put("button", button)
        }
        sendJson(msg)
    }

    // Remote desktop: watch the screen (the computer must have allowed it for this phone), and point, scroll and type on it.
    fun startScreen(maxWidth: Int = 1024, fps: Int = 2) {
        _screenProblem.value = null
        sendJson(JSONObject().apply {
            put("type", ProtocolTypes.SCREEN_START)
            put("maxWidth", maxWidth)
            put("fps", fps)
        })
    }

    fun stopScreen() {
        sendJson(JSONObject().apply { put("type", ProtocolTypes.SCREEN_STOP) })
        _screenFrame.value = null
    }

    /** [x] and [y] are fractions (0 to 1) of the picture's width and height. */
    fun sendInputMoveTo(x: Float, y: Float) {
        sendJson(JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "MOVE_TO")
            put("x", x.toDouble())
            put("y", y.toDouble())
        })
    }

    fun sendInputScroll(lines: Int) {
        sendJson(JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "SCROLL")
            put("lines", lines)
        })
    }

    fun sendInputType(text: String) {
        sendJson(JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "TYPE")
            put("text", text)
        })
    }

    fun sendInputKey(key: String) {
        sendJson(JSONObject().apply {
            put("type", ProtocolTypes.INPUT_EVENT)
            put("action", "KEY")
            put("key", key)
        })
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
                val answer = CompletableDeferred<String>()
                pendingAccepts[transferId] = answer
                sendJson(offer)

                // The computer says yes or why not (too large, too many at once). Without waiting, a refused file was streamed into
                // nothing and the transfer looked stuck.
                val verdict = withTimeoutOrNull(8000) { answer.await() }
                pendingAccepts.remove(transferId)
                if (verdict == null) {
                    _notices.tryEmit("The computer did not answer. Is Zohara Link turned on there?")
                    onComplete(false)
                    return@launch
                }
                if (verdict.isNotEmpty()) {
                    _notices.tryEmit("The computer did not accept $fileName: $verdict")
                    onComplete(false)
                    return@launch
                }

                // 2. Read chunks and stream
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    onComplete(false)
                    return@launch
                }

                val buffer = ByteArray(32 * 1024) // 32KB chunks
                var bytesRead: Int
                var totalSent = 0L
                var lastPercent = -1

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

                        // Unknown size (0): no percentage to show. Report only when the percentage changes, not for every 32 KB.
                        val progress = if (fileSize > 0) ((totalSent.toDouble() / fileSize) * 100).toInt().coerceAtMost(99) else 0
                        if (progress != lastPercent) {
                            lastPercent = progress
                            onProgress(progress)
                        }
                        delay(2) // Avoid overflowing socket buffer
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

    // One writer thread: packets leave in the order they were queued. Before, every packet was its own coroutine on a pool of
    // threads, so a click could overtake the moves before it and a stream of moves arrived jumbled.
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    private fun sendJson(json: JSONObject) {
        scope.launch(sendDispatcher) {
            try {
                val w = writer ?: return@launch
                val line = json.toString()
                w.println(line)
            } catch (e: Exception) {
                Log.e(TAG, "Error writing socket: ${e.message}")
            }
        }
    }

    /** The short code of the computer this phone is (or was last) connected to; the same one its Settings page shows. */
    fun computerCode(): String = if (observedFingerprint.isEmpty()) "" else shortFingerprint(observedFingerprint)

    /** Forget the computer: drop its pinned certificate and token and disconnect. The phone must pair again to use it. */
    fun forgetCurrentComputer() {
        val fp = observedFingerprint
        if (fp.isNotEmpty()) pinPrefs.edit().remove(fp).apply()
        disconnect()
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
        _screenFrame.value = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun createPinningSslContext(): SSLContext {
        val pinning = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("The computer sent no certificate")
                val fp = sha256Hex(leaf.encoded)
                observedFingerprint = fp
                if (requirePin && !pinPrefs.contains(fp)) {
                    throw CertificateException("This is not the computer you paired with (its certificate changed)")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, pinning, SecureRandom())
        return sslContext
    }

    companion object {
        private const val TAG = "TlsSocketEngine"

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** First 16 hex digits in groups of four: the same short form the computer shows in its pairing notification. */
        fun shortFingerprint(full: String): String = full.take(16).chunked(4).joinToString(" ")
    }
}

package com.example.network

import android.content.Context
import com.example.model.ProtocolTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.PlatformSoftwareVideoDecoderFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

/**
 * The phone's end of the live game stream (see zohara-link/docs/STREAMING.md).
 *
 * The computer makes the offer; this answers it. Video arrives as an H.264 track that Android decodes with the phone's hardware
 * decoder and draws on a SurfaceViewRenderer. The computer also opens a data channel named "input": messages sent on it are the
 * same INPUT_EVENT bodies the touchpad uses, so a move or a controller state reaches the computer without going through the
 * slower encrypted message connection. The signalling (offer, answer, candidates) travels over the paired connection.
 */
class GameStreamClient(context: Context, private val sendSignal: (JSONObject) -> Unit) {
    val eglBase: EglBase = EglBase.create()

    private val _video = MutableStateFlow<VideoTrack?>(null)
    val video: StateFlow<VideoTrack?> = _video.asStateFlow()

    /** idle, connecting, streaming, closed */
    private val _state = MutableStateFlow("idle")
    val state: StateFlow<String> = _state.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    private var mids: List<String> = emptyList()

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DecoderFactoryWithSoftwareFallback(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    /** Asks the computer to start streaming. [bitrateKbps] 4000 to 20000 is a good range on Wi-Fi. */
    fun start(width: Int = 1280, height: Int = 720, fps: Int = 60, bitrateKbps: Int = 8000) {
        stop(notifyComputer = false)
        _problem.value = null
        _state.value = "connecting"
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // the computer and the phone are on the same Wi-Fi or on Tailscale: direct paths are found without a relay
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        pc = factory.createPeerConnection(config, observer)
        sendSignal(JSONObject().apply {
            put("type", ProtocolTypes.STREAM_START)
            put("width", width)
            put("height", height)
            put("fps", fps)
            put("bitrate", bitrateKbps)
        })
    }

    fun stop(notifyComputer: Boolean = true) {
        if (notifyComputer && _state.value != "idle") sendSignal(JSONObject().apply { put("type", ProtocolTypes.STREAM_STOP) })
        channel?.close()
        channel = null
        pc?.close()
        pc = null
        _video.value = null
        _state.value = "idle"
    }

    /** True when the data channel is open: input can go the fast way. */
    fun inputOpen(): Boolean = channel?.state() == DataChannel.State.OPEN

    /** Sends an INPUT_EVENT body on the data channel. False: not open, the caller uses the normal connection. */
    fun sendInput(body: JSONObject): Boolean {
        val c = channel ?: return false
        if (c.state() != DataChannel.State.OPEN) return false
        return c.send(DataChannel.Buffer(ByteBuffer.wrap(body.toString().toByteArray(Charsets.UTF_8)), false))
    }

    /** A message from the computer (STREAM_OFFER, STREAM_ICE, STREAM_STATE, STREAM_ERROR). */
    fun onSignal(json: JSONObject) {
        when (json.optString("type")) {
            ProtocolTypes.STREAM_OFFER -> answer(json.optString("sdp"))
            ProtocolTypes.STREAM_ICE -> {
                val mline = json.optInt("mline", 0)
                val mid = mids.getOrNull(mline) ?: mline.toString()
                pc?.addIceCandidate(IceCandidate(mid, mline, json.optString("candidate")))
            }
            ProtocolTypes.STREAM_STATE -> if (json.optString("state") == "closed" && _state.value != "idle") {
                _state.value = "closed"
                _video.value = null
            }
            ProtocolTypes.STREAM_ERROR -> {
                _problem.value = json.optString("error", "The stream could not start.")
                _state.value = "closed"
                _video.value = null
            }
        }
    }

    private fun answer(offerSdp: String) {
        val connection = pc ?: return
        // candidates name the media section by position; libwebrtc wants its id, which the offer lists in the same order
        mids = Regex("(?m)^a=mid:(\\S+)").findAll(offerSdp).map { it.groupValues[1] }.toList()
        connection.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() {
                connection.createAnswer(object : SimpleSdp() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        connection.setLocalDescription(object : SimpleSdp() {
                            override fun onSetSuccess() {
                                sendSignal(JSONObject().apply {
                                    put("type", ProtocolTypes.STREAM_ANSWER)
                                    put("sdp", desc.description)
                                })
                            }
                        }, desc)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> _state.value = "streaming"
                PeerConnection.IceConnectionState.FAILED -> {
                    _problem.value = "The phone could not reach the computer for the stream."
                    _state.value = "closed"
                }
                PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.CLOSED -> if (_state.value == "streaming") _state.value = "closed"
                else -> {}
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate) {
            sendSignal(JSONObject().apply {
                put("type", ProtocolTypes.STREAM_ICE)
                put("mline", c.sdpMLineIndex)
                put("candidate", c.sdp)
            })
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel) {
            channel = dc
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            (receiver?.track() as? VideoTrack)?.let {
                it.setEnabled(true)
                _video.value = it
            }
        }
    }

    /** An SdpObserver that only cares about the steps it overrides. */
    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    fun release() {
        stop()
        factory.dispose()
        eglBase.release()
    }
}

/**
 * Android's own decoders first (the phone's hardware H.264), and Android's software H.264 when there is no hardware one.
 * libwebrtc leaves software H.264 out by default, so a device without a hardware decoder (the Waydroid test phone, some
 * tablets) answered "no video codec in common" and the stream showed nothing.
 */
private class DecoderFactoryWithSoftwareFallback(context: EglBase.Context) : VideoDecoderFactory {
    private val main = DefaultVideoDecoderFactory(context)
    private val platformSoftware = PlatformSoftwareVideoDecoderFactory(context)

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? = main.createDecoder(info) ?: platformSoftware.createDecoder(info)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val all = main.supportedCodecs.toMutableList()
        for (c in platformSoftware.supportedCodecs) {
            if (all.none { it.name.equals(c.name, ignoreCase = true) && it.params == c.params }) all.add(c)
        }
        return all.toTypedArray()
    }
}

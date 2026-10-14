package com.example.ui.zl

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.network.GameStreamClient
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * The computer's screen as live video, full screen, with the game controller on top (or a touchpad). Needs the computer to allow
 * "Show the screen" and "Control the mouse" for this phone, and Game streaming to be set up there (Settings > Zohara Link).
 *
 * Quality: [bitrateKbps] and [fps] are what the phone asks for; the computer keeps them inside safe bounds.
 */
@Composable
fun GameStreamScreen(
    client: GameStreamClient,
    computerName: String,
    connected: Boolean,
    fps: Int,
    bitrateKbps: Int,
    onGamepadState: (buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, hatX: Int, hatY: Int) -> Unit,
    onGamepadOff: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onClick: (String) -> Unit,
    onButton: (String, Boolean) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val video by client.video.collectAsState()
    val state by client.state.collectAsState()
    val problem by client.problem.collectAsState()
    var showPad by remember { mutableStateOf(true) }
    var showTouchpad by remember { mutableStateOf(false) }

    // sideways, no bars; both undone when leaving
    DisposableEffect(Unit) {
        val activity = context.activity()
        val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = before
        }
    }
    // start while connected; stop (and remove the controller on the computer) when leaving
    DisposableEffect(connected) {
        if (connected) client.start(1280, 720, fps, bitrateKbps)
        onDispose {
            client.stop()
            onGamepadOff()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val track = video
        if (track != null) {
            VideoSurface(client, track, Modifier.fillMaxSize())
        } else {
            Text(
                when {
                    !connected -> "Not connected to the computer."
                    problem != null -> problem ?: ""
                    state == "closed" -> "The stream ended."
                    else -> "Starting the stream…\nThe computer must allow \"Show the screen\" for this phone, and Game streaming must be set up in Settings > Zohara Link."
                },
                color = Zl.muted, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

        if (showTouchpad) {
            Touchpad(Modifier.fillMaxSize(), onMove = onMove, onClick = onClick, onButton = onButton, onScroll = onScroll)
        }
        if (showPad) {
            GamepadControls(Modifier, computerName, connected, onGamepadState, onClose = { showPad = false })
        }

        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!showPad) SecondaryButton("Controller") { showPad = true; showTouchpad = false }
            SecondaryButton(if (showTouchpad) "Hide touchpad" else "Touchpad") { showTouchpad = !showTouchpad; if (showTouchpad) showPad = false }
            SecondaryButton("Exit") { onClose() }
        }
    }
}

/** The video, drawn by the phone's hardware. */
@Composable
private fun VideoSurface(client: GameStreamClient, track: VideoTrack, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(client.eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                track.addSink(this)
            }
        },
        onRelease = { view ->
            track.removeSink(view)
            view.release()
        },
    )
}

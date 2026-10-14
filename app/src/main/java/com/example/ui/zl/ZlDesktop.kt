package com.example.ui.zl

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Remote desktop (alpha): the computer's screen as a picture that refreshes a few times a second, with tap = click, long press =
 * right click, drag up/down = scroll, and a box to type into. The computer must allow "Show the screen" (to see) and "Control the
 * mouse" (to click, scroll and type) for this phone in Settings > Zohara Link; each is off until chosen there.
 *
 * Not a video stream: there is no sound and a few frames a second. Plenty to open a file or fix a setting from the sofa; not for
 * games. See docs/SECURITY.md for what protects it.
 */
@Composable
fun DesktopScreen(
    computerName: String,
    connected: Boolean,
    frame: Bitmap?,
    problem: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTap: (Float, Float, Boolean) -> Unit,
    onScroll: (Int) -> Unit,
    onType: (String) -> Unit,
    onKey: (String) -> Unit,
    onMove: (Float, Float) -> Unit,
    onClick: (String) -> Unit,
    onButton: (String, Boolean) -> Unit,
    onScrollSmooth: (Float, Float) -> Unit,
    onBack: () -> Unit,
) {
    // Watch while this screen is open and the computer is connected; stop the moment either stops being true.
    DisposableEffect(connected) {
        if (connected) onStart()
        onDispose { onStop() }
    }
    var text by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(false) }

    if (full) {
        FullScreenDesktop(frame, connected, problem, onMove, onClick, onButton, onScrollSmooth, onKey, onTap) { full = false }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Zl.bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(computerName, color = Zl.text, fontSize = 18.sp, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Full screen") { full = true }
                SecondaryButton("Close") { onBack() }
            }
        }

        val shape = RoundedCornerShape(14.dp)
        if (frame == null) {
            Text(
                if (!connected) "Not connected to the computer."
                else problem ?: "Waiting for the computer's screen…\nIf nothing appears, turn on \"Show the screen\" for this phone in Settings > Zohara Link on the computer.",
                color = Zl.muted, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth().clip(shape).background(Zl.card).padding(16.dp),
            )
        } else {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "The computer's screen. Tap to click, long press to right-click, drag up or down to scroll.",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(frame.width.toFloat() / frame.height.toFloat())
                    .clip(shape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { o -> onTap(o.x / size.width, o.y / size.height, false) },
                            onLongPress = { o -> onTap(o.x / size.width, o.y / size.height, true) },
                        )
                    }
                    .pointerInput(Unit) {
                        var carried = 0f
                        detectVerticalDragGestures { _, dy ->
                            carried += dy
                            val lines = (carried / 40f).toInt()
                            if (lines != 0) {
                                carried -= lines * 40f
                                onScroll(-lines) // dragging up scrolls down, like a phone
                            }
                        }
                    },
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Type on the computer") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (text.isNotEmpty()) onType(text)
                text = ""
            }),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Send text", Modifier.weight(1f)) { if (text.isNotEmpty()) onType(text); text = "" }
            SecondaryButton("Enter", Modifier.weight(1f)) { onKey("ENTER") }
            SecondaryButton("Backspace", Modifier.weight(1f)) { onKey("BACKSPACE") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Esc", Modifier.weight(1f)) { onKey("ESCAPE") }
            SecondaryButton("Tab", Modifier.weight(1f)) { onKey("TAB") }
            SecondaryButton("◀", Modifier.weight(1f)) { onKey("LEFT") }
            SecondaryButton("▲", Modifier.weight(1f)) { onKey("UP") }
            SecondaryButton("▼", Modifier.weight(1f)) { onKey("DOWN") }
            SecondaryButton("▶", Modifier.weight(1f)) { onKey("RIGHT") }
        }
    }
}

/**
 * The whole phone screen is the computer's screen: the phone turns sideways, the bars go away, and the screen is shown as large as
 * it fits. Two ways to control it, switched at the top: **Touchpad** (the whole screen is a touchpad: move, tap, two fingers
 * scroll, tap-and-drag to hold the button) and **Touch** (tap where you want to click, like a touch screen).
 * The picture is a few frames a second, not a video: good for controlling the desktop, not for watching or playing on it.
 */
@Composable
private fun FullScreenDesktop(
    frame: Bitmap?,
    connected: Boolean,
    problem: String?,
    onMove: (Float, Float) -> Unit,
    onClick: (String) -> Unit,
    onButton: (String, Boolean) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onKey: (String) -> Unit,
    onTap: (Float, Float, Boolean) -> Unit,
    onExit: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) {
        var c: android.content.Context = context
        var activity: android.app.Activity? = null
        while (activity == null && c is android.content.ContextWrapper) {
            if (c is android.app.Activity) activity = c else c = c.baseContext
        }
        val before = activity?.requestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val window = activity?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = before
        }
    }
    var touchMode by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        if (frame == null) {
            Text(
                if (!connected) "Not connected to the computer." else problem ?: "Waiting for the computer's screen…",
                color = Zl.muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "The computer's screen",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (touchMode) {
            // tap where to click: the picture fills the phone's screen width or height, so positions are fractions of what is shown
            Box(
                Modifier.fillMaxSize().pointerInput(frame?.width, frame?.height) {
                    detectTapGestures(
                        onTap = { o -> fractionOf(o.x, o.y, size.width, size.height, frame)?.let { (x, y) -> onTap(x, y, false) } },
                        onLongPress = { o -> fractionOf(o.x, o.y, size.width, size.height, frame)?.let { (x, y) -> onTap(x, y, true) } },
                    )
                },
            ) {}
        } else {
            Touchpad(Modifier.fillMaxSize(), onMove = onMove, onClick = onClick, onButton = onButton, onScroll = onScroll)
        }
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(if (touchMode) "Use as touchpad" else "Use as touch screen") { touchMode = !touchMode }
            SecondaryButton("Esc") { onKey("ESCAPE") }
            SecondaryButton("Exit full screen") { onExit() }
        }
    }
}

/** Where a touch at ([px], [py]) is on the computer's screen, as fractions 0..1, when the picture is shown as large as fits. Null: outside the picture. */
internal fun fractionOf(px: Float, py: Float, viewW: Int, viewH: Int, frame: Bitmap?): Pair<Float, Float>? {
    if (frame == null || viewW <= 0 || viewH <= 0) return null
    val scale = minOf(viewW.toFloat() / frame.width, viewH.toFloat() / frame.height)
    val w = frame.width * scale
    val h = frame.height * scale
    val x = (px - (viewW - w) / 2f) / w
    val y = (py - (viewH - h) / 2f) / h
    return if (x in 0f..1f && y in 0f..1f) x to y else null
}

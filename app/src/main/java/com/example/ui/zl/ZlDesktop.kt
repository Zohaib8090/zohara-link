package com.example.ui.zl

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Remote desktop: the computer's screen as a picture that refreshes smoothly, with a live touchpad in the bottom space
 * to control the mouse pointer (tap to click, two fingers to scroll and right-click), direct screen touch, keyboard input,
 * and full screen mode.
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
    onKeyCode: (Int, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    // Watch while this screen is open and the computer is connected; stop the moment either stops being true.
    DisposableEffect(connected) {
        if (connected) onStart()
        onDispose { onStop() }
    }
    var text by remember { mutableStateOf("") }
    var full by rememberSaveable { mutableStateOf(false) }
    var showKeyboard by rememberSaveable { mutableStateOf(false) }

    if (full) {
        FullScreenDesktop(
            frame = frame,
            connected = connected,
            problem = problem,
            onMove = onMove,
            onClick = onClick,
            onButton = onButton,
            onScroll = onScrollSmooth,
            onKey = onKey,
            onTap = onTap,
            onExit = { full = false },
        )
        return
    }

    // the keyboard: the phone's own, with the extra keys right above it
    if (showKeyboard) {
        DesktopKeyboardScreen(computerName, connected, onType, onKey, onKeyCode, onClose = { showKeyboard = false })
        return
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenShape = RoundedCornerShape(14.dp)
    val padShape = RoundedCornerShape(18.dp)

    if (isLandscape) {
        // Landscape layout: Computer screen on the left, Touchpad and controls on the right
        Row(
            Modifier
                .fillMaxSize()
                .background(Zl.bg)
                .statusBarsPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Left Column: Screen preview + title bar
            Column(
                Modifier.weight(1.3f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(computerName, color = Zl.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DesktopActionButton(if (showKeyboard) "Hide keys" else "Keyboard", active = showKeyboard) { showKeyboard = !showKeyboard }
                        DesktopActionButton("Full screen") { full = true }
                        DesktopActionButton("Close") { onBack() }
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DesktopScreenViewer(frame, connected, problem, screenShape, onTap, onScroll)
                }
            }

            // Right Column: Touchpad + Click buttons + optional keyboard drawer
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showKeyboard) {
                    DesktopKeyboardDrawer(
                        text = text,
                        onTextChange = { text = it },
                        onType = onType,
                        onKey = onKey,
                    )
                }
                DesktopTouchpadBox(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    padShape = padShape,
                    onMove = onMove,
                    onClick = onClick,
                    onButton = onButton,
                    onScrollSmooth = onScrollSmooth,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecondaryButton("Left click", Modifier.weight(1f)) { onClick("LEFT") }
                    SecondaryButton("Right click", Modifier.weight(1f)) { onClick("RIGHT") }
                }
            }
        }
    } else {
        // Portrait layout: Screen on top, Touchpad filling the entire bottom free space
        Column(
            Modifier
                .fillMaxSize()
                .background(Zl.bg)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top Header Bar
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    computerName,
                    color = Zl.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DesktopActionButton(if (showKeyboard) "Hide keys" else "Keyboard", active = showKeyboard) { showKeyboard = !showKeyboard }
                    DesktopActionButton("Full screen") { full = true }
                    DesktopActionButton("Close") { onBack() }
                }
            }

            // Computer Screen Preview
            DesktopScreenViewer(frame, connected, problem, screenShape, onTap, onScroll)

            // Optional Keyboard & Shortcut Drawer
            if (showKeyboard) {
                DesktopKeyboardDrawer(
                    text = text,
                    onTextChange = { text = it },
                    onType = onType,
                    onKey = onKey,
                )
            }

            // Bottom free space used as a Touchpad
            DesktopTouchpadBox(
                modifier = Modifier.fillMaxWidth().weight(1f),
                padShape = padShape,
                onMove = onMove,
                onClick = onClick,
                onButton = onButton,
                onScrollSmooth = onScrollSmooth,
            )

            // Physical Left/Right Click Buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Left click", Modifier.weight(1f)) { onClick("LEFT") }
                SecondaryButton("Right click", Modifier.weight(1f)) { onClick("RIGHT") }
            }
        }
    }
}

@Composable
private fun DesktopScreenViewer(
    frame: Bitmap?,
    connected: Boolean,
    problem: String?,
    shape: RoundedCornerShape,
    onTap: (Float, Float, Boolean) -> Unit,
    onScroll: (Int) -> Unit,
) {
    if (frame == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(shape)
                .background(Zl.card)
                .border(1.dp, Zl.line, shape)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (!connected) "Not connected to the computer."
                else problem ?: "Waiting for the computer's screen…\nIf nothing appears, turn on \"Show the screen\" for this phone in Settings > Zohara Link on the computer.",
                color = Zl.muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    } else {
        val aspect = (frame.width.toFloat() / frame.height.toFloat().coerceAtLeast(1f)).coerceIn(0.6f, 2.5f)
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = "The computer's screen. Tap directly to click, or use the touchpad below.",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(shape)
                .background(Color.Black)
                .border(1.dp, Zl.lineStrong, shape)
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
                            onScroll(-lines)
                        }
                    }
                },
        )
    }
}

@Composable
private fun DesktopTouchpadBox(
    modifier: Modifier,
    padShape: RoundedCornerShape,
    onMove: (Float, Float) -> Unit,
    onClick: (String) -> Unit,
    onButton: (String, Boolean) -> Unit,
    onScrollSmooth: (Float, Float) -> Unit,
) {
    Touchpad(
        modifier = modifier
            .clip(padShape)
            .background(Zl.card)
            .border(1.dp, Zl.lineStrong, padShape)
            .semantics { contentDescription = "Touchpad. Drag to move pointer, tap to click, two fingers to scroll." },
        onMove = onMove,
        onClick = onClick,
        onButton = onButton,
        onScroll = onScrollSmooth,
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Mouse,
                contentDescription = null,
                tint = Color(0xFF4A5D73),
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text("Touchpad", color = Zl.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text("Slide finger to move pointer · Tap to click", color = Zl.muted, fontSize = 12.sp)
            Text("Two fingers: scroll & right-click · Double-tap & drag to hold", color = Zl.muted, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun DesktopKeyboardDrawer(
    text: String,
    onTextChange: (String) -> Unit,
    onType: (String) -> Unit,
    onKey: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Zl.card)
            .border(1.dp, Zl.line, RoundedCornerShape(14.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Type on the computer") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (text.isNotEmpty()) onType(text)
                onTextChange("")
            }),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton("Send text", Modifier.weight(1.2f)) {
                if (text.isNotEmpty()) onType(text)
                onTextChange("")
            }
            SecondaryButton("Enter", Modifier.weight(1f)) { onKey("ENTER") }
            SecondaryButton("Bksp", Modifier.weight(1f)) { onKey("BACKSPACE") }
            SecondaryButton("Esc", Modifier.weight(0.9f)) { onKey("ESCAPE") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton("Tab", Modifier.weight(1f)) { onKey("TAB") }
            SecondaryButton("◀", Modifier.weight(1f)) { onKey("LEFT") }
            SecondaryButton("▲", Modifier.weight(1f)) { onKey("UP") }
            SecondaryButton("▼", Modifier.weight(1f)) { onKey("DOWN") }
            SecondaryButton("▶", Modifier.weight(1f)) { onKey("RIGHT") }
        }
    }
}

@Composable
private fun DesktopActionButton(text: String, active: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (active) Zl.accent else Zl.card)
            .border(1.dp, if (active) Zl.accent else Zl.lineStrong, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (active) Zl.accentInk else Zl.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The whole phone screen is the computer's screen: the phone turns sideways, the bars go away, and the screen is shown as large as
 * it fits. Two ways to control it, switched at the top: **Touchpad** (the whole screen is a touchpad: move, tap, two fingers
 * scroll, tap-and-drag to hold the button) and **Touch** (tap where you want to click, like a touch screen).
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
    val context = LocalContext.current
    BackHandler { onExit() }

    DisposableEffect(Unit) {
        var c: android.content.Context = context
        var activity: android.app.Activity? = null
        while (activity == null && c is android.content.ContextWrapper) {
            if (c is android.app.Activity) activity = c else c = c.baseContext
        }
        val before = activity?.requestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        runCatching {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val window = activity?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        runCatching {
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            runCatching {
                controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                activity?.requestedOrientation = before
            }
        }
    }
    var touchMode by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DesktopActionButton(if (touchMode) "Touchpad mode" else "Touch screen mode", active = touchMode) { touchMode = !touchMode }
            DesktopActionButton("Esc") { onKey("ESCAPE") }
            DesktopActionButton("Exit full screen") { onExit() }
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

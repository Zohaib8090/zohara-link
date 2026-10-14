package com.example.ui.zl

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The bits of the button mask the computer expects (see linux-daemon/src/uinput.rs, PAD_BUTTONS). */
object Pad {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val X = 1 shl 2
    const val Y = 1 shl 3
    const val LB = 1 shl 4
    const val RB = 1 shl 5
    const val BACK = 1 shl 6
    const val START = 1 shl 7
    const val GUIDE = 1 shl 8
    const val LS = 1 shl 9
    const val RS = 1 shl 10
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The phone as a game controller: two sticks, a direction pad, four face buttons, two bumpers, two triggers, Back, Start and
 * Guide. The computer shows it as a wired Xbox 360 controller, so games and Steam already know it. The screen turns sideways
 * while it is open, and the controller disappears from the computer when it is closed.
 *
 * The state is sent about 60 times a second, only while something changed. The computer must allow this phone to control the
 * mouse (Settings > Zohara Link); the controller is covered by the same permission.
 */
@Composable
fun GamepadScreen(
    computerName: String,
    connected: Boolean,
    onState: (buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, hatX: Int, hatY: Int) -> Unit,
    onOff: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            onOff()
            activity?.requestedOrientation = before
        }
    }

    GamepadControls(Modifier.background(Zl.bg), computerName, connected, onState, onClose)
}

/**
 * The controller's buttons and sticks, without a background, so they can sit on top of the live video (set a transparent
 * [modifier]) or on the plain controller screen. [onState] is called when something changed, at most about 60 times a second.
 */
@Composable
fun GamepadControls(
    modifier: Modifier,
    computerName: String,
    connected: Boolean,
    onState: (buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, hatX: Int, hatY: Int) -> Unit,
    onClose: () -> Unit,
) {
    var buttons by remember { mutableIntStateOf(0) }
    var lx by remember { mutableFloatStateOf(0f) }
    var ly by remember { mutableFloatStateOf(0f) }
    var rx by remember { mutableFloatStateOf(0f) }
    var ry by remember { mutableFloatStateOf(0f) }
    var lt by remember { mutableFloatStateOf(0f) }
    var rt by remember { mutableFloatStateOf(0f) }
    var hatX by remember { mutableIntStateOf(0) }
    var hatY by remember { mutableIntStateOf(0) }

    // send when something changed, at most about 60 times a second
    LaunchedEffect(connected) {
        var last = ""
        while (true) {
            val now = "$buttons $lx $ly $rx $ry $lt $rt $hatX $hatY"
            if (connected && now != last) {
                onState(buttons, lx, ly, rx, ry, lt, rt, hatX, hatY)
                last = now
            }
            delay(16)
        }
    }

    fun setBit(bit: Int, on: Boolean) {
        buttons = if (on) buttons or bit else buttons and bit.inv()
    }

    Box(modifier.fillMaxSize()) {
        // top row: close, status, bumpers and triggers
        Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (connected) "Controller on $computerName" else "Not connected to the computer",
                color = if (connected) Zl.ok else Zl.muted, fontSize = 12.sp,
            )
        }
        PadButton("Close", Modifier.align(Alignment.TopCenter).padding(top = 30.dp), 64.dp, 30.dp, onPress = { onClose() }, onRelease = {})

        PadButton("LT", Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 12.dp), 84.dp, 44.dp, onPress = { lt = 1f }, onRelease = { lt = 0f })
        PadButton("LB", Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 62.dp), 84.dp, 40.dp, onPress = { setBit(Pad.LB, true) }, onRelease = { setBit(Pad.LB, false) })
        PadButton("RT", Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 12.dp), 84.dp, 44.dp, onPress = { rt = 1f }, onRelease = { rt = 0f })
        PadButton("RB", Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 62.dp), 84.dp, 40.dp, onPress = { setBit(Pad.RB, true) }, onRelease = { setBit(Pad.RB, false) })

        // middle: Back, Guide, Start
        Row(Modifier.align(Alignment.Center).padding(top = 40.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            PadButton("Back", Modifier, 58.dp, 34.dp, onPress = { setBit(Pad.BACK, true) }, onRelease = { setBit(Pad.BACK, false) })
            PadButton("Guide", Modifier, 58.dp, 34.dp, onPress = { setBit(Pad.GUIDE, true) }, onRelease = { setBit(Pad.GUIDE, false) })
            PadButton("Start", Modifier, 58.dp, 34.dp, onPress = { setBit(Pad.START, true) }, onRelease = { setBit(Pad.START, false) })
        }

        // left stick (bottom left) and direction pad above it
        Stick(Modifier.align(Alignment.BottomStart).padding(start = 36.dp, bottom = 24.dp), 150.dp) { x, y -> lx = x; ly = y }
        Box(Modifier.align(Alignment.CenterStart).padding(start = 40.dp, bottom = 40.dp)) {
            DPad { x, y -> hatX = x; hatY = y }
        }

        // right stick (bottom, towards the middle) and the four face buttons on the right
        Stick(Modifier.align(Alignment.BottomEnd).padding(end = 190.dp, bottom = 24.dp), 130.dp) { x, y -> rx = x; ry = y }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 30.dp, bottom = 40.dp).size(150.dp)) {
            val bs = 52.dp
            PadButton("Y", Modifier.align(Alignment.TopCenter), bs, bs, round = true, color = Color(0xFFE0B341), onPress = { setBit(Pad.Y, true) }, onRelease = { setBit(Pad.Y, false) })
            PadButton("A", Modifier.align(Alignment.BottomCenter), bs, bs, round = true, color = Color(0xFF5FC47A), onPress = { setBit(Pad.A, true) }, onRelease = { setBit(Pad.A, false) })
            PadButton("X", Modifier.align(Alignment.CenterStart), bs, bs, round = true, color = Color(0xFF5B9BE6), onPress = { setBit(Pad.X, true) }, onRelease = { setBit(Pad.X, false) })
            PadButton("B", Modifier.align(Alignment.CenterEnd), bs, bs, round = true, color = Color(0xFFE06B6B), onPress = { setBit(Pad.B, true) }, onRelease = { setBit(Pad.B, false) })
        }
    }
}

/** A button that is down while a finger is on it. Several buttons can be held at once (each has its own touch). */
@Composable
private fun PadButton(
    label: String,
    modifier: Modifier,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    round: Boolean = false,
    color: Color = Zl.card,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(width, height)
            .clip(if (round) CircleShape else RoundedCornerShape(12.dp))
            .background(if (down) Zl.accent else color)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    down = true
                    onPress()
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Main)
                        e.changes.forEach { it.consume() }
                        if (e.changes.none { it.pressed }) break
                    }
                    down = false
                    onRelease()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (down) Zl.accentInk else Zl.text, fontSize = 13.sp)
    }
}

/** An analog stick: drag from where the finger lands; the value is how far, up to the edge of the circle. */
@Composable
private fun Stick(modifier: Modifier, size: androidx.compose.ui.unit.Dp, onValue: (Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Zl.cardAlt)
            .pointerInput(Unit) {
                val radius = this.size.width / 2f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    // the stick is where the circle's middle is, not where the finger landed: far simpler to learn
                    val center = Offset(radius, radius)
                    var pos = down.position - center
                    fun update() {
                        val len = hypot(pos.x, pos.y)
                        val limited = if (len > radius * 0.8f) pos * (radius * 0.8f / len) else pos
                        knob = limited
                        onValue((limited.x / (radius * 0.8f)).coerceIn(-1f, 1f), (limited.y / (radius * 0.8f)).coerceIn(-1f, 1f))
                    }
                    update()
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Main)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        pos = c.position - center
                        update()
                        c.consume()
                    }
                    knob = Offset.Zero
                    onValue(0f, 0f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xFF232C3A), radius = this.size.width / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
        Box(
            Modifier
                .offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .size(size * 0.38f)
                .clip(CircleShape)
                .background(Zl.accent.copy(alpha = if (knob == Offset.Zero) 0.55f else 0.9f)),
        )
    }
}

/** The direction pad: touch the arrow, or slide between them; the middle is nothing. */
@Composable
private fun DPad(onValue: (Int, Int) -> Unit) {
    var hx by remember { mutableIntStateOf(0) }
    var hy by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(Zl.cardAlt)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    fun set(p: Offset) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val d = p - c
                        val dead = size.width * 0.14f
                        hx = if (d.x > dead && kotlin.math.abs(d.x) >= kotlin.math.abs(d.y) * 0.6f) 1 else if (d.x < -dead && kotlin.math.abs(d.x) >= kotlin.math.abs(d.y) * 0.6f) -1 else 0
                        hy = if (d.y > dead && kotlin.math.abs(d.y) >= kotlin.math.abs(d.x) * 0.6f) 1 else if (d.y < -dead && kotlin.math.abs(d.y) >= kotlin.math.abs(d.x) * 0.6f) -1 else 0
                        onValue(hx, hy)
                    }
                    set(down.position)
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Main)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        set(c.position)
                        c.consume()
                    }
                    hx = 0
                    hy = 0
                    onValue(0, 0)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "▲\n◀   ▶\n▼",
            color = Zl.muted,
            fontSize = 18.sp,
            lineHeight = 26.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            when {
                hx != 0 || hy != 0 -> "${if (hy < 0) "▲" else if (hy > 0) "▼" else ""}${if (hx < 0) "◀" else if (hx > 0) "▶" else ""}"
                else -> ""
            },
            color = Zl.accent,
            fontSize = 22.sp,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

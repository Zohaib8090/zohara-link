package com.example.ui.zl

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlin.math.hypot
import kotlin.math.max

/**
 * How far the pointer moves for a finger move of [distance] pixels in one frame: slow movement is fine and precise, a fast
 * flick crosses the screen (the same idea as a laptop touchpad's acceleration).
 */
fun accelerate(dx: Float, dy: Float): Pair<Float, Float> {
    val speed = hypot(dx, dy)
    val gain = (1.1f + 0.09f * speed).coerceAtMost(4.0f)
    return dx * gain to dy * gain
}

/** Two-finger scroll: this many finger pixels are one line. */
const val SCROLL_PIXELS_PER_LINE = 36f

/**
 * A touchpad like a laptop's:
 *  - one finger moves the pointer (with acceleration);
 *  - a tap clicks; a tap with two fingers right-clicks;
 *  - two fingers dragging scroll, smoothly;
 *  - tap, then touch again and drag: the left button is held down (drag and drop, selecting text, windows).
 */
@Composable
fun Touchpad(
    modifier: Modifier = Modifier,
    onMove: (Float, Float) -> Unit,
    onClick: (String) -> Unit,
    onButton: (String, Boolean) -> Unit,
    onScroll: (Float, Float) -> Unit,
    content: @Composable () -> Unit = {},
) {
    val move by rememberUpdatedState(onMove)
    val click by rememberUpdatedState(onClick)
    val button by rememberUpdatedState(onButton)
    val scroll by rememberUpdatedState(onScroll)
    val slop = LocalViewConfiguration.current.touchSlop
    Box(
        modifier.pointerInput(Unit) {
            var lastTapEnd = 0L
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.uptimeMillis
                // touching again soon after a tap: a drag with the button held
                val holding = start - lastTapEnd < 300
                var total = Offset.Zero
                var moved = false
                var maxFingers = 1
                var buttonDown = false
                var lastTime = start
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    lastTime = event.changes.maxOf { it.uptimeMillis }
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    maxFingers = max(maxFingers, pressed.size)
                    if (pressed.size >= 2) {
                        val n = pressed.size
                        val dx = pressed.sumOf { it.positionChange().x.toDouble() }.toFloat() / n
                        val dy = pressed.sumOf { it.positionChange().y.toDouble() }.toFloat() / n
                        total += Offset(dx, dy)
                        if (hypot(total.x, total.y) > slop) moved = true
                        // dragging up scrolls down, like a phone
                        if (dx != 0f || dy != 0f) scroll(-dx / SCROLL_PIXELS_PER_LINE, -dy / SCROLL_PIXELS_PER_LINE)
                    } else {
                        val d = pressed[0].positionChange()
                        total += d
                        if (!moved && hypot(total.x, total.y) > slop) {
                            moved = true
                            if (holding && maxFingers == 1) {
                                buttonDown = true
                                button("LEFT", true)
                            }
                        }
                        if (moved && (d.x != 0f || d.y != 0f)) {
                            val (ax, ay) = accelerate(d.x, d.y)
                            move(ax, ay)
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
                if (buttonDown) button("LEFT", false)
                // a short touch that did not move is a tap (all times are the touch screen's own clock)
                val tap = !moved && lastTime - start < 250
                if (tap && maxFingers >= 2) {
                    click("RIGHT")
                } else if (tap && !holding) {
                    click("LEFT")
                    lastTapEnd = lastTime
                } else if (tap) {
                    click("LEFT") // the second tap of a double click
                    lastTapEnd = 0L
                }
            }
        },
    ) { content() }
}

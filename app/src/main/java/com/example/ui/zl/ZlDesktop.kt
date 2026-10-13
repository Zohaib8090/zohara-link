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
    onBack: () -> Unit,
) {
    // Watch while this screen is open and the computer is connected; stop the moment either stops being true.
    DisposableEffect(connected) {
        if (connected) onStart()
        onDispose { onStop() }
    }
    var text by remember { mutableStateOf("") }

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
            SecondaryButton("Close") { onBack() }
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

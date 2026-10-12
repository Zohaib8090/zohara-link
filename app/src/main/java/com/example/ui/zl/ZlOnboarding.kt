package com.example.ui.zl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DiscoveredDevice

/** The four-diamond Zohara mark, drawn so the app needs no image file. */
@Composable
private fun ZoharaMark(modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(64.dp)
            .semantics { contentDescription = "Zohara logo" },
    ) {
        val u = size.width / 64f
        fun diamond(cx: Float, cy: Float, alpha: Float) {
            val r = 10f * u
            val p = Path().apply {
                moveTo(cx * u, cy * u - r)
                lineTo(cx * u + r, cy * u)
                lineTo(cx * u, cy * u + r)
                lineTo(cx * u - r, cy * u)
                close()
            }
            drawPath(p, Zl.accent.copy(alpha = alpha))
        }
        diamond(32f, 14f, 1f)
        diamond(14f, 32f, 0.55f)
        diamond(50f, 32f, 0.55f)
        diamond(32f, 50f, 0.3f)
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Box(
        Modifier
            .padding(top = 8.dp)
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Zl.text)
    }
}

@Composable
private fun Benefit(icon: ImageVector, title: String, line: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        IconTile(icon)
        Column {
            Text(title, color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(line, color = Zl.muted, fontSize = 14.sp)
        }
    }
}

// 1 ---------------------------------------------------------------------------------------------------------------

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Zl.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        ZoharaMark()
        Spacer(Modifier.height(28.dp))
        Text(
            "Your phone and your Zohara computer, together.",
            color = Zl.text, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Share your clipboard, send files and see phone notifications on the desktop, over your own Wi-Fi.",
            color = Zl.muted, fontSize = 16.sp, lineHeight = 24.sp,
        )
        Spacer(Modifier.height(40.dp))
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Benefit(Icons.Filled.ContentPaste, "Copy here, paste there.", "One clipboard for both.")
            Benefit(Icons.Filled.FileUpload, "Send files straight across.", "No cable, no cloud.")
            Benefit(Icons.Filled.Notifications, "Phone alerts on your desktop.", "You choose when.")
        }
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Shield, contentDescription = null, tint = Zl.ok, modifier = Modifier.size(18.dp))
            Text(
                "Everything is encrypted and only goes to a computer you pair with a PIN. Nothing leaves your Wi-Fi.",
                color = Zl.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Find my computer", onClick = onStart)
    }
}

// 2 ---------------------------------------------------------------------------------------------------------------

@Composable
fun FindScreen(
    devices: List<DiscoveredDevice>,
    connecting: Boolean,
    error: String?,
    onPick: (DiscoveredDevice) -> Unit,
    onManual: (String) -> Unit,
    onBack: () -> Unit,
) {
    var manualOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Zl.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        BackRow(onBack)
        Spacer(Modifier.height(12.dp))
        Text("Find your computer", color = Zl.text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Both devices need to be on the same Wi-Fi. Zohara computers appear here by themselves.",
            color = Zl.muted, fontSize = 15.sp, lineHeight = 22.sp,
        )

        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = Zl.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Text(
                if (connecting) "CONNECTING" else "LOOKING NEARBY",
                color = Zl.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
            )
        }
        Spacer(Modifier.height(14.dp))

        if (devices.isEmpty()) {
            Text("Nothing found yet.", color = Zl.muted, fontSize = 14.sp)
        }
        devices.forEach { d ->
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Zl.card)
                    .border(1.dp, Zl.line, shape)
                    .clickable(enabled = !connecting, role = Role.Button) { onPick(d) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconTile(Icons.Filled.Computer, tile = 48, background = Zl.bg)
                Column(Modifier.weight(1f)) {
                    Text(d.serviceName, color = Zl.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Zohara OS · same Wi-Fi", color = Zl.muted, fontSize = 13.sp)
                }
                Box(
                    Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Zl.accent)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Pair", color = Zl.accentInk, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }

        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, color = Zl.danger, fontSize = 14.sp, lineHeight = 20.sp)
        }

        Spacer(Modifier.height(24.dp))
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Zl.cardAlt)
                .border(1.dp, Zl.line, shape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Not on the list?", color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "1. On the computer open Settings > Zohara Link.\n" +
                    "2. Press Pair a phone. Pairing is off until you do, so nobody else on the Wi-Fi can ask.\n" +
                    "3. Come back here: the computer shows up by itself.",
                color = Zl.muted, fontSize = 14.sp, lineHeight = 22.sp,
            )
        }
        Spacer(Modifier.height(20.dp))
        SecondaryButton("Type the computer's address instead") { manualOpen = true }
        Spacer(Modifier.height(24.dp))
    }

    if (manualOpen) {
        var host by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manualOpen = false },
            containerColor = Zl.card,
            title = { Text("Computer's address", color = Zl.text) },
            text = {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    singleLine = true,
                    label = { Text("For example 192.168.1.20") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            },
            confirmButton = {
                TextButton(enabled = host.isNotBlank(), onClick = { manualOpen = false; onManual(host.trim()) }) {
                    Text("Connect", color = Zl.accent)
                }
            },
            dismissButton = { TextButton(onClick = { manualOpen = false }) { Text("Cancel", color = Zl.muted) } },
        )
    }
}

// 3 ---------------------------------------------------------------------------------------------------------------

@Composable
fun PairScreen(
    computerName: String,
    fingerprint: String,
    error: String?,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Zl.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        BackRow(onCancel)
        Spacer(Modifier.height(12.dp))
        Text(
            "Pair with $computerName",
            color = Zl.text, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold,
        )

        Spacer(Modifier.height(28.dp))
        StepTitle("1", "Check the code")
        Spacer(Modifier.height(10.dp))
        ZlCard(Modifier.padding(0.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    fingerprint,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = Zl.accent, fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
                Text(
                    "It must be the same as \"This computer's code\" in Settings > Zohara Link on the computer.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = Zl.muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        StepTitle("2", "Type the PIN from the computer")
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = pin,
            onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) pin = v },
            modifier = Modifier.focusRequester(focus),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent),
            decorationBox = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { i ->
                        val active = i == pin.length
                        val shape = RoundedCornerShape(14.dp)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(60.dp)
                                .clip(shape)
                                .background(Zl.card)
                                .border(1.5.dp, if (active) Zl.accent else Zl.lineStrong, shape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                pin.getOrNull(i)?.toString() ?: "",
                                color = Zl.text, fontSize = 26.sp, fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "The PIN is only shown on the computer. It stops working after 3 wrong tries or 2 minutes.",
            color = Zl.muted, fontSize = 13.sp, lineHeight = 19.sp,
        )

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = Zl.danger, fontSize = 14.sp, lineHeight = 20.sp)
        }

        Spacer(Modifier.height(24.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Zl.dangerBg)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Zl.danger, modifier = Modifier.size(18.dp))
            Text(
                "Codes different? Stop and cancel. Someone else on this Wi-Fi may be in the middle.",
                color = Zl.danger, fontSize = 13.sp, lineHeight = 19.sp,
            )
        }

        Spacer(Modifier.height(28.dp))
        PrimaryButton("Pair this phone", enabled = pin.length == 6) { onConfirm(pin) }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Cancel", color = Zl.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepTitle(n: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(Zl.accent),
            contentAlignment = Alignment.Center,
        ) { Text(n, color = Zl.accentInk, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold) }
        Text(text, color = Zl.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

package com.example.ui.zl

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MediaControlState
import com.example.model.TransferRecordEntity

private val Page: Modifier get() = Modifier
    .fillMaxSize()
    .background(Zl.bg)

@Composable
private fun Title(text: String) {
    Text(text, color = Zl.text, fontSize = 38.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontWeight = FontWeight.Normal)
}

private fun ago(ts: Long): String =
    DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

// 4 ---------------------------------------------------------------------------------------------------------------

data class RecentEvent(val text: String, val time: Long)

@Composable
private fun Tile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .heightIn(min = 88.dp)
            .clip(shape)
            .background(Zl.card)
            .border(1.dp, Zl.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, contentDescription = null, tint = Zl.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(10.dp))
        Text(label, color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HomeScreen(
    computerName: String,
    connected: Boolean,
    connecting: Boolean,
    code: String,
    alertsOn: Boolean,
    events: List<RecentEvent>,
    onReconnect: () -> Unit,
    onSendClipboard: () -> Unit,
    onFiles: () -> Unit,
    onRemote: () -> Unit,
    onLock: () -> Unit,
    onAlerts: (Boolean) -> Unit,
    updateText: String?,
    onUpdate: () -> Unit,
) {
    Column(
        Page
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (updateText != null) {
            ZlCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("A new version of Zohara Link is ready", color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(updateText, color = Zl.muted, fontSize = 13.sp)
                    PrimaryButton("Update now", onClick = onUpdate)
                }
            }
        }
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Brush.linearGradient(listOf(Zl.accentSoft, Zl.card)))
                .border(1.dp, Zl.accentSoft, shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconTile(Icons.Filled.Computer, tile = 52, background = Zl.bg)
                Column(Modifier.weight(1f)) {
                    Text(computerName, color = Zl.text, fontSize = 26.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Zl.ok else Zl.muted))
                        Text(
                            when {
                                connected -> "Connected"
                                connecting -> "Connecting…"
                                else -> "Not connected"
                            },
                            color = if (connected) Zl.ok else Zl.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            if (connected && code.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Zl.accent, modifier = Modifier.size(16.dp))
                    Text(
                        "Encrypted, and it is the computer you paired with (code $code)",
                        color = Zl.muted, fontSize = 12.5.sp, lineHeight = 18.sp,
                    )
                }
            }
            if (!connected && !connecting) {
                SecondaryButton("Connect again", onClick = onReconnect)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Icons.Filled.ContentPaste, "Send clipboard", Modifier.weight(1f), onSendClipboard)
            Tile(Icons.Filled.FileUpload, "Send a file", Modifier.weight(1f), onFiles)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Icons.Filled.Mouse, "Control the computer", Modifier.weight(1f), onRemote)
            Tile(Icons.Filled.Lock, "Lock the computer", Modifier.weight(1f), onLock)
        }

        ZlCard {
            SwitchRow(
                "Show my phone alerts",
                "On the computer. Needs notification access in Access.",
                alertsOn, onAlerts,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Recently")
            Spacer(Modifier.height(4.dp))
            if (events.isEmpty()) {
                Text("Nothing yet. Send a file or your clipboard.", color = Zl.muted, fontSize = 14.sp)
            }
            events.forEach { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Zl.muted, modifier = Modifier.size(20.dp))
                    Text(e.text, color = Zl.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ago(e.time), color = Zl.muted, fontSize = 12.5.sp)
                }
            }
        }
    }
}


// 5 ---------------------------------------------------------------------------------------------------------------

@Composable
fun FilesScreen(
    computerName: String,
    transfers: List<TransferRecordEntity>,
    onChoose: () -> Unit,
) {
    val active = transfers.filter { it.status == "TRANSFERRING" || it.status == "PENDING" }
    val recent = transfers.filter { it.status != "TRANSFERRING" && it.status != "PENDING" }.sortedByDescending { it.timestamp }.take(6)

    Column(
        Page
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Title("Files")

        val shape = RoundedCornerShape(24.dp)
        val dashColor = Zl.accent.copy(alpha = 0.5f)
        Column(
            Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Zl.accentSoft, Zl.card)))
                .drawBehind {
                    drawRoundRect(
                        color = dashColor,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 14f))),
                    )
                }
                .clickable(role = Role.Button, onClick = onChoose),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(Zl.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.FileUpload, contentDescription = null, tint = Zl.accentInk, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("Choose files to send", color = Zl.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("They go straight to $computerName", color = Zl.muted, fontSize = 13.sp)
        }

        if (active.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Sending now")
                active.forEach { t ->
                    val frac = if (t.fileSize > 0) (t.bytesTransferred.toFloat() / t.fileSize).coerceIn(0f, 1f) else 0f
                    ZlCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            FileLine(t.fileName, "${(frac * 100).toInt()}% · sending")
                            LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Zl.accent,
                                trackColor = Zl.line,
                            )
                        }
                    }
                }
            }
        }

        Column {
            SectionLabel("Earlier", Modifier.padding(bottom = 8.dp))
            if (recent.isEmpty()) {
                Text("No files yet.", color = Zl.muted, fontSize = 14.sp)
            }
            recent.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = Zl.muted, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.fileName, color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (t.isUpload) "Sent to the computer" else "From the computer") +
                                when (t.status) {
                                    "FAILED" -> " · failed"
                                    "CANCELLED" -> " · cancelled"
                                    else -> ""
                                } + " · " + ago(t.timestamp),
                            color = if (t.status == "FAILED") Zl.danger else Zl.muted, fontSize = 12.5.sp,
                        )
                    }
                }
            }
        }

        Text(
            "Files you send are saved on the computer in Downloads/ZoharaLink. A file with the same name is never replaced: the new one gets a number.",
            color = Zl.muted, fontSize = 12.5.sp, lineHeight = 19.sp,
        )
    }
}

@Composable
private fun FileLine(name: String, line: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(Icons.Filled.Description, tile = 40, background = Zl.bg)
        Column(Modifier.weight(1f)) {
            Text(name, color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, color = Zl.muted, fontSize = 13.sp)
        }
    }
}

// 6 ---------------------------------------------------------------------------------------------------------------

@Composable
fun RemoteScreen(
    computerName: String,
    connected: Boolean,
    media: MediaControlState,
    onMove: (Float, Float) -> Unit,
    onClick: (String) -> Unit,
    onButton: (String, Boolean) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onGamepad: () -> Unit,
    onStream: () -> Unit,
    onMedia: (String) -> Unit,
    onDesktop: () -> Unit,
    onKeyboard: () -> Unit,
) {
    Column(
        Page
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Title("Remote")
                Text("Use your phone as a touchpad", color = Zl.muted, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Zl.ok else Zl.muted))
                Text(computerName, color = if (connected) Zl.ok else Zl.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
            }
        }

        // what is playing on the computer
        val cardShape = RoundedCornerShape(24.dp)
        Row(
            Modifier.fillMaxWidth().clip(cardShape).background(Zl.card).border(1.dp, Zl.line, cardShape).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Zl.accent, Zl.accentSoft))), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Zl.accentInk, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (media.title.isBlank() || media.title == "No media playing") "Nothing playing" else media.title,
                    color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(if (media.artist.isNotBlank()) media.artist else "Playing on the computer", color = Zl.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundIcon(Icons.Filled.SkipPrevious, "Previous", 40, Color.Transparent, Zl.text) { onMedia("PREVIOUS") }
            RoundIcon(if (media.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (media.isPlaying) "Pause" else "Play", 44, Zl.accent, Zl.accentInk) { onMedia("PLAY_PAUSE") }
            RoundIcon(Icons.Filled.SkipNext, "Next", 40, Color.Transparent, Zl.text) { onMedia("NEXT") }
        }

        val pad = RoundedCornerShape(30.dp)
        Touchpad(
            modifier = Modifier
                .fillMaxWidth()
                .height(268.dp)
                .clip(pad)
                .background(Brush.radialGradient(listOf(Zl.accentSoft, Zl.card)))
                .border(1.dp, Zl.line, pad)
                .semantics { contentDescription = "Touchpad. Drag a finger to move the pointer, tap to click, two fingers to scroll." },
            onMove = onMove,
            onClick = onClick,
            onButton = onButton,
            onScroll = onScroll,
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).border(1.5.dp, Zl.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Mouse, contentDescription = null, tint = Zl.accent, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("Drag to move the pointer", color = Zl.muted, fontSize = 13.5.sp)
                Text("Two fingers: scroll · Tap, then drag: hold", color = Zl.muted, fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClickKey("Left click", Modifier.weight(1f)) { onClick("LEFT") }
            ClickKey("Right click", Modifier.weight(1f)) { onClick("RIGHT") }
        }
        Text(
            "If nothing moves, the computer has not allowed this phone yet: Settings > Zohara Link on the computer.",
            color = Zl.muted, fontSize = 12.sp, lineHeight = 17.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeTile(Icons.Filled.DesktopWindows, "Screen", Modifier.weight(1f), onDesktop)
            ModeTile(Icons.Filled.Keyboard, "Keyboard", Modifier.weight(1f), onKeyboard)
            ModeTile(Icons.Filled.SportsEsports, "Gamepad", Modifier.weight(1f), onGamepad)
        }
        ModeTile(Icons.Filled.PlayArrow, "Play: live video with a game controller", Modifier.fillMaxWidth(), onStream, wide = true)
    }
}

@Composable
private fun ClickKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.height(50.dp).clip(shape).background(Zl.card).border(1.dp, Zl.line, shape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Zl.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ModeTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit, wide: Boolean = false) {
    val shape = RoundedCornerShape(18.dp)
    val body: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, tint = Zl.accent, modifier = Modifier.size(22.dp))
        Text(label, color = Zl.text, fontSize = if (wide) 14.sp else 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
    if (wide) {
        Row(
            modifier.height(56.dp).clip(shape).background(Zl.card).border(1.dp, Zl.line, shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) { body() }
    } else {
        Column(
            modifier.height(76.dp).clip(shape).background(Zl.card).border(1.dp, Zl.line, shape).clickable(role = Role.Button, onClick = onClick).padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) { body() }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, size: Int, bg: androidx.compose.ui.graphics.Color, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size((size / 2.4).dp))
    }
}

// 7 ---------------------------------------------------------------------------------------------------------------

@Composable
fun AccessScreen(
    computerName: String,
    code: String,
    alertsOn: Boolean,
    notificationAccessGranted: Boolean,
    seenApps: () -> List<Pair<String, String>>,
    mutedApps: Set<String>,
    onSetMuted: (String, Boolean) -> Unit,
    onAlerts: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    versionLabel: String,
    updateLine: String,
    onCheckUpdate: () -> Unit,
    onForget: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    var appsOpen by remember { mutableStateOf(false) }

    Column(
        Page
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Title("Access")

        val context = androidx.compose.ui.platform.LocalContext.current
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Appearance")
            ZlCard {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "Follow phone", "dark" to "Dark", "light" to "Light").forEach { (m, label) ->
                        val on = Zl.mode == m
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) Zl.accent else Zl.cardAlt)
                                .border(1.dp, if (on) Zl.accent else Zl.line, RoundedCornerShape(12.dp))
                                .clickable(role = Role.RadioButton) { Zl.choose(context, m) }.padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (on) Zl.accentInk else Zl.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("This phone shares")
            ZlCard {
                SwitchRow("Phone alerts", "Shown on the computer", alertsOn, onAlerts)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable(enabled = !notificationAccessGranted, role = Role.Button, onClick = onOpenNotificationAccess)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Notification access", color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (notificationAccessGranted) "Allowed. Android lets Zohara Link read notifications."
                            else "Not allowed yet. Tap to open Android's setting and turn Zohara Link on.",
                            color = if (notificationAccessGranted) Zl.muted else Zl.accent, fontSize = 12.5.sp, lineHeight = 17.sp,
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZlCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable(role = Role.Button) { appsOpen = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Choose which apps", color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (mutedApps.isEmpty()) "Every app is shown. Switch one off if it keeps repeating."
                            else "${mutedApps.size} app(s) switched off",
                            color = Zl.muted, fontSize = 12.5.sp, lineHeight = 17.sp,
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("The computer decides")
            ZlCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Control the mouse and lock the computer", color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Turned on per phone in Settings > Zohara Link on the computer. Only the computer can change it, so a phone can never give itself more control.",
                        color = Zl.muted, fontSize = 12.5.sp, lineHeight = 18.sp,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Connection")
            ZlCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(computerName, color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (code.isNotEmpty()) {
                        Row {
                            Text("Its code: ", color = Zl.muted, fontSize = 12.5.sp)
                            Text(code, color = Zl.text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text(
                        "If this code ever changes, the phone refuses to connect and asks you to pair again.",
                        color = Zl.muted, fontSize = 12.5.sp, lineHeight = 18.sp,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("This app")
            ZlCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(versionLabel, color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (updateLine.isNotEmpty()) Text(updateLine, color = Zl.muted, fontSize = 12.5.sp, lineHeight = 18.sp)
                    SecondaryButton("Check for updates", onClick = onCheckUpdate)
                }
            }
        }

        SecondaryButton("Forget this computer", danger = true) { confirm = true }
        Spacer(Modifier.height(8.dp))
    }

    if (appsOpen) {
        val apps = remember { seenApps() }
        AlertDialog(
            onDismissRequest = { appsOpen = false },
            containerColor = Zl.card,
            title = { Text("Phone alerts from", color = Zl.text) },
            text = {
                if (apps.isEmpty()) {
                    Text("Apps appear here after they send a notification.", color = Zl.muted, fontSize = 14.sp)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(apps.size) { i ->
                            val (pkg, name) = apps[i]
                            SwitchRow(name, pkg, checked = pkg !in mutedApps, onChange = { on -> onSetMuted(pkg, !on) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { appsOpen = false }) { Text("Done", color = Zl.accent) } },
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Zl.card,
            title = { Text("Forget $computerName?", color = Zl.text) },
            text = { Text("This phone will disconnect and pair again from the start. The computer keeps its own list: remove the phone there too (Settings > Zohara Link).", color = Zl.muted) },
            confirmButton = { TextButton(onClick = { confirm = false; onForget() }) { Text("Forget", color = Zl.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = Zl.muted) } },
        )
    }
}


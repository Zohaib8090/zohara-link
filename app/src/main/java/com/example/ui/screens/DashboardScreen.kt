package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.TelemetryData
import com.example.network.ConnectionState
import com.example.ui.components.MediaControlWidget
import com.example.ui.theme.AlertRed
import com.example.ui.theme.BentoAmber
import com.example.ui.theme.BentoBg
import com.example.ui.theme.BentoBorder
import com.example.ui.theme.BentoCardWhite
import com.example.ui.theme.BentoDarkNavy
import com.example.ui.theme.BentoGreen
import com.example.ui.theme.BentoPrimaryBlue
import com.example.ui.theme.BentoSlate
import com.example.ui.theme.BentoSoftBlue
import com.example.ui.theme.BentoTextBody
import com.example.ui.theme.BentoTextMuted
import com.example.ui.theme.BentoTextPrimary
import com.example.viewmodel.EcosystemViewModel

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.example.model.PairedDeviceEntity

@Composable
fun DashboardScreen(
    viewModel: EcosystemViewModel,
    onNavigateToDiscovery: () -> Unit,
    onNavigateToTrackpad: () -> Unit,
    onNavigateToClipboard: () -> Unit,
    onNavigateToTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val latencyMs by viewModel.latencyMs.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val mediaState by viewModel.mediaState.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val currentClipboard by viewModel.currentClipboard.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()

    val activeTransfer = transfers.firstOrNull { it.status == "TRANSFERRING" } ?: transfers.firstOrNull()
    val hostName = when (connectionState) {
        is ConnectionState.Connected -> (connectionState as ConnectionState.Connected).serverName
        else -> "arch-precision-workstation"
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(BentoBg)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Bento Hero Box (Deep Navy - Active Desktop)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(BentoDarkNavy)
                    .padding(20.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Active Desktop",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = hostName,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Computer,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Uptime: 14h 22m • IPC Ready",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .background(BentoPrimaryBlue, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "DAEMON v1.2.0",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .background(BentoGreen, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "DBUS ACTIVE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "42°C",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "CPU TEMP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }

                    if (connectionState is ConnectionState.Connected) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { viewModel.sendLockDesktop() },
                                colors = ButtonDefaults.buttonColors(containerColor = BentoAmber, contentColor = BentoDarkNavy),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("lock_desktop_btn")
                            ) {
                                Icon(imageVector = Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Lock Host", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { viewModel.disconnect() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f), contentColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("disconnect_btn")
                            ) {
                                Icon(imageVector = Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Disconnect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToDiscovery,
                            colors = ButtonDefaults.buttonColors(containerColor = BentoPrimaryBlue, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("discover_pair_btn")
                        ) {
                            Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pair with Arch Linux Daemon", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Multi-Device Mesh Quick Switcher Row
        if (pairedDevices.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Quick Node Switcher",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "Manage",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoPrimaryBlue,
                                modifier = Modifier.clickable { onNavigateToDiscovery() }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(pairedDevices, key = { it.deviceId }) { dev ->
                                val isActive = connectionState is ConnectionState.Connected &&
                                        (connectionState as ConnectionState.Connected).host == dev.hostAddress

                                FilterChip(
                                    selected = isActive,
                                    onClick = {
                                        if (isActive) {
                                            viewModel.disconnect()
                                        } else {
                                            viewModel.switchActiveDevice(dev)
                                        }
                                    },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .background(if (isActive) BentoGreen else BentoAmber, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(dev.deviceName, fontSize = 11.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium)
                                        }
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = BentoGreen.copy(alpha = 0.15f),
                                        selectedLabelColor = BentoGreen
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }

                            item {
                                OutlinedButton(
                                    onClick = onNavigateToDiscovery,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add Node", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Bento Grid Row: Clipboard Bento & File Stream Bento
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Clipboard Bento Card (Slate #E1E2EC)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(190.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(BentoSlate)
                        .clickable { onNavigateToClipboard() }
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(BentoCardWhite, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = BentoDarkNavy,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Clipboard",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = BentoDarkNavy
                            )
                            Text(
                                text = if (currentClipboard.isNotBlank()) "'$currentClipboard'" else "'sudo pacman -Syu --noconfirm...'",
                                fontSize = 10.sp,
                                color = BentoTextMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Button(
                            onClick = onNavigateToClipboard,
                            colors = ButtonDefaults.buttonColors(containerColor = BentoCardWhite, contentColor = BentoDarkNavy),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                        ) {
                            Text("SYNC NOW", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }
                    }
                }

                // File Stream Bento Card (Periwinkle Blue #DDE1FF)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(190.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(BentoSoftBlue)
                        .clickable { onNavigateToTransfers() }
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(BentoPrimaryBlue, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (activeTransfer != null) "${(activeTransfer.bytesTransferred * 100 / (activeTransfer.fileSize.coerceAtLeast(1))).coerceIn(0, 100)}%" else "78%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoDarkNavy
                                )
                                Box(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(4.dp)
                                        .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.78f)
                                            .fillMaxSize()
                                            .background(BentoPrimaryBlue, RoundedCornerShape(2.dp))
                                    )
                                }
                            }
                        }

                        Column {
                            Text(
                                text = "File Stream",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = BentoDarkNavy
                            )
                            Text(
                                text = activeTransfer?.fileName ?: "kernel-source.tar.gz",
                                fontSize = 10.sp,
                                color = BentoTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Text(
                            text = if (activeTransfer != null) "${activeTransfer.bytesTransferred / 1024 / 1024}MB / ${activeTransfer.fileSize / 1024 / 1024}MB" else "1.2 GB / 1.5 GB",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoPrimaryBlue
                        )
                    }
                }
            }
        }

        // 3. Bento Grid Row: Phone/Desktop Battery Metric & System Volume Metric
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Battery Metric Card
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(BentoCardWhite)
                        .border(1.dp, BentoBorder, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(text = if (telemetry.isCharging) "⚡" else "🔋", fontSize = 20.sp)
                        Column {
                            Text(
                                text = if (telemetry.isCharging) "Charging" else "Device Bat",
                                fontSize = 10.sp,
                                color = BentoTextMuted,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${telemetry.batteryLevel}%",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoDarkNavy
                            )
                        }
                    }
                }

                // Volume Metric Card
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(BentoCardWhite)
                        .border(1.dp, BentoBorder, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(text = "🔊", fontSize = 20.sp)
                        Column {
                            Text(
                                text = "Sys Volume",
                                fontSize = 10.sp,
                                color = BentoTextMuted,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${mediaState.volume}%",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoDarkNavy
                            )
                        }
                    }
                }
            }
        }

        // 4. Bento Proximity Lock & RSSI Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFFDFBFF))
                    .border(1.5.dp, BentoBorder, RoundedCornerShape(28.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(if (connectionState is ConnectionState.Connected) BentoGreen else BentoAmber, CircleShape)
                        )
                        Text(
                            text = if (connectionState is ConnectionState.Connected) "PROXIMITY LINK: ACTIVE" else "PROXIMITY LINK: STANDBY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            color = BentoTextBody
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .height(14.dp)
                            .width(1.dp)
                            .background(BentoBorder)
                    )
                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = if (telemetry.wifiRssi != 0) "RSSI: ${telemetry.wifiRssi} dBm" else "RSSI: -64 dBm",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = BentoTextMuted
                    )
                }
            }
        }

        // 5. Desktop MPRIS2 Media Player Controller (Bento Styled)
        item {
            MediaControlWidget(
                mediaState = mediaState,
                onAction = { viewModel.sendMediaAction(it) },
                onVolumeChange = { viewModel.sendVolume(it) }
            )
        }

        // 6. Quick Remote Bento Actions
        item {
            Text(
                text = "Ecosystem Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BentoDarkNavy
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BentoActionCard(
                    title = "Virtual Trackpad",
                    subtitle = "Remote Pointer",
                    icon = Icons.Default.Mouse,
                    bgTint = BentoSlate,
                    iconBg = BentoCardWhite,
                    iconTint = BentoDarkNavy,
                    onClick = onNavigateToTrackpad,
                    modifier = Modifier.weight(1f)
                )

                BentoActionCard(
                    title = "Scan Network",
                    subtitle = "mDNS Discovery",
                    icon = Icons.Default.QrCodeScanner,
                    bgTint = BentoSoftBlue,
                    iconBg = BentoPrimaryBlue,
                    iconTint = Color.White,
                    onClick = onNavigateToDiscovery,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 7. Recent Mirrored Notifications (Bento Cards)
        if (notifications.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Mirrored Notifications",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BentoDarkNavy
                    )
                    Text(
                        text = "${notifications.size} synced",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoPrimaryBlue
                    )
                }
            }

            items(notifications.take(3).size) { idx ->
                val notif = notifications[idx]
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(BentoCardWhite)
                        .border(1.dp, BentoBorder.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(BentoSoftBlue, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = null,
                                tint = BentoPrimaryBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "[${notif.appName}] ${notif.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextBody,
                                maxLines = 1
                            )
                            if (notif.text.isNotBlank()) {
                                Text(
                                    text = notif.text,
                                    fontSize = 11.sp,
                                    color = BentoTextMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BentoActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    bgTint: Color,
    iconBg: Color,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(bgTint)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(iconBg, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = BentoDarkNavy
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = BentoTextMuted
            )
        }
    }
}


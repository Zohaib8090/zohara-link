package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BentoBg
import com.example.ui.theme.BentoBorder
import com.example.ui.theme.BentoCardWhite
import com.example.ui.theme.BentoDarkNavy
import com.example.ui.theme.BentoPrimaryBlue
import com.example.ui.theme.BentoSlate
import com.example.ui.theme.BentoSoftBlue
import com.example.ui.theme.BentoTextBody
import com.example.ui.theme.BentoTextMuted
import com.example.viewmodel.EcosystemViewModel

@Composable
fun LinuxDaemonHubScreen(
    viewModel: EcosystemViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) } // 0: Python Daemon & D-Bus, 1: Rust zoharad & WebRTC Mesh

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(BentoBg)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Tab Selector Chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("Linux D-Bus Daemon", fontWeight = FontWeight.Bold) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BentoPrimaryBlue,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("Zero-Cost P2P Mesh", fontWeight = FontWeight.Bold) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BentoDarkNavy,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (selectedTab == 0) {
            // Arch Linux Architecture Overview
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BentoBorder, RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = BentoCardWhite),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(BentoSoftBlue, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Computer,
                                    contentDescription = null,
                                    tint = BentoPrimaryBlue,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Arch Linux Daemon Architecture",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoDarkNavy
                                )
                                Text(
                                    text = "zohara-linkd • TLS Socket + D-Bus / Unix IPC",
                                    fontSize = 11.sp,
                                    color = BentoTextMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "The Linux daemon acts as a high-performance middleware service running as a systemd user unit. It handles mDNS (Avahi) discovery, TLS handshake with SAS PIN authorization, and bridges Wayland/X11 clipboard, MPRIS2 playerctl media, and D-Bus notifications.",
                            fontSize = 12.sp,
                            color = BentoTextBody,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // Quick Installation Script Card
            item {
                CodeSnippetCard(
                    title = "1. Arch Linux Dependencies",
                    code = "sudo pacman -S python wl-clipboard playerctl openssl ydotool libnotify",
                    onCopy = { viewModel.sendClipboard("sudo pacman -S python wl-clipboard playerctl openssl ydotool libnotify") }
                )
            }

            item {
                CodeSnippetCard(
                    title = "2. Systemd User Service Setup",
                    code = "mkdir -p ~/.config/systemd/user ~/.config/zohara-link\n# Copy daemon.py & zohara-linkd.service\nsystemctl --user daemon-reload\nsystemctl --user enable --now zohara-linkd.service",
                    onCopy = { viewModel.sendClipboard("systemctl --user enable --now zohara-linkd.service") }
                )
            }

            item {
                CodeSnippetCard(
                    title = "3. Custom GTK/Qt/Waybar D-Bus & IPC Query",
                    code = "# Connect to local Unix Domain Socket at:\n/run/user/\$UID/zohara.sock\n\n# Or query state via Python:\ns = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)\ns.connect('/run/user/1000/zohara.sock')\ns.send(b'{\"command\":\"GET_STATUS\"}\\n')",
                    onCopy = { viewModel.sendClipboard("echo '{\"command\":\"GET_STATUS\"}' | nc -U /run/user/1000/zohara.sock") }
                )
            }

            item {
                CodeSnippetCard(
                    title = "4. Waybar Custom Module",
                    code = "\"custom/zohara_battery\": {\n  \"format\": \" {}%\",\n  \"interval\": 10,\n  \"exec\": \"python3 ~/.config/zohara-link/gtk_settings_app.py --waybar\"\n}",
                    onCopy = { viewModel.sendClipboard("\"custom/zohara\": { \"format\": \" {}\" }") }
                )
            }
        } else {
            // P2P Zero-Cost Rust Mesh
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BentoBorder, RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = BentoCardWhite),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(BentoDarkNavy, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = Color(0xFF80D8FF),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Zohara OS Mesh (zoharad)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoDarkNavy
                                )
                                Text(
                                    text = "Dual LAN mDNS + WAN WebRTC / Render Bridge",
                                    fontSize = 11.sp,
                                    color = BentoTextMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "A zero-cost, zero-configuration P2P mesh network for Zohara OS nodes. Features automatic mTLS Ed25519 identity generation at /etc/zohara/identity.pem, local LAN mDNS discovery on _zohara-mesh._udp.local, and fallback to free public STUN + Render WebSocket signaling.",
                            fontSize = 12.sp,
                            color = BentoTextBody,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            item {
                CodeSnippetCard(
                    title = "1. Build & Install Rust Daemon (zoharad)",
                    code = "cd zoharad\ncargo build --release\nsudo install -Dm755 target/release/zoharad /usr/bin/zoharad\nsudo mkdir -p /etc/zohara",
                    onCopy = { viewModel.sendClipboard("cargo build --release && sudo install -Dm755 target/release/zoharad /usr/bin/zoharad") }
                )
            }

            item {
                CodeSnippetCard(
                    title = "2. Systemd Service Unit (/etc/systemd/system/zohara-mesh.service)",
                    code = "[Unit]\nDescription=Zohara OS Zero-Config P2P Mesh Network Daemon\nAfter=network-online.target systemd-resolved.service\nWants=network-online.target\n\n[Service]\nType=simple\nUser=root\nWorkingDirectory=/etc/zohara\nExecStart=/usr/bin/zoharad\nRestart=always\nRestartSec=3s\n\n[Install]\nWantedBy=multi-user.target",
                    onCopy = { viewModel.sendClipboard("sudo systemctl enable --now zohara-mesh.service") }
                )
            }

            item {
                CodeSnippetCard(
                    title = "3. Render WebSocket Signaling Node (<30MB RAM)",
                    code = "# Stateless signaling bridge with /ping keep-alive\ngit clone https://github.com/zohara-os/signaling-bridge\ncd signaling-bridge && npm install\nPORT=10000 node --max-old-space-size=40 server.js",
                    onCopy = { viewModel.sendClipboard("npm install && node --max-old-space-size=40 server.js") }
                )
            }
        }
    }
}

@Composable
fun CodeSnippetCard(
    title: String,
    code: String,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BentoBorder.copy(alpha = 0.7f), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = BentoCardWhite),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = BentoDarkNavy
                )

                Button(
                    onClick = onCopy,
                    colors = ButtonDefaults.buttonColors(containerColor = BentoSoftBlue, contentColor = BentoDarkNavy),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("copy_code_btn")
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BentoDarkNavy, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF80D8FF),
                    lineHeight = 17.sp
                )
            }
        }
    }
}

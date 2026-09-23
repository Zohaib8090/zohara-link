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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.ClipboardRecordEntity
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
import com.example.viewmodel.EcosystemViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ClipboardScreen(
    viewModel: EcosystemViewModel,
    modifier: Modifier = Modifier
) {
    val currentClipboard by viewModel.currentClipboard.collectAsStateWithLifecycle()
    val clipboardHistory by viewModel.clipboardHistory.collectAsStateWithLifecycle()
    var inputSnippet by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(BentoBg)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Status & Live Sync Banner
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BentoBorder, RoundedCornerShape(24.dp)),
                colors = CardDefaults.cardColors(containerColor = BentoCardWhite),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(BentoSoftBlue, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = BentoPrimaryBlue,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Bidirectional Clipboard Sync",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoDarkNavy
                                )
                                Text(
                                    text = "Wayland (wl-clipboard) / X11 ⟷ Android",
                                    fontSize = 11.sp,
                                    color = BentoTextMuted
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .background(BentoGreen.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("ACTIVE", color = BentoGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (currentClipboard.isNotBlank()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Last Synchronized Clip:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = BentoTextMuted
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BentoSlate, RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                text = currentClipboard,
                                style = MaterialTheme.typography.bodyMedium,
                                color = BentoTextBody,
                                maxLines = 4
                            )
                        }
                    }
                }
            }
        }

        // Instant Send Snippet Input
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BentoBorder, RoundedCornerShape(24.dp)),
                colors = CardDefaults.cardColors(containerColor = BentoCardWhite),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Push Text to Linux Desktop",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = BentoDarkNavy
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = inputSnippet,
                        onValueChange = { inputSnippet = it },
                        placeholder = { Text("Paste code snippet, URL, or note here...", fontSize = 13.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .testTag("clipboard_input_field"),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BentoPrimaryBlue,
                            unfocusedBorderColor = BentoBorder,
                            focusedContainerColor = BentoBg,
                            unfocusedContainerColor = BentoBg
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.sendClipboard(inputSnippet)
                                inputSnippet = ""
                            },
                            enabled = inputSnippet.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = BentoPrimaryBlue, contentColor = Color.White),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("send_clipboard_btn"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Push to Linux", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { viewModel.readLocalClipboard() },
                            colors = ButtonDefaults.buttonColors(containerColor = BentoSoftBlue, contentColor = BentoDarkNavy),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("read_clipboard_btn")
                        ) {
                            Icon(imageVector = Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Grab Device Clip", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Synced Clipboard History
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Clipboard History (${clipboardHistory.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BentoDarkNavy
                )

                if (clipboardHistory.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.clearClipboardHistory() },
                        modifier = Modifier.testTag("clear_clipboard_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear History", tint = BentoTextMuted)
                    }
                }
            }
        }

        if (clipboardHistory.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BentoSlate, RoundedCornerShape(18.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No synchronized clips yet. Any text copied on Android or Arch Linux will appear here automatically.",
                        fontSize = 12.sp,
                        color = BentoTextMuted
                    )
                }
            }
        } else {
            items(clipboardHistory) { clip ->
                ClipboardHistoryCard(
                    clip = clip,
                    onCopy = { viewModel.sendClipboard(clip.text) }
                )
            }
        }
    }
}

@Composable
fun ClipboardHistoryCard(
    clip: ClipboardRecordEntity,
    onCopy: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(clip.timestamp) { formatter.format(Date(clip.timestamp)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BentoBorder.copy(alpha = 0.7f), RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = BentoCardWhite),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            if (clip.source == "Linux") BentoDarkNavy else BentoSoftBlue,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "From ${clip.source}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (clip.source == "Linux") Color.White else BentoDarkNavy
                    )
                }

                Text(
                    text = timeStr,
                    fontSize = 11.sp,
                    color = BentoTextMuted
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = clip.text,
                style = MaterialTheme.typography.bodyMedium,
                color = BentoTextBody,
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp).testTag("copy_clip_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = BentoPrimaryBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}


package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.TransferRecordEntity
import com.example.ui.theme.AlertRed
import com.example.ui.theme.BentoBorder
import com.example.ui.theme.BentoCardWhite
import com.example.ui.theme.BentoDarkNavy
import com.example.ui.theme.BentoGreen
import com.example.ui.theme.BentoPrimaryBlue
import com.example.ui.theme.BentoSlate
import com.example.ui.theme.BentoSoftBlue
import com.example.ui.theme.BentoTextBody
import com.example.ui.theme.BentoTextMuted

@Composable
fun TransferItemCard(
    transfer: TransferRecordEntity,
    modifier: Modifier = Modifier
) {
    val progress = if (transfer.fileSize > 0) {
        (transfer.bytesTransferred.toFloat() / transfer.fileSize.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val (statusColor, containerBg, statusText) = when (transfer.status) {
        "COMPLETED" -> Triple(BentoGreen, BentoGreen.copy(alpha = 0.15f), "Completed")
        "TRANSFERRING" -> Triple(BentoPrimaryBlue, BentoSoftBlue, "Streaming ${(progress * 100).toInt()}%")
        "FAILED" -> Triple(AlertRed, Color(0xFFFFDAD6), "Failed")
        else -> Triple(BentoPrimaryBlue, BentoSlate, transfer.status)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, BentoBorder.copy(alpha = 0.7f), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = BentoCardWhite),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(if (transfer.isUpload) BentoDarkNavy else BentoPrimaryBlue, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (transfer.isUpload) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transfer.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextBody,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${formatBytes(transfer.bytesTransferred)} / ${formatBytes(transfer.fileSize)} • ${if (transfer.isUpload) "To Desktop" else "From Desktop"}",
                        fontSize = 11.sp,
                        color = BentoTextMuted
                    )
                }

                Box(
                    modifier = Modifier
                        .background(containerBg, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (transfer.status == "TRANSFERRING") {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = BentoPrimaryBlue,
                    trackColor = BentoSlate,
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}


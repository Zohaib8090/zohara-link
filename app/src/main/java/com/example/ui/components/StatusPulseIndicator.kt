package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.network.ConnectionState
import com.example.ui.theme.AlertRed
import com.example.ui.theme.BentoDarkNavy
import com.example.ui.theme.BentoGreen
import com.example.ui.theme.BentoPrimaryBlue
import com.example.ui.theme.BentoSlate
import com.example.ui.theme.BentoSoftBlue
import com.example.ui.theme.WarningAmber

@Composable
fun StatusPulseIndicator(
    state: ConnectionState,
    latencyMs: Long,
    modifier: Modifier = Modifier
) {
    val (statusColor, containerBg, statusText) = when (state) {
        is ConnectionState.Connected -> Triple(BentoPrimaryBlue, BentoSoftBlue, "CONNECTED")
        is ConnectionState.Connecting -> Triple(BentoPrimaryBlue, BentoSoftBlue, "CONNECTING")
        is ConnectionState.PairingRequired -> Triple(WarningAmber, BentoSlate, "PAIRING NEEDED")
        is ConnectionState.Error -> Triple(AlertRed, Color(0xFFFFDAD6), "ERROR")
        ConnectionState.Disconnected -> Triple(Color(0xFF74777F), BentoSlate, "OFFLINE")
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Row(
        modifier = modifier
            .background(containerBg, RoundedCornerShape(100.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .scale(pulseScale)
                        .background(statusColor.copy(alpha = 0.35f), CircleShape)
                )
            }
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(statusColor, CircleShape)
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = statusText,
            color = BentoDarkNavy,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )

        if (state is ConnectionState.Connected && latencyMs > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${latencyMs}ms",
                color = BentoPrimaryBlue,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}


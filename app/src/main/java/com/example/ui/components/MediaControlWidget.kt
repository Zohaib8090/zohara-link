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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MediaControlState
import com.example.ui.theme.BentoBorder
import com.example.ui.theme.BentoCardWhite
import com.example.ui.theme.BentoDarkNavy
import com.example.ui.theme.BentoPrimaryBlue
import com.example.ui.theme.BentoSlate
import com.example.ui.theme.BentoSoftBlue
import com.example.ui.theme.BentoTextMuted

@Composable
fun MediaControlWidget(
    mediaState: MediaControlState,
    onAction: (String) -> Unit,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var localVolume by remember { mutableFloatStateOf(mediaState.volume.toFloat()) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, BentoBorder, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = BentoCardWhite),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(BentoSoftBlue, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = BentoPrimaryBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (mediaState.title.isNotBlank()) mediaState.title else "Desktop Media Controller",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BentoDarkNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (mediaState.artist.isNotBlank()) mediaState.artist else "Linux MPRIS2 Playerctl Bridge",
                        fontSize = 12.sp,
                        color = BentoTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Playback Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = { onAction("PREVIOUS") },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = BentoSlate, contentColor = BentoDarkNavy),
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("media_prev_btn")
                ) {
                    Icon(imageVector = Icons.Default.FastRewind, contentDescription = "Previous Track")
                }

                Spacer(modifier = Modifier.width(16.dp))

                FilledIconButton(
                    onClick = { onAction("PLAY_PAUSE") },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = BentoPrimaryBlue, contentColor = Color.White),
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("media_play_pause_btn")
                ) {
                    Icon(
                        imageVector = if (mediaState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                FilledIconButton(
                    onClick = { onAction("NEXT") },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = BentoSlate, contentColor = BentoDarkNavy),
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("media_next_btn")
                ) {
                    Icon(imageVector = Icons.Default.FastForward, contentDescription = "Next Track")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Volume Control Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Volume",
                    tint = BentoPrimaryBlue,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = localVolume,
                    onValueChange = {
                        localVolume = it
                        onVolumeChange(it.toInt())
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("volume_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = BentoPrimaryBlue,
                        activeTrackColor = BentoPrimaryBlue,
                        inactiveTrackColor = BentoSlate
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${localVolume.toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoDarkNavy
                )
            }
        }
    }
}


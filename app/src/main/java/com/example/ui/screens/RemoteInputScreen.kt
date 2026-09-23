package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
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
fun RemoteInputScreen(
    viewModel: EcosystemViewModel,
    modifier: Modifier = Modifier
) {
    var keyboardInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BentoBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Trackpad Surface Card (Bento Rounded 28.dp)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, BentoBorder, RoundedCornerShape(28.dp)),
            colors = CardDefaults.cardColors(containerColor = BentoCardWhite),
            shape = RoundedCornerShape(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("virtual_trackpad_surface")
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            viewModel.sendInputMove(dragAmount.x * 1.5f, dragAmount.y * 1.5f)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { viewModel.sendInputClick("LEFT") },
                            onDoubleTap = { viewModel.sendInputClick("LEFT") },
                            onLongPress = { viewModel.sendInputClick("RIGHT") }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(BentoSoftBlue, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mouse,
                            contentDescription = null,
                            tint = BentoPrimaryBlue,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Wireless Multi-Touch Trackpad",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BentoDarkNavy
                    )
                    Text(
                        text = "• Slide finger to move Linux cursor\n• Tap for Left Click\n• Long-press for Right Click",
                        fontSize = 12.sp,
                        color = BentoTextMuted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Left & Right Mouse Click Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { viewModel.sendInputClick("LEFT") },
                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimaryBlue, contentColor = Color.White),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("left_click_btn"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Left Click", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Button(
                onClick = { viewModel.sendInputClick("RIGHT") },
                colors = ButtonDefaults.buttonColors(containerColor = BentoDarkNavy, contentColor = Color.White),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("right_click_btn"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Right Click", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        // Remote Keyboard Input & Desktop Actions
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BentoBorder, RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = BentoCardWhite),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Remote Keyboard Sender",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = BentoDarkNavy
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = keyboardInput,
                        onValueChange = { keyboardInput = it },
                        placeholder = { Text("Type text to send to Linux...", fontSize = 13.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("keyboard_text_input"),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BentoPrimaryBlue,
                            unfocusedBorderColor = BentoBorder,
                            focusedContainerColor = BentoBg,
                            unfocusedContainerColor = BentoBg
                        ),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            if (keyboardInput.isNotBlank()) {
                                viewModel.sendClipboard(keyboardInput)
                                keyboardInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimaryBlue, contentColor = Color.White),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("send_keystrokes_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Send, contentDescription = "Send Text")
                    }
                }
            }
        }
    }
}

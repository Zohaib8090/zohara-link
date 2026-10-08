package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AlertRed
import com.example.ui.theme.BentoBorder
import com.example.ui.theme.BentoCardWhite
import com.example.ui.theme.BentoDarkNavy
import com.example.ui.theme.BentoPrimaryBlue
import com.example.ui.theme.BentoSlate
import com.example.ui.theme.BentoSoftBlue
import com.example.ui.theme.BentoTextBody
import com.example.ui.theme.BentoTextMuted

/**
 * Pairing: the computer shows a 6-digit PIN in a notification (it is never sent to the phone, so nobody on the network
 * can read it), and the person types it here. The fingerprint below must match the code in that same notification: it
 * proves this phone is talking to that computer and not to something relaying the connection.
 */
@Composable
fun SasPairingDialog(
    fingerprint: String,
    hostName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(BentoSoftBlue, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = BentoPrimaryBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Pairing Authorization",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BentoDarkNavy
                )
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Type the 6-digit PIN shown on $hostName, and check that the code below is the same one it shows:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BentoTextMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Prominent PIN Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BentoSlate, RoundedCornerShape(18.dp))
                        .border(1.dp, BentoBorder, RoundedCornerShape(18.dp))
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = fingerprint,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = BentoPrimaryBlue,
                        letterSpacing = 3.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(6) },
                    label = { Text("PIN from the computer") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().testTag("pairing_pin_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pin) },
                enabled = pin.length == 6,
                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimaryBlue, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("confirm_pairing_button")
            ) {
                Text("Confirm & Pair", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("cancel_pairing_button")
            ) {
                Text("Reject", color = BentoTextMuted)
            }
        },
        containerColor = BentoCardWhite,
        shape = RoundedCornerShape(24.dp)
    )
}

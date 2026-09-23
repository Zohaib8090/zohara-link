package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.EcosystemSyncService
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.EcosystemViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: EcosystemViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start background ecosystem sync service
        try {
            EcosystemSyncService.start(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Handle incoming Android share intents (e.g., sharing file or text directly to Arch Linux)
        handleIncomingIntent(intent)

        setContent {
            val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
            val isAutoTheme by viewModel.isAutoTheme.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val effectiveDark = if (isAutoTheme) systemDark else isDarkTheme

            MyApplicationTheme(darkTheme = effectiveDark) {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if ("text/plain" == type) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    viewModel.sendClipboard(sharedText)
                    Toast.makeText(this, "Pushed text snippet to Linux desktop", Toast.LENGTH_SHORT).show()
                }
            } else {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    viewModel.sendFile(uri)
                    Toast.makeText(this, "Streaming file to Linux desktop...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}


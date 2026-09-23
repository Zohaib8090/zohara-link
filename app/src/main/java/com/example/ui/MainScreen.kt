package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.network.ConnectionState
import com.example.ui.components.StatusPulseIndicator
import com.example.ui.screens.ClipboardScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DiscoveryPairingScreen
import com.example.ui.screens.FileTransferScreen
import com.example.ui.screens.LinuxDaemonHubScreen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import com.example.ui.screens.NotificationMirrorScreen
import com.example.ui.screens.RemoteInputScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.BentoCardWhite
import com.example.ui.theme.BentoDarkNavy
import com.example.ui.theme.BentoPrimaryBlue
import com.example.ui.theme.BentoSlate
import com.example.ui.theme.BentoSoftBlue
import com.example.ui.theme.BentoTextMuted
import com.example.ui.theme.BentoTextPrimary
import com.example.viewmodel.EcosystemViewModel

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.material.icons.filled.Devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import com.example.ui.screens.WelcomeConnectScreen
import com.example.ui.theme.BentoAmber
import com.example.ui.theme.BentoGreen

enum class NavigationTab(val title: String, val icon: ImageVector) {
    DASHBOARD("Console", Icons.Default.Dashboard),
    DISCOVERY("Devices", Icons.Default.Devices),
    CLIPBOARD("Clips", Icons.Default.ContentCopy),
    TRANSFERS("Files", Icons.Default.Folder),
    TRACKPAD("Remote", Icons.Default.Mouse),
    LINUX_HUB("Daemon", Icons.Default.Terminal),
    SETTINGS("Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: EcosystemViewModel,
    modifier: Modifier = Modifier
) {
    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsStateWithLifecycle()
    val isDemoMode by viewModel.isDemoMode.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val latencyMs by viewModel.latencyMs.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()

    val activeTransfersCount = transfers.count { it.status == "TRANSFERRING" }

    // If initial connection / onboarding is not completed, show the dedicated Zohara OS setup screen
    if (!isOnboardingCompleted) {
        WelcomeConnectScreen(viewModel = viewModel, modifier = modifier)
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Zohara",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isDemoMode) "DEMO SANDBOX • ARCH LINUX" else "ARCH OS COMPANION",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                color = if (isDemoMode) BentoAmber else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        StatusPulseIndicator(
                            state = connectionState,
                            latencyMs = latencyMs,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        IconButton(
                            onClick = { selectedTab = NavigationTab.SETTINGS.ordinal },
                            modifier = Modifier.testTag("top_settings_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = if (selectedTab == NavigationTab.SETTINGS.ordinal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )

                // Demo Mode notification banner
                if (isDemoMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BentoAmber.copy(alpha = 0.12f))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = BentoAmber,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Demo Mode Active (Simulating Arch Linux)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }

                            Text(
                                text = "Connect Real OS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoPrimaryBlue,
                                modifier = Modifier
                                    .clickable { viewModel.resetToWelcomeScreen() }
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    NavigationTab.values().forEachIndexed { index, tab ->
                        val isSelected = selectedTab == index
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { selectedTab = index },
                            alwaysShowLabel = false,
                            icon = {
                                if (tab == NavigationTab.TRANSFERS && activeTransfersCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$activeTransfersCount") } }) {
                                        Icon(imageVector = tab.icon, contentDescription = tab.title)
                                    }
                                } else {
                                    Icon(imageVector = tab.icon, contentDescription = tab.title)
                                }
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    fontSize = 10.sp,
                                    letterSpacing = (-0.3).sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            when (NavigationTab.values()[selectedTab]) {
                NavigationTab.DASHBOARD -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToDiscovery = { selectedTab = NavigationTab.DISCOVERY.ordinal },
                    onNavigateToTrackpad = { selectedTab = NavigationTab.TRACKPAD.ordinal },
                    onNavigateToClipboard = { selectedTab = NavigationTab.CLIPBOARD.ordinal },
                    onNavigateToTransfers = { selectedTab = NavigationTab.TRANSFERS.ordinal }
                )
                NavigationTab.DISCOVERY -> DiscoveryPairingScreen(viewModel = viewModel)
                NavigationTab.CLIPBOARD -> ClipboardScreen(viewModel = viewModel)
                NavigationTab.TRANSFERS -> FileTransferScreen(viewModel = viewModel)
                NavigationTab.TRACKPAD -> RemoteInputScreen(viewModel = viewModel)
                NavigationTab.LINUX_HUB -> LinuxDaemonHubScreen(viewModel = viewModel)
                NavigationTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}


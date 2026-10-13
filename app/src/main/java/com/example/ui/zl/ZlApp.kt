package com.example.ui.zl

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.network.ConnectionState
import com.example.update.AppUpdater
import com.example.update.UpdateInfo
import com.example.viewmodel.EcosystemViewModel
import kotlinx.coroutines.launch

/**
 * The Zohara Link phone app: seven screens (see docs/ANDROID.md and the design canvas). This file holds the flow between them
 * and connects them to the ViewModel; the screens themselves are in ZlOnboarding.kt and ZlMain.kt.
 *
 * Flow: a phone that already knows a computer starts on Home and reconnects by itself (certificate pinned + token). Otherwise
 * Welcome -> Find -> Pair -> Home. Connection changes move the person along: asking to pair opens Pair, a finished pairing or
 * connection opens Home.
 */
@Composable
fun ZlApp(vm: EcosystemViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn by vm.connectionState.collectAsStateWithLifecycle()
    val paired by vm.pairedDevices.collectAsStateWithLifecycle()
    val discovered by vm.discoveredDevices.collectAsStateWithLifecycle()
    val scanning by vm.isScanning.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val clips by vm.clipboardHistory.collectAsStateWithLifecycle()
    val media by vm.mediaState.collectAsStateWithLifecycle()
    val alertsOn by vm.alertsEnabled.collectAsStateWithLifecycle()
    var mutedApps by remember { mutableStateOf(vm.mutedApps()) }

    var screen by rememberSaveable { mutableStateOf(ZlScreen.WELCOME) }
    var started by rememberSaveable { mutableStateOf(false) }
    var pairName by remember { mutableStateOf("your computer") }
    var pairCode by remember { mutableStateOf("") }
    var lastError by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // The app's own update (see AppUpdater): look once when the app opens, and again when the person asks.
    val installedBuild = remember { AppUpdater.installed(context) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateLine by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }

    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    suspend fun lookForUpdate(announce: Boolean) {
        val info = AppUpdater.check()
        update = info?.takeIf { it.versionCode > installedBuild.first }
        updateLine = when {
            info == null -> "Could not reach the update server. Try again when you are online."
            update != null -> "Version ${info.versionName} is ready (you have ${installedBuild.second})."
            else -> "You have the newest version."
        }
        if (announce) say(updateLine)
    }

    fun startUpdate() {
        val info = update ?: return
        if (updating) return
        if (!AppUpdater.canInstall(context)) {
            say("Allow Zohara Link to install apps, then press Update again.")
            AppUpdater.openInstallPermission(context)
            return
        }
        scope.launch {
            updating = true
            updateLine = "Downloading…"
            val apk = AppUpdater.download(context, info) { pct -> updateLine = if (pct >= 0) "Downloading… $pct%" else "Downloading…" }
            updating = false
            if (apk == null) {
                updateLine = "The download did not finish. Try again."
                say(updateLine)
            } else {
                updateLine = "Ready to install: confirm on the next screen."
                AppUpdater.install(context, apk)
            }
        }
    }

    LaunchedEffect(Unit) { lookForUpdate(announce = false) }

    val knownComputer = paired.maxByOrNull { it.lastSeenTimestamp }

    // A phone that already knows a computer starts on Home and reconnects by itself.
    LaunchedEffect(knownComputer?.deviceId) {
        val d = knownComputer
        if (!started && d != null) {
            started = true
            screen = ZlScreen.HOME
            if (conn !is ConnectionState.Connected) vm.connectToHost(d.hostAddress, d.port, true)
        }
    }

    LaunchedEffect(conn) {
        when (val c = conn) {
            is ConnectionState.PairingRequired -> {
                pairCode = c.fingerprint
                pairName = c.host
                lastError = null
                screen = ZlScreen.PAIR
            }
            is ConnectionState.Connected -> {
                started = true
                lastError = null
                if (screen == ZlScreen.WELCOME || screen == ZlScreen.FIND || screen == ZlScreen.PAIR) screen = ZlScreen.HOME
            }
            is ConnectionState.Error -> {
                lastError = c.message
                if (screen != ZlScreen.FIND && screen != ZlScreen.PAIR) say(c.message)
            }
            else -> Unit
        }
    }

    // Messages from the computer, such as "remote input is not allowed for this phone".
    LaunchedEffect(Unit) {
        vm.notices.collect { msg ->
            say(
                if (msg.contains("not allowed")) "The computer has not allowed this. Turn it on in Settings > Zohara Link on the computer."
                else msg
            )
        }
    }

    // Look for computers only while the Find screen is open.
    DisposableEffect(screen == ZlScreen.FIND) {
        if (screen == ZlScreen.FIND) vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }

    // Automatic discovery (mDNS) is blocked on some Wi-Fi networks: if nothing turned up after a few seconds, scan the Wi-Fi instead.
    LaunchedEffect(screen == ZlScreen.FIND) {
        if (screen == ZlScreen.FIND) {
            kotlinx.coroutines.delay(5000)
            if (discovered.isEmpty()) vm.scanNetwork()
        }
    }

    BackHandler(enabled = screen != ZlScreen.HOME && screen != ZlScreen.WELCOME) {
        screen = when (screen) {
            ZlScreen.FIND -> ZlScreen.WELCOME
            ZlScreen.PAIR -> {
                vm.disconnect()
                ZlScreen.FIND
            }
            ZlScreen.DESKTOP -> ZlScreen.REMOTE
            else -> ZlScreen.HOME
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { vm.sendFile(it) }
        if (uris.isNotEmpty()) say("Sending ${uris.size} file(s)")
    }

    // Notification access can be changed in Android's settings and come back to us: look again on resume.
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val notificationAccess = remember(resumes) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    val connected = conn is ConnectionState.Connected
    val connecting = conn is ConnectionState.Connecting
    val computerName = (conn as? ConnectionState.Connected)?.serverName ?: knownComputer?.deviceName ?: "your computer"

    ZlTheme {
        Box(Modifier.fillMaxSize().background(Zl.bg)) {
            when (screen) {
                ZlScreen.WELCOME -> WelcomeScreen(onStart = { lastError = null; screen = ZlScreen.FIND })
                ZlScreen.FIND -> FindScreen(
                    devices = discovered,
                    scanning = scanning,
                    connecting = connecting,
                    error = lastError,
                    onPick = { d -> lastError = null; vm.connectToHost(d.hostAddress, d.port, false) },
                    onManual = { host -> lastError = null; vm.connectToHost(host, 42424, false) },
                    onScan = { vm.scanNetwork() },
                    onBack = { screen = ZlScreen.WELCOME },
                )
                ZlScreen.PAIR -> PairScreen(
                    computerName = pairName,
                    fingerprint = pairCode,
                    error = lastError,
                    onConfirm = { pin -> lastError = null; vm.confirmPairing(pin) },
                    onCancel = { vm.disconnect(); screen = ZlScreen.FIND },
                )
                ZlScreen.DESKTOP -> {
                    val frame by vm.screenFrame.collectAsStateWithLifecycle()
                    val problem by vm.screenProblem.collectAsStateWithLifecycle()
                    DesktopScreen(
                        computerName = computerName,
                        connected = connected,
                        frame = frame,
                        problem = problem,
                        onStart = { vm.startScreen() },
                        onStop = { vm.stopScreen() },
                        onTap = { x, y, right -> vm.sendInputMoveTo(x, y); vm.sendInputClick(if (right) "RIGHT" else "LEFT") },
                        onScroll = { vm.sendInputScroll(it) },
                        onType = { vm.sendInputType(it) },
                        onKey = { vm.sendInputKey(it) },
                        onBack = { screen = ZlScreen.REMOTE },
                    )
                }
                else -> Scaffold(
                    containerColor = Zl.bg,
                    bottomBar = { ZlBottomBar(current = screen, onGo = { screen = it }) },
                ) { inner ->
                    Box(Modifier.padding(inner).statusBarsPadding()) {
                        when (screen) {
                            ZlScreen.HOME -> HomeScreen(
                                computerName = computerName,
                                connected = connected,
                                connecting = connecting,
                                code = vm.computerCode(),
                                alertsOn = alertsOn,
                                events = remember(transfers, clips) {
                                    (transfers.map { RecentEvent((if (it.isUpload) "Sent " else "Received ") + it.fileName, it.timestamp) } +
                                        clips.map { RecentEvent(if (it.source == "Android") "Clipboard sent to the computer" else "Clipboard from the computer", it.timestamp) })
                                        .sortedByDescending { it.time }.take(3)
                                },
                                onReconnect = { knownComputer?.let { vm.connectToHost(it.hostAddress, it.port, true) } },
                                onSendClipboard = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                    if (text.isNullOrBlank()) say("Nothing copied yet")
                                    else if (!connected) say("Not connected to the computer")
                                    else { vm.sendClipboard(text); say("Clipboard sent") }
                                },
                                onFiles = { screen = ZlScreen.FILES },
                                onRemote = { screen = ZlScreen.REMOTE },
                                onLock = { if (connected) vm.sendLockDesktop() else say("Not connected to the computer") },
                                onAlerts = { vm.setAlertsEnabled(it) },
                                updateText = update?.let { if (updating) updateLine else "Version ${it.versionName} (you have ${installedBuild.second})" },
                                onUpdate = { startUpdate() },
                            )
                            ZlScreen.FILES -> FilesScreen(
                                computerName = computerName,
                                transfers = transfers,
                                onChoose = { if (connected) picker.launch("*/*") else say("Not connected to the computer") },
                            )
                            ZlScreen.REMOTE -> RemoteScreen(
                                computerName = computerName,
                                connected = connected,
                                media = media,
                                onMove = { dx, dy -> vm.sendInputMove(dx, dy) },
                                onClick = { vm.sendInputClick(it) },
                                onMedia = { vm.sendMediaAction(it) },
                                onDesktop = { screen = ZlScreen.DESKTOP },
                            )
                            else -> AccessScreen(
                                computerName = computerName,
                                code = vm.computerCode(),
                                alertsOn = alertsOn,
                                notificationAccessGranted = notificationAccess,
                                seenApps = { vm.seenApps() },
                                mutedApps = mutedApps,
                                onSetMuted = { pkg, muted -> vm.setAppMuted(pkg, muted); mutedApps = vm.mutedApps() },
                                onAlerts = { vm.setAlertsEnabled(it) },
                                versionLabel = "Version ${installedBuild.second} (build ${installedBuild.first})",
                                updateLine = updateLine,
                                onCheckUpdate = { scope.launch { updateLine = "Checking…"; lookForUpdate(announce = true) } },
                                onOpenNotificationAccess = {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                },
                                onForget = {
                                    knownComputer?.let { vm.forgetComputer(it.deviceId) } ?: vm.disconnect()
                                    screen = ZlScreen.WELCOME
                                },
                            )
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
    }
}

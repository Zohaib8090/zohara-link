package com.example.ui.zl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Zohara Link look, in dark and light. Every colour reads the current mode, so a screen that uses `Zl.text` and friends
 * changes with the phone's setting (or the person's choice in Access) without any change of its own.
 */
object Zl {
    /** "system" (follow the phone), "dark" or "light". */
    var mode by mutableStateOf("system")
    var systemDark by mutableStateOf(true)
    val dark: Boolean get() = when (mode) { "dark" -> true; "light" -> false; else -> systemDark }

    private fun pick(d: Long, l: Long) = Color(if (dark) d else l)

    val bg get() = pick(0xFF07090D, 0xFFF6F4EE)
    val card get() = pick(0xFF11161E, 0xFFFFFFFF)
    val cardAlt get() = pick(0xFF0D1218, 0xFFF0EEE6)
    val line get() = pick(0xFF202833, 0xFFE0DDD2)
    val lineStrong get() = pick(0xFF2D3745, 0xFFCBC7BA)
    val text get() = pick(0xFFEAF0F6, 0xFF1B1F24)
    val muted get() = pick(0xFF8C99AA, 0xFF5E6A78)
    val accent get() = pick(0xFF5FD4D4, 0xFF0B7F80)
    val accentInk get() = pick(0xFF06181A, 0xFFFFFFFF)
    val accentSoft get() = pick(0xFF12303A, 0xFFD9EDEC)
    val ok get() = pick(0xFF7BE0A8, 0xFF17814F)
    val danger get() = pick(0xFFFFB4AB, 0xFFB3261E)
    val dangerBg get() = pick(0xFF2A1719, 0xFFFBE3E0)
    val bar get() = pick(0xFF121821, 0xFFFFFFFF)

    private const val FILE = "zohara_link_ui"

    /** Reads the saved choice and what the phone is set to; called once when the app opens. */
    fun load(context: android.content.Context) {
        mode = context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getString("theme", "system") ?: "system"
        val night = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        systemDark = night != android.content.res.Configuration.UI_MODE_NIGHT_NO
    }

    fun choose(context: android.content.Context, m: String) {
        mode = m
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putString("theme", m).apply()
    }
}

enum class ZlScreen { WELCOME, FIND, PAIR, HOME, FILES, REMOTE, DESKTOP, GAMEPAD, STREAM, ZOAI, ACCESS }

@Composable
fun ZlTheme(content: @Composable () -> Unit) {
    val system = androidx.compose.foundation.isSystemInDarkTheme()
    androidx.compose.runtime.SideEffect { if (Zl.systemDark != system) Zl.systemDark = system }
    val dark = when (Zl.mode) { "dark" -> true; "light" -> false; else -> system }
    // the status bar icons follow the look: light icons on dark, dark icons on light
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
    }
    val scheme = if (dark) {
        darkColorScheme(primary = Zl.accent, onPrimary = Zl.accentInk, background = Zl.bg, onBackground = Zl.text, surface = Zl.card, onSurface = Zl.text, error = Zl.danger)
    } else {
        androidx.compose.material3.lightColorScheme(primary = Zl.accent, onPrimary = Zl.accentInk, background = Zl.bg, onBackground = Zl.text, surface = Zl.card, onSurface = Zl.text, error = Zl.danger)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) Zl.accent else Zl.line)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Zl.accentInk else Zl.muted, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .border(BorderStroke(1.dp, if (danger) Color(0xFF5B2A2E) else Zl.lineStrong), shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (danger) Zl.danger else Zl.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = Zl.muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
    )
}

@Composable
fun ZlCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Zl.card)
            .border(1.dp, Zl.line, shape),
        content = content,
    )
}

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tile: Int = 44, tint: Color = Zl.accent, background: Color = Zl.card) {
    Box(
        modifier
            .size(tile.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, Zl.line, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((tile / 2).dp))
    }
}

/** A row with a title, a smaller line under it and a switch, at least 48dp tall. */
@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Zl.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Zl.accentInk,
                checkedTrackColor = Zl.accent,
                uncheckedThumbColor = Zl.muted,
                uncheckedTrackColor = Zl.card,
                uncheckedBorderColor = Zl.lineStrong,
            ),
        )
    }
}

private data class NavItem(val screen: ZlScreen, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(ZlScreen.HOME, "Home", Icons.Filled.Home),
    NavItem(ZlScreen.FILES, "Files", Icons.Filled.Folder),
    NavItem(ZlScreen.REMOTE, "Remote", Icons.Filled.Computer),
    NavItem(ZlScreen.ZOAI, "ZoAI", Icons.Filled.AutoAwesome),
    NavItem(ZlScreen.ACCESS, "Access", Icons.Filled.Shield),
)

@Composable
fun ZlBottomBar(current: ZlScreen, onGo: (ZlScreen) -> Unit) {
    // a floating bar: rounded, with a hairline border, and a soft pill behind the current tab
    val shape = RoundedCornerShape(26.dp)
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp).clip(shape).background(Zl.bar).border(1.dp, Zl.line, shape),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navItems.forEach { item ->
                val on = item.screen == current
                Column(
                    Modifier.width(62.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Tab) { onGo(item.screen) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(Modifier.width(44.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Zl.accentSoft else Color.Transparent), contentAlignment = Alignment.Center) {
                        Icon(item.icon, contentDescription = null, tint = if (on) Zl.accent else Zl.muted, modifier = Modifier.size(21.dp))
                    }
                    Text(item.label, color = if (on) Zl.accent else Zl.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Zohara Link look: the dark navy and teal of the Zohara logo. Colours are the ones in the design canvas. */
object Zl {
    val bg = Color(0xFF0B0E14)
    val card = Color(0xFF141A24)
    val cardAlt = Color(0xFF101620)
    val line = Color(0xFF232C3A)
    val lineStrong = Color(0xFF2F3A4B)
    val text = Color(0xFFE8ECF1)
    val muted = Color(0xFF9AA6B5)
    val accent = Color(0xFF5FD4D4)
    val accentInk = Color(0xFF06181A)
    val accentSoft = Color(0xFF16303A)
    val ok = Color(0xFF7BE0A8)
    val danger = Color(0xFFFFB4AB)
    val dangerBg = Color(0xFF2A1719)
    val bar = Color(0xFF0F141C)
}

enum class ZlScreen { WELCOME, FIND, PAIR, HOME, FILES, REMOTE, DESKTOP, GAMEPAD, ACCESS }

@Composable
fun ZlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Zl.accent,
            onPrimary = Zl.accentInk,
            background = Zl.bg,
            onBackground = Zl.text,
            surface = Zl.card,
            onSurface = Zl.text,
            error = Zl.danger,
        ),
        content = content,
    )
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
    NavItem(ZlScreen.ACCESS, "Access", Icons.Filled.Shield),
)

@Composable
fun ZlBottomBar(current: ZlScreen, onGo: (ZlScreen) -> Unit) {
    NavigationBar(containerColor = Zl.bar) {
        navItems.forEach { item ->
            NavigationBarItem(
                selected = item.screen == current,
                onClick = { onGo(item.screen) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Zl.accent,
                    selectedTextColor = Zl.accent,
                    indicatorColor = Zl.accentSoft,
                    unselectedIconColor = Zl.muted,
                    unselectedTextColor = Zl.muted,
                ),
            )
        }
    }
}

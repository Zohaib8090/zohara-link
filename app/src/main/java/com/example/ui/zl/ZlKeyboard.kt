package com.example.ui.zl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Linux key codes (the computer's own numbering) for the keys that are sent by code.
private const val CTRL = 29
private const val SHIFT = 42
private const val ALT = 56
private const val SUPER = 125
private val LETTERS = mapOf(
    'q' to 16, 'w' to 17, 'e' to 18, 'r' to 19, 't' to 20, 'y' to 21, 'u' to 22, 'i' to 23, 'o' to 24, 'p' to 25,
    'a' to 30, 's' to 31, 'd' to 32, 'f' to 33, 'g' to 34, 'h' to 35, 'j' to 36, 'k' to 37, 'l' to 38,
    'z' to 44, 'x' to 45, 'c' to 46, 'v' to 47, 'b' to 48, 'n' to 49, 'm' to 50,
    '1' to 2, '2' to 3, '3' to 4, '4' to 5, '5' to 6, '6' to 7, '7' to 8, '8' to 9, '9' to 10, '0' to 11,
)
private val FKEYS = listOf(59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88)

/**
 * The keyboard screen: the phone's own keyboard (whatever the person uses: swipe typing, predictions, their language) types
 * on the computer, and a strip of the keys a phone keyboard lacks sits right above it: Esc, Tab, Ctrl, Alt, Super, the arrows,
 * F1 to F12 and the common shortcuts. Ctrl, Alt, Super and Shift are one-shot: tap one, then a key of the phone's keyboard.
 */
@Composable
fun DesktopKeyboardScreen(
    computerName: String,
    connected: Boolean,
    onType: (String) -> Unit,
    onKey: (String) -> Unit,
    onKeyCode: (Int, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var sup by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    fun armed(): List<Int> = buildList {
        if (ctrl) add(CTRL)
        if (alt) add(ALT)
        if (sup) add(SUPER)
        if (shift) add(SHIFT)
    }

    fun disarm() { ctrl = false; alt = false; sup = false; shift = false }

    fun combo(mods: List<Int>, key: Int) {
        mods.forEach { onKeyCode(it, true) }
        onKeyCode(key, true)
        onKeyCode(key, false)
        mods.reversed().forEach { onKeyCode(it, false) }
    }

    /** What the phone's keyboard changed in the box: typed text goes on as text, deleted text as backspaces. */
    fun changed(new: String) {
        val old = text
        text = new
        if (new.length > old.length && new.startsWith(old)) {
            val added = new.substring(old.length)
            val mods = armed()
            if (mods.isNotEmpty() && added.length == 1 && LETTERS.containsKey(added[0].lowercaseChar())) {
                combo(mods, LETTERS.getValue(added[0].lowercaseChar()))
                disarm()
                text = old
            } else {
                onType(added)
            }
        } else if (new.length < old.length && old.startsWith(new)) {
            repeat(old.length - new.length) { onKey("BACKSPACE") }
        } else if (new != old) {
            // a correction in the middle (the keyboard replaced a word): retype it after clearing the difference
            val common = old.commonPrefixWith(new).length
            repeat(old.length - common) { onKey("BACKSPACE") }
            if (new.length > common) onType(new.substring(common))
        }
        if (text.length > 400) text = ""
    }

    Column(Modifier.fillMaxSize().background(Zl.bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { onClose() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Zl.text)
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text("Keyboard", color = Zl.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (connected) "Types on $computerName" else "Not connected", color = Zl.muted, fontSize = 12.sp)
            }
        }
        // what is being typed (it is sent as it is typed)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).clip(RoundedCornerShape(24.dp)).background(Zl.card)
                .border(1.dp, Zl.line, RoundedCornerShape(24.dp)).padding(20.dp),
        ) {
            Text("SENDS AS YOU TYPE", color = Zl.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)
            Box(Modifier.padding(top = 10.dp)) {
                if (text.isEmpty()) Text("Type here, or press a shortcut below", color = Zl.muted, fontSize = 18.sp)
                BasicTextField(
                    value = text,
                    onValueChange = { changed(it) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = TextStyle(color = Zl.text, fontSize = 19.sp, lineHeight = 28.sp),
                    cursorBrush = SolidColor(Zl.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        val mods = armed()
                        if (mods.isNotEmpty()) { combo(mods, 28); disarm() } else onKey("ENTER")
                    }),
                )
            }
        }
        // the keys a phone keyboard lacks, right above it
        Column(Modifier.fillMaxWidth().background(Zl.bar).border(1.dp, Zl.line).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StripKey("Esc") { onKey("ESCAPE") }
                StripKey("Tab") { onKey("TAB") }
                StripKey("Ctrl", on = ctrl) { ctrl = !ctrl }
                StripKey("Alt", on = alt) { alt = !alt }
                StripKey("Super", on = sup) { sup = !sup }
                StripKey("Shift", on = shift) { shift = !shift }
                Box(Modifier.width(1.dp).height(24.dp).align(Alignment.CenterVertically).background(Zl.line))
                StripIcon(Icons.Filled.ChevronLeft) { onKey("LEFT") }
                StripIcon(Icons.Filled.ArrowUpward) { onKey("UP") }
                StripIcon(Icons.Filled.ArrowDownward) { onKey("DOWN") }
                StripIcon(Icons.Filled.ChevronRight) { onKey("RIGHT") }
                Box(Modifier.width(1.dp).height(24.dp).align(Alignment.CenterVertically).background(Zl.line))
                StripKey("Home") { onKey("HOME") }
                StripKey("End") { onKey("END") }
                StripKey("PgUp") { onKey("PAGE_UP") }
                StripKey("PgDn") { onKey("PAGE_DOWN") }
                StripKey("Del") { onKey("DELETE") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FKEYS.forEachIndexed { i, code -> StripKey("F${i + 1}", small = true) { combo(armed(), code); disarm() } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Shortcut("Ctrl+C") { combo(listOf(CTRL), 46) }
                Shortcut("Ctrl+V") { combo(listOf(CTRL), 47) }
                Shortcut("Ctrl+Z") { combo(listOf(CTRL), 44) }
                Shortcut("Ctrl+S") { combo(listOf(CTRL), 31) }
                Shortcut("Alt+Tab") { combo(listOf(ALT), 15) }
                Shortcut("Ctrl+Alt+T") { combo(listOf(CTRL, ALT), 20) }
                Shortcut("Super+D") { combo(listOf(SUPER), 32) }
            }
        }
    }
}

@Composable
private fun StripKey(label: String, on: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier.height(if (small) 32.dp else 38.dp).widthIn(min = if (small) 42.dp else 46.dp).clip(shape)
            .background(if (on) Zl.accent else Zl.card).border(1.dp, if (on) Zl.accent else Zl.line, shape).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Zl.accentInk else Zl.muted, fontSize = if (small) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun StripIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier.height(38.dp).widthIn(min = 46.dp).clip(shape).background(Zl.card).border(1.dp, Zl.line, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Zl.muted, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun Shortcut(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier.height(36.dp).clip(shape).background(Zl.accentSoft).border(1.dp, Zl.line, shape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Zl.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}

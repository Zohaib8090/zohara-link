package com.example.ui.zl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * ZoAI: chat with ZO-AI on the computer from the phone. Everything runs on the computer (its model, its tools, its MCP servers);
 * the phone shows the conversation and answers its questions. The computer must allow it for this phone first
 * (Settings > Zohara Link > the phone > ZO-AI), and the daemon refuses anything else.
 *
 * Wire: `ZOAI_REQ {rid, op, ...}` out; `ZOAI_EVT {rid, data}` and a final `ZOAI_END {rid, error?}` back; `ZOAI_LINE {rid, op: approve|stop}`
 * while a message is being answered. See docs/ZOAI.md.
 */

/** One thing shown in the conversation. kind: user, ai, tool, toolout, error, note. */
class ZoLine(val kind: String, var text: String)

class ZoChatInfo(val id: String, val title: String, val pinned: Boolean)

class ZoApproval(val id: Long, val rid: String, val summary: String)

class ZoServer(val name: String, val command: String, val enabled: Boolean)

/** Holds the state of the ZoAI tab and talks to the computer. One per app run. */
class ZoAiController(private val send: (JSONObject) -> Unit) {
    val lines = mutableStateListOf<ZoLine>()
    val chats = mutableStateListOf<ZoChatInfo>()
    val servers = mutableStateListOf<ZoServer>()
    val models = mutableStateListOf<String>()
    var chatId by mutableStateOf<String?>(null)
    var title by mutableStateOf("New chat")
    var busy by mutableStateOf(false)
    var provider by mutableStateOf("")
    var model by mutableStateOf("")
    var mode by mutableStateOf("Auto")
    var problem by mutableStateOf<String?>(null)
    var approval by mutableStateOf<ZoApproval?>(null)
    var message by mutableStateOf<String?>(null)

    private var nextRid = 1
    private val handlers = HashMap<String, (JSONObject) -> Unit>()
    private val enders = HashMap<String, (String?) -> Unit>()
    private var sendRid: String? = null
    /** The AI's text for the step being written (streamed in pieces, replaced by the whole text at the end). */
    private var streaming: ZoLine? = null

    private fun request(fields: JSONObject, onEvent: (JSONObject) -> Unit = {}, onEnd: (String?) -> Unit = {}): String {
        val rid = "r" + (nextRid++)
        fields.put("type", "ZOAI_REQ")
        fields.put("rid", rid)
        handlers[rid] = onEvent
        enders[rid] = onEnd
        send(fields)
        return rid
    }

    /** Called with every ZOAI_EVT / ZOAI_END the computer sends. */
    fun onPacket(p: JSONObject) {
        val rid = p.optString("rid")
        if (p.optString("type") == "ZOAI_END") {
            val err = if (p.has("error")) p.optString("error") else null
            handlers.remove(rid)
            enders.remove(rid)?.invoke(err)
            return
        }
        val data = p.optJSONObject("data") ?: return
        handlers[rid]?.invoke(data)
    }

    private fun fail(why: String?) {
        if (why == null) return
        problem = why
        ready = false
    }

    /** True once the computer has answered a status request: ZO-AI there is reachable and this phone is allowed. */
    var ready by mutableStateOf(false)
    private var statusRid: String? = null

    fun refresh() {
        problem = null
        ready = false
        val rid = request(JSONObject().put("op", "status"), { d ->
            if (d.optBoolean("ok")) {
                ready = true
                problem = null
                provider = d.optString("provider")
                model = d.optString("model")
                mode = d.optString("mode", "Auto")
            }
        }, { fail(it) })
        statusRid = rid
        // an older computer-side program ignores ZoAI messages without answering: say what to do instead of waiting for ever
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!ready && problem == null && statusRid == rid) {
                problem = "The computer did not answer. On the computer: update Zohara Settings and Zohara Link (Settings > Zohara Update), open ZO-AI once, then switch on Settings > Zohara Link > this phone > Use ZO-AI."
            }
        }, 7000)
        loadChats()
    }

    fun loadChats() {
        request(JSONObject().put("op", "chats"), { d ->
            val a = d.optJSONArray("chats") ?: JSONArray()
            chats.clear()
            for (i in 0 until a.length()) {
                val c = a.getJSONObject(i)
                chats.add(ZoChatInfo(c.optString("id"), c.optString("title"), c.optBoolean("pinned")))
            }
        })
    }

    fun newChat() {
        if (busy) return
        chatId = null
        title = "New chat"
        lines.clear()
    }

    fun openChat(id: String) {
        if (busy) return
        request(JSONObject().put("op", "chat").put("id", id), { d ->
            if (!d.optBoolean("ok")) {
                message = d.optString("error", "That chat is gone.")
                return@request
            }
            chatId = d.optString("id")
            title = d.optString("title")
            lines.clear()
            val a = d.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                val kind = when (e.optString("kind")) {
                    "msg-user" -> "user"
                    "msg-ai" -> "ai"
                    "msg-tool" -> "tool"
                    "msg-tool-out" -> "toolout"
                    "msg-error" -> "error"
                    else -> "note"
                }
                lines.add(ZoLine(kind, e.optString("text")))
            }
        }, { fail(it) })
    }

    fun deleteChat(id: String) {
        request(JSONObject().put("op", "delete_chat").put("id", id), {}, { loadChats() })
        if (chatId == id) newChat()
        chats.removeAll { it.id == id }
    }

    fun sendMessage(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        problem = null
        lines.add(ZoLine("user", t))
        streaming = null
        busy = true
        val req = JSONObject().put("op", "send").put("text", t)
        chatId?.let { req.put("chat", it) }
        sendRid = request(req, { d -> onSendEvent(d) }, { err ->
            busy = false
            sendRid = null
            streaming = null
            approval = null
            if (err != null) lines.add(ZoLine("error", err))
            loadChats()
        })
    }

    private fun onSendEvent(d: JSONObject) {
        when (d.optString("event")) {
            "chat" -> {
                chatId = d.optString("id")
                title = d.optString("title", title)
            }
            "delta" -> {
                val s = streaming ?: ZoLine("ai", "").also { lines.add(it); streaming = it }
                s.text += d.optString("text")
                bump()
            }
            "text" -> {
                val s = streaming
                if (s != null) {
                    s.text = d.optString("text")
                    streaming = null
                    bump()
                } else {
                    lines.add(ZoLine("ai", d.optString("text")))
                }
            }
            "tool_start" -> {
                streaming = null
                val summary = d.optString("summary")
                lines.add(ZoLine("tool", d.optString("name") + "  " + (summary.lines().getOrNull(1) ?: "")))
            }
            "tool_done" -> {
                val out = d.optString("output").take(400)
                lines.add(ZoLine(if (d.optBoolean("ok")) "toolout" else "error", (if (d.optBoolean("ok")) "✓ " else "✗ ") + out))
            }
            "approval" -> approval = ZoApproval(d.optLong("id"), sendRid ?: "", d.optString("summary"))
            "error" -> lines.add(ZoLine("error", d.optString("error")))
            "stopped" -> lines.add(ZoLine("note", "Stopped."))
        }
    }

    /** Makes the list redraw while the last line grows (a Compose list notices a replaced item, not a changed field). */
    private fun bump() {
        val i = lines.size - 1
        if (i >= 0) lines[i] = lines[i].let { ZoLine(it.kind, it.text).also { n -> if (streaming === it) streaming = n } }
    }

    fun answer(yes: Boolean) {
        val a = approval ?: return
        approval = null
        send(JSONObject().put("type", "ZOAI_LINE").put("rid", a.rid).put("op", "approve").put("id", a.id).put("yes", yes))
    }

    fun stop() {
        val rid = sendRid ?: return
        send(JSONObject().put("type", "ZOAI_LINE").put("rid", rid).put("op", "stop"))
    }

    fun changeMode(m: String) {
        request(JSONObject().put("op", "mode").put("set", m.lowercase()), { d -> if (d.optBoolean("ok")) mode = d.optString("mode", m) else message = d.optString("error") })
    }

    fun loadModels() {
        request(JSONObject().put("op", "models"), { d ->
            if (!d.optBoolean("ok")) {
                message = d.optString("error", "Could not list the models.")
                return@request
            }
            models.clear()
            val a = d.optJSONArray("models") ?: JSONArray()
            for (i in 0 until a.length()) models.add(a.optString(i))
            model = d.optString("current", model)
        })
    }

    fun chooseModel(m: String) {
        request(JSONObject().put("op", "select").put("model", m), { d -> if (d.optBoolean("ok")) model = d.optString("model") else message = d.optString("error") })
    }

    fun loadServers() {
        request(JSONObject().put("op", "mcp_list"), { d ->
            servers.clear()
            val a = d.optJSONArray("servers") ?: JSONArray()
            for (i in 0 until a.length()) {
                val s = a.getJSONObject(i)
                servers.add(ZoServer(s.optString("name"), s.optString("command"), s.optBoolean("enabled")))
            }
        })
    }

    /** Adds an MCP server by address (https://… or a command). If it needs a sign-in, the computer opens its browser. */
    fun addServer(name: String, address: String) {
        message = "Adding $name… (if it needs a sign-in, look at the computer)"
        request(JSONObject().put("op", "mcp_add").put("name", name).put("address", address), { d ->
            message = if (d.optBoolean("ok")) d.optString("message", "Added.") else d.optString("error", "Could not add it.")
            loadServers()
        })
    }

    fun importServers(json: String) {
        message = "Adding the servers…"
        request(JSONObject().put("op", "mcp_import").put("config", json), { d ->
            message = if (d.optBoolean("ok")) d.optString("message", "Added.") else d.optString("error", "Could not add them.")
            loadServers()
        })
    }

    fun removeServer(name: String) {
        request(JSONObject().put("op", "mcp_remove").put("name", name), { d -> if (!d.optBoolean("ok")) message = d.optString("error") }, { loadServers() })
    }

    fun toggleServer(name: String, on: Boolean) {
        request(JSONObject().put("op", "mcp_toggle").put("name", name).put("enabled", on), {}, { loadServers() })
    }
}

private enum class ZoPage { CHAT, CHATS, SETTINGS }

private val Suggestions = listOf(
    "Take a screenshot and tell me what is open",
    "How full is my disk?",
    "Open a terminal and show what is running",
)

@Composable
fun ZoAiScreen(
    computerName: String,
    connected: Boolean,
    controller: ZoAiController,
    packets: SharedFlow<JSONObject>,
) {
    var page by remember { mutableStateOf(ZoPage.CHAT) }

    LaunchedEffect(Unit) { packets.collect { controller.onPacket(it) } }
    LaunchedEffect(connected) { if (connected) controller.refresh() }

    Box(Modifier.fillMaxSize().background(Zl.bg).imePadding()) {
        when (page) {
            ZoPage.CHAT -> ChatPage(
                computerName, connected, controller,
                onChats = { controller.loadChats(); page = ZoPage.CHATS },
                onSettings = { controller.loadModels(); controller.loadServers(); page = ZoPage.SETTINGS },
            )
            ZoPage.CHATS -> ChatsPage(controller, onBack = { page = ZoPage.CHAT })
            ZoPage.SETTINGS -> SettingsPage(controller, connected, onBack = { page = ZoPage.CHAT })
        }
        controller.approval?.let { a ->
            AlertDialog(
                onDismissRequest = { controller.answer(false) },
                containerColor = Zl.card,
                shape = RoundedCornerShape(24.dp),
                title = { Text("Let ZO-AI do this?", color = Zl.text, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        a.summary, color = Zl.muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp,
                        modifier = Modifier.heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)).background(Zl.cardAlt).padding(12.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = { TextButton(onClick = { controller.answer(true) }) { Text("Allow once", color = Zl.accent, fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { controller.answer(false) }) { Text("Don't allow", color = Zl.muted) } },
            )
        }
    }
}

// ---- small building blocks ------------------------------------------------------------------------------------

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color = Zl.text, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Bar(title: String, subtitle: String? = null, left: @Composable () -> Unit, right: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        left()
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(title, color = Zl.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Zl.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        right()
    }
}

/** A card with its own inner padding (ZlCard has none). */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    ZlCard(modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (enabled) Zl.accent else Zl.accentSoft).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Zl.accentInk else Zl.muted, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Zl.accent,
    unfocusedBorderColor = Zl.lineStrong,
    focusedTextColor = Zl.text,
    unfocusedTextColor = Zl.text,
    cursorColor = Zl.accent,
    focusedLabelColor = Zl.accent,
    unfocusedLabelColor = Zl.muted,
    focusedContainerColor = Zl.cardAlt,
    unfocusedContainerColor = Zl.cardAlt,
)

// ---- rich text: **bold**, `code`, ``` blocks, headings and bullets ------------------------------------------

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Zl.cardAlt, color = Zl.accent)) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}

@Composable
private fun RichText(text: String) {
    val parts = text.split("```")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { n, part ->
            if (n % 2 == 1) {
                // a code block (the first line may name the language)
                val code = part.substringAfter('\n', part).trimEnd()
                Text(
                    code, color = Zl.text, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Zl.cardAlt).border(1.dp, Zl.line, RoundedCornerShape(12.dp)).padding(12.dp),
                )
            } else if (part.isNotBlank()) {
                val shown = part.trim('\n').lines().joinToString("\n") { l ->
                    when {
                        l.startsWith("### ") -> l.removePrefix("### ")
                        l.startsWith("## ") -> l.removePrefix("## ")
                        l.startsWith("# ") -> l.removePrefix("# ")
                        l.startsWith("- ") || l.startsWith("* ") -> "•  " + l.drop(2)
                        else -> l
                    }
                }
                Text(inline(shown), color = Zl.text, fontSize = 15.5.sp, lineHeight = 23.sp)
            }
        }
    }
}

// ---- the conversation, with commands grouped like on the computer ---------------------------------------------

private sealed class ZoItem {
    class Msg(val line: ZoLine) : ZoItem()
    class Run(val lines: List<ZoLine>, val live: Boolean) : ZoItem()
}

private fun groupItems(lines: List<ZoLine>, busy: Boolean): List<ZoItem> {
    val out = ArrayList<ZoItem>()
    var group = ArrayList<ZoLine>()
    fun flush(live: Boolean) {
        if (group.isNotEmpty()) {
            out.add(ZoItem.Run(group, live))
            group = ArrayList()
        }
    }
    lines.forEachIndexed { i, l ->
        if (l.kind == "tool" || l.kind == "toolout") group.add(l) else {
            flush(false)
            out.add(ZoItem.Msg(l))
        }
        if (i == lines.size - 1) flush(busy)
    }
    return out
}

@Composable
private fun RunBox(r: ZoItem.Run) {
    var open by remember { mutableStateOf(false) }
    val count = r.lines.count { it.kind == "tool" }
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(if (r.live) Zl.accentSoft else Zl.card).border(1.dp, if (r.live) Zl.accent else Zl.line, shape)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (r.live) CircularProgressIndicator(Modifier.size(16.dp), color = Zl.accent, strokeWidth = 2.dp)
            else Icon(Icons.Filled.Check, contentDescription = null, tint = Zl.ok, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (r.live) "Working on your computer…" else if (count == 1) "Ran 1 command" else "Ran $count commands",
                color = if (r.live) Zl.accent else Zl.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = Zl.muted, modifier = Modifier.size(20.dp))
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.lines.forEach { l ->
                    if (l.kind == "tool") Text(l.text, color = Zl.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    else Text(l.text, color = Zl.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Bubble(l: ZoLine) {
    when (l.kind) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                l.text, color = Zl.text, fontSize = 15.5.sp, lineHeight = 22.sp,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(Zl.accentSoft).padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        "ai" -> RichText(l.text)
        "error" -> Text(l.text, color = Zl.danger, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Zl.dangerBg).padding(14.dp))
        else -> Text(l.text, color = Zl.muted, fontSize = 12.5.sp, modifier = Modifier.fillMaxWidth())
    }
}

// ---- pages ---------------------------------------------------------------------------------------------------

@Composable
private fun ChatPage(computerName: String, connected: Boolean, c: ZoAiController, onChats: () -> Unit, onSettings: () -> Unit) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val shown = groupItems(c.lines, c.busy)
    LaunchedEffect(shown.size, c.lines.lastOrNull()?.text?.length) {
        if (shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
    }
    Column(Modifier.fillMaxSize()) {
        Bar(
            title = if (c.chatId == null) "ZoAI" else c.title,
            subtitle = when {
                !connected -> "Not connected to $computerName"
                c.ready && c.model.isNotEmpty() -> "${c.model}  ·  ${c.mode}  ·  $computerName"
                c.ready -> computerName
                else -> "Connecting to ZO-AI on $computerName…"
            },
            left = { RoundIcon(Icons.Filled.Menu, "Chats", onClick = onChats) },
            right = {
                RoundIcon(Icons.Filled.Add, "New chat") { c.newChat() }
                RoundIcon(Icons.Filled.Settings, "Settings", onClick = onSettings)
            },
        )
        c.problem?.let { p ->
            Text(p, color = Zl.danger, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Zl.dangerBg).padding(14.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (c.lines.isEmpty()) {
                Column(Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(76.dp).clip(CircleShape).background(Zl.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Zl.accent, modifier = Modifier.size(38.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Ask ZO-AI", color = Zl.text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(6.dp))
                    Text("It works on $computerName: files, terminal, browser, apps and your MCP servers.", color = Zl.muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(22.dp))
                    // the chats already made with ZO-AI on the computer (before and after this phone), newest first
                    if (c.chats.isNotEmpty()) {
                        Text("Your chats", color = Zl.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                        c.chats.take(5).forEach { ch ->
                            Text(
                                ch.title.ifBlank { "Chat" }, color = Zl.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Zl.card).border(1.dp, Zl.line, RoundedCornerShape(16.dp))
                                    .clickable { c.openChat(ch.id) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                        if (c.chats.size > 5) {
                            Text("All ${c.chats.size} chats", color = Zl.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp).clickable(onClick = onChats).padding(8.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    (if (c.chats.isEmpty()) Suggestions else Suggestions.take(1)).forEach { s ->
                        Text(
                            s, color = Zl.text, fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Zl.card).border(1.dp, Zl.line, RoundedCornerShape(16.dp))
                                .clickable(enabled = connected) { c.sendMessage(s) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shown.size) { i ->
                    when (val item = shown[i]) {
                        is ZoItem.Msg -> Bubble(item.line)
                        is ZoItem.Run -> RunBox(item)
                    }
                }
            }
        }
        // the message box: one rounded field with the send button inside it
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).clip(RoundedCornerShape(28.dp)).background(Zl.card).border(1.dp, Zl.lineStrong, RoundedCornerShape(28.dp)).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).heightIn(min = 40.dp, max = 140.dp).padding(vertical = 10.dp),
                textStyle = TextStyle(color = Zl.text, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(Zl.accent),
                decorationBox = { inner ->
                    Box { if (input.isEmpty()) Text("Message ZO-AI", color = Zl.muted, fontSize = 16.sp); inner() }
                },
            )
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (c.busy) Zl.dangerBg else if (input.isBlank()) Zl.accentSoft else Zl.accent)
                    .clickable {
                        if (c.busy) c.stop()
                        else if (connected && input.isNotBlank()) {
                            c.sendMessage(input)
                            input = ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (c.busy) Icon(Icons.Filled.Close, contentDescription = "Stop", tint = Zl.danger)
                else Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = if (input.isBlank()) Zl.muted else Zl.accentInk)
            }
        }
    }
}

@Composable
private fun ChatsPage(c: ZoAiController, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Bar("Chats", left = { RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack) }, right = { RoundIcon(Icons.Filled.Add, "New chat") { c.newChat(); onBack() } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c.chats.isEmpty()) item { Text("No chats yet. Start one with the + button.", color = Zl.muted, modifier = Modifier.padding(8.dp)) }
            items(c.chats.size) { i ->
                val ch = c.chats[i]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Zl.card).border(1.dp, Zl.line, RoundedCornerShape(16.dp)).clickable { c.openChat(ch.id); onBack() }.padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ch.title.ifBlank { "Chat" }, color = Zl.text, fontSize = 15.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 16.dp))
                    RoundIcon(Icons.Filled.Delete, "Delete", tint = Zl.muted) { c.deleteChat(ch.id) }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(c: ZoAiController, connected: Boolean, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var json by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Bar("ZoAI settings", left = { RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // is ZO-AI reachable?
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (c.ready) Zl.ok else Zl.danger))
                    Spacer(Modifier.width(10.dp))
                    Text(if (c.ready) "ZO-AI is ready on your computer" else if (!connected) "Not connected to your computer" else "ZO-AI is not reachable yet", color = Zl.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                if (!c.ready) {
                    Text(c.problem ?: "Switch on Settings > Zohara Link > this phone > Use ZO-AI on the computer, and keep ZO-AI running there.", color = Zl.muted, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    PrimaryButton("Check again") { c.refresh() }
                }
            }
            c.message?.let { m -> Panel { Text(m, color = Zl.text, fontSize = 14.sp, lineHeight = 20.sp) } }
            Panel {
                Text("Ask before acting", color = Zl.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Auto runs safe things and asks for the rest. Manual asks before every change. Skipping the questions (Bypass) can only be switched on at the computer.", color = Zl.muted, fontSize = 13.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                    listOf("Auto", "Manual").forEach { m ->
                        val on = c.mode.equals(m, true)
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) Zl.accent else Zl.cardAlt).border(1.dp, if (on) Zl.accent else Zl.line, RoundedCornerShape(14.dp)).clickable { c.changeMode(m) }.padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(m, color = if (on) Zl.accentInk else Zl.text, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    }
                }
            }
            Panel {
                Text("Model", color = Zl.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(if (c.provider.isEmpty()) "Choose a provider on the computer first (ZO-AI > Settings)." else "Provider: ${c.provider}", color = Zl.muted, fontSize = 13.sp)
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    c.models.forEach { m ->
                        val on = m == c.model
                        Text(
                            m, color = if (on) Zl.accent else Zl.text, fontSize = 14.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { c.chooseModel(m) }.padding(vertical = 11.dp),
                        )
                    }
                }
            }
            Panel {
                Text("MCP servers", color = Zl.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Tools you give ZO-AI. They are added on the computer, so it needs to be on.", color = Zl.muted, fontSize = 13.sp, lineHeight = 18.sp)
                if (c.servers.isEmpty()) Text("None added yet.", color = Zl.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                c.servers.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = Zl.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(s.command, color = Zl.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(
                            checked = s.enabled, onCheckedChange = { c.toggleServer(s.name, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Zl.accentInk, checkedTrackColor = Zl.accent, uncheckedThumbColor = Zl.muted, uncheckedTrackColor = Zl.card, uncheckedBorderColor = Zl.lineStrong),
                        )
                        RoundIcon(Icons.Filled.Delete, "Remove", tint = Zl.muted) { c.removeServer(s.name) }
                    }
                }
            }
            Panel {
                Text("Add an MCP server", color = Zl.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address (https://…) or command") }, shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Spacer(Modifier.height(6.dp))
                PrimaryButton("Add server", enabled = name.isNotBlank() && address.isNotBlank()) { c.addServer(name.trim(), address.trim()); name = ""; address = "" }
                Spacer(Modifier.height(10.dp))
                Text("Or paste a configuration (the JSON other apps use):", color = Zl.muted, fontSize = 13.sp)
                OutlinedTextField(value = json, onValueChange = { json = it }, label = { Text("Configuration") }, shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp).padding(top = 6.dp))
                Spacer(Modifier.height(6.dp))
                PrimaryButton("Add from configuration", enabled = json.isNotBlank()) { c.importServers(json); json = "" }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

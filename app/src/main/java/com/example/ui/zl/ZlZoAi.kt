package com.example.ui.zl

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    }

    fun refresh() {
        problem = null
        request(JSONObject().put("op", "status"), { d ->
            if (d.optBoolean("ok")) {
                provider = d.optString("provider")
                model = d.optString("model")
                mode = d.optString("mode", "Auto")
            }
        }, { fail(it) })
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

    fun setMode(m: String) {
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

    Box(Modifier.fillMaxSize().imePadding()) {
        when (page) {
            ZoPage.CHAT -> ChatPage(computerName, connected, controller, onChats = { controller.loadChats(); page = ZoPage.CHATS }, onSettings = { controller.loadModels(); controller.loadServers(); page = ZoPage.SETTINGS })
            ZoPage.CHATS -> ChatsPage(controller, onBack = { page = ZoPage.CHAT })
            ZoPage.SETTINGS -> SettingsPage(controller, onBack = { page = ZoPage.CHAT })
        }
        controller.approval?.let { a ->
            AlertDialog(
                onDismissRequest = { controller.answer(false) },
                containerColor = Zl.card,
                title = { Text("Let ZO-AI do this?", color = Zl.text) },
                text = { Text(a.summary, color = Zl.muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { controller.answer(true) }) { Text("Allow once", color = Zl.accent) } },
                dismissButton = { TextButton(onClick = { controller.answer(false) }) { Text("Don't allow", color = Zl.muted) } },
            )
        }
    }
}

@Composable
private fun TopRow(title: String, onLeft: (() -> Unit)?, leftIcon: androidx.compose.ui.graphics.vector.ImageVector, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onLeft != null) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable { onLeft() }, contentAlignment = Alignment.Center) {
                Icon(leftIcon, contentDescription = null, tint = Zl.text)
            }
        }
        Text(title, color = Zl.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        right()
    }
}

@Composable
private fun IconTap(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Zl.text)
    }
}

@Composable
private fun ChatPage(computerName: String, connected: Boolean, c: ZoAiController, onChats: () -> Unit, onSettings: () -> Unit) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(c.lines.size, c.lines.lastOrNull()?.text?.length) {
        if (c.lines.isNotEmpty()) listState.scrollToItem(c.lines.size - 1)
    }
    Column(Modifier.fillMaxSize()) {
        TopRow(c.title, onChats, Icons.Filled.Menu) {
            IconTap(Icons.Filled.Add, "New chat") { c.newChat() }
            IconTap(Icons.Filled.Settings, "Settings") { onSettings() }
        }
        val sub = when {
            !connected -> "Not connected to $computerName"
            c.model.isEmpty() -> "on $computerName"
            else -> "${c.model} · ${c.mode} · $computerName"
        }
        Text(sub, color = Zl.muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(6.dp))
        val problem = c.problem
        if (problem != null) {
            ZlCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(problem, color = Zl.danger, fontSize = 14.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (c.lines.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ZoAI", color = Zl.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Ask ZO-AI on $computerName to do something. It uses the computer's model, files, terminal, browser and MCP servers.", color = Zl.muted, fontSize = 14.sp)
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(c.lines.size) { i -> Bubble(c.lines[i]) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                placeholder = { Text("Message ZO-AI", color = Zl.muted) },
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(if (c.busy) Zl.dangerBg else Zl.accent)
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
                else Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Zl.accentInk)
            }
        }
    }
}

@Composable
private fun Bubble(l: ZoLine) {
    when (l.kind) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(l.text, color = Zl.text, fontSize = 15.sp, modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(Zl.accentSoft).padding(horizontal = 14.dp, vertical = 10.dp))
        }
        "ai" -> Text(l.text, color = Zl.text, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        "tool" -> Text("▸ " + l.text, color = Zl.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Zl.cardAlt).padding(8.dp))
        "toolout" -> Text(l.text, color = Zl.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 6, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        "error" -> Text(l.text, color = Zl.danger, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Zl.dangerBg).padding(10.dp))
        else -> Text(l.text, color = Zl.muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
    }
}

@Composable
private fun ChatsPage(c: ZoAiController, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopRow("Chats", onBack, Icons.Filled.Close) {
            IconTap(Icons.Filled.Add, "New chat") { c.newChat(); onBack() }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.chats.isEmpty()) item { Text("No chats yet.", color = Zl.muted, modifier = Modifier.padding(16.dp)) }
            items(c.chats.size) { i ->
                val ch = c.chats[i]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Zl.card).clickable { c.openChat(ch.id); onBack() }.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ch.title.ifBlank { "Chat" }, color = Zl.text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    IconTap(Icons.Filled.Delete, "Delete") { c.deleteChat(ch.id) }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(c: ZoAiController, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var json by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopRow("ZoAI settings", onBack, Icons.Filled.Close) {}
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            c.message?.let { m ->
                ZlCard { Text(m, color = Zl.text, fontSize = 14.sp) }
            }
            ZlCard {
                Text("Ask before acting", color = Zl.text, fontWeight = FontWeight.SemiBold)
                Text("Auto runs safe things and asks for the rest. Manual asks before every change. Skipping the questions (Bypass) can only be switched on at the computer.", color = Zl.muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf("Auto", "Manual").forEach { m ->
                        Text(
                            m, color = if (c.mode.equals(m, true)) Zl.accentInk else Zl.text,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (c.mode.equals(m, true)) Zl.accent else Zl.cardAlt).clickable { c.setMode(m) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            ZlCard {
                Text("Model", color = Zl.text, fontWeight = FontWeight.SemiBold)
                Text(if (c.provider.isEmpty()) "Choose a provider on the computer first." else "${c.provider}: ${c.model.ifEmpty { "none chosen" }}", color = Zl.muted, fontSize = 13.sp)
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    c.models.forEach { m ->
                        Text(
                            m, color = if (m == c.model) Zl.accent else Zl.text, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickable { c.chooseModel(m) }.padding(vertical = 9.dp),
                        )
                    }
                }
            }
            ZlCard {
                Text("MCP servers", color = Zl.text, fontWeight = FontWeight.SemiBold)
                Text("Tools you give ZO-AI on the computer. They are added there, so the computer needs to be on.", color = Zl.muted, fontSize = 13.sp)
                c.servers.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = Zl.text, fontSize = 15.sp)
                            Text(s.command, color = Zl.muted, fontSize = 11.sp, maxLines = 1)
                        }
                        Switch(checked = s.enabled, onCheckedChange = { c.toggleServer(s.name, it) })
                        IconTap(Icons.Filled.Delete, "Remove") { c.removeServer(s.name) }
                    }
                }
                if (c.servers.isEmpty()) Text("None yet.", color = Zl.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            ZlCard {
                Text("Add an MCP server", color = Zl.text, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address (https://…) or command") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                TextButton(onClick = { if (name.isNotBlank() && address.isNotBlank()) { c.addServer(name.trim(), address.trim()); name = ""; address = "" } }) { Text("Add", color = Zl.accent) }
                Text("Or paste a configuration (the JSON other apps use):", color = Zl.muted, fontSize = 13.sp)
                OutlinedTextField(value = json, onValueChange = { json = it }, label = { Text("Configuration") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 200.dp).padding(top = 8.dp))
                TextButton(onClick = { if (json.isNotBlank()) { c.importServers(json); json = "" } }) { Text("Add from configuration", color = Zl.accent) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

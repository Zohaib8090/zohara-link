# ZoAI: ZO-AI on the computer, from the phone

The **ZoAI** tab of the phone app is a chat with ZO-AI running on the computer. The model, the tools (files, shell, terminals, the
browser, sub-agents) and the MCP servers are all on the computer; the phone shows the conversation, answers ZO-AI's questions and
manages it (model, ask-before-acting mode, MCP servers). Nothing is sent to the phone's own storage except what is on screen.

## Permission (off until the owner of the computer allows it)

ZO-AI can run commands and change anything the person can, so a phone gets it only after **Settings > Zohara Link > the phone's row >
Use ZO-AI** is switched on (`allow_zoai` in `paired_devices.json`, IPC `SET_PERMISSION {"deviceId":…, "zoai":true}`). The daemon
checks it for the request and again for every line that comes back, so switching it off ends a running request at once.

Two more rules are enforced by the daemon, not by the phone (`linux-daemon/src/zoai.rs`, with tests):

* a phone may only make the requests in `OPS` (chat, history, model, mode, MCP servers); anything else is refused;
* a phone can choose **Auto** or **Manual** but never **Bypass** (no questions asked): that stays a decision made on the computer.

Risky tools still ask. When ZO-AI wants to run a command or change something, the question goes to the phone (a dialog with what it
wants to do: Allow once / Don't allow). No answer in three minutes counts as no.

## How it is wired

```
phone ZoAI tab --TLS--> zohara-linkd --unix socket--> ZO-AI (zo-ai --background, crates/zo-core/src/service.rs)
```

Phone to daemon (all on the authenticated TLS connection):

| Message | Meaning |
|---|---|
| `ZOAI_REQ {rid, op, …}` | a request; `rid` is the phone's id for it |
| `ZOAI_LINE {rid, op:"approve", id, yes}` / `{op:"stop"}` | while a `send` runs: an answer to a question, or stop |
| `ZOAI_EVT {rid, data}` | (from the computer) one answer or event |
| `ZOAI_END {rid, error?}` | (from the computer) the request is over; `error` says why when it failed |

Requests (`op`): `status`, `chats`, `chat {id}` (entries and the takes of your messages), `delete_chat`, `pin_chat`, `mode {set?}`, `providers`, `models`,
`select {provider?, model?, effort?}`, `set_key {key, provider?}` (kept in the computer's keyring, never on the phone), `profiles`, `profile_use`, `profile_create`,
`profile_rename`, `profile_delete`, `profile_pin`, `mcp_list`, `mcp_add {name, address, token?}`, `mcp_import {config}`, `mcp_remove`, `mcp_toggle`,
`scheduled`, `schedule_add`, `schedule_cancel`, `projects`, `project_create`, `compact {id}`, `switch {id, nth, delta}` (show another take) and
`send {text, chat?, mode?, edit?}`; `edit` is the number of your message to edit or ask again (what was there is kept as an earlier take, as on the computer).
`send` streams events: `chat {id,title}`, `delta {text}`, `text {text}` (the whole text of a step), `tool_start`, `tool_done`, `approval {id, summary}`,
`usage`, `error`, `stopped`, and finally `done`. Chats are saved in ZO-AI's own store, so a chat started on the phone shows in the app on the
computer and the other way round.

The socket is `$XDG_RUNTIME_DIR/zo-ai/agent.sock` (folder 0700, socket 0600). If ZO-AI is not running the phone is told so
("start it there").

## Tested and not tested

* Daemon: unit tests (`zoai.rs`) and `tests/e2e_zoai.py` over real TLS with a stand-in for ZO-AI: refused until allowed, answers carried back,
  unknown requests refused, Bypass refused, an approval travelling to the phone and its answer back, only approvals and stop accepted from the
  phone, switching the permission off ending a running request, a clear message when ZO-AI is not running.
* ZO-AI side: a unit test of the real service with a fake model (events, the chat saved).
* **The Android screen has not been compiled or run** (no Android SDK here: CI compiles it) and the real daemon has not yet talked to the
  real ZO-AI on a phone's behalf.

## Not done yet

Voice input and photos from the phone, answering from a notification while the app is closed, pictures in replies, a progress view of
terminals and the browser (the person sees them on the computer), choosing a provider or entering API keys from the phone (keys stay on the computer).

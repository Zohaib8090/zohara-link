# Sending files (both directions)

## Phone to computer (since the first version)
The phone sends `FILE_OFFER {transferId, fileName, fileSize}`; the daemon answers `FILE_ACCEPT {accepted}` (refusing files over 4 GB, too many at
once, or odd names); then `FILE_CHUNK {payloadBase64}` lines and `FILE_COMPLETE`. Files land in `~/Downloads/ZoharaLink`, never overwrite
an existing file, and a file that is cut off or longer than announced is deleted. Code: `daemon.rs` `handle_file_offer/chunk/complete`.

## Computer to phone (2026-10-16)
Names differ (`FILE_PUSH_*`) so the two directions cannot be mixed up.

1. Settings > Zohara Link > the phone's row > **send** button picks a file and sends the local command
   `{"command":"SEND_FILE","deviceId":"<paired id>","path":"/…"}` to the daemon. Answers: `OFFERED`, `NOT_CONNECTED`, `NO_FILE`,
   `EMPTY`, `TOO_BIG`, `BUSY` (at most 4 at once).
2. The daemon sends `FILE_PUSH_OFFER {transferId, fileName, fileSize}` on the phone's live connection. **Nothing else is sent.**
3. The phone shows "The computer wants to send you a file: Accept / Decline" (`ZlApp.kt`). Its answer is
   `FILE_PUSH_ACCEPT {transferId, accepted}`. Only the connection the offer went to may answer.
4. On accept the daemon streams `FILE_PUSH_CHUNK {payloadBase64}` (48 KiB raw, about 64 KiB per line) and ends with
   `FILE_PUSH_COMPLETE`. The phone can stop at any time with `FILE_PUSH_CANCEL`; the daemon stops at the next chunk.
5. The phone saves to `Downloads/ZoharaLink` through MediaStore (Android 10+, no storage permission; the file is hidden from other
   apps until complete) or to the app's own Downloads folder on older Android (`FileReceiver.kt`). A file that is not exactly the
   announced size, or a connection that drops, deletes the partial file.

Events for local clients (Settings): `FILE_SEND_OFFERED`, `FILE_SEND_PROGRESS`, `FILE_SEND_COMPLETE`, `FILE_SEND_DECLINED`,
`FILE_SEND_CANCELLED`, `FILE_SEND_FAILED`. Offers are dropped when the connection closes or the phone is unpaired.

Tested end to end over TLS in `linux-daemon/tests/e2e.py` (offer, accept, bytes identical, decline, cancel). **Not yet seen on a real phone.**

## Folders, several files, and the clipboard (2026-10-16, later)
* **A folder** is zipped by the daemon into `~/.cache/zohara-link/outgoing/<random>.zip` (links skipped, unreadable files left out, 4 GB
  limit) and offered as `<folder name>.zip`. The temporary zip is deleted when the offer ends (accepted and sent, declined, cancelled,
  connection lost, phone unpaired) and any left over are removed when the daemon starts.
* **Several files**: Settings picks many at once; each is its own offer (up to 32 waiting). The phone queues the offers and the Accept
  box shows "N files" with **Accept all / Decline all**.
* **Clipboard** (Settings > Zohara Link > Quick actions > "Offer copied files, folders and pictures", **off until chosen**, kept in
  `~/.config/zohara-link/options.json`, IPC `SET_OPTION {"clipboardFiles":true}`): the watcher also reads what `wl-paste` / `xclip`
  say is on the clipboard. `text/uri-list` with `file://` entries (a file manager's Copy) offers those files and folders (at most 20);
  otherwise `image/png` (up to 30 MB) is offered as `picture-<time>.png`. One copy is offered once; web addresses are ignored. The
  phone still has to press Accept, so nothing lands on it without a yes. Plain text keeps syncing both ways as before.
  Tests: `tests/e2e_clipboard.py` (a stand-in `wl-paste`, the real clipboard is not touched) and unit tests in `desktop.rs`.

Not done yet: a notification on the phone when the app is in the background (the offer waits until the app is opened), a progress
readout on the laptop, "Send to phone" in Dolphin's right-click menu, pictures copied on the **phone** reaching the laptop's clipboard,
an "accept automatically" switch on the phone for copied items.

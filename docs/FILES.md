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

Not done yet: a notification on the phone when the app is in the background (the offer waits until the app is opened), a progress
readout on the laptop, several files at once from one dialog, "Send to phone" in Dolphin's right-click menu.

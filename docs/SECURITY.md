# Zohara Link security (2026-10-08)

What protects the connection between a phone and a Zohara computer, what each protection stops, and what is **not** covered.
Daemon checks: `linux-daemon/tests/e2e.py` (32 checks against the real binary over real TLS) and `cargo test`.
The Android changes are **not compiled or run yet** (no Android toolchain in the session that wrote them).

## Encryption and who you are talking to

* **TLS** (rustls, TLS 1.2 and 1.3, modern ciphers only) encrypts everything between phone and computer.
* The computer's certificate is self-signed, so TLS alone does not say *who* answered. The phone therefore **pins** it: at
  pairing it remembers the SHA-256 fingerprint of the certificate, and on every later connection it refuses any other one.
  Before it was "trust any certificate", which let anyone on the Wi-Fi sit in the middle.
* During pairing the computer's notification shows the PIN and a short fingerprint code (`ab12 cd34 ...`); the phone shows the
  fingerprint of the certificate it was actually given. If they match, nobody is relaying the pairing. The computer also sends
  its fingerprint inside the TLS stream and the phone refuses if it differs from the certificate it saw.

## Pairing

* Pairing is **closed by default**. The person opens it for a few minutes from Settings ("Pair a phone", IPC `OPEN_PAIRING`)
  and it closes after one phone pairs. A stranger on the same Wi-Fi cannot make PIN pop-ups appear.
* The 6-digit PIN is only shown on the computer and **typed on the phone**; the daemon never sends it over the network. A PIN dies
  after 3 wrong tries or 2 minutes and works once.
* Pairing hands the phone a random 256-bit **token**. The computer keeps only its SHA-256 hash, so reading
  `paired_devices.json` does not let anyone pose as the phone. The phone proves itself with `AUTH` on each reconnect (a wrong
  token is answered after a 1 second pause). Unpairing deletes the hash: the old token is useless and the connection loses
  access at once.

## What a paired phone may do

* Clipboard, notifications, files, media control: allowed once paired.
* **Moving the mouse and clicking is off** for every phone until the person allows it for that phone (`SET_PERMISSION`).
* Files: never overwrite an existing file (`name (1).ext`), path parts in names are dropped, a declared size over 4 GB is refused,
  at most 4 at once, and a transfer that sends more than it declared is cancelled and its partial file deleted.
* Notification text from the phone is stripped of control characters and cut to a sane length.

## Away from home and remote desktop (2026-10-13, alpha)

* **Away from home:** no new port and no new way in. The daemon already listens on every address; `remote.rs` only finds this
  computer's addresses on overlay networks (Tailscale, ZeroTier, NetBird, WireGuard, by interface name or the 100.64.0.0/10 range)
  and tells an authenticated phone about them (`ADDRESSES`). A phone that cannot reach the saved address tries them one by one.
  Over an overlay the phone still needs the pinned certificate and its token; pairing still has to be opened on the computer and the
  PIN typed there. A phone must have been paired once (at home, on the same network) before it can use this route.
* **Watching the screen is its own permission** (`allow_screen`, off by default, `SET_PERMISSION` with `screen`), separate from the
  mouse. It is checked for every frame: switching it off, unpairing or a dropped connection ends the stream at once. Controlling
  the computer from the remote desktop (click, scroll, type, a fixed list of keys) needs the mouse permission too, so a phone with
  only "Show the screen" cannot touch anything. Typed text is cut to 200 characters and stripped of control characters; keys are a
  fixed list, not free key names.
* The screen picture shows everything on the computer, including a password typed there. The same TLS connection protects it as
  everything else; there is no extra "blank the screen" mode yet.
* `ZOHARA_LINK_SCREEN_CMD` (environment of the daemon, not of a phone) swaps the capture tool; tests use it.

## Notifications, media and files (2026-10-12)

* Phone notifications are filtered on the phone (ongoing, progress, media, service and group-summary ones are never sent; identical
  re-posts are dropped; the person can switch an app off) and again on the computer (the same app + title + text is shown once per
  two minutes; one app shows at most 12 a minute).
* Media: the buttons work through `playerctl` or, without it, MPRIS over D-Bus; the computer tells the phone what is playing.
* Files: the phone waits for the computer's yes or no (too large, too many at once) before streaming. A transfer cut off by a
  dropped connection is removed together with its partial file. A declared size of 0 means "unknown" (limited only by the 4 GB cap).
* Locking the screen from the phone needs the same per-phone permission as the mouse.

## Against attacks on the daemon itself

* One packet line is at most 256 KB (a file chunk is about 44 KB). A longer line closes the connection instead of filling memory.
* A connection that has not paired or sent its token within 15 seconds is dropped. At most 32 connections, 8 unauthenticated.
* The private key is created with mode 600 (an older key is tightened on start), the config folder is 700, the paired-phones
  list and the local socket 600. The systemd service drops privileges it does not need (`NoNewPrivileges`, address families,
  `UMask=0077`...).
* Android: `allowBackup` is off, so the pinned fingerprints and tokens are not copied to a cloud backup.

## Not covered (be honest about these)

* The pinned fingerprint and token live in the app's private storage, not in the Android Keystore; a rooted phone can read them.
* The daemon listens on all interfaces; only the pairing/auth rules above stop a stranger, not a firewall. Zohara does not ship
  a firewall rule for port 42424.
* Remote desktop and the overlay route have only been tested against the real daemon with a faked screen and no real Tailscale; never
  on a real Plasma screen or phone.
* Remote input and file receiving trust the paired phone completely once allowed.
* Never tested on a phone yet: the Android pinning, token reconnect, the new PIN dialog, notification filtering, file transfer, the
  self-update. CI builds the app; nobody has run it.
* The app's self-update trusts Android's own check that the new APK has the same signing key as the installed app. Whoever holds the
  signing key can push an update to every phone: keep the key in GitHub secrets and two offline copies, never in git.

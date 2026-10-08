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
* Remote input and file receiving trust the paired phone completely once allowed.
* Never tested on a phone yet: the Android pinning, token reconnect and the new PIN dialog.

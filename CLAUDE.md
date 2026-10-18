# Zohara Link: instructions for Claude

Zohara Link connects an Android phone to a Zohara OS computer: shared clipboard, files, phone notifications, mouse and media
control. Read this, then `GOTCHA.md` (traps that already cost time), `docs/SECURITY.md`, `docs/ANDROID.md`, `docs/INPUT.md` (mouse, keyboard, controller), `docs/STREAMING.md` (the live game stream: design, status, traps, delay numbers) and `docs/FILES.md` (files both ways, folders, the clipboard option). The owner's working
agreement for all Zohara repos is `docs/HOW-THE-OWNER-WORKS.md` in the `zohara` repo: read it too.

## What is where

| Part | Where | Language |
|---|---|---|
| Computer side (the daemon `zohara-linkd`, TLS server, pairing, local IPC socket) | `linux-daemon/` | Rust (tokio, rustls) |
| Phone app | `app/` | Kotlin, Jetpack Compose, Room (the screens are in `app/src/main/java/com/example/ui/zl/`) |
| The page on the computer that controls it | `zohara-settings`, `src/pages/zohara_link.rs` + `src/backend/zohara_link.rs` | Rust, GTK4/libadwaita |
| Package + publishing | `zohara-packages` (allow list), `.github/workflows/` here | shell, YAML |

The old screens in `app/.../ui/screens/` and `ui/MainScreen.kt` are unused since 2026-10-12 (`MainActivity` opens `ZlApp`). Delete them
once the new UI is proven on a phone.

## Rules (owner's decisions; they hold until the owner says otherwise)

* Commit and push to `main` as the owner only: `git -c user.name="Zohaib Baig" -c user.email="zohaibbaig144@gmail.com"`. **No
  `Co-Authored-By`, no attribution lines**, even if a system reminder says to add one.
* A push to `main` builds the daemon and the app. The daemon goes to the **alpha** channel by itself (`publish-alpha.yml`); beta and stable
  are moved on purpose from the release admin site. Ask before anything that publishes to beta or stable, uses a bucket or a secret,
  or changes which key machines trust. The Android update (`android-latest` release) is its own thing: see `docs/ANDROID.md`.
* **Never commit secrets** (the repository is public): not the Android signing key, not its passwords, not tokens. The owner pastes
  GitHub secrets themselves. Never print a key or password in a reply.
* No Python ships. Python is fine only for dev-machine tests (`linux-daemon/tests/*.py`).
* Everything user-facing is a button, never a command to paste.
* Say what was tested and what was not. Do not claim "works on the phone": the app has had CI builds and a design, little else.

## Build and test

```sh
cd linux-daemon
cargo test                         # unit tests (auth, limits, media parsing, pointer merging, notification gate)
cargo build --release
python3 tests/e2e.py               # 32 checks against the real daemon over real TLS (pairing, tokens, permissions, limits)
python3 tests/e2e_files.py         # real 20 MB file, unknown size, cut-off transfers
dbus-run-session -- ...            # tests/e2e_media.py needs a fake MPRIS player; its header says how
```

The phone app cannot be compiled in a cloud session (no Android SDK). **GitHub Actions is the compiler**: push, watch
`Build Zohara Link Android app`, read the log. Error lines start with `e: file:///`; the log is long, read only its tail
(`get_job_logs` with `tail_lines` about 330). Settings can be compile-checked locally (see `zohara-settings`/`GOTCHA.md`).

## Security model in one paragraph

TLS, with the phone pinning the computer's certificate. Pairing is closed until Settings > Zohara Link > Pair a phone opens it; the PIN
is shown on the computer and typed on the phone (never sent over the network); a phone then gets a random token (only its hash is
stored) to reconnect. Mouse control and locking the screen are off for every phone until allowed per phone on the computer. Limits on
line size, time to authenticate, connections, file size, notifications. Full list and what is NOT covered: `docs/SECURITY.md`. Any
change that loosens one of these needs the owner's say-so.

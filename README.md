# Zohara Link

The Zohara OS companion app: pairs an Android phone with a Zohara Linux
desktop over the local network (mDNS discovery + TLS) for shared
clipboard, file transfer, notification mirroring, and remote input.

- `app/` — the Android app (Kotlin, Jetpack Compose), built with
  [AI Studio](https://ai.studio/apps/9cb9486d-d7a9-4e8a-9966-ce1ad3104b63).
- `linux-daemon/` — `zohara-linkd`, the Rust daemon it pairs with (Tokio +
  rustls + rcgen + mdns-sd; rewritten 2026-09-23 from an earlier Python
  prototype — no Python involved anymore). Note there is a *second*,
  separate `zohara-connectd` Rust crate in the main
  [zohara](https://github.com/Zohaib8090/zohara) repo, which is still an
  unimplemented skeleton; this repo's `zohara-linkd` is the one working
  server side of the protocol today. Worth reconciling the two at some
  point rather than maintaining both.

Formerly "Zohara Connect" — renamed to avoid colliding with KDE Connect,
which this replaces on Zohara OS, and to match the "Phone Link" language
Windows uses for the same kind of feature.

## Status

Early prototype, not shipped on the ISO yet (install it from Settings > Zohara Link once it is on your update channel).
Since 2026-10-08 the connection is TLS with the phone **pinning the computer's certificate**, pairing is closed until you press
"Pair a phone" in Settings, the PIN is typed on the phone (never sent over the network), and phones reconnect with a stored token.
What is protected and what is not: [docs/SECURITY.md](docs/SECURITY.md). The daemon is tested end to end
(`linux-daemon/tests/e2e.py`); the Android changes have **not been compiled or run on a phone yet**.

**The pairing/status page lives inside `zohara-settings`**
(Personalization → Ecosystem → Zohara Link), not a standalone app — see
[zohara-settings/docs/UI-REDESIGN.md](https://github.com/Zohaib8090/zohara-settings/blob/main/docs/UI-REDESIGN.md)
for the design language and current status. That page will be the first
real client of this daemon's Unix socket IPC (below) beyond
`zohara-link-status`.

## Run the Android app locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio.
2. Select **Open** and choose this directory.
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in it to your Gemini API key (see `.env.example`).
5. Remove this line from `app/build.gradle.kts`: `signingConfig = signingConfigs.getByName("debugConfig")`.
6. Run the app on an emulator or physical device.

## Run the Linux daemon locally

See [linux-daemon/README.md](linux-daemon/README.md).

# Zohara Link

The Zohara OS companion app: pairs an Android phone with a Zohara Linux
desktop over the local network (mDNS discovery + TLS) for shared
clipboard, file transfer, notification mirroring, and remote input.

- `app/` — the Android app (Kotlin, Jetpack Compose), built with
  [AI Studio](https://ai.studio/apps/9cb9486d-d7a9-4e8a-9966-ce1ad3104b63).
- `linux-daemon/` — `zohara-linkd`, the Python prototype of the Linux-side
  daemon it pairs with. (A Rust rewrite, `zohara-connectd`, lives in the
  main [zohara](https://github.com/Zohaib8090/zohara) repo and is currently
  an unimplemented skeleton — this Python daemon is the only working
  server side of the protocol today.)

Formerly "Zohara Connect" — renamed to avoid colliding with KDE Connect,
which this replaces on Zohara OS, and to match the "Phone Link" language
Windows uses for the same kind of feature.

## Status

Early prototype, not shipped on the ISO yet. The pairing protocol has had
a security pass (2026-09-23: fixed a PIN-echo bypass and an
`approved: true` bypass in the daemon) but the Android side still trusts
any TLS certificate presented by the daemon — that needs certificate
pinning before this should be used over an untrusted network.

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

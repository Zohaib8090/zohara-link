# Zohara Link — Linux Daemon & Ecosystem Guide

Zohara Link is a high-performance, native companion ecosystem between Arch Linux and Android devices.

`zohara-linkd` is written in Rust (Tokio + rustls + rcgen + mdns-sd). It was
originally a Python prototype; rewritten 2026-09-23 for no runtime Python
dependency, in-process self-signed cert generation instead of shelling out
to `openssl`, and a real mDNS advertisement (the Python version defined the
service-name constants but never actually registered anything).

## Features
- **Zero-Config mDNS Discovery**: Advertises `_zohara-link._tcp` over the local Wi-Fi / LAN, so the Android app finds it automatically.
- **Encrypted TLS & 6-Digit PIN SAS Pairing**: End-to-end encrypted connection with Short Authentication String validation.
- **Bi-directional Shared Clipboard**: Seamless sync with Wayland (`wl-clipboard`) and X11 (`xclip`).
- **High-Speed P2P File Transfers**: Chunk streaming directly into `~/Downloads/ZoharaLink/`.
- **Notification Mirroring & Remote Reply**: Native desktop notifications (`org.freedesktop.Notifications`).
- **Device Telemetry**: Phone battery, charging state, and Wi-Fi stats available for status bars (Waybar, Polybar).
- **Remote Controls**: Media playback (MPRIS2 / `playerctl`), remote trackpad (`ydotool`/`xdotool`), and proximity screen auto-locking (`hyprlock`/`swaylock`/`loginctl`).

## Status

Security-reviewed 2026-09-23 (pairing PIN bypasses fixed), but the Android
side still trusts any TLS certificate the daemon presents — see the main
[zohara-link README](../README.md) for what that means before using this
over a network you don't trust.

## Arch Linux Quick Installation

```bash
# 1. Install runtime dependencies (pick whichever apply to your setup)
sudo pacman -S wl-clipboard playerctl ydotool libnotify

# 2. Build and install
cargo build --release
sudo install -Dm755 target/release/zohara-linkd /usr/bin/zohara-linkd
sudo install -Dm755 target/release/zohara-link-status /usr/bin/zohara-link-status
#    (or: makepkg -si, using the PKGBUILD in this directory)

# 3. Enable and start the systemd user service
mkdir -p ~/.config/systemd/user
cp zohara-linkd.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now zohara-linkd.service

# Check status
systemctl --user status zohara-linkd.service
```

## Unix Socket IPC Integration

The daemon exposes a local Unix Domain Socket at `/run/user/$UID/zohara.sock`.
Any local application (GTK4/Libadwaita, Qt/QML, Rust, Python, C++) can
connect, send newline-delimited JSON commands, and receive streaming
real-time events. `zohara-link-status` (built alongside `zohara-linkd`) is
a minimal reference client — plain output for a terminal, or `--waybar` for
a Waybar `custom/` module (see `waybar_module.json`).

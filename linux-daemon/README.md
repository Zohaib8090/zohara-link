# Zohara Link — Linux Daemon & Ecosystem Guide

Zohara Link is a high-performance, native companion ecosystem between Arch Linux and Android devices.

## Features
- **Zero-Config mDNS Discovery**: Discovered automatically over the local Wi-Fi / LAN via `_zohara-link._tcp`.
- **Encrypted TLS & 6-Digit PIN SAS Pairing**: End-to-end encrypted connection with Short Authentication String validation.
- **Bi-directional Shared Clipboard**: Seamless sync with Wayland (`wl-clipboard`) and X11 (`xclip`).
- **High-Speed P2P File Transfers**: Chunk streaming directly into `~/Downloads/ZoharaLink/`.
- **Notification Mirroring & Remote Reply**: Native desktop notifications (`org.freedesktop.Notifications`).
- **Device Telemetry**: Phone battery, charging state, and Wi-Fi stats available for status bars (Waybar, Polybar).
- **Remote Controls**: Media playback (MPRIS2 / `playerctl`), system audio volume, remote trackpad (`ydotool`/`xdotool`), and proximity screen auto-locking (`hyprlock`/`swaylock`/`loginctl`).

## Arch Linux Quick Installation

```bash
# 1. Install dependencies on Arch Linux
sudo pacman -S python wl-clipboard playerctl openssl ydotool libnotify

# 2. Copy daemon files to ~/.config/zohara-link/
mkdir -p ~/.config/zohara-link
cp daemon.py ~/.config/zohara-link/

# 3. Enable and start systemd user service
mkdir -p ~/.config/systemd/user
cp zohara-linkd.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now zohara-linkd.service

# Check status
systemctl --user status zohara-linkd.service
```

## D-Bus / Unix Socket IPC Integration

The daemon exposes a local Unix Domain Socket at `/run/user/$UID/zohara.sock`.
Any local application (GTK4/Libadwaita, Qt/QML, Python, Rust, C++) can connect, send JSON commands, and receive streaming real-time events.

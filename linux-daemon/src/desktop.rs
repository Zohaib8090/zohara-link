//! Linux desktop integrations: notifications, clipboard, pointer/media
//! control, screen lock. Each one shells out to whichever tool is present,
//! falling back through a short chain exactly like the Python daemon this
//! replaced did -- these are all external CLI tools (notify-send,
//! wl-copy/xclip, ydotool/xdotool, playerctl, hyprlock/swaylock/loginctl),
//! not something worth linking a library for.

use log::debug;
use std::process::{Command, Stdio};

pub fn send_desktop_notification(title: &str, body: &str) {
    let status = Command::new("notify-send")
        .args(["-a", "Zohara Link", title, body])
        .status();
    if let Err(e) = status {
        debug!("notify-send failed: {e}");
    }
}

fn is_wayland() -> bool {
    std::env::var_os("WAYLAND_DISPLAY").is_some()
}

/// Sets the desktop clipboard. Wayland (`wl-copy`) or X11 (`xclip`).
pub fn set_linux_clipboard(text: &str) {
    use std::io::Write;
    let result = if is_wayland() {
        Command::new("wl-copy").stdin(Stdio::piped()).spawn()
    } else {
        Command::new("xclip")
            .args(["-selection", "clipboard"])
            .stdin(Stdio::piped())
            .spawn()
    };
    match result {
        Ok(mut child) => {
            if let Some(stdin) = child.stdin.as_mut() {
                let _ = stdin.write_all(text.as_bytes());
            }
            let _ = child.wait();
        }
        Err(e) => debug!("clipboard write failed: {e}"),
    }
}

/// Reads the desktop clipboard, or an empty string if the read fails
/// (no clipboard tool installed, empty clipboard, etc.) -- callers treat
/// an empty string as "no change", matching the Python original.
pub fn get_linux_clipboard() -> String {
    let output = if is_wayland() {
        Command::new("wl-paste").arg("-n").output()
    } else {
        Command::new("xclip")
            .args(["-selection", "clipboard", "-o"])
            .output()
    };
    match output {
        Ok(o) if o.status.success() => String::from_utf8_lossy(&o.stdout).into_owned(),
        _ => String::new(),
    }
}

/// Relative pointer move via ydotool (Wayland), falling back to xdotool (X11).
pub fn simulate_pointer_move(dx: f64, dy: f64) {
    let dx = (dx as i64).to_string();
    let dy = (dy as i64).to_string();
    let ydotool = Command::new("ydotool")
        .args(["mousemove", "--", &dx, &dy])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
    if !matches!(ydotool, Ok(s) if s.success()) {
        let _ = Command::new("xdotool")
            .args(["mousemove_relative", "--", &dx, &dy])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
}

pub fn simulate_pointer_click(button: &str) {
    let xdotool_btn = if button == "LEFT" { "1" } else { "3" };
    let ydotool_code = if button == "LEFT" { "0x110" } else { "0x111" };
    let ydotool = Command::new("ydotool")
        .args(["click", ydotool_code])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
    if !matches!(ydotool, Ok(s) if s.success()) {
        let _ = Command::new("xdotool")
            .args(["click", xdotool_btn])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
}

/// `action` is one of "PLAY_PAUSE", "NEXT", anything else means "previous"
/// -- matches the Python original's ternary chain exactly.
pub fn control_mpris2_media(action: &str) {
    let cmd = match action {
        "PLAY_PAUSE" => "play-pause",
        "NEXT" => "next",
        _ => "previous",
    };
    if let Err(e) = Command::new("playerctl").arg(cmd).status() {
        debug!("playerctl command failed: {e}");
    }
}

/// Locks the desktop session, trying compositor-specific lockers first and
/// falling back to logind. Stops at the first one that succeeds.
pub fn trigger_screen_lock() {
    log::info!("Triggering proximity screen lock...");
    for cmd in [
        vec!["hyprlock"],
        vec!["swaylock"],
        vec!["loginctl", "lock-session"],
    ] {
        let ok = Command::new(cmd[0])
            .args(&cmd[1..])
            .status()
            .map(|s| s.success())
            .unwrap_or(false);
        if ok {
            break;
        }
    }
}

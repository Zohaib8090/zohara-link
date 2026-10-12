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

/// Which pointer tool worked last, so a move does not try the missing one first every time (starting a process that is not
/// there still costs time).
static POINTER_TOOL: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(0); // 0 unknown, 1 ydotool, 2 xdotool

fn run_quiet(cmd: &str, args: &[&str]) -> bool {
    Command::new(cmd)
        .args(args)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

/// Runs the same action with ydotool and xdotool, the one that worked last first.
fn with_pointer_tool(ydotool_args: &[&str], xdotool_args: &[&str]) {
    use std::sync::atomic::Ordering;
    let order: [u8; 2] = if POINTER_TOOL.load(Ordering::Relaxed) == 2 { [2, 1] } else { [1, 2] };
    for tool in order {
        let ok = if tool == 1 { run_quiet("ydotool", ydotool_args) } else { run_quiet("xdotool", xdotool_args) };
        if ok {
            POINTER_TOOL.store(tool, Ordering::Relaxed);
            return;
        }
    }
}

fn pointer_move_now(dx: i64, dy: i64) {
    let (dx, dy) = (dx.to_string(), dy.to_string());
    with_pointer_tool(&["mousemove", "--", &dx, &dy], &["mousemove_relative", "--", &dx, &dy]);
}

fn pointer_click_now(button: &str) {
    let (xdotool_btn, ydotool_code) = if button == "LEFT" { ("1", "0xC0") } else { ("3", "0xC1") };
    with_pointer_tool(&["click", ydotool_code], &["click", xdotool_btn]);
}

/// One thing to do to the pointer. Phones send a burst of tiny moves; running a tool for each one made the pointer lag more and
/// more behind the finger, so moves are queued and merged: whatever piled up while the last one ran becomes one move.
#[derive(Debug, Clone, PartialEq)]
pub enum PointerOp {
    Move(f64, f64),
    Click(String),
}

/// Adds up runs of consecutive moves (a click in between keeps its place, so "move, click, move" stays in order).
pub fn merge_ops(ops: Vec<PointerOp>) -> Vec<PointerOp> {
    let mut out: Vec<PointerOp> = Vec::new();
    for op in ops {
        match (op, out.last_mut()) {
            (PointerOp::Move(dx, dy), Some(PointerOp::Move(ax, ay))) => {
                *ax += dx;
                *ay += dy;
            }
            (op, _) => out.push(op),
        }
    }
    out
}

static POINTER_TX: std::sync::OnceLock<std::sync::mpsc::Sender<PointerOp>> = std::sync::OnceLock::new();

fn pointer_tx() -> &'static std::sync::mpsc::Sender<PointerOp> {
    POINTER_TX.get_or_init(|| {
        let (tx, rx) = std::sync::mpsc::channel::<PointerOp>();
        std::thread::spawn(move || {
            // Moves are whole pixels; the part that does not fit (0.4 px) is carried to the next move instead of being lost.
            let mut carry = (0.0f64, 0.0f64);
            while let Ok(first) = rx.recv() {
                let mut batch = vec![first];
                while let Ok(more) = rx.try_recv() {
                    batch.push(more);
                }
                for op in merge_ops(batch) {
                    match op {
                        PointerOp::Move(dx, dy) => {
                            let (x, y) = ((dx + carry.0).clamp(-3000.0, 3000.0), (dy + carry.1).clamp(-3000.0, 3000.0));
                            let (ix, iy) = (x.round() as i64, y.round() as i64);
                            carry = (x - ix as f64, y - iy as f64);
                            if ix != 0 || iy != 0 {
                                pointer_move_now(ix, iy);
                            }
                        }
                        PointerOp::Click(b) => pointer_click_now(&b),
                    }
                }
            }
        });
        tx
    })
}

/// Queues a relative move. Returns at once, so the connection that sent it is never held up by a slow tool.
pub fn simulate_pointer_move(dx: f64, dy: f64) {
    let _ = pointer_tx().send(PointerOp::Move(dx, dy));
}

pub fn simulate_pointer_click(button: &str) {
    let _ = pointer_tx().send(PointerOp::Click(button.to_string()));
}

/// `action` is "PLAY_PAUSE", "NEXT" or anything else for "previous".
///
/// Tries `playerctl` first and, when that is missing or fails, talks to the player directly: every media player on Linux
/// (Spotify, Firefox, VLC, Plasma's own) offers the MPRIS interface on the session bus, and `busctl` ships with systemd, so this
/// works on every system whether or not `playerctl` was ever installed. Before this, the buttons did nothing without playerctl.
pub fn control_mpris2_media(action: &str) {
    let (ctl, method) = match action {
        "PLAY_PAUSE" => ("play-pause", "PlayPause"),
        "NEXT" => ("next", "Next"),
        _ => ("previous", "Previous"),
    };
    let via_playerctl = Command::new("playerctl")
        .arg(ctl)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false);
    if via_playerctl {
        return;
    }
    match pick_player() {
        Some(name) => {
            let ok = Command::new("busctl")
                .args(["--user", "call", &name, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", method])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
                .map(|s| s.success())
                .unwrap_or(false);
            if !ok {
                debug!("MPRIS call {method} on {name} failed");
            }
        }
        None => debug!("no media player is running"),
    }
}

/// What is playing right now, for the phone's Remote screen. `None` when no player is running.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NowPlaying {
    pub title: String,
    pub artist: String,
    pub playing: bool,
}

pub fn now_playing() -> Option<NowPlaying> {
    let name = pick_player()?;
    let meta = busctl_json(&["get-property", &name, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Metadata"])?;
    let status = busctl_json(&["get-property", &name, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "PlaybackStatus"])?;
    let (title, artist) = parse_metadata(&meta);
    Some(NowPlaying { title, artist, playing: parse_playback_status(&status) == "Playing" })
}

fn busctl_json(args: &[&str]) -> Option<String> {
    let out = Command::new("busctl")
        .arg("--user")
        .arg("--json=short")
        .args(args)
        .stderr(Stdio::null())
        .output()
        .ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

/// The player to act on: one that is playing, else the first one found.
fn pick_player() -> Option<String> {
    let list = busctl_json(&["list"])?;
    let names = parse_mpris_names(&list);
    names
        .iter()
        .find(|n| {
            busctl_json(&["get-property", n, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "PlaybackStatus"])
                .map(|s| parse_playback_status(&s) == "Playing")
                .unwrap_or(false)
        })
        .or_else(|| names.first())
        .cloned()
}

/// Bus names of media players from `busctl --user --json=short list` (`[{"name":"org.mpris.MediaPlayer2.vlc",...},...]`).
pub fn parse_mpris_names(json: &str) -> Vec<String> {
    let v: serde_json::Value = serde_json::from_str(json).unwrap_or_default();
    v.as_array()
        .map(|a| {
            a.iter()
                .filter_map(|e| e.get("name").and_then(|n| n.as_str()))
                .filter(|n| n.starts_with("org.mpris.MediaPlayer2.") && !n.ends_with(".playerctld"))
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default()
}

/// Title and artist from `get-property ... Metadata` (`{"type":"a{sv}","data":{"xesam:title":{"type":"s","data":"..."},...}}`).
pub fn parse_metadata(json: &str) -> (String, String) {
    let v: serde_json::Value = serde_json::from_str(json).unwrap_or_default();
    let data = v.get("data");
    let title = data
        .and_then(|d| d.get("xesam:title"))
        .and_then(|t| t.get("data"))
        .and_then(|t| t.as_str())
        .unwrap_or("")
        .to_string();
    let artist = data
        .and_then(|d| d.get("xesam:artist"))
        .and_then(|t| t.get("data"))
        .and_then(|t| t.as_array())
        .map(|a| a.iter().filter_map(|x| x.as_str()).collect::<Vec<_>>().join(", "))
        .unwrap_or_default();
    (title, artist)
}

/// "Playing", "Paused" or "Stopped" from `get-property ... PlaybackStatus` (`{"type":"s","data":"Playing"}`).
pub fn parse_playback_status(json: &str) -> String {
    let v: serde_json::Value = serde_json::from_str(json).unwrap_or_default();
    v.get("data").and_then(|d| d.as_str()).unwrap_or("").to_string()
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn consecutive_moves_are_added_and_clicks_keep_their_place() {
        let ops = vec![
            PointerOp::Move(1.0, 2.0),
            PointerOp::Move(3.0, -1.0),
            PointerOp::Click("LEFT".into()),
            PointerOp::Move(5.0, 5.0),
            PointerOp::Move(1.0, 1.0),
        ];
        assert_eq!(
            merge_ops(ops),
            vec![PointerOp::Move(4.0, 1.0), PointerOp::Click("LEFT".into()), PointerOp::Move(6.0, 6.0)]
        );
    }

    #[test]
    fn media_players_are_found_by_their_mpris_bus_name() {
        let json = r#"[{"name":"org.freedesktop.DBus","pid":1},{"name":"org.mpris.MediaPlayer2.vlc","pid":5},
            {"name":"org.mpris.MediaPlayer2.playerctld","pid":6},{"name":":1.42","pid":7},{"name":"org.mpris.MediaPlayer2.firefox.instance_1_2","pid":8}]"#;
        assert_eq!(parse_mpris_names(json), vec!["org.mpris.MediaPlayer2.vlc", "org.mpris.MediaPlayer2.firefox.instance_1_2"]);
        assert!(parse_mpris_names("not json").is_empty());
    }

    #[test]
    fn title_artist_and_status_are_read_from_busctl_json() {
        let meta = r#"{"type":"a{sv}","data":{"xesam:title":{"type":"s","data":"Song"},
            "xesam:artist":{"type":"as","data":["A","B"]},"mpris:length":{"type":"x","data":1}}}"#;
        assert_eq!(parse_metadata(meta), ("Song".to_string(), "A, B".to_string()));
        assert_eq!(parse_metadata("{}"), (String::new(), String::new()));
        assert_eq!(parse_playback_status(r#"{"type":"s","data":"Playing"}"#), "Playing");
        assert_eq!(parse_playback_status("junk"), "");
    }
}

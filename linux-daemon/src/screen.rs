//! Seeing the computer's screen from the phone (the "remote desktop" half that shows; the half that clicks is `INPUT_EVENT`).
//!
//! How: the screen is captured as often as the phone asks (1 to 5 times a second) with whatever tool the system has
//! (Spectacle on Plasma, grim on wlroots compositors, maim/import on X11), shrunk to the phone's width and sent as a JPEG
//! inside a `SCREEN_FRAME` packet. A frame identical to the last one is not sent. This is simple on purpose: alpha quality,
//! a few frames a second, no sound, no video codec. A real video path (PipeWire + hardware H.264) can replace `capture` later
//! without touching the permission or the protocol around it.
//!
//! Safety: it is its own per-phone permission (`allow_screen`, off by default) because a screen shows everything. See
//! `docs/SECURITY.md`.

use image::{codecs::jpeg::JpegEncoder, imageops::FilterType, ImageEncoder};
use std::process::{Command, Stdio};

pub const MIN_WIDTH: u32 = 320;
pub const MAX_WIDTH: u32 = 1920;
pub const MAX_FPS: u32 = 5;

static LAST_SIZE: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Size of the screen in pixels as last captured, so a tap at (0.5, 0.5) on the phone can become a real position.
pub fn last_size() -> Option<(u32, u32)> {
    let v = LAST_SIZE.load(std::sync::atomic::Ordering::Relaxed);
    if v == 0 { None } else { Some(((v >> 32) as u32, v as u32)) }
}

pub fn remember_size(w: u32, h: u32) {
    LAST_SIZE.store(((w as u64) << 32) | h as u64, std::sync::atomic::Ordering::Relaxed);
}

pub struct Frame {
    pub width: u32,
    pub height: u32,
    pub source_width: u32,
    pub source_height: u32,
    pub jpeg: Vec<u8>,
}

pub fn clamp_request(max_width: u64, fps: u64) -> (u32, u32) {
    ((max_width as u32).clamp(MIN_WIDTH, MAX_WIDTH), (fps as u32).clamp(1, MAX_FPS))
}

/// Shrinks an encoded image (any format the `image` crate reads) to at most `max_width` wide and encodes it as JPEG.
pub fn shrink_to_jpeg(bytes: &[u8], max_width: u32, quality: u8) -> Option<Frame> {
    let img = image::load_from_memory(bytes).ok()?;
    let (sw, sh) = (img.width(), img.height());
    let small = if sw > max_width {
        img.resize(max_width, (sh as u64 * max_width as u64 / sw as u64).max(1) as u32, FilterType::Triangle)
    } else {
        img
    };
    let rgb = small.to_rgb8();
    let mut out = Vec::new();
    JpegEncoder::new_with_quality(&mut out, quality)
        .write_image(rgb.as_raw(), rgb.width(), rgb.height(), image::ExtendedColorType::Rgb8)
        .ok()?;
    remember_size(sw, sh);
    Some(Frame { width: rgb.width(), height: rgb.height(), source_width: sw, source_height: sh, jpeg: out })
}

/// The desktop session's environment, rebuilt when the daemon (a systemd user service) was not given it: without a Wayland
/// socket, a display or the session bus, every capture tool fails with a message nobody sees. Pure so it can be tested.
pub fn session_env(runtime_dir: &str, existing: &dyn Fn(&str) -> Option<String>, wayland_sockets: &[String]) -> Vec<(String, String)> {
    let mut out = Vec::new();
    if existing("XDG_RUNTIME_DIR").is_none() {
        out.push(("XDG_RUNTIME_DIR".to_string(), runtime_dir.to_string()));
    }
    if existing("WAYLAND_DISPLAY").is_none() {
        if let Some(sock) = wayland_sockets.iter().find(|n| n.starts_with("wayland-") && !n.ends_with(".lock")) {
            out.push(("WAYLAND_DISPLAY".to_string(), sock.clone()));
        }
    }
    if existing("DISPLAY").is_none() {
        out.push(("DISPLAY".to_string(), ":0".to_string()));
    }
    if existing("DBUS_SESSION_BUS_ADDRESS").is_none() {
        out.push(("DBUS_SESSION_BUS_ADDRESS".to_string(), format!("unix:path={runtime_dir}/bus")));
    }
    out
}

fn desktop_env() -> Vec<(String, String)> {
    let uid = unsafe { geteuid() };
    let runtime = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| format!("/run/user/{uid}"));
    let sockets: Vec<String> = std::fs::read_dir(&runtime)
        .map(|d| d.filter_map(|e| e.ok()).map(|e| e.file_name().to_string_lossy().into_owned()).collect())
        .unwrap_or_default();
    session_env(&runtime, &|k| std::env::var(k).ok(), &sockets)
}

extern "C" {
    fn geteuid() -> u32;
}

/// Takes one picture of the whole screen (PNG bytes), or says why not, in words a person can act on.
/// `ZOHARA_LINK_SCREEN_CMD` overrides the tool: a shell command that writes an image to the path in `$OUT` (tests use it).
pub fn capture() -> Result<Vec<u8>, String> {
    let dir = std::env::temp_dir().join(format!("zohara-link-screen-{}", std::process::id()));
    let _ = std::fs::create_dir_all(&dir);
    let path = dir.join("frame.png");
    let p = path.to_string_lossy().to_string();
    let env = desktop_env();
    let attempts: Vec<(String, Vec<String>)> = if let Some(cmd) = std::env::var_os("ZOHARA_LINK_SCREEN_CMD") {
        vec![("sh".into(), vec!["-c".into(), cmd.to_string_lossy().into_owned()])]
    } else {
        vec![
            ("spectacle".into(), vec!["-b".into(), "-n".into(), "-f".into(), "-o".into(), p.clone()]),
            ("grim".into(), vec![p.clone()]),
            ("maim".into(), vec![p.clone()]),
            ("import".into(), vec!["-window".into(), "root".into(), p.clone()]),
        ]
    };
    let mut problems: Vec<String> = Vec::new();
    for (cmd, args) in attempts {
        let _ = std::fs::remove_file(&path);
        let out = Command::new(&cmd).args(&args).envs(env.iter().map(|(k, v)| (k, v))).env("OUT", &p).stdin(Stdio::null()).output();
        match out {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => problems.push(format!("{cmd} is not installed")),
            Err(e) => problems.push(format!("{cmd}: {e}")),
            Ok(o) => match std::fs::read(&path) {
                Ok(bytes) if !bytes.is_empty() => {
                    let _ = std::fs::remove_file(&path);
                    return Ok(bytes);
                }
                _ => {
                    let err = String::from_utf8_lossy(&o.stderr);
                    let last = err.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("it made no picture");
                    problems.push(format!("{cmd}: {}", last.chars().take(160).collect::<String>()));
                }
            },
        }
    }
    Err(problems.join("; "))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png(w: u32, h: u32) -> Vec<u8> {
        let img = image::RgbImage::from_fn(w, h, |x, y| image::Rgb([(x % 256) as u8, (y % 256) as u8, 128]));
        let mut out = std::io::Cursor::new(Vec::new());
        img.write_to(&mut out, image::ImageFormat::Png).unwrap();
        out.into_inner()
    }

    #[test]
    fn a_wide_screen_is_shrunk_and_stays_in_proportion() {
        let f = shrink_to_jpeg(&png(1920, 1080), 640, 60).unwrap();
        assert_eq!((f.width, f.height), (640, 360));
        assert_eq!((f.source_width, f.source_height), (1920, 1080));
        assert_eq!(&f.jpeg[..2], &[0xFF, 0xD8]); // JPEG magic
    }

    #[test]
    fn a_small_screen_is_not_blown_up() {
        let f = shrink_to_jpeg(&png(400, 300), 1280, 60).unwrap();
        assert_eq!((f.width, f.height), (400, 300));
    }

    #[test]
    fn the_screen_size_is_remembered_for_absolute_taps() {
        shrink_to_jpeg(&png(800, 600), 400, 60).unwrap();
        assert_eq!(last_size(), Some((800, 600)));
    }

    #[test]
    fn garbage_is_no_frame() {
        assert!(shrink_to_jpeg(b"not an image", 640, 60).is_none());
    }

    #[test]
    fn a_service_without_the_session_gets_one_built() {
        let none = |_: &str| None;
        let env = session_env("/run/user/1000", &none, &["bus".into(), "wayland-0.lock".into(), "wayland-0".into()]);
        let get = |k: &str| env.iter().find(|(n, _)| n == k).map(|(_, v)| v.clone());
        assert_eq!(get("WAYLAND_DISPLAY").as_deref(), Some("wayland-0"));
        assert_eq!(get("XDG_RUNTIME_DIR").as_deref(), Some("/run/user/1000"));
        assert_eq!(get("DBUS_SESSION_BUS_ADDRESS").as_deref(), Some("unix:path=/run/user/1000/bus"));
        assert_eq!(get("DISPLAY").as_deref(), Some(":0"));
    }

    #[test]
    fn what_the_session_already_has_is_left_alone() {
        let all = |k: &str| Some(format!("set-{k}"));
        assert!(session_env("/run/user/1000", &all, &["wayland-0".into()]).is_empty());
    }

    #[test]
    fn requests_are_clamped() {
        assert_eq!(clamp_request(10, 0), (MIN_WIDTH, 1));
        assert_eq!(clamp_request(99999, 99), (MAX_WIDTH, MAX_FPS));
        assert_eq!(clamp_request(800, 2), (800, 2));
    }
}

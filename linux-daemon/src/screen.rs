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

/// Takes one picture of the whole screen (PNG bytes). `ZOHARA_LINK_SCREEN_CMD` overrides the tool: a shell command that writes
/// an image to the path in `$OUT` (tests use it; so can a person with an unusual setup).
pub fn capture() -> Option<Vec<u8>> {
    let dir = std::env::temp_dir().join(format!("zohara-link-screen-{}", std::process::id()));
    let _ = std::fs::create_dir_all(&dir);
    let path = dir.join("frame.png");
    let p = path.to_string_lossy().to_string();
    let _ = std::fs::remove_file(&path);
    let ran = if let Some(cmd) = std::env::var_os("ZOHARA_LINK_SCREEN_CMD") {
        Command::new("sh").arg("-c").arg(cmd).env("OUT", &p).stdout(Stdio::null()).stderr(Stdio::null()).status().map(|s| s.success()).unwrap_or(false)
    } else {
        [("spectacle", vec!["-b", "-n", "-f", "-o", p.as_str()]), ("grim", vec![p.as_str()]), ("maim", vec![p.as_str()]), ("import", vec!["-window", "root", p.as_str()])]
            .iter()
            .any(|(cmd, args)| Command::new(cmd).args(args).stdout(Stdio::null()).stderr(Stdio::null()).status().map(|s| s.success()).unwrap_or(false))
    };
    if !ran {
        return None;
    }
    let bytes = std::fs::read(&path).ok();
    let _ = std::fs::remove_file(&path);
    bytes
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
    fn requests_are_clamped() {
        assert_eq!(clamp_request(10, 0), (MIN_WIDTH, 1));
        assert_eq!(clamp_request(99999, 99), (MAX_WIDTH, MAX_FPS));
        assert_eq!(clamp_request(800, 2), (800, 2));
    }
}

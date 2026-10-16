//! Size and time limits for anything that arrives from the network, kept in one place and testable without sockets.
//!
//! Why: `lines()` reads until a newline however long it takes, so one connection that never sends one makes the daemon
//! hold memory without bound, and a connection that never pairs could sit open forever. Every limit here is generous for a
//! real phone (its file chunks are 32 KB, about 44 KB as base64) and tiny for an attacker.

use std::io;
use tokio::io::{AsyncBufRead, AsyncBufReadExt};

/// Longest accepted line (one JSON packet). A 32 KB file chunk is about 44 KB once base64-encoded.
pub const MAX_LINE: usize = 256 * 1024;
/// A connection must pair or present its token within this many seconds, or it is dropped.
pub const AUTH_DEADLINE_SECS: u64 = 60;
/// Connections open at once, and how many of those may still be unauthenticated.
pub const MAX_CONNECTIONS: usize = 32;
pub const MAX_UNAUTHENTICATED: usize = 8;
/// Largest file the phone may send, and how many transfers may run at once.
pub const MAX_FILE_BYTES: u64 = 4 * 1024 * 1024 * 1024;
pub const MAX_TRANSFERS: usize = 4;
/// Files offered to phones and not yet answered or finished (a folder of many files, or a copy of several files at once).
pub const MAX_OFFERS: usize = 32;

/// Reads one `\n`-terminated line of at most `max` bytes. `Ok(None)` is a clean end of stream; a longer line is an error
/// (the caller drops the connection) instead of growing the buffer.
pub async fn read_line_bounded<R: AsyncBufRead + Unpin>(r: &mut R, max: usize) -> io::Result<Option<String>> {
    let mut line: Vec<u8> = Vec::new();
    loop {
        let (done, used) = {
            let buf = r.fill_buf().await?;
            if buf.is_empty() {
                return if line.is_empty() { Ok(None) } else { Ok(Some(String::from_utf8_lossy(&line).into_owned())) };
            }
            match buf.iter().position(|b| *b == b'\n') {
                Some(i) => {
                    line.extend_from_slice(&buf[..i]);
                    (true, i + 1)
                }
                None => {
                    line.extend_from_slice(buf);
                    (false, buf.len())
                }
            }
        };
        r.consume(used);
        if line.len() > max {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "line too long"));
        }
        if done {
            return Ok(Some(String::from_utf8_lossy(&line).into_owned()));
        }
    }
}

/// A file name that does not exist yet in `dir`: `name`, then `name (1)`, `name (2)`... so a phone can never overwrite a file.
pub fn unique_path(dir: &std::path::Path, name: &str) -> std::path::PathBuf {
    let first = dir.join(name);
    if !first.exists() {
        return first;
    }
    let p = std::path::Path::new(name);
    let stem = p.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| name.to_string());
    let ext = p.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    (1..10_000)
        .map(|n| dir.join(format!("{stem} ({n}){ext}")))
        .find(|c| !c.exists())
        .unwrap_or_else(|| dir.join(format!("{stem}-{}{ext}", std::process::id())))
}

/// Stops the same phone notification showing again and again: apps such as a health tracker re-post their notification every few
/// seconds, and each re-post arrived as a new pop-up. The same app, title and text is shown once per two minutes, and one app
/// can show at most 12 pop-ups a minute whatever they say.
#[derive(Default)]
pub struct NotifyGate {
    seen: std::collections::HashMap<String, std::time::Instant>,
    per_app: std::collections::HashMap<String, Vec<std::time::Instant>>,
}

impl NotifyGate {
    pub const REPEAT_WINDOW: std::time::Duration = std::time::Duration::from_secs(120);
    pub const PER_APP_PER_MINUTE: usize = 12;

    pub fn allow(&mut self, app: &str, title: &str, text: &str, now: std::time::Instant) -> bool {
        self.seen.retain(|_, t| now.duration_since(*t) < Self::REPEAT_WINDOW);
        let key = format!("{app}\u{1}{title}\u{1}{text}");
        if self.seen.contains_key(&key) {
            return false;
        }
        let recent = self.per_app.entry(app.to_string()).or_default();
        recent.retain(|t| now.duration_since(*t) < std::time::Duration::from_secs(60));
        if recent.len() >= Self::PER_APP_PER_MINUTE {
            return false;
        }
        recent.push(now);
        self.seen.insert(key, now);
        true
    }
}

/// Text for a desktop notification: control characters removed and the length capped, so a phone cannot flood or garble it.
pub fn clean_text(s: &str, max_chars: usize) -> String {
    s.chars().filter(|c| !c.is_control() || *c == '\n').take(max_chars).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::BufReader;

    #[tokio::test]
    async fn reads_lines_and_end_of_stream() {
        let mut r = BufReader::new(&b"one\ntwo\nlast"[..]);
        assert_eq!(read_line_bounded(&mut r, 100).await.unwrap().as_deref(), Some("one"));
        assert_eq!(read_line_bounded(&mut r, 100).await.unwrap().as_deref(), Some("two"));
        assert_eq!(read_line_bounded(&mut r, 100).await.unwrap().as_deref(), Some("last"));
        assert_eq!(read_line_bounded(&mut r, 100).await.unwrap(), None);
    }

    #[tokio::test]
    async fn a_line_with_no_end_is_refused_not_buffered_forever() {
        let big = vec![b'a'; 1000];
        let mut r = BufReader::with_capacity(64, &big[..]);
        assert!(read_line_bounded(&mut r, 200).await.is_err());
        let mut ok = BufReader::with_capacity(64, &b"short\n"[..]);
        assert_eq!(read_line_bounded(&mut ok, 200).await.unwrap().as_deref(), Some("short"));
    }

    #[test]
    fn unique_path_never_overwrites() {
        let d = std::env::temp_dir().join(format!("zl-unique-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        let first = unique_path(&d, "a.txt");
        assert_eq!(first, d.join("a.txt"));
        std::fs::write(&first, "x").unwrap();
        let second = unique_path(&d, "a.txt");
        assert_eq!(second, d.join("a (1).txt"));
        std::fs::write(&second, "x").unwrap();
        assert_eq!(unique_path(&d, "a.txt"), d.join("a (2).txt"));
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn the_same_notification_is_shown_once_not_every_few_seconds() {
        let mut g = NotifyGate::default();
        let t0 = std::time::Instant::now();
        assert!(g.allow("Health", "Steps", "1,200 steps", t0));
        assert!(!g.allow("Health", "Steps", "1,200 steps", t0 + std::time::Duration::from_secs(5)));
        assert!(!g.allow("Health", "Steps", "1,200 steps", t0 + std::time::Duration::from_secs(100)));
        // different text (a real new message) is shown, and the same text again after the window is shown too
        assert!(g.allow("Health", "Steps", "1,300 steps", t0 + std::time::Duration::from_secs(6)));
        assert!(g.allow("Health", "Steps", "1,200 steps", t0 + std::time::Duration::from_secs(130)));
    }

    #[test]
    fn one_app_cannot_flood_the_desktop() {
        let mut g = NotifyGate::default();
        let t0 = std::time::Instant::now();
        for i in 0..NotifyGate::PER_APP_PER_MINUTE {
            assert!(g.allow("Chatty", "t", &format!("msg {i}"), t0));
        }
        assert!(!g.allow("Chatty", "t", "one more", t0));
        assert!(g.allow("Other", "t", "hello", t0), "other apps are not affected");
        assert!(g.allow("Chatty", "t", "later", t0 + std::time::Duration::from_secs(61)));
    }

    #[test]
    fn clean_text_drops_control_characters_and_caps_length() {
        assert_eq!(clean_text("a\u{1b}[31mb\u{0}c", 100), "a[31mbc");
        assert_eq!(clean_text("abcdef", 3), "abc");
    }
}

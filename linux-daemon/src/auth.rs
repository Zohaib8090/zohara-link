//! Pairing PIN and reconnect-token rules, kept free of sockets so they can be tested on their own.
//!
//! Why this exists: before it, a PIN stayed valid until someone guessed it (a million tries over the LAN), and a phone that
//! had paired once still had to redo the PIN on every connection -- the Android app skipped that step for "already paired"
//! devices and the daemon then refused every packet. Now a PIN dies after a few wrong tries or two minutes, and a successful
//! pairing hands the phone a random token. Only the token's SHA-256 is stored, so reading `paired_devices.json` does not let
//! anyone pose as the phone.

use rand::RngCore;
use sha2::{Digest, Sha256};
use std::time::{Duration, Instant};

pub const PIN_LIFETIME: Duration = Duration::from_secs(120);
pub const MAX_PIN_TRIES: u32 = 3;

/// A pairing PIN waiting for the phone to type it.
pub struct PendingPin {
    pin: String,
    /// The name the phone gave itself, for the pairing screen.
    pub name: String,
    created: Instant,
    tries: u32,
}

#[derive(Debug, PartialEq, Eq)]
pub enum PinCheck {
    Match,
    Wrong,
    /// Too many wrong tries or too old: the phone must ask for a new PIN.
    Expired,
}

impl PendingPin {
    pub fn new(pin: String) -> Self {
        Self { pin, name: String::new(), created: Instant::now(), tries: 0 }
    }

    pub fn pin(&self) -> &str {
        &self.pin
    }

    /// Still usable: not too old and not out of tries. Only live PINs are shown to the person.
    pub fn is_live(&self, now: Instant) -> bool {
        self.tries < MAX_PIN_TRIES && now.duration_since(self.created) <= PIN_LIFETIME
    }

    /// Checks one attempt. A wrong attempt counts; the third wrong one (or age) makes the PIN useless.
    pub fn check(&mut self, attempt: Option<&str>, now: Instant) -> PinCheck {
        if self.tries >= MAX_PIN_TRIES || now.duration_since(self.created) > PIN_LIFETIME {
            return PinCheck::Expired;
        }
        if matches!(attempt, Some(a) if constant_time_eq(a.as_bytes(), self.pin.as_bytes())) {
            return PinCheck::Match;
        }
        self.tries += 1;
        if self.tries >= MAX_PIN_TRIES {
            PinCheck::Expired
        } else {
            PinCheck::Wrong
        }
    }
}

/// A fresh random token (64 hex characters) the phone keeps and presents on reconnect.
pub fn new_token() -> String {
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    hex(&bytes)
}

pub fn token_hash(token: &str) -> String {
    hex(&Sha256::digest(token.as_bytes()))
}

/// True when `token` is the one whose hash was stored. An empty stored hash (a device paired before tokens existed) never matches.
pub fn token_matches(stored_hash: &str, token: &str) -> bool {
    !stored_hash.is_empty() && constant_time_eq(stored_hash.as_bytes(), token_hash(token).as_bytes())
}

/// SHA-256 of a DER certificate as lowercase hex.
pub fn fingerprint(der: &[u8]) -> String {
    hex(&Sha256::digest(der))
}

/// The fingerprint in groups of four for a person to compare: `ab12 cd34 ...` (first 16 hex digits).
pub fn short_fingerprint(full: &str) -> String {
    full.as_bytes().chunks(4).take(4).map(|c| String::from_utf8_lossy(c).into_owned()).collect::<Vec<_>>().join(" ")
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn right_pin_matches() {
        let mut p = PendingPin::new("123456".into());
        assert_eq!(p.check(Some("123456"), Instant::now()), PinCheck::Match);
    }

    #[test]
    fn wrong_pin_three_times_kills_it_even_if_the_fourth_is_right() {
        let mut p = PendingPin::new("123456".into());
        let now = Instant::now();
        assert_eq!(p.check(Some("000000"), now), PinCheck::Wrong);
        assert_eq!(p.check(None, now), PinCheck::Wrong);
        assert_eq!(p.check(Some("111111"), now), PinCheck::Expired);
        assert_eq!(p.check(Some("123456"), now), PinCheck::Expired);
    }

    #[test]
    fn liveness_follows_tries_and_age() {
        let mut p = PendingPin::new("123456".into());
        let now = Instant::now();
        assert!(p.is_live(now));
        p.check(Some("0"), now);
        p.check(Some("0"), now);
        assert!(p.is_live(now));
        p.check(Some("0"), now);
        assert!(!p.is_live(now));
        assert!(!PendingPin::new("1".into()).is_live(now + PIN_LIFETIME + Duration::from_secs(1)));
    }

    #[test]
    fn old_pin_expires() {
        let mut p = PendingPin::new("123456".into());
        let later = Instant::now() + PIN_LIFETIME + Duration::from_secs(1);
        assert_eq!(p.check(Some("123456"), later), PinCheck::Expired);
    }

    #[test]
    fn tokens_are_random_and_checked_by_hash() {
        let (a, b) = (new_token(), new_token());
        assert_eq!(a.len(), 64);
        assert_ne!(a, b);
        let h = token_hash(&a);
        assert!(token_matches(&h, &a));
        assert!(!token_matches(&h, &b));
        assert!(!token_matches("", &a), "devices paired before tokens existed must not match");
        assert!(!token_matches(&h, ""));
    }

    #[test]
    fn fingerprint_is_sha256_hex_and_short_form_groups_it() {
        // SHA-256 of the empty input.
        let f = fingerprint(b"");
        assert_eq!(f, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assert_eq!(short_fingerprint(&f), "e3b0 c442 98fc 1c14");
    }
}

//! Reaching the computer from outside the home network, through an overlay network (Tailscale, ZeroTier, NetBird, plain
//! WireGuard).
//!
//! Why this is small: the daemon already listens on every address, so a phone on the same overlay can connect today if it knows
//! the overlay address. What was missing was *knowing* that address. This module finds the overlay addresses of this computer
//! and the daemon tells every paired phone about them (`ADDRESSES` packet) after it authenticates, so the phone can fall back
//! to them when the local network does not work. Nothing here opens a port or weakens a check: pairing, the PIN, the pinned
//! certificate and the per-phone permissions are the same on every route.
//!
//! Detection reads `ip -j -4 addr` (works for every provider, needs nothing installed) and, for Tailscale, adds its machine
//! name and login state from `tailscale status --json` when the CLI exists.

use serde_json::{json, Value};
use std::net::Ipv4Addr;
use std::process::{Command, Stdio};

#[derive(Debug, Clone, PartialEq)]
pub struct Overlay {
    pub provider: &'static str,
    pub interface: String,
    pub address: String,
}

/// Which overlay does an interface belong to? By name, the way each provider names its device.
pub fn provider_of(interface: &str) -> Option<&'static str> {
    if interface.starts_with("tailscale") {
        Some("Tailscale")
    } else if interface.starts_with("zt") {
        Some("ZeroTier")
    } else if interface.starts_with("wt") || interface.starts_with("netbird") {
        Some("NetBird")
    } else if interface.starts_with("nebula") {
        Some("Nebula")
    } else if interface.starts_with("wg") {
        Some("WireGuard")
    } else {
        None
    }
}

/// 100.64.0.0/10, the "carrier-grade NAT" range Tailscale hands out; also a hint for unnamed overlay devices.
pub fn is_cgnat(ip: &str) -> bool {
    ip.parse::<Ipv4Addr>().map(|a| a.octets()[0] == 100 && (a.octets()[1] & 0xC0) == 64).unwrap_or(false)
}

/// Reads the output of `ip -j -4 addr`.
pub fn parse_ip_addr(json_text: &str) -> Vec<Overlay> {
    let Ok(Value::Array(ifaces)) = serde_json::from_str::<Value>(json_text) else { return vec![] };
    let mut out = Vec::new();
    for iface in ifaces {
        let name = iface.get("ifname").and_then(Value::as_str).unwrap_or("");
        let Some(addrs) = iface.get("addr_info").and_then(Value::as_array) else { continue };
        for a in addrs {
            let Some(ip) = a.get("local").and_then(Value::as_str) else { continue };
            let provider = provider_of(name).or_else(|| if is_cgnat(ip) { Some("Tailscale") } else { None });
            if let Some(provider) = provider {
                out.push(Overlay { provider, interface: name.to_string(), address: ip.to_string() });
            }
        }
    }
    out
}

pub fn detect() -> Vec<Overlay> {
    match run("ip", &["-j", "-4", "addr"]) {
        Some(text) => parse_ip_addr(&text),
        None => vec![],
    }
}

/// What `tailscale status --json` says about this machine.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct TailscaleInfo {
    pub state: String,
    pub dns_name: String,
    pub login_url: String,
}

pub fn parse_tailscale_status(json_text: &str) -> TailscaleInfo {
    let Ok(v) = serde_json::from_str::<Value>(json_text) else { return TailscaleInfo::default() };
    let s = |p: &Value| p.as_str().unwrap_or("").to_string();
    TailscaleInfo {
        state: s(&v["BackendState"]),
        dns_name: s(&v["Self"]["DNSName"]).trim_end_matches('.').to_string(),
        login_url: s(&v["AuthURL"]),
    }
}

/// First `https://` word in some text (the login link `tailscale up` prints).
pub fn find_login_url(text: &str) -> Option<String> {
    text.split_whitespace().find(|w| w.starts_with("https://")).map(|w| w.trim_matches(|c: char| !c.is_ascii_graphic()).to_string())
}

/// Everything Settings and the phone need, as JSON.
pub fn status_json(port: u16) -> Value {
    let overlays = detect();
    let ts = run("tailscale", &["status", "--json"]).map(|t| parse_tailscale_status(&t));
    let installed = ts.is_some();
    let mut list: Vec<Value> = overlays
        .iter()
        .map(|o| json!({"provider": o.provider, "interface": o.interface, "address": o.address, "port": port}))
        .collect();
    if let Some(t) = &ts {
        if !t.dns_name.is_empty() {
            for item in list.iter_mut().filter(|i| i["provider"] == "Tailscale") {
                item["dnsName"] = json!(t.dns_name);
            }
        }
    }
    json!({
        "overlays": list,
        "tailscale": {"installed": installed, "state": ts.as_ref().map(|t| t.state.clone()).unwrap_or_default(),
                       "loginUrl": ts.as_ref().map(|t| t.login_url.clone()).unwrap_or_default()},
    })
}

/// Starts `tailscale up` and returns the sign-in link it prints (it keeps running in the background until the person has
/// signed in). `None` when it is already signed in, not installed, or printed no link in time.
pub fn tailscale_login() -> Option<String> {
    use std::io::{BufRead, BufReader};
    let mut child = Command::new("tailscale")
        .args(["up", "--timeout=300s"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
        .ok()?;
    let stderr = child.stderr.take()?;
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            if let Some(u) = find_login_url(&line) {
                let _ = tx.send(u);
            }
        }
        let _ = child.wait(); // reaps it when the person has signed in (or the timeout passed)
    });
    rx.recv_timeout(std::time::Duration::from_secs(15)).ok()
}

fn run(cmd: &str, args: &[&str]) -> Option<String> {
    let out = Command::new(cmd).args(args).stdin(Stdio::null()).stderr(Stdio::null()).output().ok()?;
    if out.status.success() {
        Some(String::from_utf8_lossy(&out.stdout).into_owned())
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn overlays_are_found_by_device_name_or_by_range() {
        let text = r#"[
          {"ifname":"lo","addr_info":[{"local":"127.0.0.1"}]},
          {"ifname":"wlan0","addr_info":[{"local":"192.168.1.20"}]},
          {"ifname":"tailscale0","addr_info":[{"local":"100.101.102.103"}]},
          {"ifname":"ztabc123","addr_info":[{"local":"10.147.17.5"}]},
          {"ifname":"wg0","addr_info":[{"local":"10.8.0.2"}]},
          {"ifname":"mystery0","addr_info":[{"local":"100.90.1.1"}]},
          {"ifname":"eth0","addr_info":[{"local":"100.200.1.1"}]}
        ]"#;
        let found: Vec<(&str, String)> = parse_ip_addr(text).into_iter().map(|o| (o.provider, o.address)).collect();
        assert_eq!(
            found,
            vec![
                ("Tailscale", "100.101.102.103".to_string()),
                ("ZeroTier", "10.147.17.5".to_string()),
                ("WireGuard", "10.8.0.2".to_string()),
                ("Tailscale", "100.90.1.1".to_string()),
            ]
        );
    }

    #[test]
    fn cgnat_range_is_exactly_100_64_to_100_127() {
        assert!(is_cgnat("100.64.0.1") && is_cgnat("100.127.255.254"));
        assert!(!is_cgnat("100.63.0.1") && !is_cgnat("100.128.0.1") && !is_cgnat("192.168.1.1") && !is_cgnat("junk"));
    }

    #[test]
    fn garbage_gives_no_overlays() {
        assert!(parse_ip_addr("not json").is_empty());
        assert!(parse_ip_addr("{}").is_empty());
    }

    #[test]
    fn tailscale_status_is_read() {
        let t = parse_tailscale_status(r#"{"BackendState":"Running","Self":{"DNSName":"laptop.tail1234.ts.net."},"AuthURL":""}"#);
        assert_eq!(t, TailscaleInfo { state: "Running".into(), dns_name: "laptop.tail1234.ts.net".into(), login_url: "".into() });
        assert_eq!(parse_tailscale_status("x"), TailscaleInfo::default());
    }

    #[test]
    fn login_link_is_taken_from_the_cli_text() {
        assert_eq!(find_login_url("\nTo authenticate, visit:\n"), None);
        assert_eq!(find_login_url("\thttps://login.tailscale.com/a/abc123"), Some("https://login.tailscale.com/a/abc123".into()));
    }
}

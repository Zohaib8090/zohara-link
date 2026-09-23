//! zohara-link-status -- a small CLI client for zohara-linkd's local Unix
//! socket IPC. Prints current pairing/telemetry status, then streams
//! real-time daemon events until interrupted.
//!
//! Rust port of the old `gtk_settings_app.py` (which, despite the
//! filename, was never a GTK app -- just this same CLI demo). Renamed
//! accordingly; nothing here draws a window.
//!
//! `--waybar` prints just the paired phone's battery percentage and
//! exits -- the shape a Waybar `custom/` module's `exec` expects, run on
//! its own `interval`. The Android app's LinuxDaemonHubScreen already
//! told users to put `... gtk_settings_app.py --waybar` in their Waybar
//! config, but that flag was never actually implemented (the Python
//! script had no argument parsing at all); `waybar_module.json` used a
//! raw inline Python one-liner instead. This makes the documented flag
//! real, so `waybar_module.json` can just call this binary.

use clap::Parser;
use serde_json::Value;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;

#[derive(Parser)]
#[command(name = "zohara-link-status")]
struct Args {
    /// Print only the paired phone's battery percentage and exit
    /// (for a Waybar custom module's `exec`).
    #[arg(long)]
    waybar: bool,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args = Args::parse();

    let uid = unsafe {
        extern "C" {
            fn geteuid() -> u32;
        }
        geteuid()
    };
    let socket_path = format!("/run/user/{uid}/zohara.sock");

    if !std::path::Path::new(&socket_path).exists() {
        if args.waybar {
            println!("--");
        } else {
            eprintln!("[Error] Zohara daemon socket not found at {socket_path}. Is zohara-linkd running?");
        }
        std::process::exit(1);
    }

    let stream = UnixStream::connect(&socket_path).await?;
    let (read_half, mut write_half) = tokio::io::split(stream);
    let mut lines = BufReader::new(read_half).lines();

    write_half
        .write_all(b"{\"command\":\"GET_STATUS\"}\n")
        .await?;

    if args.waybar {
        let battery = match lines.next_line().await? {
            Some(line) => {
                let data: Value = serde_json::from_str(&line).unwrap_or(Value::Null);
                data.get("telemetry")
                    .and_then(Value::as_object)
                    .and_then(|t| t.values().next())
                    .and_then(|tel| tel.get("batteryLevel"))
                    .map(|v| v.to_string())
                    .unwrap_or_else(|| "--".to_string())
            }
            None => "--".to_string(),
        };
        println!("{battery}");
        return Ok(());
    }

    println!("=== Connected to Zohara Link Linux Daemon ===");
    if let Some(line) = lines.next_line().await? {
        let data: Value = serde_json::from_str(&line)?;
        let paired = data.get("paired_devices").and_then(Value::as_array).cloned().unwrap_or_default();

        println!("\n--- Current Ecosystem Status ---");
        println!("Paired Devices: {}", paired.len());
        for dev in &paired {
            let name = dev.get("device_name").and_then(Value::as_str).unwrap_or("?");
            let id = dev.get("device_id").and_then(Value::as_str).unwrap_or("?");
            let ip = dev.get("last_ip").and_then(Value::as_str).unwrap_or("?");
            println!("  - {name} (ID: {id}, Last IP: {ip})");
        }

        println!("\n--- Live Device Telemetry ---");
        if let Some(telemetry) = data.get("telemetry").and_then(Value::as_object) {
            for (dev_id, tel) in telemetry {
                let battery = tel.get("batteryLevel").map(|v| v.to_string()).unwrap_or_else(|| "N/A".into());
                let charging = if tel.get("isCharging").and_then(Value::as_bool).unwrap_or(false) {
                    "\u{26a1} Charging"
                } else {
                    "\u{1f50b} On Battery"
                };
                let wifi = tel.get("wifiSsid").and_then(Value::as_str).unwrap_or("Unknown");
                println!("  [{dev_id}] Battery: {battery}% ({charging}) | Wi-Fi: {wifi}");
            }
        }
    }

    println!("\nListening for real-time daemon events (Ctrl+C to quit)...");
    loop {
        match lines.next_line().await {
            Ok(Some(line)) => {
                if let Ok(event) = serde_json::from_str::<Value>(&line) {
                    let ev = event.get("event").and_then(Value::as_str).unwrap_or("?");
                    let data = event.get("data").cloned().unwrap_or(Value::Null);
                    println!(">> Event [{ev}]: {data}");
                }
            }
            Ok(None) => break,
            Err(_) => break,
        }
    }

    Ok(())
}

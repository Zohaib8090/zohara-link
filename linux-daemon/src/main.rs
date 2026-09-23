//! Zohara Link Daemon (zohara-linkd)
//!
//! A native Arch Linux system daemon providing encrypted companion
//! synchronization with the Zohara Link Android app: TLS/TCP for the phone,
//! a local Unix-socket IPC for desktop clients (Waybar, GTK settings
//! panels), mDNS advertisement, clipboard sync, notification mirroring,
//! file transfer, and remote pointer/media control.
//!
//! Rust port of the original Python prototype (`daemon.py`). See
//! individual module doc comments for what changed in the port versus a
//! literal translation.

mod daemon;
mod desktop;
mod mdns;
mod protocol;
mod tls;

use clap::Parser;

#[derive(Parser, Debug)]
#[command(name = "zohara-linkd", about = "Zohara Link Linux companion daemon")]
struct Args {
    /// TCP port to listen on for the Android app.
    #[arg(long, default_value_t = 42424)]
    port: u16,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    env_logger::Builder::from_env(
        env_logger::Env::default().default_filter_or("info"),
    )
    .format_timestamp_secs()
    .init();

    let args = Args::parse();
    let daemon = daemon::Daemon::new(args.port)?;
    daemon.run().await
}

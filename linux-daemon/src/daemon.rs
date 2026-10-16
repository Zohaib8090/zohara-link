use crate::protocol::{FileTransfer, PairedDevice};
use crate::{auth, desktop, limits, mdns, remote, screen, tls, uinput};
use anyhow::{Context, Result};
use rand::Rng;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Instant, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream, UnixListener, UnixStream};
use tokio::sync::{broadcast, Mutex as TokioMutex};
use tokio_rustls::TlsAcceptor;

type TlsWriteHalf = tokio::io::WriteHalf<tokio_rustls::server::TlsStream<TcpStream>>;
type SharedWriter = Arc<TokioMutex<TlsWriteHalf>>;

/// A file offered to a phone (desktop to phone). `connection` is the live connection it was offered on.
struct OutgoingFile {
    connection: String,
    path: PathBuf,
    name: String,
    size: u64,
    /// A file made just for this transfer (a zipped folder, a copied picture): deleted when the transfer ends.
    temp: bool,
}

/// Where zipped folders and copied pictures wait while they are offered.
fn outgoing_temp_dir() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_default()).join(".cache/zohara-link/outgoing")
}

/// Ends an offer: a file made for it goes away.
fn forget_outgoing(o: &OutgoingFile) {
    if o.temp {
        let _ = std::fs::remove_file(&o.path);
    }
}

/// `dir` and everything in it as a zip at `out` (links are skipped, unreadable files are left out). Returns the bytes of the
/// files put in, or an error when there are more than the size limit allows.
fn zip_folder(dir: &std::path::Path, out: &std::path::Path) -> std::io::Result<u64> {
    let root = dir.parent().unwrap_or(dir);
    let mut zip = zip::ZipWriter::new(std::fs::File::create(out)?);
    let options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated).large_file(true);
    let (mut total, mut stack) = (0u64, vec![dir.to_path_buf()]);
    // the folder itself, so an empty folder still arrives
    zip.add_directory(format!("{}/", dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "folder".into())), options)?;
    while let Some(d) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&d) else { continue };
        for entry in entries.flatten() {
            let Ok(kind) = entry.file_type() else { continue };
            let path = entry.path();
            let name = path.strip_prefix(root).unwrap_or(&path).to_string_lossy().replace('\\', "/");
            if kind.is_symlink() {
                continue;
            }
            if kind.is_dir() {
                zip.add_directory(format!("{name}/"), options)?;
                stack.push(path);
            } else if kind.is_file() {
                let Ok(mut f) = std::fs::File::open(&path) else { continue };
                total += f.metadata().map(|m| m.len()).unwrap_or(0);
                if total > limits::MAX_FILE_BYTES {
                    return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "too big"));
                }
                zip.start_file(name, options)?;
                std::io::copy(&mut f, &mut zip)?;
            }
        }
    }
    zip.finish()?;
    Ok(total)
}

/// Raw bytes per chunk sent to a phone: about 64 KiB once base64-encoded, well under the 256 KiB line limit.
const PUSH_CHUNK_BYTES: usize = 48 * 1024;

pub struct Daemon {
    port: u16,
    downloads_dir: PathBuf,
    paired_devices_file: PathBuf,
    cert_path: PathBuf,
    key_path: PathBuf,
    unix_socket_path: PathBuf,
    /// Phones may only start pairing while this is in the future. Closed by default: the person opens it from Settings
    /// ("Pair a phone"), so a stranger on the Wi-Fi cannot make PIN pop-ups appear on their own.
    pairing_open_until: TokioMutex<Option<Instant>>,
    /// SHA-256 of this daemon's TLS certificate: what phones pin and what the pairing notification shows.
    fingerprint: String,

    paired_devices: Arc<TokioMutex<HashMap<String, PairedDevice>>>,
    /// PIN waiting for each device id, with its tries and age (see `auth::PendingPin`).
    pending_pairings: TokioMutex<HashMap<String, auth::PendingPin>>,
    /// Connections (keyed by the connection-scoped "dev_<ip>_<port>" id)
    /// that have completed PAIR_VERIFY *on this TCP connection*. Nothing
    /// else here acts on a packet without this -- see handle_packet's gate.
    /// This is connection-scoped, not device-scoped: there is no
    /// persistent session token or verified client certificate (that
    /// needs real mTLS client-cert pinning, tracked separately), so a
    /// previously-paired device still has to redo the PIN handshake on
    /// every reconnect. Real UX cost, not a bug in this port.
    /// Connection id -> the paired device that proved itself on it (PIN just now, or its stored token).
    authenticated_connections: TokioMutex<HashMap<String, String>>,
    /// Live TCP connections, so the desktop side (Unix IPC "SEND_CLIPBOARD",
    /// and the clipboard watcher loop) can push to paired phones. The
    /// Python daemon this replaced declared the equivalent dict but never
    /// actually inserted into it anywhere -- it registered a connection
    /// only for removal in its `finally` block -- so desktop-to-phone
    /// clipboard push could never have worked. Fixed here: populated as
    /// soon as a TCP connection accepts.
    active_connections: TokioMutex<HashMap<String, SharedWriter>>,
    device_telemetry: TokioMutex<HashMap<String, Value>>,
    active_file_transfers: TokioMutex<HashMap<String, FileTransfer>>,
    /// Files the computer is sending to a phone: offered first, streamed only after the phone accepts.
    outgoing_files: Arc<TokioMutex<HashMap<String, OutgoingFile>>>,
    last_clipboard_text: TokioMutex<String>,
    /// Whether copied files, folders and pictures are offered to phones (Settings switch; off until chosen).
    clipboard_files: std::sync::atomic::AtomicBool,
    options_file: PathBuf,
    /// What was last offered from the clipboard, so one copy is offered once.
    last_clip_items: TokioMutex<String>,
    /// The last "now playing" state pushed to phones, so a phone that connects later gets it at once.
    last_media: TokioMutex<Option<Value>>,
    /// Drops repeated and flooding phone notifications before they reach the desktop.
    notify_gate: std::sync::Mutex<limits::NotifyGate>,
    /// Fan-out to local Unix-socket IPC clients (Waybar, a GTK settings
    /// panel, `zohara-link-status`, ...).
    ipc_broadcast: broadcast::Sender<String>,
    /// Running screen streams, one per connection, so they end with the connection or when the permission is taken away.
    screen_streams: TokioMutex<HashMap<String, tokio::task::JoinHandle<()>>>,
    /// Live game streams (the `zohara-linkd-stream` program), one per connection. See docs/STREAMING.md.
    live_streams: TokioMutex<HashMap<String, LiveStream>>,
}

/// The stream program of one connection: where commands are written, and the task that owns the process and reads what it says.
struct LiveStream {
    stdin: Arc<TokioMutex<tokio::process::ChildStdin>>,
    task: tokio::task::JoinHandle<()>,
}

/// Where the stream program is: next to the daemon, or `ZOHARA_LINK_STREAM_BIN`, or on the PATH.
fn stream_program() -> std::ffi::OsString {
    if let Some(p) = std::env::var_os("ZOHARA_LINK_STREAM_BIN") {
        return p;
    }
    if let Ok(exe) = std::env::current_exe() {
        let sibling = exe.with_file_name("zohara-linkd-stream");
        if sibling.exists() {
            return sibling.into_os_string();
        }
    }
    "zohara-linkd-stream".into()
}

impl Daemon {
    pub fn new(port: u16) -> Result<Arc<Self>> {
        let home = std::env::var("HOME").context("HOME is not set")?;
        let config_dir = PathBuf::from(&home).join(".config/zohara-link");
        let downloads_dir = PathBuf::from(&home).join("Downloads/ZoharaLink");
        std::fs::create_dir_all(&config_dir).context("create config dir")?;
        std::fs::create_dir_all(&downloads_dir).context("create downloads dir")?;
        // The folder holds the private key and the list of paired phones: only this user.
        restrict_to_owner(&config_dir, 0o700);

        // zipped folders and copied pictures left behind by a crash
        let _ = std::fs::remove_dir_all(outgoing_temp_dir());
        let options_file = config_dir.join("options.json");
        let clipboard_files = std::fs::read_to_string(&options_file)
            .ok()
            .and_then(|t| serde_json::from_str::<Value>(&t).ok())
            .and_then(|v| v.get("clipboard_files").and_then(Value::as_bool))
            .unwrap_or(false);
        let paired_devices_file = config_dir.join("paired_devices.json");
        let paired_devices = load_paired_devices(&paired_devices_file);

        let uid = unsafe { libc_geteuid() };
        // ZOHARA_LINK_SOCKET lets tests (and a second instance) use another path.
        let unix_socket_path = std::env::var_os("ZOHARA_LINK_SOCKET")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from(format!("/run/user/{uid}/zohara.sock")));

        let (ipc_broadcast, _) = broadcast::channel(64);

        let cert_path = config_dir.join("daemon.crt");
        let key_path = config_dir.join("daemon.key");
        tls::ensure_self_signed_cert(&cert_path, &key_path)?;
        let fingerprint = tls::cert_fingerprint(&cert_path)?;

        Ok(Arc::new(Self {
            port,
            cert_path,
            key_path,
            fingerprint,
            pairing_open_until: TokioMutex::new(None),
            paired_devices_file,
            downloads_dir,
            unix_socket_path,
            paired_devices: Arc::new(TokioMutex::new(paired_devices)),
            pending_pairings: TokioMutex::new(HashMap::new()),
            authenticated_connections: TokioMutex::new(HashMap::new()),
            active_connections: TokioMutex::new(HashMap::new()),
            device_telemetry: TokioMutex::new(HashMap::new()),
            active_file_transfers: TokioMutex::new(HashMap::new()),
            outgoing_files: Arc::new(TokioMutex::new(HashMap::new())),
            last_clipboard_text: TokioMutex::new(String::new()),
            clipboard_files: std::sync::atomic::AtomicBool::new(clipboard_files),
            options_file,
            last_clip_items: TokioMutex::new(String::new()),
            last_media: TokioMutex::new(None),
            notify_gate: std::sync::Mutex::new(limits::NotifyGate::default()),
            ipc_broadcast,
            screen_streams: TokioMutex::new(HashMap::new()),
            live_streams: TokioMutex::new(HashMap::new()),
        }))
    }

    pub async fn run(self: Arc<Self>) -> Result<()> {
        let tls_config = tls::load_tls_config(&self.cert_path, &self.key_path)?;
        let acceptor = TlsAcceptor::from(Arc::new(tls_config));

        let listener = TcpListener::bind(("0.0.0.0", self.port))
            .await
            .with_context(|| format!("bind 0.0.0.0:{}", self.port))?;
        log::info!(
            "Zohara Link TCP daemon listening on 0.0.0.0:{} (TLS: yes)",
            self.port
        );

        if self.unix_socket_path.exists() {
            let _ = std::fs::remove_file(&self.unix_socket_path);
        }
        let unix_listener = UnixListener::bind(&self.unix_socket_path)
            .with_context(|| format!("bind {}", self.unix_socket_path.display()))?;
        restrict_to_owner(&self.unix_socket_path, 0o600);
        log::info!(
            "Local IPC Unix domain socket active at {}",
            self.unix_socket_path.display()
        );

        mdns::advertise(&hostname(), self.port);

        {
            let this = self.clone();
            tokio::spawn(async move { this.clipboard_watcher_loop().await });
        }
        {
            let this = self.clone();
            tokio::spawn(async move { this.media_watcher_loop().await });
        }

        loop {
            tokio::select! {
                accepted = listener.accept() => {
                    match accepted {
                        Ok((stream, peer)) => {
                            let this = self.clone();
                            let acceptor = acceptor.clone();
                            tokio::spawn(async move { this.handle_client(stream, peer, acceptor).await });
                        }
                        Err(e) => log::warn!("TCP accept failed: {e}"),
                    }
                }
                accepted = unix_listener.accept() => {
                    match accepted {
                        Ok((stream, _)) => {
                            let this = self.clone();
                            tokio::spawn(async move { this.handle_unix_client(stream).await });
                        }
                        Err(e) => log::warn!("Unix socket accept failed: {e}"),
                    }
                }
            }
        }
    }

    // ----------------- TCP (Android) connections -----------------

    async fn handle_client(
        self: Arc<Self>,
        stream: TcpStream,
        peer: std::net::SocketAddr,
        acceptor: TlsAcceptor,
    ) {
        log::info!("Incoming connection from {peer}");
        let device_id = format!("dev_{}_{}", peer.ip(), peer.port());

        {
            let open = self.active_connections.lock().await.len();
            let authed = self.authenticated_connections.lock().await.len();
            if open >= limits::MAX_CONNECTIONS || open.saturating_sub(authed) >= limits::MAX_UNAUTHENTICATED {
                log::warn!("Too many open connections; refusing {peer}");
                return;
            }
        }

        let tls_stream = match acceptor.accept(stream).await {
            Ok(s) => s,
            Err(e) => {
                log::warn!("TLS handshake failed with {peer}: {e}");
                return;
            }
        };
        let (read_half, write_half) = tokio::io::split(tls_stream);
        let write_half: SharedWriter = Arc::new(TokioMutex::new(write_half));

        self.active_connections
            .lock()
            .await
            .insert(device_id.clone(), write_half.clone());

        let mut reader = BufReader::new(read_half);
        let deadline = Instant::now() + std::time::Duration::from_secs(limits::AUTH_DEADLINE_SECS);
        loop {
            // A connection that has not paired or shown its token in time is dropped; paired ones stay as long as they like.
            let authed = self.authenticated_connections.lock().await.contains_key(&device_id);
            let next = if authed {
                limits::read_line_bounded(&mut reader, limits::MAX_LINE).await
            } else {
                let left = deadline.saturating_duration_since(Instant::now());
                match tokio::time::timeout(left, limits::read_line_bounded(&mut reader, limits::MAX_LINE)).await {
                    Ok(r) => r,
                    Err(_) => {
                        log::warn!("{peer} did not pair or authenticate in time; closing");
                        break;
                    }
                }
            };
            match next {
                Ok(Some(line)) => {
                    let line = line.trim();
                    if line.is_empty() {
                        continue;
                    }
                    match serde_json::from_str::<Value>(line) {
                        Ok(packet) => self.handle_packet(&device_id, packet, &write_half).await,
                        Err(_) => log::warn!("Invalid JSON received: {line}"),
                    }
                }
                Ok(None) => break,
                Err(e) => {
                    log::warn!("Connection error with {peer}: {e}");
                    break;
                }
            }
        }

        log::info!("Connection closed for {peer}");
        self.drop_transfers_of(&device_id).await;
        self.outgoing_files.lock().await.retain(|_, o| {
            let keep = o.connection != device_id;
            if !keep {
                forget_outgoing(o);
            }
            keep
        });
        self.stop_screen(&device_id).await;
        self.stop_live_stream(&device_id).await;
        // a phone that vanished while holding a key, a button or the controller must not leave them stuck down
        uinput::release_all();
        self.active_connections.lock().await.remove(&device_id);
        self.authenticated_connections.lock().await.remove(&device_id);
    }

    async fn handle_packet(&self, device_id: &str, packet: Value, writer: &SharedWriter) {
        let ptype = packet.get("type").and_then(Value::as_str).unwrap_or("");
        log::info!("Received packet [{ptype}] from {device_id}");

        // Everything except the pairing handshake itself requires this
        // connection to have completed PAIR_VERIFY. Without this gate ANY
        // socket that can reach this port -- paired or not -- could move
        // the mouse, click, sync the clipboard, mirror notifications, or
        // write files under Downloads/ZoharaLink.
        if ptype != "PAIR_REQUEST"
            && ptype != "PAIR_VERIFY"
            && ptype != "AUTH"
            && !self
                .authenticated_connections
                .lock()
                .await
                .contains_key(device_id)
        {
            log::warn!("Rejected [{ptype}] from unauthenticated connection {device_id}");
            let _ = send_json(writer, &json!({"type": "ERROR", "error": "not paired on this connection"})).await;
            return;
        }

        match ptype {
            "PAIR_REQUEST" => self.handle_pair_request(device_id, &packet, writer).await,
            "PAIR_VERIFY" => self.handle_pair_verify(device_id, &packet, writer).await,
            "AUTH" => self.handle_auth(device_id, &packet, writer).await,
            "CLIPBOARD_SYNC" => self.handle_clipboard_sync(&packet).await,
            "TELEMETRY_STATUS" => self.handle_telemetry(device_id, packet).await,
            "NOTIFICATION_POST" => self.handle_notification_post(&packet).await,
            "FILE_OFFER" => self.handle_file_offer(device_id, &packet, writer).await,
            "FILE_CHUNK" => self.handle_file_chunk(&packet).await,
            "FILE_COMPLETE" => self.handle_file_complete(&packet).await,
            "FILE_PUSH_ACCEPT" => self.handle_push_accept(device_id, &packet, writer).await,
            "FILE_PUSH_CANCEL" => self.handle_push_cancel(device_id, &packet).await,
            "INPUT_EVENT" => {
                // Controlling the mouse is the most dangerous thing a phone can do here: it needs its own permission.
                let dev = self.authenticated_connections.lock().await.get(device_id).cloned().unwrap_or_default();
                let allowed = self.paired_devices.lock().await.get(&dev).map(|d| d.allow_input).unwrap_or(false);
                if allowed {
                    handle_input_event(&packet);
                } else {
                    let _ = send_json(writer, &json!({"type": "ERROR", "error": "remote input is not allowed for this phone"})).await;
                }
            }
            "SCREEN_START" => self.handle_screen_start(device_id, &packet, writer).await,
            "STREAM_START" => self.handle_stream_start(device_id, &packet, writer).await,
            "STREAM_ANSWER" => {
                let sdp = packet.get("sdp").and_then(Value::as_str).unwrap_or("");
                self.stream_command(device_id, json!({"cmd": "answer", "sdp": limits::clean_text(sdp, 100_000)})).await;
            }
            "STREAM_ICE" => {
                let candidate = packet.get("candidate").and_then(Value::as_str).unwrap_or("");
                let mline = packet.get("mline").and_then(Value::as_u64).unwrap_or(0);
                self.stream_command(device_id, json!({"cmd": "ice", "mline": mline, "candidate": limits::clean_text(candidate, 2000)})).await;
            }
            "STREAM_STOP" => self.stop_live_stream(device_id).await,
            "SCREEN_STOP" => self.stop_screen(device_id).await,
            "MEDIA_CONTROL" => {
                let action = packet.get("action").and_then(Value::as_str).unwrap_or("").to_string();
                // Talking to the player can take a moment: never on the thread that serves the connection.
                tokio::task::spawn_blocking(move || desktop::control_mpris2_media(&action));
            }
            "PROXIMITY_HEARTBEAT" => {}
            _ => {}
        }
    }

    async fn handle_pair_request(&self, device_id: &str, packet: &Value, writer: &SharedWriter) {
        let open = matches!(*self.pairing_open_until.lock().await, Some(t) if t > Instant::now());
        if !open {
            log::warn!("Pairing request from {device_id} refused: pairing is not open");
            let _ = send_json(
                writer,
                &json!({"type": "PAIR_CONFIRMED", "status": "FAILED",
                        "error": "Pairing is off on the computer. Open Settings > Zohara Link there and press Pair a phone."}),
            )
            .await;
            return;
        }
        let req_device_id = packet
            .get("deviceId")
            .and_then(Value::as_str)
            .unwrap_or(device_id)
            .to_string();
        let req_device_name = limits::clean_text(
            packet.get("deviceName").and_then(Value::as_str).unwrap_or("Android Device"),
            60,
        );
        let pin: u32 = rand::thread_rng().gen_range(100_000..=999_999);
        let pin = pin.to_string();

        self.pending_pairings
            .lock()
            .await
            .insert(req_device_id.clone(), {
                let mut p = auth::PendingPin::new(pin.clone());
                p.name = req_device_name.clone();
                p
            });
        let short_fp = auth::short_fingerprint(&self.fingerprint);

        log::info!("*** PAIRING AUTHORIZATION REQUIRED ***");
        log::info!("Device: {req_device_name} ({req_device_id})");
        log::info!("Verification PIN (SAS): >>> {pin} <<<");

        desktop::send_desktop_notification(
            "Zohara Link - Pairing Request",
            &format!(
                "Device '{req_device_name}' wants to pair.\nPIN: {pin}\nThe phone must show this code: {short_fp}"
            ),
        );
        self.broadcast_local_ipc(
            "PAIR_REQUEST",
            json!({"deviceId": req_device_id, "deviceName": req_device_name, "pin": pin, "fingerprint": short_fp}),
        )
        .await;

        // NOTE: the PIN is deliberately NOT included in this reply. This
        // is a Short Authentication String pairing flow: its whole
        // security property depends on the PIN reaching a human over a
        // channel the connecting device doesn't control -- the desktop
        // notification above -- so the user reads it there and enters it
        // on the phone. Echoing it back to the same socket that just
        // asked to pair would let any device on the LAN complete
        // PAIR_VERIFY by simply replaying the value it was just handed.
        let challenge = json!({
            "type": "PAIR_CHALLENGE",
            "serverName": hostname(),
            "timestamp": unix_time(),
            "certFingerprint": self.fingerprint,
        });
        let _ = send_json(writer, &challenge).await;
    }

    async fn handle_pair_verify(&self, device_id: &str, packet: &Value, writer: &SharedWriter) {
        let req_device_id = packet
            .get("deviceId")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let user_pin = packet.get("pinSas").and_then(Value::as_str);
        let req_device_name = limits::clean_text(
            packet.get("deviceName").and_then(Value::as_str).unwrap_or("Android Device"),
            60,
        );

        // No `approved == true` bypass: that field is the client's own
        // unverified claim about itself, not something the desktop user
        // approved, and accepting it would skip the PIN check entirely.
        // A PIN survives three wrong tries or two minutes, whichever comes first.
        let outcome = {
            let mut pending = self.pending_pairings.lock().await;
            let outcome = match pending.get_mut(&req_device_id) {
                Some(p) => p.check(user_pin, Instant::now()),
                None => auth::PinCheck::Expired,
            };
            if outcome != auth::PinCheck::Wrong {
                // Matched (single use) or dead: either way the PIN is gone.
                pending.remove(&req_device_id);
            }
            outcome
        };
        let matched = outcome == auth::PinCheck::Match;

        if matched {
            let token = auth::new_token();
            let last_ip = device_id
                .strip_prefix("dev_")
                .and_then(|s| s.rsplit_once('_'))
                .map(|(ip, _)| ip.to_string())
                .unwrap_or_default();
            let paired = PairedDevice {
                device_id: req_device_id.clone(),
                device_name: req_device_name.clone(),
                paired_at: unix_time_f64(),
                client_cert_thumbprint: packet
                    .get("clientThumbprint")
                    .and_then(Value::as_str)
                    .unwrap_or("trusted")
                    .to_string(),
                last_ip,
                token_hash: auth::token_hash(&token),
                allow_input: false,
                allow_screen: false,
            };
            self.paired_devices
                .lock()
                .await
                .insert(req_device_id.clone(), paired.clone());
            self.save_paired_devices().await;
            *self.pairing_open_until.lock().await = None; // one phone per press of "Pair a phone"
            self.authenticated_connections
                .lock()
                .await
                .insert(device_id.to_string(), req_device_id.clone());

            log::info!("Device '{req_device_name}' paired successfully!");
            let _ = send_json(
                writer,
                &json!({
                    "type": "PAIR_CONFIRMED",
                    "status": "SUCCESS",
                    "serverName": hostname(),
                    "sessionToken": token,
                    "certFingerprint": self.fingerprint,
                }),
            )
            .await;
            self.send_addresses(writer).await;
            self.broadcast_local_ipc("DEVICE_PAIRED", public_device(&paired)).await;
        } else {
            let error = if outcome == auth::PinCheck::Expired {
                "PIN expired, ask for a new one"
            } else {
                "PIN mismatch"
            };
            self.broadcast_local_ipc("PAIR_FAILED", json!({"deviceId": req_device_id, "error": error})).await;
            let _ = send_json(writer, &json!({"type": "PAIR_CONFIRMED", "status": "FAILED", "error": error})).await;
        }
    }

    /// A phone that paired before proves itself with the token it was given, instead of redoing the PIN on every connection.
    async fn handle_auth(&self, device_id: &str, packet: &Value, writer: &SharedWriter) {
        let req_device_id = packet.get("deviceId").and_then(Value::as_str).unwrap_or("");
        let token = packet.get("token").and_then(Value::as_str).unwrap_or("");
        let ok = match self.paired_devices.lock().await.get_mut(req_device_id) {
            Some(d) if auth::token_matches(&d.token_hash, token) => {
                d.last_ip = device_id
                    .strip_prefix("dev_")
                    .and_then(|s| s.rsplit_once('_'))
                    .map(|(ip, _)| ip.to_string())
                    .unwrap_or_default();
                true
            }
            _ => false,
        };
        if ok {
            self.authenticated_connections.lock().await.insert(device_id.to_string(), req_device_id.to_string());
            self.save_paired_devices().await;
            self.broadcast_local_ipc("DEVICE_CONNECTED", json!({"deviceId": req_device_id})).await;
            let _ = send_json(writer, &json!({"type": "AUTH_RESULT", "status": "SUCCESS", "serverName": hostname()})).await;
            self.send_addresses(writer).await;
            if let Some(m) = self.last_media.lock().await.clone() {
                let _ = send_json(writer, &m).await;
            }
        } else {
            log::warn!("AUTH refused for device '{req_device_id}' on {device_id}");
            // A short pause makes guessing a token from the network pointless.
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
            let _ = send_json(writer, &json!({"type": "AUTH_RESULT", "status": "FAILED", "error": "not paired or token wrong"})).await;
        }
    }

    /// Tells a phone every overlay address (Tailscale and the like) this computer can be reached at, so it can come back through
    /// them when the home network is not shared.
    async fn send_addresses(&self, writer: &SharedWriter) {
        let port = self.port;
        let status = tokio::task::spawn_blocking(move || remote::status_json(port)).await.unwrap_or_else(|_| json!({}));
        let _ = send_json(
            writer,
            &json!({"type": "ADDRESSES", "serverName": hostname(), "port": port, "overlays": status["overlays"]}),
        )
        .await;
    }

    fn paired_devices_handle(&self) -> Arc<TokioMutex<HashMap<String, PairedDevice>>> {
        self.paired_devices.clone()
    }

    async fn device_of(&self, connection: &str) -> String {
        self.authenticated_connections.lock().await.get(connection).cloned().unwrap_or_default()
    }

    async fn handle_screen_start(&self, connection: &str, packet: &Value, writer: &SharedWriter) {
        let dev = self.device_of(connection).await;
        if !self.paired_devices.lock().await.get(&dev).map(|d| d.allow_screen).unwrap_or(false) {
            let _ = send_json(writer, &json!({"type": "ERROR", "error": "viewing the screen is not allowed for this phone"})).await;
            return;
        }
        let (max_width, fps) = screen::clamp_request(
            packet.get("maxWidth").and_then(Value::as_u64).unwrap_or(1024),
            packet.get("fps").and_then(Value::as_u64).unwrap_or(2),
        );
        self.stop_screen(connection).await;
        let (this_conn, writer, daemon_dev) = (connection.to_string(), writer.clone(), dev);
        let paired = self.paired_devices_handle();
        let handle = tokio::spawn(async move {
            let mut seq: u64 = 0;
            let mut last_hash = String::new();
            let tick = std::time::Duration::from_millis(1000 / fps as u64);
            loop {
                let started = Instant::now();
                // Permission is checked for every frame: taking it away in Settings ends the stream at once.
                if !paired.lock().await.get(&daemon_dev).map(|d| d.allow_screen).unwrap_or(false) {
                    let _ = send_json(&writer, &json!({"type": "SCREEN_STOPPED", "reason": "not allowed"})).await;
                    break;
                }
                let shot = tokio::task::spawn_blocking(move || {
                    screen::capture().and_then(|b| screen::shrink_to_jpeg(&b, max_width, 60).ok_or_else(|| "the picture could not be read".to_string()))
                })
                .await
                .unwrap_or_else(|e| Err(e.to_string()));
                match shot {
                    Ok(f) => {
                        let hash = auth::fingerprint(&f.jpeg);
                        if hash != last_hash {
                            last_hash = hash;
                            seq += 1;
                            use base64::Engine;
                            let packet = json!({"type": "SCREEN_FRAME", "seq": seq, "width": f.width, "height": f.height,
                                "screenWidth": f.source_width, "screenHeight": f.source_height,
                                "jpegBase64": base64::engine::general_purpose::STANDARD.encode(&f.jpeg)});
                            if send_json(&writer, &packet).await.is_err() {
                                break;
                            }
                        }
                    }
                    Err(why) => {
                        log::warn!("screen capture failed for {this_conn}: {why}");
                        let _ = send_json(&writer, &json!({"type": "SCREEN_STOPPED", "reason": format!("the computer could not take a picture of its screen ({why})")})).await;
                        break;
                    }
                }
                tokio::time::sleep(tick.saturating_sub(started.elapsed())).await;
            }
            log::info!("screen stream of {this_conn} ended");
        });
        self.screen_streams.lock().await.insert(connection.to_string(), handle);
    }

    /// A phone asks for the live stream (video, and its input back over WebRTC). Needs the same permission as seeing the screen.
    async fn handle_stream_start(&self, connection: &str, packet: &Value, writer: &SharedWriter) {
        use tokio::io::AsyncWriteExt as _;
        let dev = self.device_of(connection).await;
        if !self.paired_devices.lock().await.get(&dev).map(|d| d.allow_screen).unwrap_or(false) {
            let _ = send_json(writer, &json!({"type": "ERROR", "error": "viewing the screen is not allowed for this phone"})).await;
            return;
        }
        self.stop_live_stream(connection).await;
        let n = |k: &str, d: u64| packet.get(k).and_then(Value::as_u64).unwrap_or(d);
        // a test picture only when the test switch is set in the environment; a phone cannot ask for it
        let source = if std::env::var_os("ZOHARA_LINK_STREAM_TEST").is_some() { "test" } else { "screen" };
        let start = json!({"cmd": "start", "source": source, "width": n("width", 1280), "height": n("height", 720), "fps": n("fps", 60), "bitrate": n("bitrate", 8000)});
        let mut child = match tokio::process::Command::new(stream_program())
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::inherit())
            .kill_on_drop(true)
            .spawn()
        {
            Ok(c) => c,
            Err(e) => {
                log::warn!("the stream program could not be started: {e}");
                let _ = send_json(writer, &json!({"type": "STREAM_ERROR", "error": "Game streaming is not installed on the computer yet. Open Settings > Zohara Link there and press Set up game streaming."})).await;
                return;
            }
        };
        let (Some(mut stdin), Some(stdout)) = (child.stdin.take(), child.stdout.take()) else { return };
        if stdin.write_all(format!("{start}\n").as_bytes()).await.is_err() {
            return;
        }
        let stdin = Arc::new(TokioMutex::new(stdin));
        let (writer, paired, dev2, conn) = (writer.clone(), self.paired_devices_handle(), dev, connection.to_string());
        let task = tokio::spawn(async move {
            let _child = child; // dropped (and killed) when this task ends or is aborted
            let mut lines = BufReader::new(stdout).lines();
            let mut heard = false;
            while let Ok(Some(line)) = lines.next_line().await {
                let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
                heard = true;
                let reply = match v.get("event").and_then(Value::as_str) {
                    Some("offer") => json!({"type": "STREAM_OFFER", "sdp": v["sdp"]}),
                    Some("ice") => json!({"type": "STREAM_ICE", "mline": v["mline"], "candidate": v["candidate"]}),
                    Some("state") => json!({"type": "STREAM_STATE", "state": v["state"]}),
                    Some("error") => json!({"type": "STREAM_ERROR", "error": v["message"]}),
                    Some("input") => {
                        // Controlling the computer needs its own permission, checked for every message.
                        if paired.lock().await.get(&dev2).map(|d| d.allow_input).unwrap_or(false) {
                            handle_input_event(&v["data"]);
                        }
                        continue;
                    }
                    _ => continue,
                };
                if send_json(&writer, &reply).await.is_err() {
                    break;
                }
            }
            log::info!("game stream of {conn} ended");
            if !heard {
                // it stopped without a word: GStreamer's libraries are missing, or the program crashed at once
                let _ = send_json(&writer, &json!({"type": "STREAM_ERROR", "error": "Game streaming is not installed on the computer yet. Open Settings > Zohara Link there and press Set up game streaming."})).await;
            }
            let _ = send_json(&writer, &json!({"type": "STREAM_STATE", "state": "closed"})).await;
        });
        self.live_streams.lock().await.insert(connection.to_string(), LiveStream { stdin, task });
    }

    /// Sends a command to the stream program of this connection (an answer or a candidate from the phone).
    async fn stream_command(&self, connection: &str, cmd: Value) {
        use tokio::io::AsyncWriteExt as _;
        let stdin = self.live_streams.lock().await.get(connection).map(|s| s.stdin.clone());
        if let Some(stdin) = stdin {
            let _ = stdin.lock().await.write_all(format!("{cmd}\n").as_bytes()).await;
        }
    }

    async fn stop_live_stream(&self, connection: &str) {
        if let Some(s) = self.live_streams.lock().await.remove(connection) {
            s.task.abort();
        }
    }

    async fn stop_screen(&self, connection: &str) {
        if let Some(h) = self.screen_streams.lock().await.remove(connection) {
            h.abort();
        }
    }

    async fn stop_screen_of_device(&self, device: &str) {
        let conns: Vec<String> = self.authenticated_connections.lock().await.iter().filter(|(_, d)| d.as_str() == device).map(|(c, _)| c.clone()).collect();
        for c in conns {
            self.stop_screen(&c).await;
            self.stop_live_stream(&c).await;
        }
    }

    async fn handle_clipboard_sync(&self, packet: &Value) {
        let text = packet.get("text").and_then(Value::as_str).unwrap_or("");
        if text.is_empty() {
            return;
        }
        let mut last = self.last_clipboard_text.lock().await;
        if text != *last {
            *last = text.to_string();
            drop(last);
            desktop::set_linux_clipboard(text);
            self.broadcast_local_ipc(
                "CLIPBOARD_UPDATED",
                json!({"text": text, "source": "android"}),
            )
            .await;
        }
    }

    async fn handle_telemetry(&self, device_id: &str, packet: Value) {
        self.device_telemetry
            .lock()
            .await
            .insert(device_id.to_string(), packet.clone());
        self.broadcast_local_ipc("TELEMETRY_UPDATED", packet).await;
    }

    async fn handle_notification_post(&self, packet: &Value) {
        let title = limits::clean_text(packet.get("title").and_then(Value::as_str).unwrap_or("Android Notification"), 120);
        let text = limits::clean_text(packet.get("text").and_then(Value::as_str).unwrap_or(""), 500);
        let app_name = limits::clean_text(packet.get("appName").and_then(Value::as_str).unwrap_or("Phone"), 40);
        let allowed = self
            .notify_gate
            .lock()
            .map(|mut g| g.allow(&app_name, &title, &text, Instant::now()))
            .unwrap_or(true);
        if !allowed {
            log::info!("repeated notification from {app_name} not shown again");
            return;
        }
        desktop::send_desktop_notification(&format!("[{app_name}] {title}"), &text);
        self.broadcast_local_ipc("NOTIFICATION_POSTED", packet.clone()).await;
    }

    async fn handle_file_offer(&self, device_id: &str, packet: &Value, writer: &SharedWriter) {
        let transfer_id = packet.get("transferId").and_then(Value::as_str).unwrap_or("").to_string();
        let file_name = packet.get("fileName").and_then(Value::as_str).unwrap_or("received_file").to_string();
        let file_size = packet.get("fileSize").and_then(Value::as_u64).unwrap_or(0);

        let safe_name = std::path::Path::new(&file_name)
            .file_name()
            .map(|n| limits::clean_text(&n.to_string_lossy(), 200))
            .filter(|n| !n.is_empty() && n != "." && n != "..")
            .unwrap_or_else(|| "received_file".to_string());

        // Refuse before creating anything: too big, or too many transfers already running.
        let refuse = |why: &'static str| json!({"type": "FILE_ACCEPT", "transferId": transfer_id, "accepted": false, "error": why});
        if file_size > limits::MAX_FILE_BYTES {
            let _ = send_json(writer, &refuse("file too large")).await;
            return;
        }
        if self.active_file_transfers.lock().await.len() >= limits::MAX_TRANSFERS {
            let _ = send_json(writer, &refuse("too many transfers at once")).await;
            return;
        }
        // Never replace a file that is already there.
        let dest_path = limits::unique_path(&self.downloads_dir, &safe_name);

        let handle = match tokio::fs::File::create(&dest_path).await {
            Ok(f) => f,
            Err(e) => {
                log::warn!("failed to create {}: {e}", dest_path.display());
                return;
            }
        };

        self.active_file_transfers.lock().await.insert(
            transfer_id.clone(),
            FileTransfer { owner: device_id.to_string(), file_name: file_name.clone(), file_size, dest_path, handle, received_bytes: 0 },
        );

        log::info!("Accepting incoming file: {file_name} ({file_size} bytes)");
        let _ = send_json(writer, &json!({"type": "FILE_ACCEPT", "transferId": transfer_id, "accepted": true})).await;
        self.broadcast_local_ipc(
            "FILE_TRANSFER_START",
            json!({"transferId": transfer_id, "fileName": file_name, "fileSize": file_size}),
        )
        .await;
    }

    /// A connection that dies mid-transfer leaves a half-written file and a slot in the transfer table; with enough of them
    /// every later offer was refused ("too many transfers"). Remove them and the partial files.
    async fn drop_transfers_of(&self, connection: &str) {
        let mut transfers = self.active_file_transfers.lock().await;
        let ids: Vec<String> = transfers.iter().filter(|(_, t)| t.owner == connection).map(|(id, _)| id.clone()).collect();
        for id in ids {
            if let Some(t) = transfers.remove(&id) {
                log::warn!("transfer of {} was cut off; removing the partial file", t.file_name);
                drop(t.handle);
                let _ = std::fs::remove_file(&t.dest_path);
            }
        }
    }

    async fn handle_file_chunk(&self, packet: &Value) {
        let transfer_id = packet.get("transferId").and_then(Value::as_str).unwrap_or("");
        let chunk_b64 = packet.get("payloadBase64").and_then(Value::as_str).unwrap_or("");
        let Ok(data) = base64_decode(chunk_b64) else {
            log::warn!("invalid base64 chunk for transfer {transfer_id}");
            return;
        };

        let mut transfers = self.active_file_transfers.lock().await;
        let Some(info) = transfers.get_mut(transfer_id) else { return };
        // A declared size of 0 means the phone did not know it (some Android providers do not say): then only the overall limit
        // applies. Otherwise more than declared is a broken or hostile sender: stop and delete the partial file.
        let too_much = if info.file_size == 0 {
            info.received_bytes + data.len() as u64 > limits::MAX_FILE_BYTES
        } else {
            info.received_bytes + data.len() as u64 > info.file_size
        };
        if too_much {
            log::warn!("transfer {transfer_id} sent more than its declared size; cancelled");
            let path = info.dest_path.clone();
            transfers.remove(transfer_id);
            let _ = std::fs::remove_file(path);
            return;
        }
        if info.handle.write_all(&data).await.is_err() {
            log::warn!("write failed for transfer {transfer_id}");
            return;
        }
        info.received_bytes += data.len() as u64;
        let progress = (info.received_bytes as f64 / info.file_size.max(1) as f64 * 100.0).min(100.0);
        let (received, total) = (info.received_bytes, info.file_size);
        drop(transfers);

        self.broadcast_local_ipc(
            "FILE_TRANSFER_PROGRESS",
            json!({"transferId": transfer_id, "progress": progress, "received": received, "total": total}),
        )
        .await;
    }

    async fn handle_file_complete(&self, packet: &Value) {
        let transfer_id = packet.get("transferId").and_then(Value::as_str).unwrap_or("");
        let Some(mut info) = self.active_file_transfers.lock().await.remove(transfer_id) else { return };
        let _ = info.handle.flush().await;
        log::info!("File transfer complete: {}", info.dest_path.display());
        desktop::send_desktop_notification(
            "File Received",
            &format!("Saved {} to Downloads/ZoharaLink", info.file_name),
        );
        self.broadcast_local_ipc(
            "FILE_TRANSFER_COMPLETE",
            json!({"transferId": transfer_id, "filePath": info.dest_path.display().to_string()}),
        )
        .await;
    }

    // ----------------- Desktop -> phone file sending -----------------

    /// IPC `SEND_FILE {deviceId, path}`: offers a file, or a folder (zipped first), to the phone. Nothing is sent until the phone's
    /// owner accepts.
    async fn start_file_push(&self, req: &Value) -> Value {
        let device = req.get("deviceId").and_then(Value::as_str).unwrap_or("");
        let path = PathBuf::from(req.get("path").and_then(Value::as_str).unwrap_or(""));
        let connection = self.authenticated_connections.lock().await.iter().find(|(_, d)| d.as_str() == device).map(|(c, _)| c.clone());
        let Some(connection) = connection else { return json!({"status": "NOT_CONNECTED"}) };
        self.offer_path(&connection, path, None, false).await
    }

    /// Offers `path` on a live connection. A folder is zipped to a temporary file first. `temp`: `path` was made for this offer
    /// (deleted when it ends). `rename`: what the phone should call it (default: the file's own name).
    async fn offer_path(&self, connection: &str, path: PathBuf, rename: Option<String>, temp: bool) -> Value {
        let meta = match tokio::fs::metadata(&path).await {
            Ok(m) => m,
            Err(_) => return json!({"status": "NO_FILE"}),
        };
        let (path, mut name, size, temp) = if meta.is_dir() {
            let folder = path.file_name().map(|n| limits::clean_text(&n.to_string_lossy(), 190)).filter(|n| !n.is_empty()).unwrap_or_else(|| "folder".to_string());
            let tmp_dir = outgoing_temp_dir();
            if tokio::fs::create_dir_all(&tmp_dir).await.is_err() {
                return json!({"status": "NO_FILE"});
            }
            let zipped = tmp_dir.join(format!("{:016x}.zip", rand::thread_rng().gen::<u64>()));
            let (src, dst) = (path.clone(), zipped.clone());
            match tokio::task::spawn_blocking(move || zip_folder(&src, &dst)).await {
                Ok(Ok(_)) => {}
                Ok(Err(e)) => {
                    let _ = std::fs::remove_file(&zipped);
                    return json!({"status": if e.kind() == std::io::ErrorKind::InvalidInput { "TOO_BIG" } else { "NO_FILE" }});
                }
                Err(_) => return json!({"status": "NO_FILE"}),
            }
            let size = tokio::fs::metadata(&zipped).await.map(|m| m.len()).unwrap_or(0);
            (zipped, format!("{folder}.zip"), size, true)
        } else if meta.is_file() {
            let n = path.file_name().map(|n| limits::clean_text(&n.to_string_lossy(), 200)).filter(|n| !n.is_empty()).unwrap_or_else(|| "file".to_string());
            (path, n, meta.len(), temp)
        } else {
            return json!({"status": "NO_FILE"});
        };
        if let Some(n) = rename {
            name = limits::clean_text(&n, 200);
        }
        let gone = |temp: bool, path: &std::path::Path| {
            if temp {
                let _ = std::fs::remove_file(path);
            }
        };
        if size == 0 {
            gone(temp, &path);
            return json!({"status": "EMPTY"});
        }
        if size > limits::MAX_FILE_BYTES {
            gone(temp, &path);
            return json!({"status": "TOO_BIG"});
        }
        let Some(writer) = self.active_connections.lock().await.get(connection).cloned() else {
            gone(temp, &path);
            return json!({"status": "NOT_CONNECTED"});
        };
        let id = format!("push-{:016x}", rand::thread_rng().gen::<u64>());
        {
            let mut out = self.outgoing_files.lock().await;
            if out.len() >= limits::MAX_OFFERS {
                drop(out);
                gone(temp, &path);
                return json!({"status": "BUSY"});
            }
            out.insert(id.clone(), OutgoingFile { connection: connection.to_string(), path: path.clone(), name: name.clone(), size, temp });
        }
        log::info!("Offering {name} ({size} bytes) on {connection}");
        if send_json(&writer, &json!({"type": "FILE_PUSH_OFFER", "transferId": id, "fileName": name, "fileSize": size})).await.is_err() {
            if let Some(o) = self.outgoing_files.lock().await.remove(&id) {
                forget_outgoing(&o);
            }
            return json!({"status": "NOT_CONNECTED"});
        }
        self.broadcast_local_ipc("FILE_SEND_OFFERED", json!({"transferId": id, "fileName": name, "fileSize": size})).await;
        json!({"status": "OFFERED", "transferId": id})
    }

    /// The phone answered the offer. Declined: forget it. Accepted: stream the file in the background.
    async fn handle_push_accept(&self, connection: &str, packet: &Value, writer: &SharedWriter) {
        let id = packet.get("transferId").and_then(Value::as_str).unwrap_or("").to_string();
        let accepted = packet.get("accepted").and_then(Value::as_bool).unwrap_or(false);
        let (path, name, size) = {
            let mut out = self.outgoing_files.lock().await;
            // only the connection the offer went to may answer it
            match out.get(&id) {
                Some(o) if o.connection == connection => {}
                _ => return,
            }
            if !accepted {
                if let Some(o) = out.remove(&id) {
                    forget_outgoing(&o);
                }
                drop(out);
                log::info!("The phone declined {id}");
                self.broadcast_local_ipc("FILE_SEND_DECLINED", json!({"transferId": id})).await;
                return;
            }
            let o = &out[&id];
            (o.path.clone(), o.name.clone(), o.size)
        };
        let (writer, table, ipc) = (writer.clone(), self.outgoing_files.clone(), self.ipc_broadcast.clone());
        tokio::spawn(async move {
            let say = |event: &str, data: Value| {
                if let Ok(mut line) = serde_json::to_string(&json!({"event": event, "data": data})) {
                    line.push('\n');
                    let _ = ipc.send(line);
                }
            };
            let result = push_file_chunks(&writer, &table, &id, &path, size, |sent| {
                say("FILE_SEND_PROGRESS", json!({"transferId": id, "sent": sent, "total": size}));
            })
            .await;
            if let Some(o) = table.lock().await.remove(&id) {
                forget_outgoing(&o);
            }
            match result {
                Ok(true) => {
                    log::info!("Sent {name} to the phone");
                    say("FILE_SEND_COMPLETE", json!({"transferId": id, "fileName": name}));
                }
                Ok(false) => say("FILE_SEND_CANCELLED", json!({"transferId": id})),
                Err(e) => {
                    log::warn!("Sending {name} failed: {e}");
                    say("FILE_SEND_FAILED", json!({"transferId": id, "error": e.to_string()}));
                }
            }
        });
    }

    /// The phone stopped a transfer: taking it out of the table makes the sender stop at the next chunk.
    async fn handle_push_cancel(&self, connection: &str, packet: &Value) {
        let id = packet.get("transferId").and_then(Value::as_str).unwrap_or("");
        let mut out = self.outgoing_files.lock().await;
        if matches!(out.get(id), Some(o) if o.connection == connection) {
            if let Some(o) = out.remove(id) {
                forget_outgoing(&o);
            }
            drop(out);
            self.broadcast_local_ipc("FILE_SEND_CANCELLED", json!({"transferId": id})).await;
        }
    }

    // ----------------- Local Unix-socket IPC -----------------

    async fn handle_unix_client(self: Arc<Self>, stream: UnixStream) {
        log::info!("Local IPC client connected");
        let (read_half, mut write_half) = tokio::io::split(stream);
        let mut rx = self.ipc_broadcast.subscribe();
        let mut lines = BufReader::new(read_half).lines();

        loop {
            tokio::select! {
                line = lines.next_line() => {
                    match line {
                        Ok(Some(l)) => {
                            let l = l.trim();
                            if l.is_empty() { continue; }
                            let Ok(req) = serde_json::from_str::<Value>(l) else { continue };
                            let resp = self.handle_ipc_command(&req).await;
                            let mut out = serde_json::to_string(&resp).unwrap_or_default();
                            out.push('\n');
                            if write_half.write_all(out.as_bytes()).await.is_err() { break; }
                        }
                        _ => break,
                    }
                }
                event = rx.recv() => {
                    match event {
                        Ok(line) => {
                            if write_half.write_all(line.as_bytes()).await.is_err() { break; }
                        }
                        Err(broadcast::error::RecvError::Lagged(_)) => continue,
                        Err(broadcast::error::RecvError::Closed) => break,
                    }
                }
            }
        }
    }

    async fn handle_ipc_command(&self, req: &Value) -> Value {
        match req.get("command").and_then(Value::as_str) {
            Some("GET_STATUS") => {
                let paired: Vec<Value> = self.paired_devices.lock().await.values().map(public_device).collect();
                let connected: Vec<String> = self.authenticated_connections.lock().await.values().cloned().collect();
                let telemetry = self.device_telemetry.lock().await.clone();
                let active: Vec<_> = self.active_file_transfers.lock().await.keys().cloned().collect();
                let now = Instant::now();
                let pending: Vec<Value> = self
                    .pending_pairings
                    .lock()
                    .await
                    .iter()
                    .filter(|(_, p)| p.is_live(now))
                    .map(|(id, p)| json!({"deviceId": id, "name": p.name, "pin": p.pin()}))
                    .collect();
                let pairing_open = matches!(*self.pairing_open_until.lock().await, Some(t) if t > Instant::now());
                json!({"paired_devices": paired, "connected_device_ids": connected, "pairing_open": pairing_open, "pending_pairings": pending, "telemetry": telemetry, "active_transfers": active, "clipboard_files": self.clipboard_files.load(std::sync::atomic::Ordering::Relaxed)})
            }
            Some("GET_IDENTITY") => {
                json!({
                    "name": hostname(),
                    "port": self.port,
                    "fingerprint": self.fingerprint,
                    "fingerprint_short": auth::short_fingerprint(&self.fingerprint),
                    "downloads_dir": self.downloads_dir.display().to_string(),
                })
            }
            Some("OPEN_PAIRING") => {
                let secs = req.get("seconds").and_then(Value::as_u64).unwrap_or(300).clamp(30, 600);
                *self.pairing_open_until.lock().await = Some(Instant::now() + std::time::Duration::from_secs(secs));
                self.broadcast_local_ipc("PAIRING_OPEN", json!({"seconds": secs})).await;
                json!({"status": "OK", "seconds": secs})
            }
            Some("CLOSE_PAIRING") => {
                *self.pairing_open_until.lock().await = None;
                self.broadcast_local_ipc("PAIRING_CLOSED", json!({})).await;
                json!({"status": "OK"})
            }
            Some("SET_PERMISSION") => {
                let id = req.get("deviceId").and_then(Value::as_str).unwrap_or("");
                let input = req.get("input").and_then(Value::as_bool);
                let screen = req.get("screen").and_then(Value::as_bool);
                let found = {
                    let mut devices = self.paired_devices.lock().await;
                    match devices.get_mut(id) {
                        Some(d) if input.is_some() || screen.is_some() => {
                            if let Some(v) = input {
                                d.allow_input = v;
                            }
                            if let Some(v) = screen {
                                d.allow_screen = v;
                            }
                            true
                        }
                        _ => false,
                    }
                };
                if found {
                    self.save_paired_devices().await;
                    if screen == Some(false) {
                        self.stop_screen_of_device(id).await;
                    }
                    json!({"status": "OK"})
                } else {
                    json!({"status": "NOT_FOUND"})
                }
            }
            Some("GET_REMOTE") => {
                let port = self.port;
                tokio::task::spawn_blocking(move || remote::status_json(port)).await.unwrap_or_else(|_| json!({}))
            }
            Some("REMOTE_LOGIN") => {
                let url = tokio::task::spawn_blocking(remote::tailscale_login).await.ok().flatten();
                json!({"status": if url.is_some() { "OK" } else { "NO_LINK" }, "loginUrl": url.unwrap_or_default()})
            }
            Some("UNPAIR") => {
                let id = req.get("deviceId").and_then(Value::as_str).unwrap_or("");
                let removed = self.paired_devices.lock().await.remove(id).is_some();
                if removed {
                    self.save_paired_devices().await;
                    // Whatever is connected as that device loses access at once.
                    self.stop_screen_of_device(id).await;
                    {
                        let authed = self.authenticated_connections.lock().await;
                        self.outgoing_files.lock().await.retain(|_, o| {
                            let keep = authed.get(&o.connection).map(|d| d != id).unwrap_or(true);
                            if !keep {
                                forget_outgoing(o);
                            }
                            keep
                        });
                    }
                    self.authenticated_connections.lock().await.retain(|_, dev| dev != id);
                    self.broadcast_local_ipc("DEVICE_UNPAIRED", json!({"deviceId": id})).await;
                }
                json!({"status": if removed { "OK" } else { "NOT_FOUND" }})
            }
            Some("SEND_CLIPBOARD") => {
                let text = req.get("text").and_then(Value::as_str).unwrap_or("");
                self.broadcast_to_devices(&json!({"type": "CLIPBOARD_SYNC", "text": text})).await;
                json!({"status": "OK"})
            }
            Some("SEND_FILE") => self.start_file_push(req).await,
            Some("SET_OPTION") => {
                if let Some(on) = req.get("clipboardFiles").and_then(Value::as_bool) {
                    self.clipboard_files.store(on, std::sync::atomic::Ordering::Relaxed);
                    // forget what was offered, so turning it on offers what is on the clipboard now
                    self.last_clip_items.lock().await.clear();
                    let text = serde_json::to_string(&json!({"clipboard_files": on})).unwrap_or_default();
                    let _ = std::fs::write(&self.options_file, text);
                    json!({"status": "OK"})
                } else {
                    json!({"status": "UNKNOWN_OPTION"})
                }
            }
            Some("LOCK_SCREEN") => {
                desktop::trigger_screen_lock();
                json!({"status": "OK"})
            }
            _ => json!({}),
        }
    }

    async fn broadcast_local_ipc(&self, event_type: &str, data: Value) {
        let msg = json!({"event": event_type, "data": data});
        if let Ok(mut line) = serde_json::to_string(&msg) {
            line.push('\n');
            // Err just means no local clients are currently listening.
            let _ = self.ipc_broadcast.send(line);
        }
    }

    // ----------------- Desktop -> phone push -----------------

    async fn broadcast_to_devices(&self, packet: &Value) {
        let conns = self.active_connections.lock().await;
        for writer in conns.values() {
            let _ = send_json(writer, packet).await;
        }
    }

    async fn clipboard_watcher_loop(self: Arc<Self>) {
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
            // files, folders and pictures first: a picture has no text, and the text check below would skip it
            if self.clipboard_files.load(std::sync::atomic::Ordering::Relaxed) {
                self.offer_clipboard_items().await;
            }
            let current = desktop::get_linux_clipboard();
            if current.is_empty() {
                continue;
            }
            let mut last = self.last_clipboard_text.lock().await;
            if current != *last {
                *last = current.clone();
                drop(last);
                log::info!("Desktop clipboard changed, pushing to paired Android devices...");
                let packet = json!({
                    "type": "CLIPBOARD_SYNC",
                    "text": current,
                    "source": "linux",
                    "timestamp": unix_time(),
                });
                self.broadcast_to_devices(&packet).await;
            }
        }
    }

    /// Files, folders or a picture that were copied on the computer are offered to the connected phones (the phone still has to accept).
    async fn offer_clipboard_items(&self) {
        let items = tokio::task::spawn_blocking(desktop::get_clipboard_items).await.ok().flatten();
        let Some(items) = items else {
            self.last_clip_items.lock().await.clear();
            return;
        };
        let signature = match &items {
            desktop::ClipItems::Files(paths) => format!("files:{}", paths.iter().map(|p| p.display().to_string()).collect::<Vec<_>>().join("\n")),
            desktop::ClipItems::Image(bytes) => {
                use sha2::{Digest, Sha256};
                format!("image:{:x}", Sha256::digest(bytes))
            }
        };
        {
            let mut last = self.last_clip_items.lock().await;
            if *last == signature {
                return;
            }
            *last = signature;
        }
        let connections: Vec<String> = self.authenticated_connections.lock().await.keys().cloned().collect();
        if connections.is_empty() {
            return;
        }
        log::info!("Offering what was copied on the computer to {} phone connection(s)", connections.len());
        match items {
            desktop::ClipItems::Files(paths) => {
                let temp_dir = outgoing_temp_dir();
                // a handful at most: copying a whole folder of files must not bury the phone in offers
                for path in paths.into_iter().filter(|p| !p.starts_with(&temp_dir)).take(20) {
                    for c in &connections {
                        self.offer_path(c, path.clone(), None, false).await;
                    }
                }
            }
            desktop::ClipItems::Image(bytes) => {
                let dir = outgoing_temp_dir();
                if tokio::fs::create_dir_all(&dir).await.is_err() {
                    return;
                }
                for c in &connections {
                    // one copy per phone: each offer deletes its own file when it ends
                    let path = dir.join(format!("{:016x}.png", rand::thread_rng().gen::<u64>()));
                    if tokio::fs::write(&path, &bytes).await.is_ok() {
                        self.offer_path(c, path, Some(format!("picture-{}.png", unix_time())), true).await;
                    }
                }
            }
        }
    }

    /// Tells the phones what is playing on this computer (title, artist, playing or paused) whenever it changes.
    async fn media_watcher_loop(self: Arc<Self>) {
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            if self.authenticated_connections.lock().await.is_empty() {
                continue; // nobody to tell
            }
            let now = tokio::task::spawn_blocking(desktop::now_playing).await.ok().flatten();
            let packet = match now {
                Some(n) => json!({"type": "MEDIA_CONTROL", "title": n.title, "artist": n.artist, "isPlaying": n.playing}),
                None => json!({"type": "MEDIA_CONTROL", "title": "", "artist": "", "isPlaying": false}),
            };
            let mut last = self.last_media.lock().await;
            if last.as_ref() != Some(&packet) {
                *last = Some(packet.clone());
                drop(last);
                self.broadcast_to_devices(&packet).await;
            }
        }
    }

    async fn save_paired_devices(&self) {
        let devices = self.paired_devices.lock().await;
        let map: HashMap<&String, &PairedDevice> = devices.iter().collect();
        match serde_json::to_string_pretty(&map) {
            Ok(json) => {
                if let Err(e) = std::fs::write(&self.paired_devices_file, json) {
                    log::error!("Error saving paired devices: {e}");
                }
                restrict_to_owner(&self.paired_devices_file, 0o600);
            }
            Err(e) => log::error!("Error serializing paired devices: {e}"),
        }
    }
}

/// A paired device as local clients may see it: never the token hash.
fn public_device(d: &PairedDevice) -> Value {
    let mut v = serde_json::to_value(d).unwrap_or_default();
    if let Some(o) = v.as_object_mut() {
        o.remove("token_hash");
    }
    v
}

/// Gives a file or folder to its owner alone (the private key, the paired-phones list, the IPC socket).
fn restrict_to_owner(path: &std::path::Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    if let Err(e) = std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode)) {
        log::warn!("could not restrict {}: {e}", path.display());
    }
}

fn handle_input_event(packet: &Value) {
    let f = |k: &str| packet.get(k).and_then(Value::as_f64).unwrap_or(0.0);
    // Smooth path first: events written straight to a virtual device. The old tools are the fallback when /dev/uinput
    // cannot be opened (no permission yet) so nothing stops working.
    match packet.get("action").and_then(Value::as_str) {
        Some("MOVE") => {
            let (dx, dy) = (f("dx"), f("dy"));
            if uinput::mouse_move(dx, dy).is_none() {
                desktop::simulate_pointer_move(dx, dy);
            }
        }
        Some("CLICK") => {
            let button = packet.get("button").and_then(Value::as_str).unwrap_or("LEFT");
            if uinput::mouse_click(button).is_none() {
                desktop::simulate_pointer_click(button);
            }
        }
        // Hold a button down and let it go again (dragging, and games).
        Some("BUTTON") => {
            let button = packet.get("button").and_then(Value::as_str).unwrap_or("LEFT");
            let down = packet.get("down").and_then(Value::as_bool).unwrap_or(false);
            let _ = uinput::mouse_button(button, down);
        }
        // Remote desktop: place the pointer on a spot of the shown screen, scroll, type, press a listed key.
        Some("MOVE_TO") => {
            if uinput::pointer_to(f("x"), f("y")).is_none() {
                desktop::pointer_move_to(f("x"), f("y"));
            }
        }
        Some("SCROLL") => {
            // `lines` (whole numbers, the old way) or `dy` / `dx` in lines with fractions for a smooth drag
            let dy = packet.get("dy").and_then(Value::as_f64).unwrap_or_else(|| packet.get("lines").and_then(Value::as_f64).unwrap_or(0.0));
            if uinput::mouse_scroll(dy, f("dx")).is_none() {
                desktop::pointer_scroll(dy.round() as i64);
            }
        }
        Some("TYPE") => desktop::type_text(packet.get("text").and_then(Value::as_str).unwrap_or("")),
        Some("KEY") => {
            let name = packet.get("key").and_then(Value::as_str).unwrap_or("");
            match desktop::key_code(name) {
                Some(code) if uinput::key_tap(code).is_some() => {}
                _ => desktop::press_key(name),
            }
        }
        // A key held down or let go by its Linux code (games, shortcuts like Ctrl+C from a phone keyboard bar).
        Some("KEY_DOWN") | Some("KEY_UP") => {
            let down = packet.get("action").and_then(Value::as_str) == Some("KEY_DOWN");
            if let Some(code) = packet.get("code").and_then(Value::as_u64) {
                let _ = uinput::key(code as u32, down);
            }
        }
        // The phone as a game controller: its whole state, about sixty times a second while it is used.
        Some("GAMEPAD") => {
            let _ = uinput::gamepad(uinput::PadState::from_packet(packet));
        }
        Some("GAMEPAD_OFF") => uinput::gamepad_off(),
        // Locking the screen is a form of remote control, so it needs the same permission as the mouse.
        Some("LOCK_SCREEN") => desktop::trigger_screen_lock(),
        _ => {}
    }
}

fn load_paired_devices(path: &PathBuf) -> HashMap<String, PairedDevice> {
    match std::fs::read_to_string(path) {
        Ok(text) => serde_json::from_str(&text).unwrap_or_default(),
        Err(_) => HashMap::new(),
    }
}

/// Streams `path` to a phone as FILE_PUSH_CHUNK lines, then FILE_PUSH_COMPLETE. `Ok(false)`: the transfer was taken out of the
/// table (the phone cancelled or disconnected) and sending stopped. `progress` gets the bytes sent so far, about every 2 percent.
async fn push_file_chunks(
    writer: &SharedWriter,
    table: &TokioMutex<HashMap<String, OutgoingFile>>,
    id: &str,
    path: &std::path::Path,
    size: u64,
    progress: impl Fn(u64),
) -> std::io::Result<bool> {
    use base64::Engine;
    use tokio::io::AsyncReadExt;
    let mut file = tokio::fs::File::open(path).await?;
    let mut buf = vec![0u8; PUSH_CHUNK_BYTES];
    let (mut sent, mut last_reported) = (0u64, 0u64);
    loop {
        if !table.lock().await.contains_key(id) {
            return Ok(false);
        }
        let n = file.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        sent += n as u64;
        let chunk = base64::engine::general_purpose::STANDARD.encode(&buf[..n]);
        send_json(writer, &json!({"type": "FILE_PUSH_CHUNK", "transferId": id, "payloadBase64": chunk})).await?;
        if sent - last_reported >= (size / 50).max(1) {
            last_reported = sent;
            progress(sent);
        }
    }
    send_json(writer, &json!({"type": "FILE_PUSH_COMPLETE", "transferId": id})).await?;
    Ok(true)
}

async fn send_json(writer: &SharedWriter, obj: &Value) -> std::io::Result<()> {
    let mut line = serde_json::to_string(obj).unwrap_or_default();
    line.push('\n');
    let mut w = writer.lock().await;
    w.write_all(line.as_bytes()).await
}

fn base64_decode(s: &str) -> Result<Vec<u8>, base64::DecodeError> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.decode(s)
}

fn unix_time() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
}

fn unix_time_f64() -> f64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs_f64()
}

fn hostname() -> String {
    std::process::Command::new("hostname")
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "zohara-link".to_string())
}

/// getuid(2) via libc, without pulling in the whole `libc` crate for one
/// syscall (this only ever runs on Linux, matching the rest of the daemon).
unsafe fn libc_geteuid() -> u32 {
    #[cfg(unix)]
    {
        extern "C" {
            fn geteuid() -> u32;
        }
        geteuid()
    }
    #[cfg(not(unix))]
    {
        0
    }
}

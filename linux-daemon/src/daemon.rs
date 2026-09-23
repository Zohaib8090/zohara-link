use crate::protocol::{FileTransfer, PairedDevice};
use crate::{desktop, mdns, tls};
use anyhow::{Context, Result};
use rand::Rng;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream, UnixListener, UnixStream};
use tokio::sync::{broadcast, Mutex as TokioMutex};
use tokio_rustls::TlsAcceptor;

type TlsWriteHalf = tokio::io::WriteHalf<tokio_rustls::server::TlsStream<TcpStream>>;
type SharedWriter = Arc<TokioMutex<TlsWriteHalf>>;

pub struct Daemon {
    port: u16,
    downloads_dir: PathBuf,
    paired_devices_file: PathBuf,
    cert_path: PathBuf,
    key_path: PathBuf,
    unix_socket_path: PathBuf,

    paired_devices: TokioMutex<HashMap<String, PairedDevice>>,
    pending_pairings: TokioMutex<HashMap<String, String>>,
    /// Connections (keyed by the connection-scoped "dev_<ip>_<port>" id)
    /// that have completed PAIR_VERIFY *on this TCP connection*. Nothing
    /// else here acts on a packet without this -- see handle_packet's gate.
    /// This is connection-scoped, not device-scoped: there is no
    /// persistent session token or verified client certificate (that
    /// needs real mTLS client-cert pinning, tracked separately), so a
    /// previously-paired device still has to redo the PIN handshake on
    /// every reconnect. Real UX cost, not a bug in this port.
    authenticated_connections: TokioMutex<HashSet<String>>,
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
    last_clipboard_text: TokioMutex<String>,
    /// Fan-out to local Unix-socket IPC clients (Waybar, a GTK settings
    /// panel, `zohara-link-status`, ...).
    ipc_broadcast: broadcast::Sender<String>,
}

impl Daemon {
    pub fn new(port: u16) -> Result<Arc<Self>> {
        let home = std::env::var("HOME").context("HOME is not set")?;
        let config_dir = PathBuf::from(&home).join(".config/zohara-link");
        let downloads_dir = PathBuf::from(&home).join("Downloads/ZoharaLink");
        std::fs::create_dir_all(&config_dir).context("create config dir")?;
        std::fs::create_dir_all(&downloads_dir).context("create downloads dir")?;

        let paired_devices_file = config_dir.join("paired_devices.json");
        let paired_devices = load_paired_devices(&paired_devices_file);

        let uid = unsafe { libc_geteuid() };
        let unix_socket_path = PathBuf::from(format!("/run/user/{uid}/zohara.sock"));

        let (ipc_broadcast, _) = broadcast::channel(64);

        Ok(Arc::new(Self {
            port,
            cert_path: config_dir.join("daemon.crt"),
            key_path: config_dir.join("daemon.key"),
            paired_devices_file,
            downloads_dir,
            unix_socket_path,
            paired_devices: TokioMutex::new(paired_devices),
            pending_pairings: TokioMutex::new(HashMap::new()),
            authenticated_connections: TokioMutex::new(HashSet::new()),
            active_connections: TokioMutex::new(HashMap::new()),
            device_telemetry: TokioMutex::new(HashMap::new()),
            active_file_transfers: TokioMutex::new(HashMap::new()),
            last_clipboard_text: TokioMutex::new(String::new()),
            ipc_broadcast,
        }))
    }

    pub async fn run(self: Arc<Self>) -> Result<()> {
        tls::ensure_self_signed_cert(&self.cert_path, &self.key_path)?;
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
        log::info!(
            "Local IPC Unix domain socket active at {}",
            self.unix_socket_path.display()
        );

        mdns::advertise(&hostname(), self.port);

        {
            let this = self.clone();
            tokio::spawn(async move { this.clipboard_watcher_loop().await });
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

        let mut lines = BufReader::new(read_half).lines();
        loop {
            match lines.next_line().await {
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
            && !self
                .authenticated_connections
                .lock()
                .await
                .contains(device_id)
        {
            log::warn!("Rejected [{ptype}] from unauthenticated connection {device_id}");
            let _ = send_json(writer, &json!({"type": "ERROR", "error": "not paired on this connection"})).await;
            return;
        }

        match ptype {
            "PAIR_REQUEST" => self.handle_pair_request(device_id, &packet, writer).await,
            "PAIR_VERIFY" => self.handle_pair_verify(device_id, &packet, writer).await,
            "CLIPBOARD_SYNC" => self.handle_clipboard_sync(&packet).await,
            "TELEMETRY_STATUS" => self.handle_telemetry(device_id, packet).await,
            "NOTIFICATION_POST" => self.handle_notification_post(&packet).await,
            "FILE_OFFER" => self.handle_file_offer(&packet, writer).await,
            "FILE_CHUNK" => self.handle_file_chunk(&packet).await,
            "FILE_COMPLETE" => self.handle_file_complete(&packet).await,
            "INPUT_EVENT" => handle_input_event(&packet),
            "MEDIA_CONTROL" => {
                let action = packet.get("action").and_then(Value::as_str).unwrap_or("");
                desktop::control_mpris2_media(action);
            }
            "PROXIMITY_HEARTBEAT" => {}
            _ => {}
        }
    }

    async fn handle_pair_request(&self, device_id: &str, packet: &Value, writer: &SharedWriter) {
        let req_device_id = packet
            .get("deviceId")
            .and_then(Value::as_str)
            .unwrap_or(device_id)
            .to_string();
        let req_device_name = packet
            .get("deviceName")
            .and_then(Value::as_str)
            .unwrap_or("Android Device")
            .to_string();
        let pin: u32 = rand::thread_rng().gen_range(100_000..=999_999);
        let pin = pin.to_string();

        self.pending_pairings
            .lock()
            .await
            .insert(req_device_id.clone(), pin.clone());

        log::info!("*** PAIRING AUTHORIZATION REQUIRED ***");
        log::info!("Device: {req_device_name} ({req_device_id})");
        log::info!("Verification PIN (SAS): >>> {pin} <<<");

        desktop::send_desktop_notification(
            "Zohara Link - Pairing Request",
            &format!("Device '{req_device_name}' wants to pair.\nSAS Verification PIN: {pin}"),
        );
        self.broadcast_local_ipc(
            "PAIR_REQUEST",
            json!({"deviceId": req_device_id, "deviceName": req_device_name, "pin": pin}),
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
        let req_device_name = packet
            .get("deviceName")
            .and_then(Value::as_str)
            .unwrap_or("Android Device")
            .to_string();

        let expected_pin = self.pending_pairings.lock().await.get(&req_device_id).cloned();

        // No `approved == true` bypass: that field is the client's own
        // unverified claim about itself, not something the desktop user
        // approved, and accepting it would skip the PIN check entirely.
        let matched = matches!((&expected_pin, user_pin), (Some(exp), Some(p)) if exp == p);

        if matched {
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
            };
            self.paired_devices
                .lock()
                .await
                .insert(req_device_id.clone(), paired.clone());
            self.save_paired_devices().await;
            self.pending_pairings.lock().await.remove(&req_device_id);
            self.authenticated_connections
                .lock()
                .await
                .insert(device_id.to_string());

            log::info!("Device '{req_device_name}' paired successfully!");
            let _ = send_json(
                writer,
                &json!({"type": "PAIR_CONFIRMED", "status": "SUCCESS", "serverName": hostname()}),
            )
            .await;
            self.broadcast_local_ipc("DEVICE_PAIRED", serde_json::to_value(&paired).unwrap())
                .await;
        } else {
            let _ = send_json(
                writer,
                &json!({"type": "PAIR_CONFIRMED", "status": "FAILED", "error": "PIN mismatch"}),
            )
            .await;
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
        let title = packet.get("title").and_then(Value::as_str).unwrap_or("Android Notification");
        let text = packet.get("text").and_then(Value::as_str).unwrap_or("");
        let app_name = packet.get("appName").and_then(Value::as_str).unwrap_or("Phone");
        desktop::send_desktop_notification(&format!("[{app_name}] {title}"), text);
        self.broadcast_local_ipc("NOTIFICATION_POSTED", packet.clone()).await;
    }

    async fn handle_file_offer(&self, packet: &Value, writer: &SharedWriter) {
        let transfer_id = packet.get("transferId").and_then(Value::as_str).unwrap_or("").to_string();
        let file_name = packet.get("fileName").and_then(Value::as_str).unwrap_or("received_file").to_string();
        let file_size = packet.get("fileSize").and_then(Value::as_u64).unwrap_or(0);

        let safe_name = std::path::Path::new(&file_name)
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| "received_file".to_string());
        let dest_path = self.downloads_dir.join(&safe_name);

        let handle = match tokio::fs::File::create(&dest_path).await {
            Ok(f) => f,
            Err(e) => {
                log::warn!("failed to create {}: {e}", dest_path.display());
                return;
            }
        };

        self.active_file_transfers.lock().await.insert(
            transfer_id.clone(),
            FileTransfer { file_name: file_name.clone(), file_size, dest_path, handle, received_bytes: 0 },
        );

        log::info!("Accepting incoming file: {file_name} ({file_size} bytes)");
        let _ = send_json(writer, &json!({"type": "FILE_ACCEPT", "transferId": transfer_id, "accepted": true})).await;
        self.broadcast_local_ipc(
            "FILE_TRANSFER_START",
            json!({"transferId": transfer_id, "fileName": file_name, "fileSize": file_size}),
        )
        .await;
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
                let paired: Vec<_> = self.paired_devices.lock().await.values().cloned().collect();
                let telemetry = self.device_telemetry.lock().await.clone();
                let active: Vec<_> = self.active_file_transfers.lock().await.keys().cloned().collect();
                json!({"paired_devices": paired, "telemetry": telemetry, "active_transfers": active})
            }
            Some("SEND_CLIPBOARD") => {
                let text = req.get("text").and_then(Value::as_str).unwrap_or("");
                self.broadcast_to_devices(&json!({"type": "CLIPBOARD_SYNC", "text": text})).await;
                json!({"status": "OK"})
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

    async fn save_paired_devices(&self) {
        let devices = self.paired_devices.lock().await;
        let map: HashMap<&String, &PairedDevice> = devices.iter().collect();
        match serde_json::to_string_pretty(&map) {
            Ok(json) => {
                if let Err(e) = std::fs::write(&self.paired_devices_file, json) {
                    log::error!("Error saving paired devices: {e}");
                }
            }
            Err(e) => log::error!("Error serializing paired devices: {e}"),
        }
    }
}

fn handle_input_event(packet: &Value) {
    match packet.get("action").and_then(Value::as_str) {
        Some("MOVE") => {
            let dx = packet.get("dx").and_then(Value::as_f64).unwrap_or(0.0);
            let dy = packet.get("dy").and_then(Value::as_f64).unwrap_or(0.0);
            desktop::simulate_pointer_move(dx, dy);
        }
        Some("CLICK") => {
            let button = packet.get("button").and_then(Value::as_str).unwrap_or("LEFT");
            desktop::simulate_pointer_click(button);
        }
        _ => {}
    }
}

fn load_paired_devices(path: &PathBuf) -> HashMap<String, PairedDevice> {
    match std::fs::read_to_string(path) {
        Ok(text) => serde_json::from_str(&text).unwrap_or_default(),
        Err(_) => HashMap::new(),
    }
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

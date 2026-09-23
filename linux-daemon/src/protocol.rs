//! Wire types shared with the Android app's `ProtocolMessages.kt`.
//!
//! Packets are kept as loosely-typed `serde_json::Value` rather than a
//! closed enum, matching the Python original's `packet.get(...)` style: the
//! Android side can add optional fields freely without both sides needing
//! to change in lockstep, and an unknown packet type is simply ignored
//! instead of a hard deserialization error killing the connection.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub device_id: String,
    pub device_name: String,
    pub paired_at: f64,
    pub client_cert_thumbprint: String,
    pub last_ip: String,
}

pub struct FileTransfer {
    pub file_name: String,
    pub file_size: u64,
    pub dest_path: std::path::PathBuf,
    pub handle: tokio::fs::File,
    pub received_bytes: u64,
}

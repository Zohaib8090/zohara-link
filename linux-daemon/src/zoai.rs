//! Phone to ZO-AI: what a phone may ask the ZO-AI program on the computer, and where that program listens.
//!
//! The daemon only carries lines between the phone and ZO-AI's local socket (see `service.rs` in the ZO-AI repository). It decides two
//! things itself: which requests a phone may make, and that a phone can never switch ZO-AI to "Bypass" (no questions asked): that
//! stays a decision made on the computer.

use serde_json::{json, Value};
use std::path::PathBuf;

/// The requests a phone may make. Anything else is refused here.
const OPS: &[&str] = &[
    "status", "chats", "chat", "delete_chat", "pin_chat", "mode", "providers", "models", "select", "mcp_list", "mcp_add",
    "mcp_import", "mcp_remove", "mcp_toggle", "send", "switch", "set_key", "profiles", "profile_use", "profile_create",
    "profile_rename", "profile_delete", "profile_pin", "memory", "memory_add", "memory_forget", "memory_pin", "memory_enable", "memory_clear", "personalities", "personality_use", "personality_add", "personality_generate", "personality_remove", "scheduled", "schedule_cancel", "schedule_add", "projects", "project_create",
    "compact",
];

/// Where ZO-AI listens: `$XDG_RUNTIME_DIR/zo-ai/agent.sock`, like the program itself.
pub fn socket_path() -> PathBuf {
    let base = std::env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(format!("/tmp/zo-ai-{}", unsafe { libc::getuid() })));
    base.join("zo-ai").join("agent.sock")
}

fn allowed_mode(m: &str) -> bool {
    matches!(m.to_ascii_lowercase().as_str(), "auto" | "manual")
}

/// The request line for ZO-AI, made from a phone's `ZOAI_REQ` packet (without its `type` and `rid`).
pub fn clean_request(packet: &Value) -> Result<String, String> {
    let mut req = packet.as_object().cloned().ok_or("not an object")?;
    req.remove("type");
    req.remove("rid");
    let op = req.get("op").and_then(Value::as_str).unwrap_or("").to_string();
    if !OPS.contains(&op.as_str()) {
        return Err(format!("unknown request: {op}"));
    }
    // the way ZO-AI asks before it acts: a phone may choose Auto or Manual, never Bypass
    for key in ["mode", "set"] {
        if let Some(m) = req.get(key).and_then(Value::as_str) {
            if !allowed_mode(m) {
                return Err("Bypass (no questions asked) can only be switched on at the computer.".into());
            }
        }
    }
    Ok(Value::Object(req).to_string())
}

/// A line the phone sends while its request runs: an approval answer or "stop", nothing else.
pub fn clean_line(packet: &Value) -> Option<String> {
    match packet.get("op").and_then(Value::as_str)? {
        "approve" => Some(json!({"op": "approve", "id": packet.get("id").and_then(Value::as_u64)?, "yes": packet.get("yes").and_then(Value::as_bool).unwrap_or(false)}).to_string()),
        "stop" => Some(json!({"op": "stop"}).to_string()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_known_requests_pass_and_the_wrapper_fields_are_dropped() {
        let ok = clean_request(&json!({"type": "ZOAI_REQ", "rid": "1", "op": "send", "text": "hi"})).unwrap();
        let v: Value = serde_json::from_str(&ok).unwrap();
        assert_eq!(v["op"], "send");
        assert!(v.get("type").is_none() && v.get("rid").is_none());
        assert!(clean_request(&json!({"op": "rm_rf"})).is_err());
        assert!(clean_request(&json!({"op": ""})).is_err());
    }

    #[test]
    fn a_phone_cannot_turn_on_bypass() {
        assert!(clean_request(&json!({"op": "send", "text": "x", "mode": "bypass"})).is_err());
        assert!(clean_request(&json!({"op": "mode", "set": "Bypass"})).is_err());
        assert!(clean_request(&json!({"op": "mode", "set": "manual"})).is_ok());
        assert!(clean_request(&json!({"op": "send", "text": "x", "mode": "auto"})).is_ok());
    }

    #[test]
    fn a_phone_line_is_an_approval_or_stop_only() {
        assert_eq!(clean_line(&json!({"op": "stop"})).unwrap(), r#"{"op":"stop"}"#);
        assert!(clean_line(&json!({"op": "approve", "id": 3, "yes": true})).unwrap().contains("\"yes\":true"));
        assert!(clean_line(&json!({"op": "approve", "yes": true})).is_none());
        assert!(clean_line(&json!({"op": "send", "text": "x"})).is_none());
    }
}

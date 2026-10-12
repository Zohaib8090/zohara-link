//! mDNS advertisement so the Android app's NSD discovery
//! (`_zohara-link._tcp`) can find this daemon with no manual IP entry.
//!
//! The Python daemon this replaced defined `MDNS_SERVICE_TYPE` /
//! `MDNS_SERVICE_NAME` constants but never actually registered a service
//! with anything (no zeroconf/avahi call anywhere in that file) -- so
//! automatic discovery never worked; pairing needed the phone to already
//! know the desktop's IP. This is a real, new capability, not a port of
//! existing behaviour, so failures here are logged and swallowed rather
//! than treated as fatal: the TLS/TCP server the phone actually talks to
//! doesn't depend on it, manual IP entry still works either way, and a
//! network without mDNS support (some corporate Wi-Fi) shouldn't stop the
//! daemon from starting.

use mdns_sd::{IfKind, ServiceDaemon, ServiceInfo};

pub const SERVICE_TYPE: &str = "_zohara-link._tcp.local.";

/// Interfaces that only exist inside this computer (containers, virtual machines, loopback). Announcing their addresses made phones
/// pick one they cannot reach (for example Docker's 172.17.0.1), so the computer showed up nowhere or connected to nothing.
pub fn is_virtual_iface(name: &str) -> bool {
    ["docker", "br-", "virbr", "veth", "vmnet", "vboxnet", "lxc", "lxd", "podman", "cni", "flannel"]
        .iter()
        .any(|p| name.starts_with(p))
        || name == "lo"
}

/// Names of the network interfaces on this computer (from /sys/class/net).
fn interface_names() -> Vec<String> {
    std::fs::read_dir("/sys/class/net")
        .map(|d| d.flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect())
        .unwrap_or_default()
}

/// Registers the service and leaks the `ServiceDaemon` for the process
/// lifetime (it owns a background thread that keeps advertising; there is
/// no clean shutdown path in this daemon's design, matching how the rest
/// of it runs until killed).
pub fn advertise(hostname: &str, port: u16) {
    let instance_name = format!("ZoharaLinux-{hostname}");
    let result = (|| -> Result<(), mdns_sd::Error> {
        let mdns = ServiceDaemon::new()?;
        // Phones connect over IPv4; an IPv6 link-local address in the announcement cannot be connected to without a scope id.
        mdns.disable_interface(IfKind::IPv6)?;
        for name in interface_names().into_iter().filter(|n| is_virtual_iface(n)) {
            mdns.disable_interface(IfKind::Name(name))?;
        }
        let host_fqdn = format!("{hostname}.local.");
        let service = ServiceInfo::new(
            SERVICE_TYPE,
            &instance_name,
            &host_fqdn,
            "",
            port,
            None::<std::collections::HashMap<String, String>>,
        )?
        .enable_addr_auto();
        mdns.register(service)?;
        // Leak: keeping the daemon alive for the process lifetime is the
        // point -- dropping it would tear down the advertisement.
        std::mem::forget(mdns);
        Ok(())
    })();

    match result {
        Ok(()) => log::info!("Advertising {SERVICE_TYPE} as '{instance_name}' on port {port}"),
        Err(e) => log::warn!(
            "mDNS advertisement failed ({e}); phone will need the desktop's IP entered manually"
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_real_networks_are_announced() {
        for virtual_name in ["lo", "docker0", "br-1a2b3c", "virbr0", "veth12ab", "vmnet8", "podman0", "cni0"] {
            assert!(is_virtual_iface(virtual_name), "{virtual_name} should be skipped");
        }
        for real in ["wlan0", "wlp2s0", "enp3s0", "eth0", "tun0", "wg0"] {
            assert!(!is_virtual_iface(real), "{real} should be announced");
        }
    }
}

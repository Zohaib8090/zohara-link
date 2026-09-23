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

use mdns_sd::{ServiceDaemon, ServiceInfo};

pub const SERVICE_TYPE: &str = "_zohara-link._tcp.local.";

/// Registers the service and leaks the `ServiceDaemon` for the process
/// lifetime (it owns a background thread that keeps advertising; there is
/// no clean shutdown path in this daemon's design, matching how the rest
/// of it runs until killed).
pub fn advertise(hostname: &str, port: u16) {
    let instance_name = format!("ZoharaLinux-{hostname}");
    let result = (|| -> Result<(), mdns_sd::Error> {
        let mdns = ServiceDaemon::new()?;
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

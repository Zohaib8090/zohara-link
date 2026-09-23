//! Self-signed TLS certificate generation and loading.
//!
//! The Python daemon shelled out to the `openssl` CLI to generate this.
//! `rcgen` does it in-process instead, which drops `openssl` from the
//! runtime dependency list entirely (it's still needed at build time by
//! rustls' crypto backend, but not as an external command the daemon
//! calls at startup).

use anyhow::{Context, Result};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use std::path::Path;

/// Generates a self-signed cert/key pair at the given paths if neither
/// already exists. Mirrors the Python original's behaviour: it only
/// (re)generates when BOTH files are missing, so an existing cert survives
/// daemon restarts and upgrades.
pub fn ensure_self_signed_cert(cert_path: &Path, key_path: &Path) -> Result<()> {
    if cert_path.exists() && key_path.exists() {
        return Ok(());
    }
    log::info!("Generating self-signed TLS certificate for Zohara Link daemon...");

    let hostname = hostname();
    let mut params = rcgen::CertificateParams::new(vec![hostname.clone()])
        .context("build certificate params")?;
    params
        .distinguished_name
        .push(rcgen::DnType::CommonName, hostname);
    params
        .distinguished_name
        .push(rcgen::DnType::OrganizationName, "ZoharaLink");
    params
        .distinguished_name
        .push(rcgen::DnType::OrganizationalUnitName, "LinuxEcosystem");
    // 10 years, matching the openssl -days 3650 the Python version used.
    params.not_after = rcgen::date_time_ymd(2036, 1, 1);

    let key_pair = rcgen::KeyPair::generate().context("generate key pair")?;
    let cert = params
        .self_signed(&key_pair)
        .context("self-sign certificate")?;

    std::fs::write(cert_path, cert.pem()).context("write cert file")?;
    std::fs::write(key_path, key_pair.serialize_pem()).context("write key file")?;
    log::info!(
        "Certificate written to {} / {}",
        cert_path.display(),
        key_path.display()
    );
    Ok(())
}

pub fn load_tls_config(cert_path: &Path, key_path: &Path) -> Result<rustls::ServerConfig> {
    let cert_pem = std::fs::read(cert_path).context("read cert file")?;
    let key_pem = std::fs::read(key_path).context("read key file")?;

    let certs: Vec<CertificateDer<'static>> = rustls_pemfile::certs(&mut cert_pem.as_slice())
        .collect::<std::result::Result<_, _>>()
        .context("parse certificate PEM")?;
    let key: PrivateKeyDer<'static> = rustls_pemfile::private_key(&mut key_pem.as_slice())
        .context("parse private key PEM")?
        .context("no private key found in key file")?;

    let config = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .context("build TLS server config")?;
    Ok(config)
}

fn hostname() -> String {
    hostname_str().unwrap_or_else(|| "zohara-link".to_string())
}

#[cfg(unix)]
fn hostname_str() -> Option<String> {
    std::process::Command::new("hostname")
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
}

#[cfg(not(unix))]
fn hostname_str() -> Option<String> {
    std::env::var("COMPUTERNAME").ok()
}

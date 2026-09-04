use std::time::Duration;

use mdns_sd::{ServiceDaemon, ServiceEvent};
use tracing::{debug, info};

pub const TRUSTED_RUNTIME_SERVICE: &str = "_vortex._tcp.local.";

#[derive(Debug, Clone)]
pub struct LanCandidate {
    pub instance_name: String,
    pub host: String,
    pub port: u16,
    pub addresses: Vec<std::net::IpAddr>,
}

pub async fn discover_first(timeout: Duration) -> Result<Option<LanCandidate>, String> {
    let daemon = ServiceDaemon::new().map_err(|e| format!("mdns daemon: {e}"))?;
    let result = discover_first_inner(&daemon, timeout).await;
    let _ = daemon.shutdown();
    result
}

async fn discover_first_inner(
    daemon: &ServiceDaemon,
    timeout: Duration,
) -> Result<Option<LanCandidate>, String> {
    let receiver = daemon.browse(TRUSTED_RUNTIME_SERVICE).map_err(|e| format!("browse: {e}"))?;
    info!("browsing {}", TRUSTED_RUNTIME_SERVICE);

    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        let now = tokio::time::Instant::now();
        if now >= deadline {
            return Ok(None);
        }
        let remaining = deadline - now;
        let event = match tokio::task::spawn_blocking({
            let receiver = receiver.clone();
            let to = remaining;
            move || receiver.recv_timeout(to)
        })
        .await
        {
            Ok(Ok(event)) => event,
            Ok(Err(_recv_err)) => return Ok(None),
            Err(join_err) => return Err(format!("mdns task: {join_err}")),
        };

        if let ServiceEvent::ServiceResolved(info) = event {
            let fullname = info.get_fullname().to_string();
            debug!("resolved {} -> {}:{}", fullname, info.get_hostname(), info.get_port());
            if !looks_like_vortex_instance(&fullname) {
                debug!("ignoring non-vortex instance {fullname}");
                continue;
            }
            let addrs: Vec<std::net::IpAddr> = info.get_addresses().iter().copied().collect();
            if addrs.is_empty() {
                continue;
            }
            return Ok(Some(LanCandidate {
                instance_name: fullname,
                host: info.get_hostname().to_string(),
                port: info.get_port(),
                addresses: addrs,
            }));
        }
    }
}

pub fn watch_candidates() -> Result<tokio::sync::mpsc::UnboundedReceiver<LanCandidate>, String> {
    let daemon = ServiceDaemon::new().map_err(|e| format!("mdns daemon: {e}"))?;
    let recv = daemon.browse(TRUSTED_RUNTIME_SERVICE).map_err(|e| format!("browse: {e}"))?;
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    std::thread::spawn(move || {
        let _keepalive = daemon;
        while let Ok(event) = recv.recv() {
            if let ServiceEvent::ServiceResolved(info) = event {
                let fullname = info.get_fullname().to_string();
                if !looks_like_vortex_instance(&fullname) {
                    continue;
                }
                let addrs: Vec<std::net::IpAddr> = info.get_addresses().iter().copied().collect();
                if addrs.is_empty() {
                    continue;
                }
                let cand = LanCandidate {
                    instance_name: fullname,
                    host: info.get_hostname().to_string(),
                    port: info.get_port(),
                    addresses: addrs,
                };
                if tx.send(cand).is_err() {
                    break;
                }
            }
        }
    });
    Ok(rx)
}

fn looks_like_vortex_instance(fullname: &str) -> bool {
    let lower = fullname.to_ascii_lowercase();
    if !lower.ends_with("._vortex._tcp.local.") {
        return false;
    }
    let instance = match lower.split_once('.') {
        Some((head, _)) => head,
        None => return false,
    };
    let Some(suffix) = instance.strip_prefix("vortex-") else {
        return false;
    };
    suffix.len() == 16 && suffix.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

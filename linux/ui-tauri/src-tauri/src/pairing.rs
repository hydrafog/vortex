use std::sync::Arc;
use std::time::Duration;

use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::oneshot;
use tracing::{info, warn};

use vortex_l3_daemon::core::ble::client::VortexClient;
use vortex_l3_daemon::core::identity::IdentityRecord;
use vortex_l3_daemon::core::pairing::handshake::{run_pairing_initiator, LocalDecision};
use vortex_l3_daemon::core::storage::peers::{PeerStore, TrustedPeer};

use crate::{emit_peers, CmdChannel, PairDecisionState, UiCmd};

pub(crate) fn redact_sas(sas: &str) -> String {
    let chars: Vec<char> = sas.chars().collect();
    if chars.len() <= 6 {
        return "***".to_string();
    }
    let head: String = chars.iter().take(3).collect();
    let tail: String = chars.iter().rev().take(3).collect::<Vec<_>>().into_iter().rev().collect();
    format!("{head}…{tail}")
}

pub(crate) async fn do_pair(
    app: &AppHandle,
    adapter: &bluer::Adapter,
    addr_str: &str,
    identity: &IdentityRecord,
    peer_store: Arc<dyn PeerStore>,
) -> Result<(), String> {
    let addr: bluer::Address = addr_str.parse().map_err(|e| format!("bad BD_ADDR: {e}"))?;

    let t_pair = std::time::Instant::now();

    if let Ok(device) = adapter.device(addr) {
        if device.is_connected().await.unwrap_or(false) {
            let _ = device.disconnect().await;
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
    }
    let hygiene_ms = t_pair.elapsed().as_millis();

    let client = VortexClient::connect(adapter, addr).await.map_err(|e| format!("connect: {e}"))?;
    info!(
        hygiene_ms,
        connect_total_ms = t_pair.elapsed().as_millis(),
        "pair: connected to {addr_str}; running Noise XX"
    );

    let local_name = std::fs::read_to_string("/proc/sys/kernel/hostname")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());
    let app_for_sas = app.clone();
    let outcome = run_pairing_initiator(
        &client,
        &identity.static_priv.0,
        Duration::from_secs(60),
        move |sas: &str| {
            let sas_string = sas.to_string();
            let app = app_for_sas.clone();
            async move {
                info!(sas.redacted = %redact_sas(&sas_string), "Linux awaiting user approval");
                let (tx, rx) = oneshot::channel::<LocalDecision>();
                if let Some(decision_state) = app.try_state::<PairDecisionState>() {
                    if let Ok(mut slot) = decision_state.0.lock() {
                        *slot = Some(tx);
                    } else {
                        warn!("PairDecisionState lock poisoned; defaulting to Reject");
                        return LocalDecision::Reject;
                    }
                } else {
                    warn!("PairDecisionState not managed; defaulting to Reject");
                    return LocalDecision::Reject;
                }
                info!(
                    sas_ready_ms = t_pair.elapsed().as_millis(),
                    "pair: Noise XX complete, SAS ready (≈ Android modal appears now)"
                );
                let _ = app.emit("vortex:pairing_sas", sas_string);
                match tokio::time::timeout(Duration::from_secs(60), rx).await {
                    Ok(Ok(decision)) => decision,
                    Ok(Err(_)) => {
                        warn!("pairing decision sender dropped; treating as Reject");
                        LocalDecision::Reject
                    }
                    Err(_) => {
                        warn!("pairing SAS approval timed out after 60s; treating as Reject");
                        if let Some(decision_state) = app.try_state::<PairDecisionState>() {
                            if let Ok(mut slot) = decision_state.0.lock() {
                                *slot = None;
                            }
                        }
                        LocalDecision::Reject
                    }
                }
            }
        },
        local_name.as_deref(),
    )
    .await
    .map_err(|e| format!("handshake: {e}"))?;

    let paired_at = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let trusted = TrustedPeer {
        peer_static_pub: outcome.xx.peer_static_pub,
        prs: outcome.prs,
        paired_at,
        peer_name: outcome.peer_name.clone(),
    };
    peer_store.save(&trusted).map_err(|e| format!("save trust: {e}"))?;

    emit_peers(app, peer_store.clone()).await;

    // NOTE: no BT bond here. We investigated LE bonding to resolve the
    Ok(())
}

pub(crate) async fn send_revoke_to_peer(
    identity: &IdentityRecord,
    peer: &TrustedPeer,
    peer_pub: &[u8; 32],
    local_counter: u64,
) -> Result<(), String> {
    let candidate = vortex_l3_daemon::core::lan::discovery::discover_first(Duration::from_secs(3))
        .await
        .map_err(|e| format!("mdns: {e}"))?
        .ok_or_else(|| "no LAN candidate".to_string())?;
    let ip = candidate
        .addresses
        .iter()
        .find(|a| matches!(a, std::net::IpAddr::V4(_)))
        .copied()
        .or_else(|| candidate.addresses.first().copied())
        .ok_or_else(|| "no IP".to_string())?;
    let socket_addr = std::net::SocketAddr::new(ip, candidate.port);
    let mut state = vortex_l3_daemon::core::appstate::AppState::now_laptop();
    state.revoked = true;
    let _ = vortex_l3_daemon::core::lan::tcp_client::run_lan_reconnect(
        socket_addr,
        &identity.static_priv.0,
        &peer.peer_static_pub,
        &peer.prs,
        local_counter,
        state,
        Duration::from_secs(4),
        None,
    )
    .await
    .map_err(|e| format!("revoke send: {e}"))?;
    tracing::info!("revoke notification delivered to {}", hex::encode(&peer_pub[..8]));
    Ok(())
}

#[tauri::command]
pub fn start_pair(addr: String, state: State<'_, CmdChannel>) -> Result<(), String> {
    state.0.send(UiCmd::Pair(addr)).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn pair_decision(approve: bool, state: State<'_, PairDecisionState>) -> Result<(), String> {
    let maybe = state.0.lock().map_err(|e| e.to_string())?.take();
    let Some(sender) = maybe else {
        return Ok(());
    };
    let decision = if approve { LocalDecision::Approve } else { LocalDecision::Reject };
    let _ = sender.send(decision);
    Ok(())
}

#[tauri::command]
pub fn forget_peer(peer_static_pub: String, state: State<'_, CmdChannel>) -> Result<(), String> {
    state.0.send(UiCmd::ForgetPeer(peer_static_pub)).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn forget_all(state: State<'_, CmdChannel>) -> Result<(), String> {
    state.0.send(UiCmd::ForgetAll).map_err(|e| e.to_string())
}

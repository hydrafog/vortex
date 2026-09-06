use std::sync::mpsc::Sender;
use std::sync::Arc;

use serde::Serialize;
use tauri::{AppHandle, Emitter};

use vortex_l3_daemon::core::appstate::AppState;
use vortex_l3_daemon::core::storage::peers::PeerStore;

pub(crate) enum UiCmd {
    Scan,
    Pair(String),
    RemoveBond(String),
    ForgetPeer(String),
    ForgetAll,
    RefreshState,
    RefreshLocalEarbuds,
    RequestEarbudsSwitch { peer_static_pub: String, mac: String },
    SendEarbudsClaim { peer_static_pub: String, mac: String },
    ToggleEarbuds,
    StartMirror { width: u32, height: u32, fps: u32, bitrate: u32 },
    StopMirror,
}

#[derive(Serialize, Clone)]
pub(crate) struct IdentityInfo {
    pub(crate) ready: bool,
}

#[derive(Serialize, Clone)]
pub(crate) struct ScanHitDto {
    pub(crate) addr: String,
    pub(crate) rssi: i16,
    pub(crate) instance: String,
    pub(crate) name: Option<String>,
}

#[derive(Serialize, Clone)]
pub(crate) struct TrustedPeerDto {
    peer_static_pub: String,
    paired_at: u64,
    peer_name: Option<String>,
}

#[derive(Serialize, Clone)]
pub(crate) struct PeerStateDto {
    peer_static_pub: String,
    battery: Option<u8>,
    class: String,
    name: Option<String>,
    locale: Option<String>,
    theme: Option<String>,
    earbuds: Option<EarbudsDto>,
    charging: bool,
    ts: u64,
}

#[derive(Serialize, Clone)]
pub(crate) struct EarbudsDto {
    name: String,
    battery: Option<u8>,
    connected: bool,
}

impl From<vortex_l3_daemon::core::appstate::EarbudsInfo> for EarbudsDto {
    fn from(e: vortex_l3_daemon::core::appstate::EarbudsInfo) -> Self {
        EarbudsDto { name: e.name, battery: e.battery, connected: e.connected }
    }
}

pub(crate) fn app_state_to_dto(peer_pub_hex: String, s: AppState) -> PeerStateDto {
    let class = match s.class {
        vortex_l3_daemon::core::appstate::DeviceClass::Laptop => "laptop",
        vortex_l3_daemon::core::appstate::DeviceClass::Phone => "phone",
        vortex_l3_daemon::core::appstate::DeviceClass::Tablet => "tablet",
        vortex_l3_daemon::core::appstate::DeviceClass::Earbuds => "earbuds",
        vortex_l3_daemon::core::appstate::DeviceClass::Unknown => "unknown",
    }
    .to_string();
    let dto = PeerStateDto {
        peer_static_pub: peer_pub_hex,
        battery: s.battery,
        class,
        name: s.name,
        locale: s.locale,
        theme: s.theme,
        earbuds: s.earbuds.map(|e| EarbudsDto {
            name: e.name,
            battery: e.battery,
            connected: e.connected,
        }),
        charging: s.charging,
        ts: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0),
    };
    if let Ok(mut cache) = peer_state_cache().lock() {
        cache.insert(dto.peer_static_pub.clone(), dto.clone());
    }
    dto
}

fn peer_state_cache() -> &'static std::sync::Mutex<std::collections::HashMap<String, PeerStateDto>>
{
    static CACHE: std::sync::OnceLock<
        std::sync::Mutex<std::collections::HashMap<String, PeerStateDto>>,
    > = std::sync::OnceLock::new();
    CACHE.get_or_init(|| std::sync::Mutex::new(std::collections::HashMap::new()))
}

#[tauri::command]
pub(crate) fn get_peer_states() -> Vec<PeerStateDto> {
    let in_contact = crate::ble::peer_contact_age_ms() < CONTACT_FRESH_MS;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    peer_state_cache()
        .lock()
        .map(|m| {
            m.values()
                .cloned()
                .map(|mut dto| {
                    if in_contact {
                        dto.ts = now;
                    }
                    dto
                })
                .collect()
        })
        .unwrap_or_default()
}

const CONTACT_FRESH_MS: u64 = 35_000;

#[derive(Serialize, Clone)]
pub(crate) struct PairingStartedDto {
    pub(crate) peer_addr: String,
}

#[derive(Serialize, Clone)]
#[serde(untagged)]
pub(crate) enum PairingResultDto {
    Ok { ok: bool, message: String },
    Err { ok: bool, error: String },
}

pub(crate) struct CmdChannel(pub(crate) Sender<UiCmd>);

pub(crate) fn switch_state_dto(
    s: &vortex_l3_daemon::core::audio_orchestrator::SwitchState,
) -> serde_json::Value {
    use vortex_l3_daemon::core::audio_orchestrator::SwitchState as S;
    match s {
        S::Idle => serde_json::json!({ "kind": "idle" }),
        S::Preparing => serde_json::json!({ "kind": "preparing" }),
        S::WaitingApproval => serde_json::json!({ "kind": "waiting_approval" }),
        S::WaitingReleased => serde_json::json!({ "kind": "waiting_released" }),
        S::Connecting => serde_json::json!({ "kind": "connecting" }),
        S::AlmostDone => serde_json::json!({ "kind": "almost_done" }),
        S::Failed(reason) => serde_json::json!({ "kind": "failed", "reason": reason }),
    }
}

pub(crate) async fn emit_peers(app: &AppHandle, store: Arc<dyn PeerStore>) {
    let list_result = tokio::task::spawn_blocking({
        let store = store.clone();
        move || store.list()
    })
    .await;
    let list_result = match list_result {
        Ok(r) => r,
        Err(join_err) => {
            tracing::warn!("emit_peers join error: {join_err}");
            return;
        }
    };
    match list_result {
        Ok(list) => {
            let dtos: Vec<TrustedPeerDto> = list
                .into_iter()
                .map(|p| TrustedPeerDto {
                    peer_static_pub: hex::encode(p.peer_static_pub),
                    paired_at: p.paired_at,
                    peer_name: p.peer_name,
                })
                .collect();
            let _ = app.emit("vortex:peers", dtos);
        }
        Err(err) => {
            tracing::warn!("peer store list failed: {err}");
            let _ = app.emit("vortex:peer_store_error", err.to_string());
        }
    }
}

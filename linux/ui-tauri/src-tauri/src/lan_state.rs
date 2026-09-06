use tauri::AppHandle;

use crate::{app_state_to_dto, MEDIA_WATCH};

static LAST_APPSTATE_CALL_ID: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

static LAST_APPSTATE_CALL_SOME_AT: std::sync::Mutex<Option<std::time::Instant>> =
    std::sync::Mutex::new(None);

static LAST_LOCK_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

pub(crate) fn dispatch_lock_command(state: &vortex_l3_daemon::core::appstate::AppState) {
    use std::sync::atomic::Ordering;
    let Some(cmd) = state.lock_command.clone() else { return };
    let seq = state.lock_command_seq;
    if seq == 0 || seq <= LAST_LOCK_SEQ.load(Ordering::Relaxed) {
        return;
    }
    if cmd == "unlock" && state.unlocked != Some(true) {
        tracing::info!(seq, "remote unlock held — phone is locked (owner-present gate)");
        return;
    }
    LAST_LOCK_SEQ.store(seq, Ordering::Relaxed);
    tokio::spawn(async move {
        let res = match cmd.as_str() {
            "lock" => vortex_l3_daemon::core::session_lock::lock().await,
            "unlock" => vortex_l3_daemon::core::session_lock::unlock().await,
            "suspend" | "sleep" => vortex_l3_daemon::core::session_lock::suspend().await,
            "poweroff" | "shutdown" => vortex_l3_daemon::core::session_lock::poweroff().await,
            other => Err(format!("unknown lock command {other:?}")),
        };
        match res {
            Ok(()) => tracing::info!(%cmd, seq, "remote lock command executed"),
            Err(e) => tracing::warn!(%cmd, seq, "remote lock command failed: {e}"),
        }
    });
}

pub(crate) fn dispatch_appstate_call(call: &Option<vortex_l3_daemon::core::call_event::CallEvent>) {
    let Some(tx) = crate::CALL_MIRROR_TX.get() else { return };
    let mut last = match LAST_APPSTATE_CALL_ID.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    match call {
        Some(ev) => {
            if let Ok(mut t) = LAST_APPSTATE_CALL_SOME_AT.lock() {
                *t = Some(std::time::Instant::now());
            }
            let _ = tx.send(ev.clone());
            *last = Some(ev.id.clone());
        }
        None => {
            let recent = LAST_APPSTATE_CALL_SOME_AT
                .lock()
                .ok()
                .and_then(|g| *g)
                .map(|t| t.elapsed() < std::time::Duration::from_secs(5))
                .unwrap_or(false);
            if recent {
                return;
            }
            if let Some(id) = last.take() {
                let _ = tx.send(vortex_l3_daemon::core::call_event::CallEvent {
                    id,
                    phase: vortex_l3_daemon::core::call_event::CallEvent::PHASE_ENDED.to_string(),
                    name: String::new(),
                    number: String::new(),
                    started_at: 0,
                    outgoing: false,
                    connected: false,
                    app_id: String::new(),
                    sent_at: 0,
                    muted: false,
                    speaker: false,
                    has_earbuds: false,
                });
            }
        }
    }
}

pub(crate) fn spawn_state_consumer(
    app: AppHandle,
    peer_store: std::sync::Arc<dyn vortex_l3_daemon::core::storage::peers::PeerStore>,
) -> tokio::sync::mpsc::UnboundedSender<([u8; 32], vortex_l3_daemon::core::appstate::AppState)> {
    let (ble_state_tx, mut ble_state_rx) = tokio::sync::mpsc::unbounded_channel::<(
        [u8; 32],
        vortex_l3_daemon::core::appstate::AppState,
    )>();
    {
        let app_state = app.clone();
        let peer_store = peer_store.clone();
        tokio::spawn(async move {
            use tauri::Emitter;
            let adapter = match bluer::Session::new().await {
                Ok(s) => s.default_adapter().await.ok(),
                Err(_) => None,
            };
            while let Some((peer_pub, state)) = ble_state_rx.recv().await {
                crate::ble::touch_presence();
                crate::ble::touch_peer_contact();
                crate::lan::note_peer_reported_ip(&state);
                if state.revoked {
                    tracing::info!(
                        "peer revoked us (via BLE); forgetting {}",
                        hex::encode(&peer_pub[..8])
                    );
                    let _ = peer_store.forget(&peer_pub);
                    crate::emit_peers(&app_state, peer_store.clone()).await;
                    continue;
                }
                dispatch_appstate_call(&state.call);
                crate::handoff::dispatch_appstate_handoff(&state.handoff);
                crate::laptop_cast::dispatch_request(
                    state.laptop_mirror_req,
                    state.laptop_mirror_extend,
                );
                dispatch_lock_command(&state);
                crate::media_remote::dispatch_media_command(&state);
                crate::proximity::note_phone_unlocked(state.unlocked);
                let dto = app_state_to_dto(hex::encode(peer_pub), state.clone());
                let _ = app_state.emit("vortex:peer_state", dto);
                crate::earbuds::persist_peer_earbuds(&state);
                if let Some(mw) = MEDIA_WATCH.get() {
                    if mw.apply_setting(state.smart_switch_enabled, state.smart_switch_changed_at) {
                        tracing::info!(
                            enabled = state.smart_switch_enabled,
                            "smart-switch: adopted peer setting (LWW, via BLE)"
                        );
                        let _ = app_state.emit("vortex:smart_switch", state.smart_switch_enabled);
                    }
                }
                let local_earbuds = match &adapter {
                    Some(a) => vortex_l3_daemon::core::earbuds::scan_local_earbuds(a).await,
                    None => None,
                };
                crate::tray::update_battery_rows(&app_state, local_earbuds.as_ref(), Some(&state));
            }
        });
    }
    ble_state_tx
}

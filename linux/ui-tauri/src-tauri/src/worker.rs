use std::sync::mpsc::Receiver;
use std::sync::Arc;
use std::time::Duration;

use tauri::{AppHandle, Emitter, State};

use vortex_l3_daemon::core::identity::Platform;
use vortex_l3_daemon::core::storage::peers::{PeerStore, SecretServicePeerStore};
use vortex_l3_daemon::core::storage::secret_service::SecretServiceIdentityStore;
use vortex_l3_daemon::core::storage::{load_or_generate, IdentityStore, InMemoryIdentityStore};

use crate::ble::run_ble_persistent_loop;
use crate::call::spawn_consumer as spawn_call_consumer;
use crate::call_log::spawn_consumer as spawn_call_log_consumer;
use crate::contacts::spawn_consumer as spawn_contacts_consumer;
use crate::ipc::{emit_peers, CmdChannel, IdentityInfo, TrustedPeerDto, UiCmd};
use crate::lan::{self, load_last_peer_ip, try_lan_reconnect};
use crate::live_activity::spawn_consumer as spawn_live_consumer;
use crate::sms::{self, spawn_consumer as spawn_sms_consumer};
use crate::{
    cmd_earbuds, cmd_pairing, earbuds, notifications, worker_ctx, BLE_RETRY_NUDGE, CALL_MIRROR_TX,
    CALL_WRITER, SYNC_NUDGE,
};

#[tauri::command]
pub fn start_scan(state: State<'_, CmdChannel>) -> Result<(), String> {
    state.0.send(UiCmd::Scan).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn refresh_state(state: State<'_, CmdChannel>) -> Result<(), String> {
    state.0.send(UiCmd::RefreshState).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn start_screen_mirror(
    state: State<'_, CmdChannel>,
    width: u32,
    height: u32,
    fps: u32,
    bitrate: u32,
) -> Result<(), String> {
    state.0.send(UiCmd::StartMirror { width, height, fps, bitrate }).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn stop_screen_mirror(state: State<'_, CmdChannel>) -> Result<(), String> {
    state.0.send(UiCmd::StopMirror).map_err(|e| e.to_string())
}

pub(crate) fn run_worker(app: AppHandle, cmd_rx: Receiver<UiCmd>) {
    load_last_peer_ip();
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(8)
        .enable_all()
        .build()
        .expect("tokio runtime");
    rt.block_on(async move {
        let id_store: Box<dyn IdentityStore> = match SecretServiceIdentityStore::new() {
            Ok(s) => Box::new(s),
            Err(err) => {
                tracing::error!("FATAL: secret-service unavailable ({err}); cannot start");
                let _ = app.emit(
                    "vortex:fatal",
                    format!("Secure storage unavailable: {err}. Unlock your keyring and restart Vortex."),
                );
                if std::env::var("VORTEX_INSECURE").as_deref() == Ok("1") {
                    tracing::warn!("VORTEX_INSECURE=1 — falling back to in-memory identity (dev only)");
                    Box::new(InMemoryIdentityStore::new())
                } else {
                    return;
                }
            }
        };
        let identity = match load_or_generate(&*id_store, Platform::Linux) {
            Ok(id) => id,
            Err(err) => {
                tracing::error!("FATAL: identity init failed: {err}");
                return;
            }
        };
        let _ = app.emit("vortex:identity", IdentityInfo { ready: true });

        let peer_store: Arc<dyn PeerStore> = match SecretServicePeerStore::new() {
            Ok(s) => Arc::new(s),
            Err(err) => {
                tracing::error!("secret-service peer store unavailable: {err}");
                let _ = app.emit::<Vec<TrustedPeerDto>>("vortex:peers", Vec::new());
                return;
            }
        };
        emit_peers(&app, peer_store.clone()).await;
        let _have_trust = !peer_store.list().unwrap_or_default().is_empty();

        let session = match bluer::Session::new().await {
            Ok(s) => s,
            Err(err) => {
                tracing::error!("BLE session init failed: {err}");
                return;
            }
        };
        let adapter = match session.default_adapter().await {
            Ok(a) => a,
            Err(err) => {
                tracing::error!("BLE adapter init failed: {err}");
                return;
            }
        };
        let _ = adapter.set_powered(true).await;

        let make_agent = |default: bool| bluer::agent::Agent {
            request_default: default,
            request_authorization: Some(Box::new(|_req| {
                Box::pin(async move { Ok(()) })
            })),
            ..Default::default()
        };
        let _agent_handle = match session.register_agent(make_agent(true)).await {
            Ok(h) => {
                tracing::info!("BlueZ pairing agent registered as default (Just Works)");
                Some(h)
            }
            Err(e) => {
                tracing::warn!("BlueZ agent register (default) failed: {e}; attempting non-default fallback");
                match session.register_agent(make_agent(false)).await {
                    Ok(h) => {
                        tracing::info!("BlueZ pairing agent registered (non-default fallback)");
                        Some(h)
                    }
                    Err(e2) => {
                        tracing::warn!("BlueZ agent register failed: {e2}; bonding disabled this session");
                        None
                    }
                }
            }
        };

        let earbuds::AudioSetup {
            session_writers,
            ble_audio_writers,
            switch_orchestrator,
            media_watch,
            media_in_call,
            media_store,
        } = earbuds::setup_audio(&app, &adapter, peer_store.clone()).await;
        let last_call_phase: Arc<tokio::sync::Mutex<Option<String>>> =
            Arc::new(tokio::sync::Mutex::new(None));

        let auto_lock = Arc::new(tokio::sync::Mutex::new(()));

        let last_reconnect_at: Arc<tokio::sync::Mutex<Option<tokio::time::Instant>>> =
            Arc::new(tokio::sync::Mutex::new(None));
        const MDNS_RECONNECT_COOLDOWN: Duration = Duration::from_secs(10);

        let sync_nudge = Arc::new(tokio::sync::Notify::new());
        let _ = SYNC_NUDGE.set(sync_nudge.clone());
        let _ = crate::PENDING_IMAGE_TOKEN.set(std::sync::Mutex::new(None));
        let _ = crate::PENDING_FILE_OFFERS.set(std::sync::Mutex::new(std::collections::VecDeque::new()));

        let ble_retry_nudge = Arc::new(tokio::sync::Notify::new());
        let _ = BLE_RETRY_NUDGE.set(ble_retry_nudge.clone());

        let ble_state_tx =
            crate::lan_state::spawn_state_consumer(app.clone(), peer_store.clone());

        let (call_action_tx, call_action_rx) =
            tokio::sync::mpsc::unbounded_channel::<String>();
        let ble_live_tx = spawn_live_consumer(app.clone(), call_action_tx).await;

        let (ble_call_tx, ble_call_writer) =
            spawn_call_consumer(app.clone(), ble_live_tx.clone(), call_action_rx).await;
        let _ = CALL_WRITER.set(ble_call_writer.clone());

        let ble_contacts_tx = spawn_contacts_consumer(app.clone()).await;

        let ble_call_log_tx = spawn_call_log_consumer(app.clone()).await;

        let ble_sms_tx = spawn_sms_consumer(app.clone()).await;
        let ble_sms_thread_tx = sms::spawn_thread_consumer(app.clone()).await;
        let _ = CALL_MIRROR_TX.set(ble_call_tx.clone());

        let ble_handoff_tx = crate::handoff::spawn_consumer(app.clone(), ble_live_tx.clone());
        let _ = crate::handoff::HANDOFF_TX.set(ble_handoff_tx.clone());

        let ble_sealed_writer: Arc<tokio::sync::Mutex<Option<crate::SealedWriter>>> =
            Arc::new(tokio::sync::Mutex::new(None));
        let ble_notes_tx = crate::notes::spawn_sync(app.clone(), ble_sealed_writer.clone());
        crate::notes::spawn_reminders();

        let ble_icon_tx = notifications::spawn_icon_consumer();

        let (ble_notif_tx, ble_notif_writer) = notifications::spawn_subsystem(app.clone());

        let (ble_clipboard_tx, ble_clipboard_writer, ble_clipboard_image_tx, ble_clipboard_image_writer) =
            crate::clipboard_sync::spawn_clipboard_sync(app.clone());
        let ble_clipboard_offer_tx = crate::clipboard_sync::spawn_image_offer_consumer();
        crate::worker_transfers::wire_transfer_indicators(ble_live_tx.clone());
        {
            let app = app.clone();
            vortex_l3_daemon::core::wifi_direct::set_hook(Box::new(move |ssid, pass| {
                crate::lan_wifi_direct::on_wifi_direct_offer(app.clone(), ssid, pass);
            }));
        }

        lan::spawn_heartbeat(app.clone(), identity.clone(), peer_store.clone(), auto_lock.clone(), switch_orchestrator.clone(), session_writers.clone(), media_store.clone(), last_call_phase.clone(), media_watch.clone(), media_in_call.clone(), adapter.clone(), last_reconnect_at.clone(), sync_nudge.clone(), ble_audio_writers.clone());

        lan::spawn_power_watcher(sync_nudge.clone());

        lan::spawn_locked_watch(sync_nudge.clone());

        crate::proximity::spawn_proximity_watch(
            ble_audio_writers.clone(),
            adapter.clone(),
            peer_store.clone(),
        );

        crate::clipboard::spawn_clipboard_watcher(app.clone());

        if let Err(e) = adapter
            .set_discovery_filter(bluer::DiscoveryFilter {
                transport: bluer::DiscoveryTransport::Le,
                ..Default::default()
            })
            .await
        {
            tracing::warn!("could not pin LE-only discovery filter at startup: {e}");
        } else {
            tracing::info!("BLE discovery pinned to LE-only transport (no BR/EDR inquiry)");
        }

        {
            let ble_adapter = adapter.clone();
            let ble_identity = identity.clone();
            let ble_peer_store = peer_store.clone();
            let ble_orch = switch_orchestrator.clone();
            let ble_media = media_store.clone();
            let ble_writers = ble_audio_writers.clone();
            let ble_state_tx = ble_state_tx.clone();
            let ble_notif_tx = ble_notif_tx.clone();
            let ble_live_tx = ble_live_tx.clone();
            let ble_icon_tx = ble_icon_tx.clone();
            let ble_call_tx = ble_call_tx.clone();
            let ble_contacts_tx = ble_contacts_tx.clone();
            let ble_call_log_tx = ble_call_log_tx.clone();
            let ble_sms_tx = ble_sms_tx.clone();
            let ble_sms_thread_tx = ble_sms_thread_tx.clone();
            let ble_clipboard_tx = ble_clipboard_tx.clone();
            let ble_clipboard_image_tx = ble_clipboard_image_tx.clone();
            let ble_clipboard_offer_tx = ble_clipboard_offer_tx.clone();
            let ble_handoff_tx = ble_handoff_tx.clone();
            let ble_notes_tx = ble_notes_tx.clone();
            let ble_notif_writer = ble_notif_writer.clone();
            let ble_clipboard_writer = ble_clipboard_writer.clone();
            let ble_clipboard_image_writer = ble_clipboard_image_writer.clone();
            let ble_call_writer = ble_call_writer.clone();
            let ble_sealed_writer = ble_sealed_writer.clone();
            let ble_nudge = ble_retry_nudge.clone();
            tokio::spawn(async move {
                run_ble_persistent_loop(
                    ble_adapter,
                    ble_identity,
                    ble_peer_store,
                    ble_orch,
                    ble_media,
                    ble_writers,
                    ble_state_tx,
                    ble_notif_tx,
                    ble_live_tx,
                    ble_icon_tx,
                    ble_call_tx,
                    ble_contacts_tx,
                    ble_call_log_tx,
                    ble_sms_tx,
                    ble_sms_thread_tx,
                    ble_clipboard_tx,
                    ble_clipboard_image_tx,
                    ble_clipboard_offer_tx,
                    ble_handoff_tx,
                    ble_notes_tx,
                    ble_notif_writer,
                    ble_clipboard_writer,
                    ble_clipboard_image_writer,
                    ble_call_writer,
                    ble_sealed_writer,
                    ble_nudge,
                )
                .await;
            });
        }

        if let Ok(mut mdns_rx) =
            vortex_l3_daemon::core::lan::discovery::watch_candidates()
        {
            let auto_app = app.clone();
            let auto_identity = identity.clone();
            let auto_peer_store = peer_store.clone();
            let auto_lock_clone = auto_lock.clone();
            let auto_orch = switch_orchestrator.clone();
            let auto_writers = session_writers.clone();
            let auto_media = media_store.clone();
            let auto_last_phase = last_call_phase.clone();
            let auto_media_watch = media_watch.clone();
            let auto_media_in_call = media_in_call.clone();
            let auto_adapter = adapter.clone();
            let auto_ble_writers = ble_audio_writers.clone();
            let mdns_last_reconnect = last_reconnect_at.clone();
            tokio::spawn(async move {
                while let Some(_cand) = mdns_rx.recv().await {
                    let g = match auto_lock_clone.try_lock() {
                        Ok(g) => g,
                        Err(_) => continue,
                    };
                    {
                        let last = mdns_last_reconnect.lock().await;
                        if let Some(t) = *last {
                            if t.elapsed() < MDNS_RECONNECT_COOLDOWN {
                                drop(g);
                                continue;
                            }
                        }
                    }
                    let have_trust = {
                        let store = auto_peer_store.clone();
                        tokio::task::spawn_blocking(move || {
                            !store.list().unwrap_or_default().is_empty()
                        })
                        .await
                        .unwrap_or(false)
                    };
                    if have_trust {
                        tracing::info!("mDNS wake-up: triggering immediate reconnect");
                        let ble_live = !auto_ble_writers.lock().await.is_empty();
                        let _ = try_lan_reconnect(
                            &auto_app,
                            &auto_identity,
                            auto_peer_store.clone(),
                            Some(auto_orch.clone()),
                            Some(auto_writers.clone()),
                            Some(auto_media.clone()),
                            Some(auto_last_phase.clone()),
                            ble_live,
                            Some(auto_adapter.clone()),
                            Some(auto_media_watch.clone()),
                            Some(auto_media_in_call.clone()),
                        )
                        .await;
                        *mdns_last_reconnect.lock().await =
                            Some(tokio::time::Instant::now());
                    }
                    drop(g);
                }
            });
        }

        let ctx = worker_ctx::WorkerCtx {
            app: app.clone(),
            adapter: adapter.clone(),
            identity: identity.clone(),
            peer_store: peer_store.clone(),
            switch_orchestrator: switch_orchestrator.clone(),
            session_writers: session_writers.clone(),
        };
        let mut active_scan: Option<tokio::task::JoinHandle<()>> = None;
        loop {
            let cmd = match cmd_rx.recv_timeout(Duration::from_millis(500)) {
                Ok(c) => c,
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => continue,
                Err(_) => break,
            };
            match cmd {
                UiCmd::Scan => cmd_pairing::scan(&ctx, &mut active_scan),
                UiCmd::Pair(addr_str) => cmd_pairing::pair(&ctx, addr_str, &mut active_scan).await,
                UiCmd::ForgetPeer(hex_str) => cmd_pairing::forget_peer(&ctx, hex_str).await,
                UiCmd::ForgetAll => cmd_pairing::forget_all(&ctx).await,
                UiCmd::RefreshState => cmd_earbuds::refresh_state(&ctx).await,
                UiCmd::RefreshLocalEarbuds => cmd_earbuds::refresh_local_earbuds(&ctx).await,
                UiCmd::RequestEarbudsSwitch { peer_static_pub, mac } => {
                    cmd_earbuds::request_switch(&ctx, peer_static_pub, mac).await
                }
                UiCmd::SendEarbudsClaim { peer_static_pub, mac } => {
                    cmd_earbuds::send_claim(&ctx, peer_static_pub, mac).await
                }
                UiCmd::ToggleEarbuds => cmd_earbuds::toggle_earbuds(&ctx).await,
                UiCmd::StartMirror { width, height, fps, bitrate } => {
                    crate::mirror::handle_start_cmd(&ctx, width, height, fps, bitrate).await
                }
                UiCmd::StopMirror => crate::mirror::handle_stop_cmd(),
            }
        }
    });
}

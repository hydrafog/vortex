use std::sync::Arc;

use tauri::{Emitter, State};

use crate::ipc::switch_state_dto;
use crate::{CmdChannel, UiCmd, MEDIA_WATCH, SYNC_NUDGE};

pub(crate) fn persist_peer_earbuds(state: &vortex_l3_daemon::core::appstate::AppState) {
    let Some(buds) = state.earbuds.as_ref() else { return };
    if !buds.connected || buds.address.is_empty() {
        return;
    }
    if vortex_l3_daemon::core::earbuds_store::load().is_some() {
        return;
    }
    let saved = vortex_l3_daemon::core::earbuds_store::SavedEarbuds {
        address: buds.address.clone(),
        name: buds.name.clone(),
    };
    match vortex_l3_daemon::core::earbuds_store::save(&saved) {
        Ok(()) => {
            tracing::info!(name = %buds.name, "auto-saved peer earbuds (card pinned locally)")
        }
        Err(e) => tracing::warn!("auto-save peer earbuds failed: {e}"),
    }
}

#[tauri::command]
pub fn refresh_local_earbuds(state: State<'_, CmdChannel>) -> Result<(), String> {
    state.0.send(UiCmd::RefreshLocalEarbuds).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn request_earbuds_switch(
    peer_static_pub: String,
    mac: String,
    state: State<'_, CmdChannel>,
) -> Result<(), String> {
    state.0.send(UiCmd::RequestEarbudsSwitch { peer_static_pub, mac }).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn send_earbuds_claim(
    peer_static_pub: String,
    mac: String,
    state: State<'_, CmdChannel>,
) -> Result<(), String> {
    state.0.send(UiCmd::SendEarbudsClaim { peer_static_pub, mac }).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn scan_bluetooth_devices(
) -> Result<Vec<vortex_l3_daemon::core::earbuds::BluetoothDevice>, String> {
    let session = bluer::Session::new().await.map_err(|e| format!("bluer session: {e}"))?;
    let adapter = session.default_adapter().await.map_err(|e| format!("bluer adapter: {e}"))?;
    let _ = adapter.set_powered(true).await;
    vortex_l3_daemon::core::earbuds::start_brief_discovery(
        &adapter,
        std::time::Duration::from_secs(4),
    )
    .await;
    Ok(vortex_l3_daemon::core::earbuds::list_known_devices(&adapter).await)
}

#[tauri::command]
pub async fn save_earbuds(address: String, name: String) -> Result<(), String> {
    vortex_l3_daemon::core::earbuds_store::save(
        &vortex_l3_daemon::core::earbuds_store::SavedEarbuds { address: address.clone(), name },
    )
    .map_err(|e| format!("save earbuds: {e}"))?;
    tokio::spawn(async move {
        match bluer::Session::new().await {
            Ok(session) => match session.default_adapter().await {
                Ok(adapter) => {
                    if let Err(e) =
                        vortex_l3_daemon::core::audio_switch::connect_audio(&adapter, &address)
                            .await
                    {
                        tracing::warn!(%address, "save_earbuds: auto-connect failed: {e}");
                    } else {
                        tracing::info!(%address, "save_earbuds: auto-connect ok");
                    }
                }
                Err(e) => tracing::warn!("save_earbuds: adapter unavailable: {e}"),
            },
            Err(e) => tracing::warn!("save_earbuds: bluer session: {e}"),
        }
    });
    Ok(())
}

#[tauri::command]
pub fn clear_earbuds() -> Result<(), String> {
    vortex_l3_daemon::core::earbuds_store::clear().map_err(|e| format!("clear earbuds: {e}"))
}

#[tauri::command]
pub fn get_saved_earbuds() -> Option<vortex_l3_daemon::core::earbuds_store::SavedEarbuds> {
    vortex_l3_daemon::core::earbuds_store::load()
}

#[tauri::command]
pub fn open_bluetooth_settings() -> Result<(), String> {
    let candidates: &[&[&str]] = &[
        &["gnome-control-center", "bluetooth"],
        &["blueberry"],
        &["blueman-manager"],
        &["systemsettings5", "bluetooth"],
        &["plasma-settings", "kcm_bluetooth"],
        &["xdg-open", "bluetooth://"],
    ];
    for argv in candidates {
        let (cmd, args) = argv.split_first().unwrap();
        if std::process::Command::new(cmd)
            .args(args.iter())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .is_ok()
        {
            return Ok(());
        }
    }
    Err("no Bluetooth settings launcher found".into())
}

#[tauri::command]
pub fn set_smart_switch_enabled(enabled: bool) {
    use std::sync::atomic::Ordering;
    if let Some(mw) = MEDIA_WATCH.get() {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        let ts = now.max(mw.enabled_changed_at.load(Ordering::Relaxed) + 1);
        mw.apply_setting(enabled, ts);
        if let Some(n) = SYNC_NUDGE.get() {
            n.notify_one();
        }
    }
}

#[tauri::command]
pub fn get_smart_switch_enabled() -> bool {
    MEDIA_WATCH
        .get()
        .map(|mw| mw.enabled.load(std::sync::atomic::Ordering::Relaxed))
        .unwrap_or(true)
}

pub(crate) struct AudioSetup {
    pub(crate) session_writers: vortex_l3_daemon::core::audio_lan_session::SessionWriterMap,
    pub(crate) ble_audio_writers: vortex_l3_daemon::core::audio_lan_session::SessionWriterMap,
    pub(crate) switch_orchestrator:
        Arc<vortex_l3_daemon::core::audio_orchestrator::SwitchOrchestrator>,
    pub(crate) media_watch: Arc<vortex_l3_daemon::core::media_watch::MediaWatch>,
    pub(crate) media_in_call: Arc<std::sync::atomic::AtomicBool>,
    pub(crate) media_store: vortex_l3_daemon::core::media_runtime::MediaStateStore,
}

pub(crate) async fn setup_audio(
    app: &tauri::AppHandle,
    adapter: &bluer::Adapter,
    peer_store: Arc<dyn vortex_l3_daemon::core::storage::peers::PeerStore>,
) -> AudioSetup {
    let session_writers = vortex_l3_daemon::core::audio_lan_session::new_session_writer_map();
    let ble_audio_writers = vortex_l3_daemon::core::audio_lan_session::new_session_writer_map();
    let switch_orchestrator: Arc<vortex_l3_daemon::core::audio_orchestrator::SwitchOrchestrator> =
        Arc::new({
            let lan_writers = session_writers.clone();
            let ble_writers = ble_audio_writers.clone();
            vortex_l3_daemon::core::audio_orchestrator::SwitchOrchestrator::new(
                Arc::new(vortex_l3_daemon::core::audio_orchestrator::BluerBt::new(adapter.clone())),
                peer_store.clone(),
                Arc::new(move |peer_pub, frame| {
                    let lan_writers = lan_writers.clone();
                    let ble_writers = ble_writers.clone();
                    Box::pin(async move {
                        let op_dbg = format!("{:?}", frame.op);
                        let prefix = hex::encode(&peer_pub[..4]);
                        let lan = { lan_writers.lock().await.get(&peer_pub).cloned() };
                        let ble = { ble_writers.lock().await.get(&peer_pub).cloned() };
                        fn flat(
                            r: Result<Result<(), String>, tokio::task::JoinError>,
                        ) -> Result<(), String> {
                            match r {
                                Ok(inner) => inner,
                                Err(e) => Err(format!("task: {e}")),
                            }
                        }
                        match (lan, ble) {
                            (None, None) => {
                                tracing::warn!(peer = %prefix, op = %op_dbg, "no active session writer (LAN + BLE both absent)");
                                Err("no active session".to_string())
                            }
                            (Some(w), None) => w(frame).await,
                            (None, Some(w)) => w(frame).await,
                            (Some(lw), Some(bw)) => {
                                let f2 = frame.clone();
                                let mut lan_task = tokio::spawn(lw(frame));
                                let mut ble_task = tokio::spawn(bw(f2));
                                tokio::select! {
                                    r = &mut lan_task => match flat(r) {
                                        Ok(()) => Ok(()),
                                        Err(le) => match flat(ble_task.await) {
                                            Ok(()) => Ok(()),
                                            Err(be) => {
                                                tracing::warn!(peer = %prefix, op = %op_dbg, "both transports failed (lan: {le}; ble: {be})");
                                                Err(format!("both failed (lan: {le}; ble: {be})"))
                                            }
                                        },
                                    },
                                    r = &mut ble_task => match flat(r) {
                                        Ok(()) => Ok(()),
                                        Err(be) => match flat(lan_task.await) {
                                            Ok(()) => Ok(()),
                                            Err(le) => {
                                                tracing::warn!(peer = %prefix, op = %op_dbg, "both transports failed (ble: {be}; lan: {le})");
                                                Err(format!("both failed (ble: {be}; lan: {le})"))
                                            }
                                        },
                                    },
                                }
                            }
                        }
                    })
                }),
                Arc::new(|| vortex_l3_daemon::core::audio_orchestrator::Acceptance::Allow),
            )
        });
    switch_orchestrator.recover_on_start().await;

    if !vortex_l3_daemon::core::earbuds_store::autodetect_done() {
        if vortex_l3_daemon::core::earbuds_store::load().is_none() {
            if let Some(found) =
                vortex_l3_daemon::core::earbuds::detect_connected_earbud(adapter).await
            {
                match vortex_l3_daemon::core::earbuds_store::save(&found) {
                    Ok(()) => tracing::info!(
                        name = %found.name,
                        addr = %found.address,
                        "first-run: adopted already-connected earbuds"
                    ),
                    Err(e) => tracing::warn!("first-run earbuds adopt failed: {e}"),
                }
            }
        }
        let _ = vortex_l3_daemon::core::earbuds_store::mark_autodetect_done();
    }

    let media_watch = vortex_l3_daemon::core::media_watch::MediaWatch::new();
    let _ = MEDIA_WATCH.set(media_watch.clone());
    let media_in_call = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));

    let media_store = vortex_l3_daemon::core::media_runtime::new_media_state_store();
    vortex_l3_daemon::core::media_watch::spawn(
        media_watch.clone(),
        switch_orchestrator.clone(),
        adapter.clone(),
        peer_store.clone(),
        media_in_call.clone(),
        media_store.clone(),
    );

    {
        let media_store_w = media_store.clone();
        let mut rx = switch_orchestrator.state();
        let orch_w = switch_orchestrator.clone();
        tokio::spawn(async move {
            use vortex_l3_daemon::core::audio_orchestrator::SwitchState;
            let mut was_active = false;
            let mut last_active_mac: Option<String> = None;
            loop {
                if rx.changed().await.is_err() {
                    break;
                }
                let s = rx.borrow().clone();
                let active = !matches!(s, SwitchState::Idle | SwitchState::Failed(_));
                if active {
                    if let Some(m) = orch_w.current_mac().await {
                        last_active_mac = Some(m);
                    }
                }
                if was_active && !active {
                    let store = media_store_w.clone();
                    let mac = last_active_mac.take();
                    tokio::spawn(async move {
                        let need_resume = store.read().await.is_paused();
                        if !need_resume {
                            tracing::debug!("no MPRIS pause record; skipping route/resume");
                            return;
                        }
                        if let Some(mac) = mac {
                            let outcome =
                                vortex_l3_daemon::core::audio_route::wait_for_route(&mac).await;
                            tracing::info!(
                                sink = ?outcome.sink,
                                ready = outcome.ready,
                                routed = outcome.routed,
                                elapsed_ms = outcome.elapsed.as_millis() as u64,
                                "audio-route wait result"
                            );
                        }
                        let resumed =
                            vortex_l3_daemon::core::media_runtime::resume_paused_for_call(&store)
                                .await;
                        if !resumed.is_empty() {
                            tracing::info!(?resumed, "media resumed after call");
                        }
                    });
                }
                was_active = active;
            }
        });
    }

    {
        let app_state = app.clone();
        let mut rx = switch_orchestrator.state();
        tokio::spawn(async move {
            let _ = app_state.emit("vortex:switch_state", switch_state_dto(&rx.borrow()));
            loop {
                if rx.changed().await.is_err() {
                    break;
                }
                let s = rx.borrow().clone();
                let _ = app_state.emit("vortex:switch_state", switch_state_dto(&s));
            }
        });
    }

    AudioSetup {
        session_writers,
        ble_audio_writers,
        switch_orchestrator,
        media_watch,
        media_in_call,
        media_store,
    }
}

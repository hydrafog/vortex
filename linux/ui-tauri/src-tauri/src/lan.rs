
use std::sync::Arc;
use std::time::Duration;

use tauri::{AppHandle, Emitter};

use vortex_l3_daemon::core::appstate::AppState;
use vortex_l3_daemon::core::identity::IdentityRecord;
use vortex_l3_daemon::core::lan::discovery::discover_first;
use vortex_l3_daemon::core::lan::tcp_client::run_lan_reconnect;
use vortex_l3_daemon::core::storage::peers::PeerStore;

use crate::{app_state_to_dto, emit_peers};


pub(crate) const LAN_DEFAULT_PORT: u16 = 51820;

use crate::lan_wifi_direct::{restore_wifi, wd_active, WIFI_DIRECT_GO_IP};
use crate::lan_state::{dispatch_appstate_call, dispatch_lock_command};

pub(crate) static LAST_GOOD_PEER_IP: std::sync::Mutex<Option<std::net::IpAddr>> =
    std::sync::Mutex::new(None);

fn last_peer_ip_path() -> Option<std::path::PathBuf> {
    let mut p = std::path::PathBuf::from(std::env::var_os("HOME")?);
    p.push(".cache/vortex/last_peer_ip");
    Some(p)
}

pub(crate) fn files_queued() -> bool {
    crate::PENDING_FILE_OFFERS
        .get()
        .and_then(|m| m.lock().ok().map(|g| !g.is_empty()))
        .unwrap_or(false)
}

static QUEUE_PROGRESS_AT: std::sync::Mutex<Option<std::time::Instant>> =
    std::sync::Mutex::new(None);

const QUEUE_STALL_GRACE: Duration = Duration::from_secs(60);

pub(crate) fn note_queue_progress() {
    if let Ok(mut g) = QUEUE_PROGRESS_AT.lock() {
        *g = Some(std::time::Instant::now());
    }
}

pub(crate) fn file_pull_active() -> bool {
    if !files_queued() {
        return false;
    }
    QUEUE_PROGRESS_AT
        .lock()
        .ok()
        .and_then(|g| *g)
        .map(|t| t.elapsed() < QUEUE_STALL_GRACE)
        .unwrap_or(false)
}

pub(crate) fn persist_last_peer_ip(ip: std::net::IpAddr) {
    let changed = {
        let mut g = LAST_GOOD_PEER_IP.lock().unwrap_or_else(|e| e.into_inner());
        let changed = *g != Some(ip);
        *g = Some(ip);
        changed
    };
    if changed {
        if let Some(p) = last_peer_ip_path() {
            let _ = vortex_l3_daemon::core::fs_private::write_private(&p, ip.to_string().as_bytes());
        }
    }
}

pub(crate) fn clear_last_peer_ip() {
    *LAST_GOOD_PEER_IP.lock().unwrap_or_else(|e| e.into_inner()) = None;
    if let Some(p) = last_peer_ip_path() {
        let _ = std::fs::remove_file(&p);
    }
}

pub(crate) fn load_last_peer_ip() {
    if let Some(ip) = last_peer_ip_path()
        .and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|s| s.trim().parse::<std::net::IpAddr>().ok())
    {
        *LAST_GOOD_PEER_IP.lock().unwrap_or_else(|e| e.into_inner()) = Some(ip);
        tracing::info!(%ip, "loaded cached peer IP (LAN fast-path on restart)");
    }
}

pub(crate) static PEER_DISPLAY_HZ: std::sync::atomic::AtomicU32 =
    std::sync::atomic::AtomicU32::new(0);

pub(crate) fn note_peer_reported_ip(state: &AppState) {
    if let Some(hz) = state.display_hz {
        if (20..=240).contains(&hz)
            && crate::lan::PEER_DISPLAY_HZ.swap(hz, std::sync::atomic::Ordering::Relaxed) != hz
        {
            tracing::info!(hz, "peer reports its display refresh rate (mirror fps ceiling)");
        }
    }
    let Some(s) = state.wifi_ip.as_deref() else { return };
    let Ok(ip) = s.parse::<std::net::IpAddr>() else { return };
    if ip.is_loopback() || ip.is_unspecified() {
        return;
    }
    persist_last_peer_ip(ip);
}

pub(crate) async fn resolve_peer_addr(fresh: bool) -> Option<std::net::SocketAddr> {
    use std::net::{IpAddr, SocketAddr};
    async fn probe(sa: SocketAddr) -> bool {
        for attempt in 0..3u32 {
            if attempt > 0 {
                tokio::time::sleep(Duration::from_millis(400)).await;
            }
            if matches!(
                tokio::time::timeout(Duration::from_secs(2), tokio::net::TcpStream::connect(sa))
                    .await,
                Ok(Ok(_))
            ) {
                return true;
            }
        }
        false
    }
    if wd_active() {
        return Some(SocketAddr::new(IpAddr::from(WIFI_DIRECT_GO_IP), LAN_DEFAULT_PORT));
    }
    if !fresh {
        let cached = *LAST_GOOD_PEER_IP.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(ip) = cached {
            let sa = SocketAddr::new(ip, LAN_DEFAULT_PORT);
            if probe(sa).await {
                return Some(sa);
            }
            tracing::info!(%ip, "cached peer IP failed the probe; rediscovering");
        }
    }
    match discover_first(Duration::from_secs(6)).await {
        Ok(Some(c)) => {
            if let Some(ip) = c
                .addresses
                .iter()
                .find(|a| matches!(a, IpAddr::V4(_)))
                .copied()
                .or_else(|| c.addresses.first().copied())
            {
                persist_last_peer_ip(ip);
                return Some(SocketAddr::new(ip, c.port));
            }
        }
        Ok(None) => {}
        Err(e) => tracing::warn!("resolve_peer_addr: mdns: {e}"),
    }
    if let Some(g) = default_gateway_v4() {
        let sa = SocketAddr::new(IpAddr::V4(g), LAN_DEFAULT_PORT);
        if probe(sa).await {
            return Some(sa);
        }
    }
    if !fresh {
        let cached = *LAST_GOOD_PEER_IP.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(ip) = cached {
            let sa = SocketAddr::new(ip, LAN_DEFAULT_PORT);
            if probe(sa).await {
                tracing::info!(%ip, "cached peer IP answered on the second pass");
                return Some(sa);
            }
        }
    }
    None
}

pub(crate) fn default_gateway_v4() -> Option<std::net::Ipv4Addr> {
    let data = std::fs::read_to_string("/proc/net/route").ok()?;
    for line in data.lines().skip(1) {
        let mut f = line.split_whitespace();
        let _iface = f.next()?;
        let dest = f.next()?;
        let gw = f.next()?;
        if dest == "00000000" {
            let raw = u32::from_str_radix(gw, 16).ok()?;
            let o = raw.to_le_bytes();
            let ip = std::net::Ipv4Addr::new(o[0], o[1], o[2], o[3]);
            if !ip.is_unspecified() {
                return Some(ip);
            }
        }
    }
    None
}
pub(crate) async fn try_lan_reconnect(
    app: &AppHandle,
    identity: &IdentityRecord,
    peer_store: Arc<dyn PeerStore>,
    switch_orchestrator: Option<Arc<vortex_l3_daemon::core::audio_orchestrator::SwitchOrchestrator>>,
    session_writers: Option<vortex_l3_daemon::core::audio_lan_session::SessionWriterMap>,
    media_store: Option<vortex_l3_daemon::core::media_runtime::MediaStateStore>,
    last_call_phase: Option<Arc<tokio::sync::Mutex<Option<String>>>>,
    ble_live: bool,
    shared_adapter: Option<bluer::Adapter>,
    media_watch: Option<Arc<vortex_l3_daemon::core::media_watch::MediaWatch>>,
    media_in_call: Option<Arc<std::sync::atomic::AtomicBool>>,
) -> Result<Option<AppState>, String> {
    let mut peers = peer_store
        .list()
        .map_err(|e| format!("list: {e}"))?;
    if peers.is_empty() {
        return Err("no trusted peers".to_string());
    }
    peers.sort_by_key(|p| std::cmp::Reverse(p.paired_at));

    let fast_path: Option<std::net::SocketAddr> = if wd_active() {
        None
    } else {
        let cached = *LAST_GOOD_PEER_IP.lock().unwrap_or_else(|e| e.into_inner());
        match cached {
            Some(ip) => {
                let sa = std::net::SocketAddr::new(ip, LAN_DEFAULT_PORT);
                let attempts = if file_pull_active() { 3 } else { 1 };
                let mut found = None;
                for attempt in 0..attempts {
                    if attempt > 0 {
                        tokio::time::sleep(Duration::from_millis(400)).await;
                    }
                    if matches!(
                        tokio::time::timeout(
                            Duration::from_secs(2),
                            tokio::net::TcpStream::connect(sa),
                        )
                        .await,
                        Ok(Ok(_probe))
                    ) {
                        found = Some(sa);
                        break;
                    }
                }
                found
            }
            None => None,
        }
    };

    let socket_addr = if wd_active() {
        std::net::SocketAddr::new(std::net::IpAddr::from(WIFI_DIRECT_GO_IP), LAN_DEFAULT_PORT)
    } else if let Some(sa) = fast_path {
        sa
    } else {
        match discover_first(Duration::from_secs(6))
        .await
        .map_err(|e| format!("mdns: {e}"))?
    {
        Some(candidate) => {
            let ip = candidate
                .addresses
                .iter()
                .find(|a| matches!(a, std::net::IpAddr::V4(_)))
                .copied()
                .or_else(|| candidate.addresses.first().copied())
                .ok_or_else(|| "no IP".to_string())?;
            persist_last_peer_ip(ip);
            std::net::SocketAddr::new(ip, candidate.port)
        }
        None => {
            let cached = *LAST_GOOD_PEER_IP.lock().unwrap_or_else(|e| e.into_inner());
            let gw = default_gateway_v4();
            let cached_on_subnet = match (cached, gw) {
                (Some(std::net::IpAddr::V4(ip)), Some(g)) => ip.octets()[..3] == g.octets()[..3],
                (Some(_), None) => true,
                _ => false,
            };
            if let (Some(ip), true) = (cached, cached_on_subnet) {
                tracing::info!(%ip, "mDNS empty; retrying last-known peer IP");
                std::net::SocketAddr::new(ip, LAN_DEFAULT_PORT)
            } else if let Some(g) = gw {
                if cached.is_some() {
                    tracing::info!(%g, "mDNS empty; cached IP off-subnet (network changed) → gateway");
                } else {
                    tracing::info!(%g, "mDNS empty, no cache; falling back to gateway (phone-as-hotspot)");
                }
                std::net::SocketAddr::new(std::net::IpAddr::V4(g), LAN_DEFAULT_PORT)
            } else {
                return Err(
                    "no LAN candidate (mDNS empty, cached IP off-subnet, no gateway)".to_string(),
                );
            }
        }
    }
    };

    let mut local_state = vortex_l3_daemon::core::appstate::AppState::now_laptop();
    if let Some(adapter) = shared_adapter.as_ref() {
        local_state.earbuds =
            vortex_l3_daemon::core::earbuds::scan_local_earbuds(adapter).await;
    }
    if let Ok(mut g) = crate::PENDING_CALL_CONTROL.lock() {
        local_state.call_control = g.take();
    }
    if let Ok(mut g) = crate::notifications::PENDING_NOTIF_INVOKE.lock() {
        local_state.notif_invoke = g.take();
    }
    local_state.locked = vortex_l3_daemon::core::session_lock::locked_hint().await;
    local_state.laptop_cast = crate::laptop_cast::current_offer();
    local_state.laptop_cast_error = crate::laptop_cast::current_error();
    local_state.camera_req = crate::camera::camera_wanted();
    local_state.camera_facing = crate::camera::camera_facing();
    local_state.ring_seq = crate::ring::ring_seq();
    crate::media_remote::fill_now_playing(&mut local_state).await;
    if let Some(mw) = media_watch.as_ref() {
        use std::sync::atomic::Ordering;
        local_state.media_playing = mw.playing.load(Ordering::Relaxed);
        local_state.media_play_age_ms = {
            let e = mw.play_epoch_mono.load(Ordering::Relaxed);
            if e == 0 {
                0
            } else {
                vortex_l3_daemon::core::media_watch::mono_ms().saturating_sub(e)
            }
        };
        local_state.smart_switch_enabled = mw.enabled.load(Ordering::Relaxed);
        local_state.smart_switch_changed_at = mw.enabled_changed_at.load(Ordering::Relaxed);
        if mw.claim_peer.swap(false, Ordering::Relaxed) {
            local_state.audio_claim_request = true;
        }
    }

    let mut last_err: Option<String> = None;
    for peer in peers {
        let local_counter = {
            let store = peer_store.clone();
            let peer_pub = peer.peer_static_pub;
            tokio::task::spawn_blocking(move || {
                store.load_counter(&peer_pub).unwrap_or(0)
            })
            .await
            .unwrap_or(0)
        };
        let mut bulk_obj = serde_json::json!({
            "contacts": crate::contacts::cache_hash(),
            "call_log": crate::call_log::cache_hash(),
            "sms": crate::sms::cache_hash(),
            "sms_history": crate::sms::history_since().to_string(),
            "call_log_history": crate::call_log::history_since().to_string(),
            "sms_ids": crate::sms::ids_hash(),
        });
        let requested_img_token: Option<String> = crate::PENDING_IMAGE_TOKEN
            .get()
            .and_then(|m| m.lock().ok().and_then(|g| g.clone()));
        if let Some(token) = &requested_img_token {
            bulk_obj["clipboard_image"] = serde_json::Value::String(token.clone());
        }
        let requested_file_token: Option<String> = crate::PENDING_FILE_OFFERS
            .get()
            .and_then(|m| m.lock().ok().and_then(|g| g.front().map(|(t, _, _, _)| t.clone())));
        if let Some(token) = &requested_file_token {
            bulk_obj["clipboard_file"] = serde_json::Value::String(token.clone());
        }
        let bulk_request = bulk_obj.to_string();
        match run_lan_reconnect(
            socket_addr,
            &identity.static_priv.0,
            &peer.peer_static_pub,
            &peer.prs,
            local_counter,
            local_state.clone(),
            Duration::from_secs(15),
            Some(&bulk_request),
        )
        .await
        {
            Ok(outcome) => {
                persist_last_peer_ip(outcome.remote.ip());
                if let Some(s) = &outcome.peer_state {
                    note_peer_reported_ip(s);
                }
                if outcome.peer_counter < local_counter {
                    tracing::warn!(
                        "possible trust rollback: peer counter={} local={}",
                        outcome.peer_counter,
                        local_counter
                    );
                }
                for (ty_byte, json) in &outcome.bulk {
                    match *ty_byte {
                        vortex_l3_daemon::core::ble::frame::ty::CONTACTS => {
                            crate::contacts::deliver(app, json, "LAN bulk-sync");
                        }
                        vortex_l3_daemon::core::ble::frame::ty::CALL_LOG => {
                            crate::call_log::deliver(app, json, "LAN bulk-sync");
                        }
                        vortex_l3_daemon::core::ble::frame::ty::SMS => {
                            crate::sms::deliver(app, json, "LAN bulk-sync");
                        }
                        vortex_l3_daemon::core::ble::frame::ty::SMS_THREAD => {
                            crate::sms::merge_history(app, json);
                        }
                        vortex_l3_daemon::core::ble::frame::ty::CALL_LOG_HISTORY => {
                            crate::call_log::merge_history(app, json);
                        }
                        vortex_l3_daemon::core::ble::frame::ty::SMS_IDS => {
                            crate::sms::reconcile_ids(app, json);
                        }
                        vortex_l3_daemon::core::ble::frame::ty::CLIPBOARD_IMAGE => {
                            crate::clipboard_sync::apply_synced_image(app, json.clone()).await;
                            if let Some(slot) = crate::PENDING_IMAGE_TOKEN.get() {
                                if let Ok(mut g) = slot.lock() {
                                    *g = None;
                                }
                            }
                        }
                        vortex_l3_daemon::core::ble::frame::ty::CLIPBOARD_FILE => {
                            let meta = crate::PENDING_FILE_OFFERS
                                .get()
                                .and_then(|m| m.lock().ok().and_then(|mut g| g.pop_front()));
                            if let Some((_, name, mime, id)) = meta {
                                note_queue_progress();
                                match crate::clipboard_sync::apply_synced_file(
                                    app,
                                    &name,
                                    &mime,
                                    json.clone(),
                                )
                                .await
                                {
                                    Some(_) => crate::transfers::complete(id),
                                    None => crate::transfers::fail(id),
                                }
                            }
                            if files_queued() {
                                if let Some(nudge) = crate::SYNC_NUDGE.get() {
                                    nudge.notify_one();
                                }
                            }
                        }
                        other => tracing::warn!(
                            "bulk-sync delivered unknown dataset 0x{other:02x}; ignoring"
                        ),
                    }
                }
                if let Some(req) = &requested_img_token {
                    if let Some(slot) = crate::PENDING_IMAGE_TOKEN.get() {
                        if let Ok(mut g) = slot.lock() {
                            if g.as_deref() == Some(req.as_str()) {
                                *g = None;
                            }
                        }
                    }
                }
                if let Some(req) = &requested_file_token {
                    if outcome
                        .bulk_status
                        .as_ref()
                        .is_some_and(|s| s.unservable("clipboard_file"))
                    {
                        let dead = crate::PENDING_FILE_OFFERS.get().and_then(|m| {
                            m.lock().ok().and_then(|mut g| {
                                let front_matches =
                                    g.front().is_some_and(|(t, _, _, _)| t == req);
                                if front_matches { g.pop_front() } else { None }
                            })
                        });
                        if let Some((_, name, _, id)) = dead {
                            note_queue_progress();
                            crate::transfers::fail(id);
                            tracing::warn!(
                                name = %name,
                                status = outcome
                                    .bulk_status
                                    .as_ref()
                                    .and_then(|s| s.get("clipboard_file"))
                                    .unwrap_or("?"),
                                "phone can no longer serve this file (token evicted?); \
                                 dropping it from the pull queue"
                            );
                            if files_queued() {
                                if let Some(nudge) = crate::SYNC_NUDGE.get() {
                                    nudge.notify_one();
                                }
                            }
                        }
                    }
                }
                if wd_active() {
                    if !files_queued() {
                        tracing::info!("Wi-Fi Direct: all files pulled → restoring Wi-Fi");
                        restore_wifi(app).await;
                    } else if let Some(n) = crate::SYNC_NUDGE.get() {
                        n.notify_one();
                    }
                }
                {
                    let store_c = peer_store.clone();
                    let peer_c = peer.peer_static_pub;
                    let val = outcome.peer_counter;
                    tokio::spawn(async move {
                        let _ = store_c.bump_counter(&peer_c, val);
                    });
                }

                if let Some(s) = &outcome.peer_state {
                    if s.revoked {
                        tracing::info!(
                            "peer revoked us; forgetting {}",
                            hex::encode(&peer.peer_static_pub[..8])
                        );
                        let _ = peer_store.forget(&peer.peer_static_pub);
                        emit_peers(app, peer_store.clone()).await;
                        return Ok(None);
                    }
                    if let (Some(last_mu), Some(store)) =
                        (last_call_phase.as_ref(), media_store.as_ref())
                    {
                        let cur = s.call_phase.clone();
                        let prev = {
                            let mut g = last_mu.lock().await;
                            let prev = g.clone();
                            *g = cur.clone();
                            prev
                        };
                        tracing::info!(
                            ?prev,
                            ?cur,
                            "call_phase read from AppState"
                        );
                        if prev != cur {
                            let in_call = matches!(cur.as_deref(), Some("ringing") | Some("active"));
                            let was_in_call = matches!(prev.as_deref(), Some("ringing") | Some("active"));
                            if let Some(ic) = media_in_call.as_ref() {
                                ic.store(in_call, std::sync::atomic::Ordering::Relaxed);
                            }
                            if !was_in_call && in_call && ble_live {
                                tracing::info!(?cur, "call_phase ringing but BLE live; deferring to BLE Request fast-path (no LAN release)");
                            } else if !was_in_call && in_call {
                                tracing::info!(?cur, "phone entered a call; pausing media + releasing buds");
                                let store_c = store.clone();
                                tokio::spawn(async move {
                                    let paused = vortex_l3_daemon::core::media_runtime::pause_playing_for_call(&store_c).await;
                                    if !paused.is_empty() {
                                        tracing::info!(?paused, "paused for call");
                                    }
                                });
                                if let (Some(saved), Some(adapter)) = (
                                    vortex_l3_daemon::core::earbuds_store::load(),
                                    shared_adapter.clone(),
                                ) {
                                    let mac = saved.address;
                                    tokio::spawn(async move {
                                        if let Err(e) =
                                            vortex_l3_daemon::core::audio_switch::disconnect_audio(
                                                &adapter, &mac,
                                            )
                                            .await
                                        {
                                            tracing::debug!("call-start disconnect: {e}");
                                        }
                                    });
                                }
                            }
                        }
                    }

                    if let Some(mw) = media_watch.as_ref() {
                        use std::sync::atomic::Ordering;
                        let peer_now = s.media_playing;
                        mw.peer_playing.store(peer_now, Ordering::Relaxed);
                        let peer_epoch_mono = if s.media_play_age_ms > 0 && s.media_playing {
                            vortex_l3_daemon::core::media_watch::mono_ms()
                                .saturating_sub(s.media_play_age_ms)
                        } else {
                            0
                        };
                        mw.peer_play_epoch_mono
                            .store(peer_epoch_mono, Ordering::Relaxed);
                        let our_epoch = mw.play_epoch_mono.load(Ordering::Relaxed);
                        let peer_played_last = our_epoch == 0
                            || (peer_epoch_mono != 0 && peer_epoch_mono > our_epoch);
                        if let Ok(mut g) = mw.peer_holds_buds_seen.lock() {
                            *g = if s.earbuds.as_ref().map(|e| e.connected).unwrap_or(false) {
                                Some(std::time::Instant::now())
                            } else {
                                None
                            };
                        }
                        if mw.apply_setting(s.smart_switch_enabled, s.smart_switch_changed_at) {
                            tracing::info!(
                                enabled = s.smart_switch_enabled,
                                "smart-switch: adopted peer setting (LWW)"
                            );
                            let _ = app.emit("vortex:smart_switch", s.smart_switch_enabled);
                        }
                        let in_call_now = matches!(
                            s.call_phase.as_deref(),
                            Some("ringing") | Some("active")
                        );
                        if peer_now
                            && peer_played_last
                            && mw.enabled.load(Ordering::Relaxed)
                            && !in_call_now
                        {
                            if let (Some(saved), Some(adapter)) = (
                                vortex_l3_daemon::core::earbuds_store::load(),
                                shared_adapter.clone(),
                            ) {
                                let mac = saved.address;
                                if let Ok(addr) = mac.parse::<bluer::Address>() {
                                    if vortex_l3_daemon::core::audio_switch::audio_active(
                                        &adapter, addr,
                                    )
                                    .await
                                    {
                                        tracing::info!(%mac, "peer started media; releasing buds so the phone can grab");
                                        tokio::spawn(async move {
                                            let _ = vortex_l3_daemon::core::audio_switch::disconnect_audio_initiate(&adapter, &mac).await;
                                        });
                                    }
                                }
                            }
                        }
                    }

                    if s.audio_claim_request {
                        let already_busy = switch_orchestrator
                            .as_ref()
                            .map(|o| *o.state().borrow() != vortex_l3_daemon::core::audio_orchestrator::SwitchState::Idle)
                            .unwrap_or(false);
                        if already_busy {
                            tracing::info!("peer set audio_claim_request but we're busy; ignoring");
                        } else if let (Some(orch), Some(writers)) =
                            (switch_orchestrator.clone(), session_writers.clone())
                        {
                            tracing::info!("peer set audio_claim_request; running initiator");
                            let peer_c = peer.clone();
                            let identity_priv = identity.static_priv.0;
                            let peer_store_c = peer_store.clone();
                            let addr_target = outcome.remote;
                            let mac_addr = vortex_l3_daemon::core::earbuds_store::load()
                                .map(|s| s.address)
                                .unwrap_or_default();
                            if mac_addr.is_empty() {
                                tracing::warn!("audio_claim_request: no saved earbuds MAC; skipping");
                            } else {
                                tokio::spawn(async move {
                                    let local_counter = peer_store_c
                                        .load_counter(&peer_c.peer_static_pub)
                                        .unwrap_or(0);
                                    let _ = vortex_l3_daemon::core::audio_lan_session::start_initiator_session(
                                        addr_target,
                                        &identity_priv,
                                        &peer_c.peer_static_pub,
                                        &peer_c.prs,
                                        local_counter,
                                        mac_addr,
                                        orch,
                                        writers,
                                    ).await;
                                });
                            }
                        }
                    }
                }

                if let Some(state) = outcome.peer_state.clone() {
                    crate::earbuds::persist_peer_earbuds(&state);
                    crate::tray::update_battery_rows(
                        &app,
                        local_state.earbuds.as_ref(),
                        Some(&state),
                    );
                    dispatch_appstate_call(&state.call);
                    crate::handoff::dispatch_appstate_handoff(&state.handoff);
                    crate::laptop_cast::dispatch_request(state.laptop_mirror_req, state.laptop_mirror_extend);
                    crate::camera::dispatch_offer(
                        &state.camera_offer,
                        LAST_GOOD_PEER_IP.lock().ok().and_then(|g| *g),
                    );
                    dispatch_lock_command(&state);
                    crate::media_remote::dispatch_media_command(&state);
                    crate::proximity::note_phone_unlocked(state.unlocked);
                    crate::ble::touch_peer_contact();
                    let dto = app_state_to_dto(hex::encode(peer.peer_static_pub), state);
                    let _ = app.emit("vortex:peer_state", dto);
                }
                return Ok(outcome.peer_state);
            }
            Err(e) => {
                last_err = Some(format!("lan: {e}"));
            }
        }
    }
    if fast_path.is_some() {
        *LAST_GOOD_PEER_IP.lock().unwrap_or_else(|e| e.into_inner()) = None;
        tracing::info!("fast-path IP failed the handshake; cache dropped for rediscovery");
    }
    Err(last_err.unwrap_or_else(|| "no peer accepted reconnect".to_string()))
}



pub(crate) fn spawn_locked_watch(sync_nudge: std::sync::Arc<tokio::sync::Notify>) {
    tokio::spawn(async move {
        let res = vortex_l3_daemon::core::session_lock::watch_locked_hint(move |locked| {
            tracing::info!(locked, "session LockedHint changed; nudging state heartbeats");
            sync_nudge.notify_waiters();
            crate::ble::state_nudge().notify_one();
        })
        .await;
        if let Err(e) = res {
            tracing::warn!("locked-hint watch unavailable: {e}");
        }
    });
}

pub(crate) fn spawn_power_watcher(sync_nudge: std::sync::Arc<tokio::sync::Notify>) {
                let watch_nudge = sync_nudge.clone();
                tokio::spawn(async move {
                    use vortex_l3_daemon::core::status::{read_local_battery, read_local_charging};
                    let mut last_charging = read_local_charging();
                    let mut last_level = read_local_battery().0;
                    loop {
                        tokio::time::sleep(Duration::from_millis(500)).await;
                        let charging = read_local_charging();
                        let level = read_local_battery().0;
                        let level_changed = match (last_level, level) {
                            (Some(a), Some(b)) => (a as i16 - b as i16).abs() >= 2,
                            (None, Some(_)) | (Some(_), None) => true,
                            (None, None) => false,
                        };
                        if charging != last_charging || level_changed {
                            last_charging = charging;
                            last_level = level;
                            tracing::info!(charging, ?level, "power change → nudging heartbeat");
                            watch_nudge.notify_one();
                        }
                    }
                });
}


pub(crate) fn spawn_heartbeat(
    app: tauri::AppHandle,
    identity: IdentityRecord,
    peer_store: std::sync::Arc<dyn PeerStore>,
    auto_lock: std::sync::Arc<tokio::sync::Mutex<()>>,
    switch_orchestrator: std::sync::Arc<vortex_l3_daemon::core::audio_orchestrator::SwitchOrchestrator>,
    session_writers: vortex_l3_daemon::core::audio_lan_session::SessionWriterMap,
    media_store: vortex_l3_daemon::core::media_runtime::MediaStateStore,
    last_call_phase: std::sync::Arc<tokio::sync::Mutex<Option<String>>>,
    media_watch: std::sync::Arc<vortex_l3_daemon::core::media_watch::MediaWatch>,
    media_in_call: std::sync::Arc<std::sync::atomic::AtomicBool>,
    adapter: bluer::Adapter,
    last_reconnect_at: std::sync::Arc<tokio::sync::Mutex<Option<tokio::time::Instant>>>,
    sync_nudge: std::sync::Arc<tokio::sync::Notify>,
    ble_audio_writers: vortex_l3_daemon::core::audio_lan_session::SessionWriterMap,
) {
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
            let auto_last_reconnect = last_reconnect_at.clone();
            let auto_nudge = sync_nudge.clone();
            let auto_ble_writers = ble_audio_writers.clone();
            tokio::spawn(async move {
                let mut consec_lan_fail = 0u32;
                let mut lan_ever_synced = false;
                loop {
                    let (had_trust, lan_synced) = {
                        let _g = auto_lock_clone.lock().await;
                        let have_trust = {
                            let store = auto_peer_store.clone();
                            tokio::task::spawn_blocking(move || {
                                !store.list().unwrap_or_default().is_empty()
                            })
                            .await
                            .unwrap_or(false)
                        };
                        let mut synced = false;
                        if have_trust {
                            let ble_live = !auto_ble_writers.lock().await.is_empty();
                            synced = matches!(
                                try_lan_reconnect(
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
                                .await,
                                Ok(Some(_))
                            );
                            *auto_last_reconnect.lock().await =
                                Some(tokio::time::Instant::now());
                        }
                        (have_trust, synced)
                    };
                    if lan_synced {
                        if consec_lan_fail > 0 || !lan_ever_synced {
                            if let Some(n) = crate::BLE_RETRY_NUDGE.get() {
                                n.notify_one();
                            }
                        }
                        lan_ever_synced = true;
                        consec_lan_fail = 0;
                    } else if had_trust {
                        consec_lan_fail = consec_lan_fail.saturating_add(1);
                    }
                    let next = if crate::call::call_pill_active() {
                        Duration::from_secs(2)
                    } else if had_trust && !lan_synced && consec_lan_fail <= 3 {
                        Duration::from_secs(2)
                    } else if file_pull_active() {
                        if consec_lan_fail <= 15 {
                            Duration::from_secs(2)
                        } else {
                            Duration::from_secs(12)
                        }
                    } else if auto_ble_writers.lock().await.is_empty() {
                        Duration::from_secs(12)
                    } else {
                        Duration::from_secs(240)
                    };
                    tokio::select! {
                        _ = tokio::time::sleep(next) => {}
                        _ = auto_nudge.notified() => {
                            tracing::info!("heartbeat woken early by local state-change nudge");
                        }
                    }
                }
            });
}

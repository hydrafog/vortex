use std::sync::Arc;
use std::time::Duration;

use vortex_l3_daemon::core::ble::client::VortexClient;
use vortex_l3_daemon::core::ble::scanner::run_filtered_scan;
use vortex_l3_daemon::core::identity::IdentityRecord;
use vortex_l3_daemon::core::pairing::reconnect::run_ik_initiator;
use vortex_l3_daemon::core::storage::peers::PeerStore;

use crate::NotifWriter;

const CONNECT_WEDGE_THRESHOLD: u32 = 6;

pub(crate) fn state_nudge() -> &'static tokio::sync::Notify {
    static NUDGE: std::sync::OnceLock<tokio::sync::Notify> = std::sync::OnceLock::new();
    NUDGE.get_or_init(tokio::sync::Notify::new)
}

pub(crate) static LAST_PRESENCE_MS: std::sync::atomic::AtomicU64 =
    std::sync::atomic::AtomicU64::new(0);

pub(crate) fn touch_presence() {
    LAST_PRESENCE_MS.store(now_ms(), std::sync::atomic::Ordering::Relaxed);
}

pub(crate) static LAST_PEER_CONTACT_MS: std::sync::atomic::AtomicU64 =
    std::sync::atomic::AtomicU64::new(0);

pub(crate) fn touch_peer_contact() {
    LAST_PEER_CONTACT_MS.store(now_ms(), std::sync::atomic::Ordering::Relaxed);
}

pub(crate) fn peer_contact_age_ms() -> u64 {
    let last = LAST_PEER_CONTACT_MS.load(std::sync::atomic::Ordering::Relaxed);
    if last == 0 {
        return u64::MAX;
    }
    now_ms().saturating_sub(last)
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

const PRESENCE_ROTATION_SEC: u64 = 60;

pub(crate) fn expected_presence_tokens(
    peers: &[vortex_l3_daemon::core::storage::peers::TrustedPeer],
) -> std::collections::HashSet<[u8; 8]> {
    use std::time::{SystemTime, UNIX_EPOCH};
    use vortex_l3_daemon::core::crypto::presence::{current_bucket, derive_presence_token};
    let now_sec = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let bucket_now = current_bucket(now_sec, PRESENCE_ROTATION_SEC);
    peers
        .iter()
        .flat_map(|p| {
            [-2i64, -1, 0, 1, 2]
                .iter()
                .map(move |d| derive_presence_token(&p.prs, (bucket_now as i64 + *d) as u64))
        })
        .collect()
}

pub(crate) async fn find_trusted_presence_peer(
    adapter: &bluer::Adapter,
    peer_store: &Arc<dyn PeerStore>,
    wait: Duration,
) -> Option<bluer::Address> {
    let peers = {
        let store = peer_store.clone();
        tokio::task::spawn_blocking(move || store.list().unwrap_or_default())
            .await
            .unwrap_or_default()
    };
    if peers.is_empty() {
        return None;
    }
    let expected = expected_presence_tokens(&peers);

    let (tx, mut rx) = tokio::sync::mpsc::channel::<bluer::Address>(1);
    let scan = {
        let adapter = adapter.clone();
        tokio::spawn(async move {
            let _ = run_filtered_scan(adapter, move |c| {
                if !c.payload.flags.is_trusted_presence() {
                    return;
                }
                if !expected.contains(&c.payload.payload_8) {
                    return;
                }
                let _ = tx.try_send(c.address);
            })
            .await;
        })
    };
    let res = tokio::time::timeout(wait, rx.recv()).await.ok().flatten();
    scan.abort();
    let _ = scan.await;
    if res.is_some() {
        touch_presence();
    }
    if res.is_some() {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        loop {
            if !adapter.is_discovering().await.unwrap_or(false) {
                break;
            }
            if tokio::time::Instant::now() >= deadline {
                tracing::debug!(
                    "presence scan: adapter still discovering after 2s; connecting anyway"
                );
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }
    res
}

static MONITOR_UNSUPPORTED: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

enum PresenceWait {
    Found(bluer::Address),
    Reevaluate,
    Unsupported,
}

async fn validate_presence_candidate(
    adapter: &bluer::Adapter,
    peers: &[vortex_l3_daemon::core::storage::peers::TrustedPeer],
    addr: bluer::Address,
) -> bool {
    use vortex_l3_daemon::core::ble::{AdvPayload, VORTEX_SERVICE_UUID};
    let Ok(device) = adapter.device(addr) else { return false };
    let Ok(Some(sd)) = device.service_data().await else { return false };
    let Some(bytes) = sd.get(&VORTEX_SERVICE_UUID) else { return false };
    let Ok(payload) = AdvPayload::decode(bytes) else { return false };
    payload.flags.is_trusted_presence()
        && expected_presence_tokens(peers).contains(&payload.payload_8)
}

async fn monitor_presence_wait(
    adapter: &bluer::Adapter,
    peer_store: &Arc<dyn PeerStore>,
    peers: &[vortex_l3_daemon::core::storage::peers::TrustedPeer],
    retry_nudge: &tokio::sync::Notify,
) -> PresenceWait {
    use bluer::monitor::{Monitor, MonitorEvent, Pattern, RssiSamplingPeriod, Type};
    use futures::StreamExt;

    const AD_SERVICE_DATA_128: u8 = 0x21;
    let uuid_le: Vec<u8> =
        vortex_l3_daemon::core::ble::VORTEX_SERVICE_UUID.as_bytes().iter().rev().copied().collect();

    let manager = match adapter.monitor().await {
        Ok(m) => m,
        Err(e) => {
            tracing::warn!("advertisement monitor unavailable ({e}); using scan fallback");
            return PresenceWait::Unsupported;
        }
    };
    let mut handle = match manager
        .register(Monitor {
            monitor_type: Type::OrPatterns,
            patterns: Some(vec![Pattern::new(AD_SERVICE_DATA_128, 0, &uuid_le)]),
            rssi_sampling_period: Some(RssiSamplingPeriod::First),
            ..Default::default()
        })
        .await
    {
        Ok(h) => h,
        Err(e) => {
            tracing::warn!("advertisement monitor rejected ({e}); using scan fallback");
            return PresenceWait::Unsupported;
        }
    };
    tracing::info!("advertisement monitor armed (passive presence watch)");

    loop {
        tokio::select! {
            ev = handle.next() => match ev {
                Some(MonitorEvent::DeviceFound(id)) => {
                    if validate_presence_candidate(adapter, peers, id.device).await {
                        tracing::info!(addr = %id.device, "presence monitor: trusted peer on air");
                        touch_presence();
                        return PresenceWait::Found(id.device);
                    }
                    tracing::debug!(addr = %id.device, "presence monitor: candidate failed token gate; one scan round");
                    if let Some(a) =
                        find_trusted_presence_peer(adapter, peer_store, Duration::from_secs(15)).await
                    {
                        return PresenceWait::Found(a);
                    }
                }
                Some(MonitorEvent::DeviceLost(_)) => {}
                Some(_) => {}
                None => {
                    tracing::warn!("advertisement monitor stream ended; re-evaluating");
                    return PresenceWait::Reevaluate;
                }
            },
            _ = retry_nudge.notified() => {
                tracing::info!("presence wait: woken by LAN cross-transport nudge");
                return PresenceWait::Reevaluate;
            }
            _ = tokio::time::sleep(Duration::from_secs(300)) => return PresenceWait::Reevaluate,
        }
    }
}

async fn monitor_unsupported_wait(
    adapter: &bluer::Adapter,
    peer_store: &Arc<dyn PeerStore>,
    retry_nudge: &tokio::sync::Notify,
) -> PresenceWait {
    let started = tokio::time::Instant::now();
    let mut backoff = Duration::from_secs(5);
    loop {
        if let Some(a) =
            find_trusted_presence_peer(adapter, peer_store, Duration::from_secs(15)).await
        {
            return PresenceWait::Found(a);
        }
        if started.elapsed() > Duration::from_secs(300) {
            return PresenceWait::Reevaluate;
        }
        tokio::select! {
            _ = tokio::time::sleep(backoff) => {}
            _ = retry_nudge.notified() => {
                tracing::info!("presence wait (scan): woken by LAN cross-transport nudge");
                return PresenceWait::Reevaluate;
            }
        }
        backoff = (backoff * 2).min(Duration::from_secs(45));
    }
}

pub(crate) async fn wait_for_presence(
    adapter: &bluer::Adapter,
    peer_store: &Arc<dyn PeerStore>,
    retry_nudge: &tokio::sync::Notify,
) -> Option<bluer::Address> {
    use std::sync::atomic::Ordering;
    let peers = {
        let store = peer_store.clone();
        tokio::task::spawn_blocking(move || store.list().unwrap_or_default())
            .await
            .unwrap_or_default()
    };
    if peers.is_empty() {
        tokio::time::sleep(Duration::from_secs(10)).await;
        return None;
    }
    if let Some(a) = find_trusted_presence_peer(adapter, peer_store, Duration::from_secs(15)).await
    {
        return Some(a);
    }
    if !MONITOR_UNSUPPORTED.load(Ordering::Relaxed) {
        match monitor_presence_wait(adapter, peer_store, &peers, retry_nudge).await {
            PresenceWait::Found(a) => return Some(a),
            PresenceWait::Reevaluate => return None,
            PresenceWait::Unsupported => {
                MONITOR_UNSUPPORTED.store(true, Ordering::Relaxed);
            }
        }
    }
    match monitor_unsupported_wait(adapter, peer_store, retry_nudge).await {
        PresenceWait::Found(a) => Some(a),
        _ => None,
    }
}

pub(crate) async fn connect_bonded_or_scan(
    adapter: &bluer::Adapter,
    peer_store: &Arc<dyn PeerStore>,
    last_rpa: &mut Option<bluer::Address>,
    retry_nudge: &tokio::sync::Notify,
    consec_connect_fail: &mut u32,
) -> Option<VortexClient> {
    if let Some(addr) = *last_rpa {
        match tokio::time::timeout(Duration::from_secs(3), VortexClient::connect(adapter, addr))
            .await
        {
            Ok(Ok(client)) => {
                tracing::info!(addr = %addr, "BLE persistent: last-RPA direct-connect succeeded");
                *consec_connect_fail = 0;
                return Some(client);
            }
            Ok(Err(e)) => {
                tracing::debug!(addr = %addr, "BLE persistent: last-RPA connect failed: {e}; scanning");
                clear_pending_connect(adapter, addr).await;
                forget_stale_device(adapter, addr).await;
            }
            Err(_) => {
                tracing::debug!(addr = %addr, "BLE persistent: last-RPA connect timed out; scanning");
                clear_pending_connect(adapter, addr).await;
                forget_stale_device(adapter, addr).await;
            }
        }
    }
    let addr = match wait_for_presence(adapter, peer_store, retry_nudge).await {
        Some(a) => a,
        None => {
            return None;
        }
    };
    const CONNECT_CAP: Duration = Duration::from_secs(8);
    let connect =
        match tokio::time::timeout(CONNECT_CAP, VortexClient::connect(adapter, addr)).await {
            Ok(r) => r,
            Err(_) => {
                Err(vortex_l3_daemon::core::ble::client::ClientError::Timeout("connect (capped)"))
            }
        };
    match connect {
        Ok(client) => {
            *last_rpa = Some(addr);
            *consec_connect_fail = 0;
            Some(client)
        }
        Err(e) => {
            if *last_rpa == Some(addr) {
                *last_rpa = None;
            }
            clear_pending_connect(adapter, addr).await;
            forget_stale_device(adapter, addr).await;
            *consec_connect_fail = consec_connect_fail.saturating_add(1);
            tracing::warn!("P2.13: BLE connect to {addr} ({consec_connect_fail}x): {e}");
            None
        }
    }
}

async fn power_cycle_adapter(adapter: &bluer::Adapter) {
    tracing::warn!("BLE: repeated connect failures — power-cycling adapter to clear the stack");
    if let Err(e) = adapter.set_powered(false).await {
        tracing::warn!("BLE power-cycle: set_powered(false) failed: {e}");
    }
    tokio::time::sleep(Duration::from_secs(2)).await;
    match adapter.set_powered(true).await {
        Ok(()) => tracing::info!("BLE: adapter power-cycled — retrying reconnect"),
        Err(e) => tracing::warn!("BLE power-cycle: set_powered(true) failed: {e}"),
    }
    tokio::time::sleep(Duration::from_secs(1)).await;
}

async fn forget_stale_device(adapter: &bluer::Adapter, addr: bluer::Address) {
    match tokio::time::timeout(Duration::from_secs(3), adapter.remove_device(addr)).await {
        Ok(Ok(())) => tracing::debug!(addr = %addr, "stale RPA entry removed from BlueZ"),
        Ok(Err(e)) => tracing::debug!(addr = %addr, "remove_device: {e} (ignored)"),
        Err(_) => tracing::debug!(addr = %addr, "remove_device timed out (ignored)"),
    }
}

pub(crate) async fn clear_pending_connect(adapter: &bluer::Adapter, addr: bluer::Address) {
    if let Ok(dev) = adapter.device(addr) {
        let _ = tokio::time::timeout(Duration::from_secs(3), dev.disconnect()).await;
    }
}

pub(crate) async fn run_ble_persistent_loop(
    adapter: bluer::Adapter,
    identity: IdentityRecord,
    peer_store: Arc<dyn PeerStore>,
    switch_orchestrator: Arc<vortex_l3_daemon::core::audio_orchestrator::SwitchOrchestrator>,
    media_store: vortex_l3_daemon::core::media_runtime::MediaStateStore,
    ble_audio_writers: vortex_l3_daemon::core::audio_lan_session::SessionWriterMap,
    state_tx: tokio::sync::mpsc::UnboundedSender<(
        [u8; 32],
        vortex_l3_daemon::core::appstate::AppState,
    )>,
    notif_tx: tokio::sync::mpsc::UnboundedSender<
        vortex_l3_daemon::core::notif_mirror::NotificationMirror,
    >,
    live_tx: tokio::sync::mpsc::UnboundedSender<
        vortex_l3_daemon::core::live_activity::LiveActivity,
    >,
    icon_tx: tokio::sync::mpsc::UnboundedSender<(String, u16, u16, Vec<u8>)>,
    call_tx: tokio::sync::mpsc::UnboundedSender<vortex_l3_daemon::core::call_event::CallEvent>,
    contacts_tx: tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)>,
    call_log_tx: tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)>,
    sms_tx: tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)>,
    sms_thread_tx: tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)>,
    clipboard_tx: tokio::sync::mpsc::UnboundedSender<
        vortex_l3_daemon::core::clipboard_mirror::ClipboardMirror,
    >,
    clipboard_image_tx: tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)>,
    clipboard_offer_tx: tokio::sync::mpsc::UnboundedSender<
        vortex_l3_daemon::core::clipboard_mirror::ClipboardImageOffer,
    >,
    handoff_tx: tokio::sync::mpsc::UnboundedSender<vortex_l3_daemon::core::handoff::HandoffEvent>,
    raw_frame_tx: tokio::sync::mpsc::UnboundedSender<(u8, Vec<u8>)>,
    notif_writer: Arc<tokio::sync::Mutex<Option<NotifWriter>>>,
    clipboard_writer: Arc<tokio::sync::Mutex<Option<crate::ClipboardWriter>>>,
    clipboard_image_writer: Arc<tokio::sync::Mutex<Option<crate::ClipboardImageWriter>>>,
    call_writer: Arc<tokio::sync::Mutex<Option<crate::CallWriter>>>,
    sealed_writer: Arc<tokio::sync::Mutex<Option<crate::SealedWriter>>>,
    retry_nudge: Arc<tokio::sync::Notify>,
) {
    use vortex_l3_daemon::core::audio_lan_session::SessionWriter;
    use vortex_l3_daemon::core::audio_op::AudioOpFrame;
    use vortex_l3_daemon::core::ble::audio_signal;
    let mut last_rpa: Option<bluer::Address> = None;
    let mut consec_ik_fail: u32 = 0;
    let mut consec_connect_fail: u32 = 0;
    loop {
        let peer = {
            let store = peer_store.clone();
            let first = tokio::task::spawn_blocking(move || {
                store.list().unwrap_or_default().into_iter().next()
            })
            .await
            .unwrap_or(None);
            match first {
                Some(p) => p,
                None => {
                    tokio::time::sleep(Duration::from_secs(10)).await;
                    continue;
                }
            }
        };

        if !adapter.is_powered().await.unwrap_or(false) {
            match adapter.set_powered(true).await {
                Ok(()) => tracing::info!("BLE adapter was off — powered it on for reconnect"),
                Err(e) => {
                    tracing::warn!("BLE adapter off and power-on failed ({e}); waiting");
                    tokio::select! {
                        _ = tokio::time::sleep(Duration::from_secs(30)) => {}
                        _ = retry_nudge.notified() => {}
                    }
                    continue;
                }
            }
        }

        {
            use vortex_l3_daemon::core::audio_orchestrator::SwitchState;
            let mut waited_ms = 0u64;
            while *switch_orchestrator.state().borrow() != SwitchState::Idle && waited_ms < 20_000 {
                tokio::time::sleep(Duration::from_millis(400)).await;
                waited_ms += 400;
            }
        }

        let client = match connect_bonded_or_scan(
            &adapter,
            &peer_store,
            &mut last_rpa,
            &retry_nudge,
            &mut consec_connect_fail,
        )
        .await
        {
            Some(c) => c,
            None => {
                if consec_connect_fail >= CONNECT_WEDGE_THRESHOLD {
                    power_cycle_adapter(&adapter).await;
                    consec_connect_fail = 0;
                    last_rpa = None;
                    continue;
                }
                tokio::time::sleep(Duration::from_secs(1)).await;
                continue;
            }
        };
        if client.audio_signal.is_none() {
            tracing::warn!("P2.13: peer has no AUDIO_SIGNAL characteristic — older phone build?");
            tokio::time::sleep(Duration::from_secs(30)).await;
            continue;
        }

        let local_counter = {
            let store = peer_store.clone();
            let peer_pub = peer.peer_static_pub;
            tokio::task::spawn_blocking(move || store.load_counter(&peer_pub).unwrap_or(0))
                .await
                .unwrap_or(0)
        };
        tracing::info!("P2.13: BLE IK starting");
        let outcome = match run_ik_initiator(
            &client,
            &identity.static_priv.0,
            &peer.peer_static_pub,
            &peer.prs,
            local_counter,
            Duration::from_secs(10),
        )
        .await
        {
            Ok(o) => o,
            Err(e) => {
                consec_ik_fail = consec_ik_fail.saturating_add(1);
                let backoff = [3u64, 10, 30, 60][consec_ik_fail.min(4) as usize - 1];
                tracing::warn!(
                    "P2.13: BLE IK failed ({consec_ik_fail}x): {e}; backing off {backoff}s"
                );
                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_secs(backoff)) => {}
                    _ = retry_nudge.notified() => {}
                }
                continue;
            }
        };
        consec_ik_fail = 0;
        tracing::info!("P2.13: BLE IK returned; peer_counter={}", outcome.peer_counter);

        let Some(transport) = outcome.transport else {
            tracing::error!("P2.13: IK outcome missing transport state — internal bug");
            tokio::time::sleep(Duration::from_secs(5)).await;
            continue;
        };
        let transport = Arc::new(tokio::sync::Mutex::new(transport));
        tracing::info!(
            peer = %hex::encode(&peer.peer_static_pub[..4]),
            "P2.13: BLE audio-signal session established"
        );
        touch_presence();

        let counter_store = peer_store.clone();
        let counter_peer = peer.peer_static_pub;
        let counter_value = outcome.peer_counter;
        tokio::spawn(async move {
            let _ = counter_store.bump_counter(&counter_peer, counter_value);
        });

        let client_arc = Arc::new(client);
        let writer_transport = transport.clone();
        let writer_client = client_arc.clone();
        let writer_fn: SessionWriter = Arc::new(move |frame: AudioOpFrame| {
            let transport = writer_transport.clone();
            let client = writer_client.clone();
            Box::pin(async move { audio_signal::write_audio_op(&client, transport, frame).await })
        });
        {
            let mut m = ble_audio_writers.lock().await;
            m.insert(peer.peer_static_pub, writer_fn);
        }
        crate::proximity::nudge().notify_one();

        {
            let nw_transport = transport.clone();
            let nw_client = client_arc.clone();
            let writer: NotifWriter = Arc::new(move |notif| {
                let transport = nw_transport.clone();
                let client = nw_client.clone();
                Box::pin(async move {
                    audio_signal::write_notification(&client, transport, &notif).await
                })
            });
            *notif_writer.lock().await = Some(writer);
        }

        {
            let cw_transport = transport.clone();
            let cw_client = client_arc.clone();
            let writer: crate::ClipboardWriter = Arc::new(move |clip| {
                let transport = cw_transport.clone();
                let client = cw_client.clone();
                Box::pin(
                    async move { audio_signal::write_clipboard(&client, transport, &clip).await },
                )
            });
            *clipboard_writer.lock().await = Some(writer);
        }

        {
            let cw_transport = transport.clone();
            let cw_client = client_arc.clone();
            let writer: crate::ClipboardImageWriter = Arc::new(move |png| {
                let transport = cw_transport.clone();
                let client = cw_client.clone();
                Box::pin(async move {
                    audio_signal::write_clipboard_image(&client, transport, &png).await
                })
            });
            *clipboard_image_writer.lock().await = Some(writer);
        }

        {
            let cw_transport = transport.clone();
            let cw_client = client_arc.clone();
            let writer: crate::CallWriter = Arc::new(move |ctrl| {
                let transport = cw_transport.clone();
                let client = cw_client.clone();
                Box::pin(async move {
                    audio_signal::write_call_control(&client, transport, &ctrl).await
                })
            });
            *call_writer.lock().await = Some(writer);
        }

        {
            let sw_transport = transport.clone();
            let sw_client = client_arc.clone();
            let writer: crate::SealedWriter = Arc::new(move |ty, payload| {
                let transport = sw_transport.clone();
                let client = sw_client.clone();
                Box::pin(async move {
                    audio_signal::write_sealed(&client, transport, ty, &payload).await
                })
            });
            *sealed_writer.lock().await = Some(writer);
        }

        {
            let st_client = client_arc.clone();
            let st_transport = transport.clone();
            let st_adapter = adapter.clone();
            tokio::spawn(async move {
                tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
                let mut first = true;
                const STATE_PUSH_MAX_FAILS: u32 = 6;
                let mut consecutive_fail: u32 = 0;
                loop {
                    let mut state = vortex_l3_daemon::core::appstate::AppState::now_laptop();
                    state.earbuds =
                        vortex_l3_daemon::core::earbuds::scan_local_earbuds(&st_adapter).await;
                    state.locked = vortex_l3_daemon::core::session_lock::locked_hint().await;
                    state.laptop_cast = crate::laptop_cast::current_offer();
                    state.laptop_cast_error = crate::laptop_cast::current_error();
                    state.camera_req = crate::camera::camera_wanted();
                    state.camera_facing = crate::camera::camera_facing();
                    state.ring_seq = crate::ring::ring_seq();
                    crate::media_remote::fill_now_playing(&mut state).await;
                    match audio_signal::write_state(&st_client, st_transport.clone(), &state).await
                    {
                        Ok(()) => {
                            consecutive_fail = 0;
                            touch_presence();
                            touch_peer_contact();
                            if first {
                                tracing::info!(
                                    earbuds = ?state.earbuds,
                                    "→ BLE state heartbeat to phone (keeps laptop connected over BLE)"
                                );
                                first = false;
                            }
                        }
                        Err(e) => {
                            consecutive_fail += 1;
                            if consecutive_fail >= STATE_PUSH_MAX_FAILS {
                                tracing::info!(
                                    "BLE state heartbeat stopped (session gone after {consecutive_fail} failed writes): {e}"
                                );
                                let _ = st_adapter.remove_device(st_client.address).await;
                                break;
                            }
                            tracing::debug!(
                                "BLE state write failed (#{consecutive_fail}); retrying: {e}"
                            );
                            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
                            continue;
                        }
                    }
                    tokio::select! {
                        _ = tokio::time::sleep(std::time::Duration::from_secs(12)) => {}
                        _ = state_nudge().notified() => {}
                    }
                }
            });
        }

        {
            let cw_adapter = adapter.clone();
            let cw_addr = client_arc.address;
            tokio::spawn(async move {
                use futures::StreamExt;
                let dev = match cw_adapter.device(cw_addr) {
                    Ok(d) => d,
                    Err(_) => return,
                };
                let mut events = match dev.events().await {
                    Ok(e) => e,
                    Err(_) => return,
                };
                while let Some(ev) = events.next().await {
                    if let bluer::DeviceEvent::PropertyChanged(bluer::DeviceProperty::Connected(
                        false,
                    )) = ev
                    {
                        tracing::info!(
                            addr = %cw_addr,
                            "BLE Connected→false (event) — forcing teardown for instant reconnect"
                        );
                        let _ = cw_adapter.remove_device(cw_addr).await;
                        break;
                    }
                }
            });
        }

        let _ = audio_signal::run_listener(
            &client_arc,
            transport,
            peer.peer_static_pub,
            switch_orchestrator.clone(),
            media_store.clone(),
            Some(state_tx.clone()),
            Some(notif_tx.clone()),
            Some(live_tx.clone()),
            Some(icon_tx.clone()),
            Some(call_tx.clone()),
            Some(contacts_tx.clone()),
            Some(call_log_tx.clone()),
            Some(sms_tx.clone()),
            Some(sms_thread_tx.clone()),
            Some(clipboard_tx.clone()),
            Some(clipboard_image_tx.clone()),
            Some(clipboard_offer_tx.clone()),
            Some(handoff_tx.clone()),
            Some(raw_frame_tx.clone()),
        )
        .await;

        {
            let mut m = ble_audio_writers.lock().await;
            m.remove(&peer.peer_static_pub);
        }
        forget_stale_device(&adapter, client_arc.address).await;
        crate::proximity::nudge().notify_one();
        *notif_writer.lock().await = None;
        *clipboard_writer.lock().await = None;
        *clipboard_image_writer.lock().await = None;
        *call_writer.lock().await = None;

        if let Some(n) = crate::SYNC_NUDGE.get() {
            n.notify_one();
        }

        let dropped_addr = client_arc.address;
        if let Ok(dev) = adapter.device(dropped_addr) {
            let _ = tokio::time::timeout(Duration::from_secs(3), dev.disconnect()).await;
        }

        tracing::info!(addr = %dropped_addr, "P2.13: BLE audio-signal listener returned; reopening");
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
}

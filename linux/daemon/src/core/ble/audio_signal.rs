use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

pub(crate) static RESYNC_EVENTS: AtomicU64 = AtomicU64::new(0);
pub(crate) static RESYNC_FRAMES_SKIPPED: AtomicU64 = AtomicU64::new(0);
pub(crate) static REHANDSHAKE_EVENTS: AtomicU64 = AtomicU64::new(0);

use futures::{pin_mut, StreamExt};
use snow::TransportState;
use tokio::sync::Mutex;
use tracing::{debug, info, warn};

use bluer::gatt::remote::{Characteristic, CharacteristicWriteRequest};
use bluer::gatt::WriteOp;

use super::client::VortexClient;
use super::frame::{ty, Frame};
use crate::core::appstate::AppState;
use crate::core::audio_op::{AudioOp, AudioOpFrame};
use crate::core::audio_orchestrator::SwitchOrchestrator;
use crate::core::media_runtime::{pause_playing_for_call, MediaStateStore};

pub async fn run_listener(
    client: &VortexClient,
    transport: Arc<Mutex<TransportState>>,
    peer_pub: [u8; 32],
    orchestrator: Arc<SwitchOrchestrator>,
    media_store: MediaStateStore,
    state_tx: Option<tokio::sync::mpsc::UnboundedSender<([u8; 32], AppState)>>,
    notif_tx: Option<
        tokio::sync::mpsc::UnboundedSender<crate::core::notif_mirror::NotificationMirror>,
    >,
    live_tx: Option<tokio::sync::mpsc::UnboundedSender<crate::core::live_activity::LiveActivity>>,
    icon_tx: Option<tokio::sync::mpsc::UnboundedSender<(String, u16, u16, Vec<u8>)>>,
    call_tx: Option<tokio::sync::mpsc::UnboundedSender<crate::core::call_event::CallEvent>>,
    contacts_tx: Option<tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)>>,
    call_log_tx: Option<tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)>>,
    sms_tx: Option<tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)>>,
    sms_thread_tx: Option<tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)>>,
    clipboard_tx: Option<
        tokio::sync::mpsc::UnboundedSender<crate::core::clipboard_mirror::ClipboardMirror>,
    >,
    clipboard_image_tx: Option<tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)>>,
    clipboard_offer_tx: Option<
        tokio::sync::mpsc::UnboundedSender<crate::core::clipboard_mirror::ClipboardImageOffer>,
    >,
    handoff_tx: Option<tokio::sync::mpsc::UnboundedSender<crate::core::handoff::HandoffEvent>>,
    raw_frame_tx: Option<tokio::sync::mpsc::UnboundedSender<(u8, Vec<u8>)>>,
) -> Result<(), String> {
    let char = client
        .audio_signal
        .as_ref()
        .ok_or_else(|| "peer has no AUDIO_SIGNAL characteristic".to_string())?;
    let notifies = char.notify().await.map_err(|e| format!("subscribe AUDIO_SIGNAL: {e}"))?;
    pin_mut!(notifies);
    info!(addr = %client.address, "BLE audio-signal listener up");
    if let Some(tx) = notif_tx.as_ref() {
        let _ = tx.send(crate::core::notif_mirror::NotificationMirror::catch_up_signal());
    }

    let mut aead_fails = 0u32;
    let session_start = tokio::time::Instant::now();
    const STALE_DRAIN_GRACE: Duration = Duration::from_secs(2);
    const NONCE_RESYNC_WINDOW: u64 = 128;

    let mut clip_text_asm = crate::core::clipboard_mirror::ImageAssembler::default();
    let mut frag_asm = crate::core::clipboard_mirror::ImageAssembler::default();
    let mut reassembled: std::collections::VecDeque<Vec<u8>> = std::collections::VecDeque::new();

    loop {
        let raw: Vec<u8> = match reassembled.pop_front() {
            Some(inner) => inner,
            None => match notifies.next().await {
                Some(r) => r.to_vec(),
                None => break,
            },
        };
        info!("BLE AUDIO_SIGNAL raw notify: {} bytes", raw.len());
        let frame = match Frame::decode(&raw) {
            Ok(f) => f,
            Err(e) => {
                warn!("audio-signal frame decode: {e}; ignoring");
                continue;
            }
        };
        if frame.ty == ty::FRAG {
            if let Some((total, idx, data)) = crate::core::sms::parse_chunk(&frame.payload) {
                if let Some(inner) = frag_asm.add(total, idx, data) {
                    reassembled.push_back(inner);
                }
            } else {
                warn!("audio-signal FRAG chunk malformed; dropping");
            }
            continue;
        }
        if frame.ty != ty::AUDIO_OP
            && frame.ty != ty::STATE
            && frame.ty != ty::NOTIFICATION
            && frame.ty != ty::LIVE_ACTIVITY
            && frame.ty != ty::ICON
            && frame.ty != ty::CALL
            && frame.ty != ty::CONTACTS
            && frame.ty != ty::CALL_LOG
            && frame.ty != ty::SMS
            && frame.ty != ty::SMS_THREAD
            && frame.ty != ty::CLIPBOARD
            && frame.ty != ty::CLIPBOARD_IMAGE
            && frame.ty != ty::CLIPBOARD_IMAGE_OFFER
            && frame.ty != ty::CLIPBOARD_TEXT
            && frame.ty != ty::WIFI_DIRECT_OFFER
            && frame.ty != ty::HANDOFF
            && frame.ty != ty::NOTES_SYNC
        {
            warn!("audio-signal unexpected frame ty=0x{:02x}; ignoring", frame.ty);
            continue;
        }
        let mut plain = vec![0u8; frame.payload.len()];
        let n = {
            let mut t = match tokio::time::timeout(Duration::from_secs(5), transport.lock()).await {
                Ok(g) => g,
                Err(_) => {
                    warn!("audio-signal: transport lock busy >5s (seal path stalled?) — still waiting");
                    transport.lock().await
                }
            };
            match t.read_message(&frame.payload, &mut plain) {
                Ok(n) => {
                    aead_fails = 0;
                    Some(n)
                }
                Err(_) => {
                    let base = t.receiving_nonce();
                    let mut recovered = None;
                    for skip in 1..=NONCE_RESYNC_WINDOW {
                        t.set_receiving_nonce(base + skip);
                        if let Ok(len) = t.read_message(&frame.payload, &mut plain) {
                            recovered = Some((skip, len));
                            break;
                        }
                    }
                    match recovered {
                        Some((skip, len)) => {
                            let events = RESYNC_EVENTS.fetch_add(1, Ordering::Relaxed) + 1;
                            let frames =
                                RESYNC_FRAMES_SKIPPED.fetch_add(skip, Ordering::Relaxed) + skip;
                            warn!(
                                dropped = skip,
                                resync_events = events,
                                frames_skipped_total = frames,
                                rehandshakes = REHANDSHAKE_EVENTS.load(Ordering::Relaxed),
                                "audio-signal: resynced past dropped BLE frame(s) (no re-handshake)"
                            );
                            aead_fails = 0;
                            if let Some(tx) = notif_tx.as_ref() {
                                let _ = tx.send(
                                    crate::core::notif_mirror::NotificationMirror::catch_up_signal(
                                    ),
                                );
                            }
                            Some(len)
                        }
                        None => {
                            t.set_receiving_nonce(base);
                            None
                        }
                    }
                }
            }
        };
        let n = match n {
            Some(n) => n,
            None => {
                if session_start.elapsed() < STALE_DRAIN_GRACE {
                    warn!("audio-signal aead-open failed during stale-drain grace; dropping frame (not counted)");
                    continue;
                }
                aead_fails += 1;
                warn!("audio-signal aead-open failed (#{aead_fails}); dropping frame");
                if aead_fails >= 3 {
                    let rehandshakes = REHANDSHAKE_EVENTS.fetch_add(1, Ordering::Relaxed) + 1;
                    warn!(
                        rehandshakes,
                        resync_events = RESYNC_EVENTS.load(Ordering::Relaxed),
                        "audio-signal: receive cipher desynced ({aead_fails} consecutive \
                         AEAD failures) — dropping the BLE session to force a fresh IK \
                         handshake (resyncs both directions)"
                    );
                    return Ok(());
                }
                continue;
            }
        };

        if frame.ty == ty::STATE {
            match serde_json::from_slice::<AppState>(&plain[..n]) {
                Ok(state) => {
                    info!(battery = ?state.battery, charging = state.charging, "← BLE state push");
                    if let Some(tx) = state_tx.as_ref() {
                        let _ = tx.send((peer_pub, state));
                    }
                }
                Err(e) => warn!("audio-signal STATE payload not AppState: {e}; dropping"),
            }
            continue;
        }

        if frame.ty == ty::NOTIFICATION {
            match serde_json::from_slice::<crate::core::notif_mirror::NotificationMirror>(
                &plain[..n],
            ) {
                Ok(notif) => {
                    info!(app = %notif.app, "← BLE notification mirror");
                    if let Some(tx) = notif_tx.as_ref() {
                        let _ = tx.send(notif);
                    }
                }
                Err(e) => warn!("audio-signal NOTIFICATION payload invalid: {e}; dropping"),
            }
            continue;
        }

        if frame.ty == ty::CLIPBOARD {
            match serde_json::from_slice::<crate::core::clipboard_mirror::ClipboardMirror>(
                &plain[..n],
            ) {
                Ok(clip) => {
                    info!(chars = clip.text.chars().count(), "← BLE clipboard sync");
                    if let Some(tx) = clipboard_tx.as_ref() {
                        let _ = tx.send(clip);
                    }
                }
                Err(e) => warn!("audio-signal CLIPBOARD payload invalid: {e}; dropping"),
            }
            continue;
        }

        if frame.ty == ty::CLIPBOARD_TEXT {
            if let Some((total, idx, data)) = crate::core::sms::parse_chunk(&plain[..n]) {
                if let Some(bytes) = clip_text_asm.add(total, idx, data) {
                    match String::from_utf8(bytes) {
                        Ok(text) => {
                            info!(chars = text.chars().count(), "← BLE clipboard text (chunked)");
                            if let Some(tx) = clipboard_tx.as_ref() {
                                let _ = tx.send(crate::core::clipboard_mirror::ClipboardMirror {
                                    text,
                                    ts: 0,
                                });
                            }
                        }
                        Err(e) => warn!("CLIPBOARD_TEXT reassembly not UTF-8: {e}; dropping"),
                    }
                }
            } else {
                warn!("audio-signal CLIPBOARD_TEXT chunk malformed; dropping");
            }
            continue;
        }

        if frame.ty == ty::WIFI_DIRECT_OFFER {
            match serde_json::from_slice::<serde_json::Value>(&plain[..n]) {
                Ok(v) => {
                    let ssid = v.get("ssid").and_then(|s| s.as_str()).unwrap_or("").to_string();
                    let pass = v.get("pass").and_then(|s| s.as_str()).unwrap_or("").to_string();
                    if !ssid.is_empty() {
                        info!(%ssid, "← BLE Wi-Fi Direct offer");
                        crate::core::wifi_direct::report(ssid, pass);
                    }
                }
                Err(e) => warn!("audio-signal WIFI_DIRECT_OFFER invalid: {e}; dropping"),
            }
            continue;
        }

        if frame.ty == ty::CLIPBOARD_IMAGE {
            if let Some((total, idx, data)) = crate::core::sms::parse_chunk(&plain[..n]) {
                if let Some(tx) = clipboard_image_tx.as_ref() {
                    let _ = tx.send((total, idx, data));
                }
            } else {
                warn!("audio-signal CLIPBOARD_IMAGE chunk malformed; dropping");
            }
            continue;
        }

        if frame.ty == ty::CLIPBOARD_IMAGE_OFFER {
            match serde_json::from_slice::<crate::core::clipboard_mirror::ClipboardImageOffer>(
                &plain[..n],
            ) {
                Ok(offer) => {
                    info!(bytes = offer.bytes, "← BLE clipboard image offer");
                    if let Some(tx) = clipboard_offer_tx.as_ref() {
                        let _ = tx.send(offer);
                    }
                }
                Err(e) => warn!("audio-signal CLIPBOARD_IMAGE_OFFER invalid: {e}; dropping"),
            }
            continue;
        }

        if frame.ty == ty::LIVE_ACTIVITY {
            match serde_json::from_slice::<crate::core::live_activity::LiveActivity>(&plain[..n]) {
                Ok(live) => {
                    info!(app = %live.app, ended = live.ended, "← BLE live activity");
                    if let Some(tx) = live_tx.as_ref() {
                        let _ = tx.send(live);
                    }
                }
                Err(e) => warn!("audio-signal LIVE_ACTIVITY payload invalid: {e}; dropping"),
            }
            continue;
        }

        if frame.ty == ty::CALL {
            match crate::core::call_event::CallEvent::from_json(&plain[..n]) {
                Some(ev) => {
                    info!(phase = %ev.phase, named = !ev.name.is_empty(), "← BLE call event");
                    if let Some(tx) = call_tx.as_ref() {
                        let _ = tx.send(ev);
                    }
                }
                None => warn!("audio-signal CALL payload invalid; dropping"),
            }
            continue;
        }

        if frame.ty == ty::HANDOFF {
            match crate::core::handoff::HandoffEvent::from_json(&plain[..n]) {
                Some(ev) => {
                    info!(open_now = ev.open_now, has_url = !ev.url.is_empty(), "← BLE handoff");
                    if let Some(tx) = handoff_tx.as_ref() {
                        let _ = tx.send(ev);
                    }
                }
                None => warn!("audio-signal HANDOFF payload invalid; dropping"),
            }
            continue;
        }

        if frame.ty == ty::ICON {
            if let Some((app_id, total, idx, data)) =
                crate::core::icon_cache::parse_chunk(&plain[..n])
            {
                if let Some(tx) = icon_tx.as_ref() {
                    let _ = tx.send((app_id, total, idx, data));
                }
            } else {
                warn!("audio-signal ICON chunk malformed; dropping");
            }
            continue;
        }

        if frame.ty == ty::CONTACTS {
            if let Some((total, idx, data)) = crate::core::contacts::parse_chunk(&plain[..n]) {
                if let Some(tx) = contacts_tx.as_ref() {
                    let _ = tx.send((total, idx, data));
                }
            } else {
                warn!("audio-signal CONTACTS chunk malformed; dropping");
            }
            continue;
        }

        if frame.ty == ty::CALL_LOG {
            if let Some((total, idx, data)) = crate::core::call_log::parse_chunk(&plain[..n]) {
                if let Some(tx) = call_log_tx.as_ref() {
                    let _ = tx.send((total, idx, data));
                }
            } else {
                warn!("audio-signal CALL_LOG chunk malformed; dropping");
            }
            continue;
        }

        if frame.ty == ty::SMS {
            if let Some((total, idx, data)) = crate::core::sms::parse_chunk(&plain[..n]) {
                if let Some(tx) = sms_tx.as_ref() {
                    let _ = tx.send((total, idx, data));
                }
            } else {
                warn!("audio-signal SMS chunk malformed; dropping");
            }
            continue;
        }

        if frame.ty == ty::SMS_THREAD {
            if let Some((total, idx, data)) = crate::core::sms::parse_chunk(&plain[..n]) {
                if let Some(tx) = sms_thread_tx.as_ref() {
                    let _ = tx.send((total, idx, data));
                }
            } else {
                warn!("audio-signal SMS_THREAD chunk malformed; dropping");
            }
            continue;
        }

        // and isn't an AUDIO_OP is an additive feature frame (e.g. NOTES_SYNC):
        if frame.ty != ty::AUDIO_OP {
            if let Some(tx) = raw_frame_tx.as_ref() {
                let _ = tx.send((frame.ty, plain[..n].to_vec()));
            }
            continue;
        }

        let af = match AudioOpFrame::from_json(&plain[..n]) {
            Ok(f) => f,
            Err(e) => {
                warn!("audio-signal payload not AudioOpFrame: {e}; dropping");
                continue;
            }
        };
        debug!(op = ?af.op, nonce = af.nonce, "← audio-signal");

        if matches!(af.op, AudioOp::Request) {
            let store = media_store.clone();
            tokio::spawn(async move {
                let paused = pause_playing_for_call(&store).await;
                if !paused.is_empty() {
                    info!(?paused, "BLE fast-path: paused MPRIS for call");
                }
            });
        }

        let orch = orchestrator.clone();
        let peer_copy = peer_pub;
        tokio::spawn(async move {
            let _ = orch.on_incoming(peer_copy, af).await;
        });
    }
    info!(addr = %client.address, "BLE audio-signal listener: stream closed");
    Ok(())
}

pub async fn write_audio_op(
    client: &VortexClient,
    transport: Arc<Mutex<TransportState>>,
    frame: AudioOpFrame,
) -> Result<(), String> {
    let char = client
        .audio_signal
        .as_ref()
        .ok_or_else(|| "peer has no AUDIO_SIGNAL characteristic".to_string())?;
    let json = frame.to_json().map_err(|e| format!("AudioOpFrame to_json: {e}"))?;
    let mut ct = vec![0u8; json.len() + 16];
    let mut t = transport.lock().await;
    let n =
        t.write_message(&json, &mut ct).map_err(|e| format!("audio-signal write_message: {e}"))?;
    ct.truncate(n);
    let wire = Frame::new(ty::AUDIO_OP, 0, ct).encode();
    char.write(&wire).await.map_err(|e| format!("BLE write to AUDIO_SIGNAL: {e}"))?;
    drop(t);
    debug!(op = ?frame.op, nonce = frame.nonce, "→ audio-signal (BLE write)");
    Ok(())
}

async fn write_framed(char: &Characteristic, wire: &[u8]) -> bluer::Result<()> {
    let req = CharacteristicWriteRequest { op_type: WriteOp::Request, ..Default::default() };
    char.write_ext(wire, &req).await
}

pub async fn write_state(
    client: &VortexClient,
    transport: Arc<Mutex<TransportState>>,
    state: &AppState,
) -> Result<(), String> {
    let char = client
        .audio_signal
        .as_ref()
        .ok_or_else(|| "peer has no AUDIO_SIGNAL characteristic".to_string())?;
    let json = serde_json::to_vec(state).map_err(|e| format!("AppState to_json: {e}"))?;
    let mut ct = vec![0u8; json.len() + 16];
    let mut t = transport.lock().await;
    let n = t.write_message(&json, &mut ct).map_err(|e| format!("state write_message: {e}"))?;
    ct.truncate(n);
    let wire = Frame::new(ty::STATE, 0, ct).encode();
    write_framed(char, &wire).await.map_err(|e| format!("BLE write STATE to AUDIO_SIGNAL: {e}"))?;
    drop(t);
    debug!(battery = ?state.battery, charging = state.charging, "→ BLE state push");
    Ok(())
}

pub async fn write_notification(
    client: &VortexClient,
    transport: Arc<Mutex<TransportState>>,
    notif: &crate::core::notif_mirror::NotificationMirror,
) -> Result<(), String> {
    let char = client
        .audio_signal
        .as_ref()
        .ok_or_else(|| "peer has no AUDIO_SIGNAL characteristic".to_string())?;
    let json = serde_json::to_vec(notif).map_err(|e| format!("notif to_json: {e}"))?;
    let mut ct = vec![0u8; json.len() + 16];
    let mut t = transport.lock().await;
    let n = t.write_message(&json, &mut ct).map_err(|e| format!("notif write_message: {e}"))?;
    ct.truncate(n);
    let wire = Frame::new(ty::NOTIFICATION, 0, ct).encode();
    write_framed(char, &wire)
        .await
        .map_err(|e| format!("BLE write NOTIFICATION to AUDIO_SIGNAL: {e}"))?;
    debug!(app = %notif.app, "→ BLE notification push (laptop→phone)");
    Ok(())
}

pub async fn write_sealed(
    client: &VortexClient,
    transport: Arc<Mutex<TransportState>>,
    ty: u8,
    payload: &[u8],
) -> Result<(), String> {
    let char = client
        .audio_signal
        .as_ref()
        .ok_or_else(|| "peer has no AUDIO_SIGNAL characteristic".to_string())?;
    let mut ct = vec![0u8; payload.len() + 16];
    let mut t = transport.lock().await;
    let n = t.write_message(payload, &mut ct).map_err(|e| format!("sealed write_message: {e}"))?;
    ct.truncate(n);
    let wire = Frame::new(ty, 0, ct).encode();
    write_framed(char, &wire)
        .await
        .map_err(|e| format!("BLE write 0x{ty:02x} to AUDIO_SIGNAL: {e}"))?;
    Ok(())
}

pub async fn write_clipboard(
    client: &VortexClient,
    transport: Arc<Mutex<TransportState>>,
    clip: &crate::core::clipboard_mirror::ClipboardMirror,
) -> Result<(), String> {
    let char = client
        .audio_signal
        .as_ref()
        .ok_or_else(|| "peer has no AUDIO_SIGNAL characteristic".to_string())?;
    if clip.text.len() > crate::core::clipboard_mirror::MAX_SINGLE_FRAME_TEXT_BYTES {
        let chunks = crate::core::clipboard_mirror::build_text_chunks(&clip.text);
        let total = chunks.len();
        for payload in chunks {
            let mut ct = vec![0u8; payload.len() + 16];
            {
                let mut t = transport.lock().await;
                let n = t
                    .write_message(&payload, &mut ct)
                    .map_err(|e| format!("clipboard-text write_message: {e}"))?;
                ct.truncate(n);
                let wire = Frame::new(ty::CLIPBOARD_TEXT, 0, ct).encode();
                write_framed(char, &wire)
                    .await
                    .map_err(|e| format!("BLE write CLIPBOARD_TEXT to AUDIO_SIGNAL: {e}"))?;
            }
            tokio::time::sleep(Duration::from_millis(12)).await;
        }
        debug!(
            chars = clip.text.chars().count(),
            total, "→ BLE clipboard text push chunked (laptop→phone)"
        );
        return Ok(());
    }
    let json = serde_json::to_vec(clip).map_err(|e| format!("clipboard to_json: {e}"))?;
    let mut ct = vec![0u8; json.len() + 16];
    let mut t = transport.lock().await;
    let n = t.write_message(&json, &mut ct).map_err(|e| format!("clipboard write_message: {e}"))?;
    ct.truncate(n);
    let wire = Frame::new(ty::CLIPBOARD, 0, ct).encode();
    write_framed(char, &wire)
        .await
        .map_err(|e| format!("BLE write CLIPBOARD to AUDIO_SIGNAL: {e}"))?;
    debug!(chars = clip.text.chars().count(), "→ BLE clipboard push (laptop→phone)");
    Ok(())
}

pub async fn write_clipboard_image(
    client: &VortexClient,
    transport: Arc<Mutex<TransportState>>,
    png: &[u8],
) -> Result<(), String> {
    let char = client
        .audio_signal
        .as_ref()
        .ok_or_else(|| "peer has no AUDIO_SIGNAL characteristic".to_string())?;
    let chunks = crate::core::clipboard_mirror::build_image_chunks(png);
    let total = chunks.len();
    for payload in chunks {
        let mut ct = vec![0u8; payload.len() + 16];
        {
            let mut t = transport.lock().await;
            let n = t
                .write_message(&payload, &mut ct)
                .map_err(|e| format!("clipboard-image write_message: {e}"))?;
            ct.truncate(n);
            let wire = Frame::new(ty::CLIPBOARD_IMAGE, 0, ct).encode();
            write_framed(char, &wire)
                .await
                .map_err(|e| format!("BLE write CLIPBOARD_IMAGE to AUDIO_SIGNAL: {e}"))?;
        }
        tokio::time::sleep(Duration::from_millis(12)).await;
    }
    debug!(bytes = png.len(), total, "→ BLE clipboard image push (laptop→phone)");
    Ok(())
}

pub async fn write_call_control(
    client: &VortexClient,
    transport: Arc<Mutex<TransportState>>,
    ctrl: &crate::core::call_event::CallControl,
) -> Result<(), String> {
    let char = client
        .audio_signal
        .as_ref()
        .ok_or_else(|| "peer has no AUDIO_SIGNAL characteristic".to_string())?;
    let json = ctrl.to_json();
    let mut ct = vec![0u8; json.len() + 16];
    let mut t = transport.lock().await;
    let n =
        t.write_message(&json, &mut ct).map_err(|e| format!("call-control write_message: {e}"))?;
    ct.truncate(n);
    let wire = Frame::new(ty::CALL_CONTROL, 0, ct).encode();
    write_framed(char, &wire)
        .await
        .map_err(|e| format!("BLE write CALL_CONTROL to AUDIO_SIGNAL: {e}"))?;
    debug!(action = %ctrl.action, "→ BLE call-control push (laptop→phone)");
    Ok(())
}

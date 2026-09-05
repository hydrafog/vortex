use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use tauri::AppHandle;
use tokio::sync::Mutex;

use vortex_l3_daemon::core::call_event::{action, CallControl, CallEvent};
use vortex_l3_daemon::core::live_activity::LiveActivity;
use vortex_l3_daemon::core::notification_display;

pub(crate) static CALL_MIRROR_TX: std::sync::OnceLock<
    tokio::sync::mpsc::UnboundedSender<vortex_l3_daemon::core::call_event::CallEvent>,
> = std::sync::OnceLock::new();

pub(crate) static CALL_CONTROL_SEQ: std::sync::atomic::AtomicI64 =
    std::sync::atomic::AtomicI64::new(1);

pub(crate) static PENDING_CALL_CONTROL: std::sync::Mutex<
    Option<vortex_l3_daemon::core::call_event::CallControl>,
> = std::sync::Mutex::new(None);

pub(crate) type CallWriter = Arc<
    dyn Fn(
            vortex_l3_daemon::core::call_event::CallControl,
        ) -> futures::future::BoxFuture<'static, Result<(), String>>
        + Send
        + Sync,
>;

pub(crate) static CALL_WRITER: std::sync::OnceLock<Arc<tokio::sync::Mutex<Option<CallWriter>>>> =
    std::sync::OnceLock::new();

pub(crate) const CALL_PILL_KEY: &str = "vortex-call";

pub(crate) static CALL_PILL_ACTIVE: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

pub(crate) fn call_pill_active() -> bool {
    CALL_PILL_ACTIVE.load(Ordering::SeqCst)
}

static CURRENT_CALL_ID: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

fn set_current_call_id(id: &str) {
    if let Ok(mut g) = CURRENT_CALL_ID.lock() {
        g.clear();
        g.push_str(id);
    }
}

#[derive(Default)]
struct CallState {
    banner_id: u32,
    call_id: String,
    ring_title: String,
    ring_body: String,
    ring_app_id: String,
    banner_silenced: bool,
    last_handled: (String, String),
    pill_started_ms: i64,
    pill_title: String,
    pill_app_id: String,
    pill_text: String,
    pill_muted: bool,
    pill_speaker: bool,
    pill_earbuds: bool,
    pill_connected: bool,
}

async fn send_call_control(
    act: &str,
    call_id: String,
    writer: &Arc<Mutex<Option<crate::CallWriter>>>,
) {
    if call_id.is_empty() {
        tracing::warn!(action = act, "call action but no active call; ignoring");
        return;
    }
    let seq = crate::CALL_CONTROL_SEQ.fetch_add(1, Ordering::SeqCst);
    let ctrl = CallControl { id: call_id, action: act.to_string(), arg: String::new(), seq };
    let w = { writer.lock().await.clone() };
    let ble_ok = match w {
        Some(w) => match w(ctrl.clone()).await {
            Ok(()) => {
                tracing::info!(action = act, seq, "→ call-control sent (BLE)");
                true
            }
            Err(e) => {
                tracing::warn!(action = act, "BLE call-control failed: {e}");
                false
            }
        },
        None => false,
    };
    if !ble_ok {
        if let Ok(mut g) = crate::PENDING_CALL_CONTROL.lock() {
            *g = Some(ctrl);
        }
        if let Some(n) = crate::SYNC_NUDGE.get() {
            n.notify_one();
        }
        tracing::info!(action = act, seq, "→ call-control queued (LAN backstop)");
    }
}

async fn send_media_control(
    act: &str,
    pkg: String,
    writer: &Arc<Mutex<Option<crate::CallWriter>>>,
) {
    let seq = crate::CALL_CONTROL_SEQ.fetch_add(1, Ordering::SeqCst);
    let ctrl = CallControl { id: String::new(), action: act.to_string(), arg: pkg, seq };
    let w = { writer.lock().await.clone() };
    let ble_ok = match w {
        Some(w) => match w(ctrl.clone()).await {
            Ok(()) => {
                tracing::info!(action = act, seq, "→ media-control sent (BLE)");
                true
            }
            Err(e) => {
                tracing::warn!(action = act, "BLE media-control failed: {e}");
                false
            }
        },
        None => false,
    };
    if !ble_ok {
        if let Ok(mut g) = crate::PENDING_CALL_CONTROL.lock() {
            *g = Some(ctrl);
        }
        if let Some(n) = crate::SYNC_NUDGE.get() {
            n.notify_one();
        }
        tracing::info!(action = act, seq, "→ media-control queued (LAN backstop)");
    }
}

async fn control_active_call(act: &str) {
    let id = CURRENT_CALL_ID.lock().map(|g| g.clone()).unwrap_or_default();
    if id.is_empty() {
        tracing::warn!(action = act, "call-control requested but no active call");
        return;
    }
    if let Some(w) = crate::CALL_WRITER.get() {
        send_call_control(act, id, w).await;
    }
}

#[tauri::command]
pub(crate) async fn call_accept() {
    control_active_call(vortex_l3_daemon::core::call_event::action::ACCEPT).await;
}

#[tauri::command]
pub(crate) async fn call_decline() {
    control_active_call(vortex_l3_daemon::core::call_event::action::DECLINE).await;
}

#[tauri::command]
pub(crate) async fn dial(number: String) {
    let number = number.trim().to_string();
    if number.is_empty() {
        tracing::warn!("dial: empty number");
        return;
    }
    let seq = crate::CALL_CONTROL_SEQ.fetch_add(1, Ordering::SeqCst);
    let ctrl = CallControl {
        id: String::new(),
        action: vortex_l3_daemon::core::call_event::action::ORIGINATE_CALL.to_string(),
        arg: number,
        seq,
    };
    let w = match crate::CALL_WRITER.get() {
        Some(m) => m.lock().await.clone(),
        None => None,
    };
    let ble_ok = match w {
        Some(w) => match w(ctrl.clone()).await {
            Ok(()) => {
                tracing::info!(seq, "→ dial sent (BLE)");
                true
            }
            Err(e) => {
                tracing::warn!("BLE dial failed: {e}");
                false
            }
        },
        None => false,
    };
    if !ble_ok {
        if let Ok(mut g) = crate::PENDING_CALL_CONTROL.lock() {
            *g = Some(ctrl);
        }
        if let Some(n) = crate::SYNC_NUDGE.get() {
            n.notify_one();
        }
        tracing::info!(seq, "→ dial queued (AppState/LAN fallback)");
    }
}

#[tauri::command]
pub(crate) async fn send_sms(number: String, body: String) {
    let number = number.trim().to_string();
    if number.is_empty() || body.is_empty() {
        tracing::warn!("send_sms: empty number/body");
        return;
    }
    let arg = serde_json::json!({ "to": number, "body": body }).to_string();
    let seq = crate::CALL_CONTROL_SEQ.fetch_add(1, Ordering::SeqCst);
    let ctrl = CallControl {
        id: String::new(),
        action: vortex_l3_daemon::core::call_event::action::SEND_SMS.to_string(),
        arg,
        seq,
    };
    let w = match crate::CALL_WRITER.get() {
        Some(m) => m.lock().await.clone(),
        None => None,
    };
    let ble_ok = match w {
        Some(w) => match w(ctrl.clone()).await {
            Ok(()) => {
                tracing::info!(seq, "→ send_sms sent (BLE)");
                true
            }
            Err(e) => {
                tracing::warn!("BLE send_sms failed: {e}");
                false
            }
        },
        None => false,
    };
    if !ble_ok {
        if let Ok(mut g) = crate::PENDING_CALL_CONTROL.lock() {
            *g = Some(ctrl);
        }
        if let Some(n) = crate::SYNC_NUDGE.get() {
            n.notify_one();
        }
        tracing::info!(seq, "→ send_sms queued (AppState/LAN fallback)");
    }
}

#[tauri::command]
pub(crate) async fn mark_sms_read(thread: i64, number: String) {
    let arg = serde_json::json!({ "thread": thread, "address": number.trim() }).to_string();
    let seq = crate::CALL_CONTROL_SEQ.fetch_add(1, Ordering::SeqCst);
    let ctrl = CallControl {
        id: String::new(),
        action: vortex_l3_daemon::core::call_event::action::MARK_READ.to_string(),
        arg,
        seq,
    };
    let w = match crate::CALL_WRITER.get() {
        Some(m) => m.lock().await.clone(),
        None => None,
    };
    let ble_ok = match w {
        Some(w) => w(ctrl.clone()).await.is_ok(),
        None => false,
    };
    if !ble_ok {
        if let Ok(mut g) = crate::PENDING_CALL_CONTROL.lock() {
            *g = Some(ctrl);
        }
        if let Some(n) = crate::SYNC_NUDGE.get() {
            n.notify_one();
        }
    }
    tracing::info!(seq, ble = ble_ok, "→ mark_read sent");
}

#[tauri::command]
pub(crate) async fn load_sms_thread(thread: i64, number: String, offset: i64, limit: i64) {
    let arg = serde_json::json!({
        "thread": thread,
        "address": number.trim(),
        "offset": offset.max(0),
        "limit": limit.clamp(1, 200),
    })
    .to_string();
    let seq = crate::CALL_CONTROL_SEQ.fetch_add(1, Ordering::SeqCst);
    let ctrl = CallControl {
        id: String::new(),
        action: vortex_l3_daemon::core::call_event::action::LOAD_THREAD.to_string(),
        arg,
        seq,
    };
    let w = match crate::CALL_WRITER.get() {
        Some(m) => m.lock().await.clone(),
        None => None,
    };
    let ble_ok = match w {
        Some(w) => w(ctrl.clone()).await.is_ok(),
        None => false,
    };
    if !ble_ok {
        if let Ok(mut g) = crate::PENDING_CALL_CONTROL.lock() {
            *g = Some(ctrl);
        }
        if let Some(n) = crate::SYNC_NUDGE.get() {
            n.notify_one();
        }
    }
    tracing::info!(seq, thread, offset, ble = ble_ok, "→ load_thread sent");
}

fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub(crate) async fn spawn_consumer(
    _app: AppHandle,
    live_tx: tokio::sync::mpsc::UnboundedSender<LiveActivity>,
    mut call_action_rx: tokio::sync::mpsc::UnboundedReceiver<String>,
) -> (tokio::sync::mpsc::UnboundedSender<CallEvent>, Arc<Mutex<Option<crate::CallWriter>>>) {
    let (call_tx, mut call_rx) = tokio::sync::mpsc::unbounded_channel::<CallEvent>();
    let call_writer: Arc<Mutex<Option<crate::CallWriter>>> = Arc::new(Mutex::new(None));
    let state: Arc<Mutex<CallState>> = Arc::new(Mutex::new(CallState::default()));
    let tick_gen: Arc<AtomicU64> = Arc::new(AtomicU64::new(0));

    {
        let state = state.clone();
        let tick_gen = tick_gen.clone();
        let writer_for_keepalive = call_writer.clone();
        tokio::spawn(async move {
            while let Some(ev) = call_rx.recv().await {
                crate::ble::touch_peer_contact();
                {
                    let key = (ev.id.clone(), ev.phase.clone());
                    let mut s = state.lock().await;
                    let pill_swept = matches!(
                        ev.phase.as_str(),
                        CallEvent::PHASE_RINGING | CallEvent::PHASE_ACTIVE
                    ) && !CALL_PILL_ACTIVE.load(Ordering::SeqCst);
                    if s.last_handled == key && !pill_swept {
                        let audio_changed = (s.pill_muted, s.pill_speaker, s.pill_earbuds)
                            != (ev.muted, ev.speaker, ev.has_earbuds);
                        let connect_now = ev.outgoing && ev.connected && !s.pill_connected;
                        if ev.phase == CallEvent::PHASE_ACTIVE && (audio_changed || connect_now) {
                            s.pill_muted = ev.muted;
                            s.pill_speaker = ev.speaker;
                            s.pill_earbuds = ev.has_earbuds;
                            if connect_now {
                                s.pill_connected = true;
                                s.pill_text = "On call".to_string();
                                s.pill_started_ms = if ev.started_at > 0 {
                                    if ev.sent_at > 0 {
                                        ev.started_at + (now_millis() - ev.sent_at)
                                    } else {
                                        ev.started_at
                                    }
                                } else {
                                    now_millis()
                                };
                            }
                            let pill = LiveActivity {
                                key: CALL_PILL_KEY.to_string(),
                                app: "Phone".to_string(),
                                app_id: s.pill_app_id.clone(),
                                title: s.pill_title.clone(),
                                text: s.pill_text.clone(),
                                sub: String::new(),
                                progress: -1,
                                started_at: s.pill_started_ms,
                                muted: ev.muted,
                                speaker: ev.speaker,
                                has_earbuds: ev.has_earbuds,
                                ended: false,
                                playing: None,
                            };
                            drop(s);
                            let _ = live_tx.send(pill);
                            tracing::info!(
                                connect = connect_now,
                                "call pill updated (audio/connect)"
                            );
                        }
                        continue;
                    }
                    s.last_handled = key;
                }
                tracing::info!(
                    phase = %ev.phase,
                    named = !ev.name.is_empty(),
                    outgoing = ev.outgoing,
                    "call mirror event",
                );
                tick_gen.fetch_add(1, Ordering::SeqCst);
                match ev.phase.as_str() {
                    CallEvent::PHASE_RINGING | CallEvent::PHASE_ACTIVE => {
                        set_current_call_id(&ev.id);
                        if !CALL_PILL_ACTIVE.swap(true, Ordering::SeqCst) {
                            if let Some(n) = crate::SYNC_NUDGE.get() {
                                n.notify_waiters();
                            }
                        }
                    }
                    CallEvent::PHASE_ENDED => {
                        set_current_call_id("");
                        CALL_PILL_ACTIVE.store(false, Ordering::SeqCst);
                    }
                    _ => {}
                }
                match ev.phase.as_str() {
                    CallEvent::PHASE_RINGING if !ev.outgoing => {
                        let title = if !ev.name.is_empty() {
                            ev.name.clone()
                        } else if !ev.number.is_empty() {
                            ev.number.clone()
                        } else {
                            "Unknown caller".to_string()
                        };
                        let body = if !ev.name.is_empty() && !ev.number.is_empty() {
                            format!("Incoming call · {}", ev.number)
                        } else {
                            "Incoming call".to_string()
                        };
                        let actions = vec![
                            ("call:accept".to_string(), "Accept".to_string()),
                            ("call:decline".to_string(), "Decline".to_string()),
                        ];
                        let prev = state.lock().await.banner_id;
                        match notification_display::show_call_banner(
                            &title, &body, &ev.app_id, &actions, prev, true,
                        )
                        .await
                        {
                            Ok(id) => {
                                let mut s = state.lock().await;
                                s.banner_id = id;
                                s.call_id = ev.id.clone();
                                s.ring_title = title.clone();
                                s.ring_body = body.clone();
                                s.ring_app_id = ev.app_id.clone();
                                s.banner_silenced = false;
                                tracing::info!(id, "call banner shown (ringing)");
                            }
                            Err(e) => tracing::warn!("call banner show failed: {e}"),
                        }
                    }
                    CallEvent::PHASE_ACTIVE => {
                        let id = {
                            let mut s = state.lock().await;
                            s.call_id = ev.id.clone();
                            s.banner_id
                        };
                        if id != 0 {
                            let _ = notification_display::close(id).await;
                            state.lock().await.banner_id = 0;
                        }
                        let caller = if !ev.name.is_empty() {
                            ev.name.clone()
                        } else if !ev.number.is_empty() {
                            ev.number.clone()
                        } else {
                            "Call".to_string()
                        };
                        let (text, started_ms) = if ev.outgoing && !ev.connected {
                            ("Calling…".to_string(), 0i64)
                        } else {
                            let s = if ev.started_at > 0 {
                                if ev.sent_at > 0 {
                                    ev.started_at + (now_millis() - ev.sent_at)
                                } else {
                                    ev.started_at
                                }
                            } else {
                                now_millis()
                            };
                            ("On call".to_string(), s)
                        };
                        let app_id = ev.app_id.clone();
                        let (muted, speaker, has_earbuds) = (ev.muted, ev.speaker, ev.has_earbuds);
                        {
                            let mut s = state.lock().await;
                            s.pill_started_ms = started_ms;
                            s.pill_title = caller.clone();
                            s.pill_app_id = app_id.clone();
                            s.pill_text = text.clone();
                            s.pill_muted = muted;
                            s.pill_speaker = speaker;
                            s.pill_earbuds = has_earbuds;
                            s.pill_connected = !ev.outgoing || ev.connected;
                        }
                        let my_gen = tick_gen.load(Ordering::SeqCst);
                        let live_tx = live_tx.clone();
                        let tick_gen2 = tick_gen.clone();
                        let mk_pill = move || LiveActivity {
                            key: CALL_PILL_KEY.to_string(),
                            app: "Phone".to_string(),
                            app_id: app_id.clone(),
                            title: caller.clone(),
                            text: text.clone(),
                            sub: String::new(),
                            progress: -1,
                            started_at: started_ms,
                            muted,
                            speaker,
                            has_earbuds,
                            ended: false,
                            playing: None,
                        };
                        let _ = live_tx.send(mk_pill());
                        let writer = writer_for_keepalive.clone();
                        let state_ka = state.clone();
                        tokio::spawn(async move {
                            loop {
                                tokio::time::sleep(std::time::Duration::from_secs(30)).await;
                                if tick_gen2.load(Ordering::SeqCst) != my_gen {
                                    break;
                                }
                                if writer.lock().await.is_none() {
                                    tracing::info!(
                                        "call pill keep-alive: BLE link gone, letting it expire"
                                    );
                                    break;
                                }
                                let pill = {
                                    let s = state_ka.lock().await;
                                    LiveActivity {
                                        key: CALL_PILL_KEY.to_string(),
                                        app: "Phone".to_string(),
                                        app_id: s.pill_app_id.clone(),
                                        title: s.pill_title.clone(),
                                        text: s.pill_text.clone(),
                                        sub: String::new(),
                                        progress: -1,
                                        started_at: s.pill_started_ms,
                                        muted: s.pill_muted,
                                        speaker: s.pill_speaker,
                                        has_earbuds: s.pill_earbuds,
                                        ended: false,
                                        playing: None,
                                    }
                                };
                                let _ = live_tx.send(pill);
                            }
                        });
                        tracing::info!("in-call pill started");
                    }
                    CallEvent::PHASE_ENDED => {
                        let id = {
                            let mut s = state.lock().await;
                            let id = s.banner_id;
                            s.banner_id = 0;
                            s.call_id.clear();
                            id
                        };
                        if id != 0 {
                            let _ = notification_display::close(id).await;
                        }
                        if id != 0 && !ev.outgoing && !ev.number.is_empty() {
                            let title = if ev.name.is_empty() {
                                ev.number.clone()
                            } else {
                                ev.name.clone()
                            };
                            let actions = vec![(
                                format!("call:redial:{}", ev.number),
                                "Call back".to_string(),
                            )];
                            match notification_display::show_call_banner(
                                &title,
                                "Missed call",
                                &ev.app_id,
                                &actions,
                                0,
                                false,
                            )
                            .await
                            {
                                Ok(_) => tracing::info!("missed-call notification shown"),
                                Err(e) => tracing::warn!("missed-call notification failed: {e}"),
                            }
                        }
                        let _ = live_tx.send(LiveActivity {
                            key: CALL_PILL_KEY.to_string(),
                            app: String::new(),
                            app_id: String::new(),
                            title: String::new(),
                            text: String::new(),
                            sub: String::new(),
                            progress: -1,
                            started_at: 0,
                            muted: false,
                            speaker: false,
                            has_earbuds: false,
                            ended: true,
                            playing: None,
                        });
                        tracing::info!("call ended; banner + pill cleared");
                    }
                    _ => {}
                }
            }
        });
    }

    {
        let state = state.clone();
        let writer = call_writer.clone();
        let (act_tx, mut act_rx) = tokio::sync::mpsc::unbounded_channel::<(u32, String)>();
        tokio::spawn(notification_display::watch_actions(act_tx));
        tokio::spawn(async move {
            while let Some((_id, key)) = act_rx.recv().await {
                let Some(verb) = key.strip_prefix("call:") else {
                    continue;
                };
                if let Some(num) = verb.strip_prefix("redial:") {
                    let n = num.to_string();
                    tokio::spawn(async move {
                        dial(n).await;
                    });
                    continue;
                }
                let act = match verb {
                    "accept" => action::ACCEPT,
                    "decline" => action::DECLINE,
                    "end" => action::END,
                    "mute" => action::MUTE,
                    "unmute" => action::UNMUTE,
                    "speaker_on" => action::SPEAKER_ON,
                    "speaker_off" => action::SPEAKER_OFF,
                    "silence" => action::SILENCE,
                    "sms" => action::SMS_REJECT,
                    other => {
                        tracing::warn!(verb = other, "unknown call action; ignoring");
                        continue;
                    }
                };
                let call_id = {
                    let s = state.lock().await;
                    s.call_id.clone()
                };
                send_call_control(act, call_id, &writer).await;
            }
        });
    }

    {
        let state = state.clone();
        let writer = call_writer.clone();
        let (closed_tx, mut closed_rx) = tokio::sync::mpsc::unbounded_channel::<(u32, u32)>();
        tokio::spawn(notification_display::watch_closed(closed_tx));
        tokio::spawn(async move {
            while let Some((id, reason)) = closed_rx.recv().await {
                if reason != 2 {
                    continue;
                }
                let (call_id, title, body, app_id) = {
                    let s = state.lock().await;
                    if id != s.banner_id
                        || s.banner_id == 0
                        || s.call_id.is_empty()
                        || s.banner_silenced
                    {
                        continue;
                    }
                    (
                        s.call_id.clone(),
                        s.ring_title.clone(),
                        s.ring_body.clone(),
                        s.ring_app_id.clone(),
                    )
                };
                send_call_control(action::SILENCE, call_id, &writer).await;
                let actions = vec![
                    ("call:accept".to_string(), "Accept".to_string()),
                    ("call:decline".to_string(), "Decline".to_string()),
                ];
                match notification_display::show_call_banner(
                    &title, &body, &app_id, &actions, 0, false,
                )
                .await
                {
                    Ok(new_id) => {
                        let mut s = state.lock().await;
                        if !s.call_id.is_empty() {
                            s.banner_id = new_id;
                            s.banner_silenced = true;
                        }
                        tracing::info!(new_id, "ringing banner silenced + re-shown quietly");
                    }
                    Err(e) => tracing::warn!("silenced re-show failed: {e}"),
                }
            }
        });
    }

    {
        let state = state.clone();
        let writer = call_writer.clone();
        tokio::spawn(async move {
            while let Some(verb) = call_action_rx.recv().await {
                if verb.starts_with("media_") {
                    let (act, pkg) = match verb.split_once(':') {
                        Some((a, p)) => (a.to_string(), p.to_string()),
                        None => (verb.clone(), String::new()),
                    };
                    send_media_control(&act, pkg, &writer).await;
                    continue;
                }
                let call_id = {
                    let s = state.lock().await;
                    s.call_id.clone()
                };
                send_call_control(&verb, call_id, &writer).await;
            }
        });
    }

    (call_tx, call_writer)
}

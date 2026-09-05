

use std::sync::Arc;

pub(crate) static ACTIVE_CHAT: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

const SMS_APP_IDS: &[&str] = &[
    "com.google.android.apps.messaging",
    "com.android.messaging",
    "com.android.mms",
    "com.samsung.android.messaging",
    "com.xiaomi.mms",
    "com.miui.smsextra",
];
const CALL_APP_IDS: &[&str] = &[
    "com.google.android.dialer",
    "com.android.dialer",
    "com.android.incallui",
    "com.samsung.android.incallui",
    "com.android.server.telecom",
];

fn notif_click_target(app_id: &str) -> Option<&'static str> {
    if SMS_APP_IDS.contains(&app_id) {
        Some("sms")
    } else if CALL_APP_IDS.contains(&app_id) {
        Some("call")
    } else {
        None
    }
}

fn is_whatsapp(app_id: &str) -> bool {
    matches!(app_id, "com.whatsapp" | "com.whatsapp.w4b")
}

fn webmail_inbox(app_id: &str) -> Option<&'static str> {
    Some(match app_id {
        "com.google.android.gm" => "https://mail.google.com/",
        "com.microsoft.office.outlook" => "https://outlook.live.com/mail/",
        "com.yahoo.mobile.client.android.mail" => "https://mail.yahoo.com/",
        "ch.protonmail.android" | "me.proton.android.mail" => "https://mail.proton.me/",
        _ => return None,
    })
}

fn wa_number(raw: &str) -> String {
    raw.chars().filter(|c| c.is_ascii_digit()).collect()
}

enum ClickAction {
    Page(&'static str),
    OpenUrl(String),
    LaunchApp(std::path::PathBuf),
    Dismiss,
}

fn resolve_notif_click(app_id: &str, app_label: &str, title: &str) -> ClickAction {
    if let Some(kind) = notif_click_target(app_id) {
        return ClickAction::Page(kind);
    }
    if is_whatsapp(app_id) {
        if let Some(num) = crate::contacts::lookup_number_by_name(title) {
            let n = wa_number(&num);
            if !n.is_empty() {
                return ClickAction::OpenUrl(format!("https://wa.me/{n}"));
            }
        }
    }
    if let Some(url) = webmail_inbox(app_id) {
        return ClickAction::OpenUrl(url.to_string());
    }
    if let Some(path) = crate::desktop_apps::match_label(app_label) {
        return ClickAction::LaunchApp(path);
    }
    ClickAction::Dismiss
}

#[cfg(test)]
mod tests {
    use super::{is_whatsapp, notif_click_target, wa_number, webmail_inbox};

    #[test]
    fn click_target_gates_by_app() {
        assert_eq!(notif_click_target("com.google.android.apps.messaging"), Some("sms"));
        assert_eq!(notif_click_target("com.android.messaging"), Some("sms"));
        assert_eq!(notif_click_target("com.google.android.dialer"), Some("call"));
        assert_eq!(notif_click_target("com.android.server.telecom"), Some("call"));
        assert_eq!(notif_click_target("org.telegram.messenger"), None);
        assert_eq!(notif_click_target("com.whatsapp"), None);
        assert_eq!(notif_click_target(""), None);
    }

    #[test]
    fn whatsapp_and_webmail_routing() {
        assert!(is_whatsapp("com.whatsapp"));
        assert!(is_whatsapp("com.whatsapp.w4b"));
        assert!(!is_whatsapp("org.telegram.messenger"));
        assert_eq!(webmail_inbox("com.google.android.gm"), Some("https://mail.google.com/"));
        assert_eq!(webmail_inbox("me.proton.android.mail"), Some("https://mail.proton.me/"));
        assert_eq!(webmail_inbox("org.telegram.messenger"), None);
    }

    #[test]
    fn wa_number_strips_to_digits() {
        assert_eq!(wa_number("+998 90 123-45-67"), "998901234567");
        assert_eq!(wa_number("(555) 010"), "555010");
    }
}

#[tauri::command]
pub fn set_active_chat(name: String) {
    if let Ok(mut g) = ACTIVE_CHAT.lock() {
        *g = name;
    }
}

pub(crate) static NOTIF_SHOW: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

pub(crate) static NOTIF_SEND: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

pub(crate) type NotifWriter = Arc<
    dyn Fn(vortex_l3_daemon::core::notif_mirror::NotificationMirror)
            -> futures::future::BoxFuture<'static, Result<(), String>>
        + Send
        + Sync,
>;

pub(crate) static NOTIF_INVOKE_SEQ: std::sync::atomic::AtomicU64 =
    std::sync::atomic::AtomicU64::new(1);

pub(crate) static PENDING_NOTIF_INVOKE: std::sync::Mutex<
    Option<vortex_l3_daemon::core::notif_mirror::NotificationMirror>,
> = std::sync::Mutex::new(None);

pub(crate) async fn prompt_reply_text(prompt: &str) -> Option<String> {
    let title = "Vortex — Reply";
    let candidates: [(&str, Vec<String>); 2] = [
        (
            "zenity",
            vec![
                "--entry".into(),
                format!("--title={title}"),
                format!("--text={prompt}"),
            ],
        ),
        (
            "kdialog",
            vec![
                "--title".into(),
                title.into(),
                "--inputbox".into(),
                prompt.into(),
            ],
        ),
    ];
    for (bin, args) in candidates {
        match tokio::process::Command::new(bin).args(&args).output().await {
            Ok(out) => {
                if !out.status.success() {
                    return None;
                }
                let text = String::from_utf8_lossy(&out.stdout).trim().to_string();
                return if text.is_empty() { None } else { Some(text) };
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(_) => return None,
        }
    }
    None
}

#[tauri::command]
pub fn set_notif_mirror_show(show: bool) {
    NOTIF_SHOW.store(show, std::sync::atomic::Ordering::Relaxed);
}

#[tauri::command]
pub fn get_notif_mirror_show() -> bool {
    NOTIF_SHOW.load(std::sync::atomic::Ordering::Relaxed)
}

#[tauri::command]
pub fn set_notif_mirror_send(send: bool) {
    NOTIF_SEND.store(send, std::sync::atomic::Ordering::Relaxed);
}

#[tauri::command]
pub fn get_notif_mirror_send() -> bool {
    NOTIF_SEND.load(std::sync::atomic::Ordering::Relaxed)
}

pub(crate) fn spawn_icon_consumer(
) -> tokio::sync::mpsc::UnboundedSender<(String, u16, u16, Vec<u8>)> {
            let (ble_icon_tx, mut ble_icon_rx) =
                tokio::sync::mpsc::unbounded_channel::<(String, u16, u16, Vec<u8>)>();
            {
                tokio::spawn(async move {
                    let mut asm = vortex_l3_daemon::core::icon_cache::IconAssembler::default();
                    while let Some((app_id, total, idx, data)) = ble_icon_rx.recv().await {
                        if let Some(path) = asm.add(app_id.clone(), total, idx, data) {
                            tracing::info!(app_id = %app_id, path = ?path, "icon: cached app logo");
                        }
                    }
                });
            }
    ble_icon_tx
}


pub(crate) fn spawn_subsystem(
    app: tauri::AppHandle,
) -> (
    tokio::sync::mpsc::UnboundedSender<vortex_l3_daemon::core::notif_mirror::NotificationMirror>,
    std::sync::Arc<tokio::sync::Mutex<Option<crate::NotifWriter>>>,
) {
            let (ble_notif_tx, mut ble_notif_rx) = tokio::sync::mpsc::unbounded_channel::<
                vortex_l3_daemon::core::notif_mirror::NotificationMirror,
            >();
            let notif_links: Arc<
                tokio::sync::Mutex<
                    std::collections::HashMap<
                        u32,
                        (String, i32, std::time::Instant, String, String, String),
                    >,
                >,
            > = Arc::new(tokio::sync::Mutex::new(std::collections::HashMap::new()));
            let notif_recent_actions: Arc<tokio::sync::Mutex<std::collections::HashSet<u32>>> =
                Arc::new(tokio::sync::Mutex::new(std::collections::HashSet::new()));

            let ble_notif_writer: Arc<tokio::sync::Mutex<Option<NotifWriter>>> =
                Arc::new(tokio::sync::Mutex::new(None));
            let delivered_keys: Arc<tokio::sync::Mutex<std::collections::HashSet<String>>> =
                Arc::new(tokio::sync::Mutex::new(std::collections::HashSet::new()));
            let catch_up_pending = Arc::new(std::sync::atomic::AtomicBool::new(false));
            let catch_up_last = Arc::new(tokio::sync::Mutex::new(
                None::<std::time::Instant>,
            ));
            const CATCH_UP_MIN_INTERVAL: std::time::Duration =
                std::time::Duration::from_secs(90);

            {
                let links = notif_links.clone();
                let app_show = app.clone();
                let writer_for_catchup = ble_notif_writer.clone();
                let delivered = delivered_keys.clone();
                let pending = catch_up_pending.clone();
                let catch_up_last = catch_up_last.clone();
                tokio::spawn(async move {
                    while let Some(notif) = ble_notif_rx.recv().await {
                        if notif.resync {
                            if !NOTIF_SHOW.load(std::sync::atomic::Ordering::Relaxed) {
                                continue;
                            }
                            if !pending.swap(true, std::sync::atomic::Ordering::SeqCst) {
                                let writer_h = writer_for_catchup.clone();
                                let delivered = delivered.clone();
                                let pending = pending.clone();
                                let catch_up_last = catch_up_last.clone();
                                tokio::spawn(async move {
                                    tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
                                    pending.store(false, std::sync::atomic::Ordering::SeqCst);
                                    if catch_up_last
                                        .lock()
                                        .await
                                        .map(|t| t.elapsed() < CATCH_UP_MIN_INTERVAL)
                                        == Some(true)
                                    {
                                        return;
                                    }
                                    let Some(w) = writer_h.lock().await.clone() else { return };
                                    let known: Vec<String> =
                                        delivered.lock().await.iter().cloned().collect();
                                    let req = vortex_l3_daemon::core::notif_mirror::NotificationMirror {
                                        resync: true,
                                        known_keys: known,
                                        ..Default::default()
                                    };
                                    match w(req).await {
                                        Ok(()) => {
                                            *catch_up_last.lock().await = Some(std::time::Instant::now());
                                            tracing::info!(
                                                "notif catch-up requested (reconcile after BLE drop/reconnect)"
                                            );
                                        }
                                        Err(e) => tracing::warn!("notif catch-up request failed: {e}"),
                                    }
                                });
                            }
                            continue;
                        }
                        if notif.dismiss {
                            let id = {
                                let mut m = links.lock().await;
                                let found = m
                                    .iter()
                                    .find(|(_, v)| v.0 == notif.key)
                                    .map(|(&id, _)| id);
                                if let Some(id) = found {
                                    m.remove(&id);
                                }
                                found
                            };
                            if let Some(id) = id {
                                let _ = vortex_l3_daemon::core::notification_display::close(id).await;
                            }
                            continue;
                        }
                        if !NOTIF_SHOW.load(std::sync::atomic::Ordering::Relaxed) {
                            tracing::info!(app = %notif.app, "notif: suppressed (show toggle off)");
                            continue;
                        }
                        {
                            use tauri::Manager;
                            let active = crate::ACTIVE_CHAT
                                .lock()
                                .map(|g| g.clone())
                                .unwrap_or_default();
                            if !active.is_empty()
                                && notif.title == active
                                && app_show
                                    .get_webview_window("main")
                                    .map(|w| w.is_focused().unwrap_or(false))
                                    .unwrap_or(false)
                            {
                                tracing::info!("notif: suppressed (chat open and focused)");
                                continue;
                            }
                        }
                        let replaces_id = if notif.key.is_empty() {
                            0
                        } else {
                            links
                                .lock()
                                .await
                                .iter()
                                .find(|(_, v)| v.0 == notif.key)
                                .map(|(&id, _)| id)
                                .unwrap_or(0)
                        };
                        match vortex_l3_daemon::core::notification_display::show(&notif, replaces_id)
                            .await
                        {
                            Ok(id) => {
                                tracing::info!(app = %notif.app, id, replaces_id, "notif: shown on desktop");
                                if !notif.key.is_empty() {
                                    let mut m = links.lock().await;
                                    if replaces_id != 0 && replaces_id != id {
                                        m.remove(&replaces_id);
                                    }
                                    m.insert(
                                        id,
                                        (
                                            notif.key.clone(),
                                            notif.reply_index,
                                            std::time::Instant::now(),
                                            notif.title.clone(),
                                            notif.app_id.clone(),
                                            notif.app.clone(),
                                        ),
                                    );
                                }
                                if !notif.key.is_empty() {
                                    delivered.lock().await.insert(notif.key.clone());
                                }
                            }
                            Err(e) => tracing::warn!("desktop notification failed: {e}"),
                        }
                    }
                });
            }

            {
                let (cap_tx, mut cap_rx) = tokio::sync::mpsc::unbounded_channel::<
                    vortex_l3_daemon::core::notif_mirror::NotificationMirror,
                >();
                vortex_l3_daemon::core::notif_capturer::spawn(cap_tx);
                let writer_handle = ble_notif_writer.clone();
                tokio::spawn(async move {
                    while let Some(notif) = cap_rx.recv().await {
                        if !NOTIF_SEND.load(std::sync::atomic::Ordering::Relaxed) {
                            continue;
                        }
                        let writer = writer_handle.lock().await.clone();
                        if let Some(w) = writer {
                            if let Err(e) = w(notif).await {
                                tracing::warn!("laptop→phone notif write failed: {e}");
                            }
                        }
                    }
                });
            }

            {
                let (closed_tx, mut closed_rx) =
                    tokio::sync::mpsc::unbounded_channel::<(u32, u32)>();
                tokio::spawn(vortex_l3_daemon::core::notification_display::watch_closed(closed_tx));
                let links = notif_links.clone();
                let recent_actions = notif_recent_actions.clone();
                let writer_handle = ble_notif_writer.clone();
                tokio::spawn(async move {
                    while let Some((id, reason)) = closed_rx.recv().await {
                        if reason != 2 {
                            links.lock().await.remove(&id);
                            continue;
                        }
                        let links = links.clone();
                        let recent_actions = recent_actions.clone();
                        let writer_handle = writer_handle.clone();
                        tokio::spawn(async move {
                            tokio::time::sleep(std::time::Duration::from_millis(300)).await;
                            let was_action = recent_actions.lock().await.remove(&id);
                            let link = links.lock().await.get(&id).cloned();
                            let Some((key, _reply_index, shown_at, _title, _app_id, _app_label)) = link else {
                                return;
                            };
                            if was_action {
                                links.lock().await.remove(&id);
                                return;
                            }
                            if shown_at.elapsed() < std::time::Duration::from_millis(1200) {
                                return;
                            }
                            links.lock().await.remove(&id);
                            if key.is_empty() {
                                return;
                            }
                            let writer = writer_handle.lock().await.clone();
                            if let Some(w) = writer {
                                let dismiss = vortex_l3_daemon::core::notif_mirror::NotificationMirror {
                                    key,
                                    dismiss: true,
                                    ..Default::default()
                                };
                                let _ = w(dismiss).await;
                            }
                        });
                    }
                });
            }

            {
                let (act_tx, mut act_rx) = tokio::sync::mpsc::unbounded_channel::<(u32, String)>();
                tokio::spawn(vortex_l3_daemon::core::notification_display::watch_actions(act_tx));
                let links = notif_links.clone();
                let recent_actions = notif_recent_actions.clone();
                let writer_handle = ble_notif_writer.clone();
                let app_open = app.clone();
                tokio::spawn(async move {
                    while let Some((id, action_key)) = act_rx.recv().await {
                        if action_key == "default" {
                            let (title, app_id, app_label) = links
                                .lock()
                                .await
                                .get(&id)
                                .map(|l| (l.3.clone(), l.4.clone(), l.5.clone()))
                                .unwrap_or_default();
                            match resolve_notif_click(&app_id, &app_label, &title) {
                                ClickAction::Page(kind) => {
                                    use tauri::{Emitter, Manager};
                                    if let Some(w) = app_open.get_webview_window("main") {
                                        let _ = w.show();
                                        let _ = w.unminimize();
                                        let _ = w.set_focus();
                                    }
                                    tracing::info!(%app_id, kind, "notif click: open laptop page");
                                    let _ = app_open.emit(
                                        "vortex:open-chat",
                                        serde_json::json!({ "title": title, "appId": app_id, "kind": kind }),
                                    );
                                }
                                ClickAction::OpenUrl(url) => {
                                    tracing::info!(%app_id, "notif click: open in browser");
                                    let _ = tokio::process::Command::new("xdg-open").arg(&url).spawn();
                                }
                                ClickAction::LaunchApp(path) => {
                                    tracing::info!(%app_id, app = %app_label, "notif click: launch desktop app");
                                    crate::desktop_apps::launch(&path);
                                }
                                ClickAction::Dismiss => {
                                    tracing::info!(%app_id, "notif click: dismiss-only (no matching laptop app)");
                                }
                            }
                            continue;
                        }
                        let Some(idx) = action_key.strip_prefix("act:").and_then(|s| s.parse::<i32>().ok())
                        else {
                            continue;
                        };
                        {
                            let recent = recent_actions.clone();
                            recent.lock().await.insert(id);
                            tokio::spawn(async move {
                                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
                                recent.lock().await.remove(&id);
                            });
                        }
                        let link = links.lock().await.get(&id).cloned();
                        let Some((key, reply_index, _shown_at, _title, _app_id, _app_label)) = link else {
                            continue;
                        };
                        let writer = writer_handle.lock().await.clone();
                        let needs_reply = idx == reply_index;
                        tokio::spawn(async move {
                            let reply = if needs_reply {
                                match prompt_reply_text("Type your reply:").await {
                                    Some(t) => t,
                                    None => return,
                                }
                            } else {
                                String::new()
                            };
                            let seq = NOTIF_INVOKE_SEQ
                                .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                            let invoke = vortex_l3_daemon::core::notif_mirror::NotificationMirror {
                                key,
                                invoke_index: idx,
                                reply,
                                seq,
                                ..Default::default()
                            };
                            let ble_ok = match writer {
                                Some(w) => w(invoke.clone()).await.is_ok(),
                                None => false,
                            };
                            if !ble_ok {
                                if let Ok(mut g) = PENDING_NOTIF_INVOKE.lock() {
                                    *g = Some(invoke);
                                }
                                if let Some(n) = crate::SYNC_NUDGE.get() {
                                    n.notify_one();
                                }
                            }
                        });
                    }
                });
            }
    (ble_notif_tx, ble_notif_writer)
}

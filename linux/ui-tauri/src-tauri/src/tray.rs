use std::collections::VecDeque;
use std::sync::Mutex;

use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    Manager,
};

use vortex_l3_daemon::core::appstate::{AppState, EarbudsInfo};

use crate::{CmdChannel, UiCmd};

pub(crate) struct BatteryMenuItem {
    pub(crate) buds: MenuItem<tauri::Wry>,
    pub(crate) phone: MenuItem<tauri::Wry>,
    pub(crate) status: MenuItem<tauri::Wry>,
    pub(crate) recent: MenuItem<tauri::Wry>,
    pub(crate) notif_toggle: MenuItem<tauri::Wry>,
    pub(crate) clip_toggle: MenuItem<tauri::Wry>,
}

struct PhoneSnap {
    name: Option<String>,
    battery: Option<u8>,
    charging: bool,
    earbuds: Option<EarbudsInfo>,
}

static LAST_PHONE: Mutex<Option<PhoneSnap>> = Mutex::new(None);

static RECENT_ALERTS: Mutex<VecDeque<(String, String)>> = Mutex::new(VecDeque::new());

const RECENT_CAP: usize = 5;

const OTP_FRESH_MS: i64 = 5 * 60 * 1000;

fn trunc_menu(s: &str, max: usize) -> String {
    if s.chars().count() > max {
        let head: String = s.chars().take(max.saturating_sub(3)).collect();
        format!("{}...", head.trim_end())
    } else {
        s.to_string()
    }
}

fn recent_label_locked(q: &VecDeque<(String, String)>) -> String {
    match q.front() {
        Some((title, summary)) => {
            let head = if !title.trim().is_empty() { title } else { summary };
            let clean = head.replace('\n', " ").trim().to_string();
            if clean.is_empty() {
                "Recent: (empty alert)".to_string()
            } else {
                format!("Recent: {}", trunc_menu(&clean, 42))
            }
        }
        None => "Recent: none".to_string(),
    }
}

pub(crate) fn push_recent_alert(app: &tauri::AppHandle, title: String, summary: String) {
    let label = {
        let mut q = RECENT_ALERTS.lock().unwrap_or_else(|p| p.into_inner());
        let t = trunc_menu(title.trim(), 64);
        let s = trunc_menu(summary.trim(), 96);
        if !t.is_empty() || !s.is_empty() {
            q.push_front((t, s));
            while q.len() > RECENT_CAP {
                q.pop_back();
            }
        }
        recent_label_locked(&q)
    };
    tracing::info!("tray: recent alert recorded");
    let app_menu = app.clone();
    let _ = app.run_on_main_thread(move || {
        if let Some(state) = app_menu.try_state::<BatteryMenuItem>() {
            let _ = state.recent.set_text(label);
        }
    });
}

pub(crate) fn show_tray_toast(title: String, body: String) {
    tauri::async_runtime::spawn(async move {
        let n = vortex_l3_daemon::core::notif_mirror::NotificationMirror {
            app: "Vortex".to_string(),
            title,
            text: body,
            ..Default::default()
        };
        let _ = vortex_l3_daemon::core::notification_display::show(&n, 0).await;
    });
}

fn latest_login_code() -> Option<String> {
    let cached = crate::sms::get_sms();
    if cached.is_empty() {
        return None;
    }
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0);
    cached
        .iter()
        .filter(|m| m.r#type == 1)
        .filter(|m| now - m.date < OTP_FRESH_MS)
        .filter_map(|m| vortex_l3_daemon::core::sms::extract_otp(&m.body).map(|c| (m.date, c)))
        .max_by_key(|(date, _)| *date)
        .map(|(_, code)| code)
}

pub(crate) fn update_battery_rows(
    app: &tauri::AppHandle,
    local_earbuds: Option<&EarbudsInfo>,
    phone: Option<&AppState>,
) {
    let mut cache = LAST_PHONE.lock().unwrap_or_else(|p| p.into_inner());
    if let Some(p) = phone {
        *cache = Some(PhoneSnap {
            name: p.name.clone(),
            battery: p.battery,
            charging: p.charging,
            earbuds: p.earbuds.clone(),
        });
    }
    let snap = &*cache;

    let pf = |v: Option<u8>| v.map(|x| format!("{x}%")).unwrap_or_else(|| "--".to_string());
    let trunc = |s: &str, max: usize| -> String {
        if s.chars().count() > max {
            let head: String = s.chars().take(max.saturating_sub(3)).collect();
            format!("{}...", head.trim_end())
        } else {
            s.to_string()
        }
    };

    let phone_buds = snap.as_ref().and_then(|s| s.earbuds.as_ref());
    let laptop_owns = local_earbuds.map(|e| e.connected).unwrap_or(false);
    let phone_has = phone_buds.map(|e| e.connected).unwrap_or(false);
    let buds_pct = if laptop_owns {
        local_earbuds.and_then(|e| e.battery)
    } else {
        phone_buds.and_then(|e| e.battery)
    };
    let owner = if laptop_owns {
        "laptop"
    } else if phone_has {
        "phone"
    } else {
        "—"
    };
    let tip = format!(
        "Vortex   Buds {} ({})   Phone {}",
        pf(buds_pct),
        owner,
        pf(snap.as_ref().and_then(|s| s.battery))
    );
    if let Some(tray) = app.tray_by_id("vortex") {
        let _ = tray.set_tooltip(Some(tip));
    }
    let buds_name = if laptop_owns {
        local_earbuds.map(|e| e.name.clone())
    } else {
        phone_buds.map(|e| e.name.clone())
    }
    .filter(|n| !n.is_empty())
    .or_else(|| vortex_l3_daemon::core::earbuds_store::load().map(|s| s.name))
    .unwrap_or_else(|| "Buds".to_string());
    let buds_text = format!("{}   {} ({})", trunc(&buds_name, 18), pf(buds_pct), owner);
    // NOTE: "(charging)" suffix marks a charging device in plain ASCII for portability.
    let phone_text = snap.as_ref().map(|s| {
        let bolt = if s.charging { " (charging)" } else { "" };
        let name = s.name.clone().filter(|n| !n.is_empty()).unwrap_or_else(|| "Phone".to_string());
        format!("{}   {}{}", trunc(&name, 18), pf(s.battery), bolt)
    });
    drop(cache);
    let app_menu = app.clone();
    let _ = app.run_on_main_thread(move || {
        if let Some(item) = app_menu.try_state::<BatteryMenuItem>() {
            let _ = item.buds.set_text(buds_text);
            if let Some(pt) = phone_text {
                let _ = item.phone.set_text(pt);
            }
        }
    });
}

fn toggle_main_visibility(app: &tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        if w.is_visible().unwrap_or(false) {
            let _ = w.hide();
        } else {
            let _ = w.show();
            let _ = w.set_focus();
        }
    }
}

pub(crate) fn setup(app: &tauri::App) -> tauri::Result<()> {
    let status_i =
        MenuItem::with_id(app, "tray_status", "Vortex — tray resident", false, None::<&str>)?;
    let recent_i = MenuItem::with_id(app, "tray_recent", "Recent: none", false, None::<&str>)?;
    let answer_i = MenuItem::with_id(app, "tray_answer", "Answer call", true, None::<&str>)?;
    let decline_i = MenuItem::with_id(app, "tray_decline", "Decline call", true, None::<&str>)?;
    let copy_code_i =
        MenuItem::with_id(app, "tray_copy_code", "Copy login code", true, None::<&str>)?;
    let buds_i = MenuItem::with_id(app, "buds_batt", "Buds   --", false, None::<&str>)?;
    let phone_i = MenuItem::with_id(app, "phone_batt", "Phone   --", false, None::<&str>)?;
    let send_files_i =
        MenuItem::with_id(app, "send_files", "Send files to phone", true, None::<&str>)?;
    let mirror_i = MenuItem::with_id(app, "mirror", "Share screen", true, None::<&str>)?;
    let clipboard_i =
        MenuItem::with_id(app, "clipboard", "Open clipboard history", true, None::<&str>)?;
    let switch_i = MenuItem::with_id(app, "switch", "Switch earbuds", true, None::<&str>)?;
    let notif_toggle_i =
        MenuItem::with_id(app, "tray_notif_toggle", "Notifications: On", true, None::<&str>)?;
    let clip_toggle_i =
        MenuItem::with_id(app, "tray_clip_toggle", "Clipboard sync: On", true, None::<&str>)?;
    let show_i = MenuItem::with_id(app, "show", "Show / Hide", true, None::<&str>)?;
    let quit_i = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(
        app,
        &[
            &status_i,
            &recent_i,
            &answer_i,
            &decline_i,
            &copy_code_i,
            &phone_i,
            &buds_i,
            &send_files_i,
            &mirror_i,
            &clipboard_i,
            &switch_i,
            &notif_toggle_i,
            &clip_toggle_i,
            &show_i,
            &quit_i,
        ],
    )?;
    app.manage(BatteryMenuItem {
        buds: buds_i,
        phone: phone_i,
        status: status_i,
        recent: recent_i,
        notif_toggle: notif_toggle_i,
        clip_toggle: clip_toggle_i,
    });
    let tray_icon = tauri::image::Image::from_bytes(include_bytes!("../icons/tray.png"))
        .unwrap_or_else(|_| app.default_window_icon().unwrap().clone());
    let _ = TrayIconBuilder::with_id("vortex")
        .icon(tray_icon)
        .tooltip("Vortex")
        .menu(&menu)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "tray_answer" => {
                // NOTE: tray compensates when the server lacks actions buttons; timeout counts as declined.
                tracing::info!("tray: answer call requested");
                tauri::async_runtime::spawn(async move { crate::call::call_accept().await });
            }
            "tray_decline" => {
                tracing::info!("tray: decline call requested");
                tauri::async_runtime::spawn(async move { crate::call::call_decline().await });
            }
            "tray_copy_code" => {
                // NOTE: conservative copy keeps OTP handling tight; stale or hint-less codes never touch the clipboard.
                match latest_login_code() {
                    Some(code) => {
                        if crate::clipboard_sync::set_local_text(&code).is_ok() {
                            show_tray_toast(
                                "Code copied".to_string(),
                                "Paste with Ctrl+V".to_string(),
                            );
                        } else {
                            tracing::warn!("tray: login-code clipboard copy failed");
                            show_tray_toast(
                                "Copy failed".to_string(),
                                "Could not reach the clipboard".to_string(),
                            );
                        }
                    }
                    None => {
                        tracing::info!("tray: copy-code with no fresh code available");
                        show_tray_toast(
                            "No login code".to_string(),
                            "No fresh verification code found".to_string(),
                        );
                    }
                }
            }
            "tray_notif_toggle" => {
                use std::sync::atomic::Ordering;
                let was = crate::notifications::NOTIF_SHOW.load(Ordering::Relaxed);
                crate::notifications::NOTIF_SHOW.store(!was, Ordering::Relaxed);
                tracing::info!(enabled = !was, "tray: notification display toggled");
                if let Some(state) = app.try_state::<BatteryMenuItem>() {
                    let label = if was { "Notifications: Off" } else { "Notifications: On" };
                    let _ = state.notif_toggle.set_text(label);
                }
            }
            "tray_clip_toggle" => {
                use std::sync::atomic::Ordering;
                let was = crate::clipboard_sync::CLIPBOARD_SYNC.load(Ordering::Relaxed);
                crate::clipboard_sync::CLIPBOARD_SYNC.store(!was, Ordering::Relaxed);
                tracing::info!(enabled = !was, "tray: clipboard sync toggled");
                if let Some(state) = app.try_state::<BatteryMenuItem>() {
                    let label = if was { "Clipboard sync: Off" } else { "Clipboard sync: On" };
                    let _ = state.clip_toggle.set_text(label);
                }
            }
            "send_files" => {
                let h = app.clone();
                tauri::async_runtime::spawn(async move {
                    let _ = crate::share::pick_and_send_files(h).await;
                });
            }
            "mirror" => {
                if let Some(ch) = app.try_state::<CmdChannel>() {
                    let _ = ch.0.send(UiCmd::StartMirror {
                        width: 720,
                        height: 1560,
                        fps: 60,
                        bitrate: 10_000_000,
                    });
                }
            }
            "clipboard" => {
                crate::clipboard_window::show_clipboard_window(app);
            }
            "switch" => {
                if let Some(ch) = app.try_state::<CmdChannel>() {
                    let _ = ch.0.send(UiCmd::ToggleEarbuds);
                }
            }
            "show" => {
                toggle_main_visibility(app);
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                // NOTE: left-click toggles resident visibility only; deleted pages are never a click target.
                toggle_main_visibility(tray.app_handle());
            }
        })
        .build(app)?;

    Ok(())
}

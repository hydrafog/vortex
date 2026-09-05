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
}

struct PhoneSnap {
    name: Option<String>,
    battery: Option<u8>,
    charging: bool,
    earbuds: Option<EarbudsInfo>,
}

static LAST_PHONE: Mutex<Option<PhoneSnap>> = Mutex::new(None);

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

pub(crate) fn setup(app: &tauri::App) -> tauri::Result<()> {
    let buds_i = MenuItem::with_id(app, "buds_batt", "Buds   --", false, None::<&str>)?;
    let phone_i = MenuItem::with_id(app, "phone_batt", "Phone   --", false, None::<&str>)?;
    let send_files_i =
        MenuItem::with_id(app, "send_files", "Send files to phone", true, None::<&str>)?;
    let mirror_i = MenuItem::with_id(app, "mirror", "Share screen", true, None::<&str>)?;
    let clipboard_i =
        MenuItem::with_id(app, "clipboard", "Open clipboard history", true, None::<&str>)?;
    let switch_i = MenuItem::with_id(app, "switch", "Switch earbuds", true, None::<&str>)?;
    let show_i = MenuItem::with_id(app, "show", "Show / Hide", true, None::<&str>)?;
    let quit_i = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(
        app,
        &[&phone_i, &buds_i, &send_files_i, &mirror_i, &clipboard_i, &switch_i, &show_i, &quit_i],
    )?;
    app.manage(BatteryMenuItem { buds: buds_i, phone: phone_i });
    let tray_icon = tauri::image::Image::from_bytes(include_bytes!("../icons/tray.png"))
        .unwrap_or_else(|_| app.default_window_icon().unwrap().clone());
    let _ = TrayIconBuilder::with_id("vortex")
        .icon(tray_icon)
        .tooltip("Vortex")
        .menu(&menu)
        .on_menu_event(|app, event| match event.id.as_ref() {
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
                if let Some(w) = app.get_webview_window("main") {
                    if w.is_visible().unwrap_or(false) {
                        let _ = w.hide();
                    } else {
                        let _ = w.show();
                        let _ = w.set_focus();
                    }
                }
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
                let app = tray.app_handle();
                if let Some(w) = app.get_webview_window("main") {
                    if w.is_visible().unwrap_or(false) {
                        let _ = w.hide();
                    } else {
                        let _ = w.show();
                        let _ = w.set_focus();
                    }
                }
            }
        })
        .build(app)?;

    Ok(())
}

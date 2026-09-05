#![allow(
    dead_code,
    clippy::too_many_arguments,
    clippy::type_complexity,
    clippy::manual_checked_ops,
    clippy::collapsible_match,
    clippy::needless_borrow,
    clippy::question_mark,
    clippy::empty_line_after_doc_comments
)]

use std::sync::mpsc::{self, Receiver};
use std::sync::{Arc, Mutex};
use std::thread;

use tauri::WindowEvent;
use tokio::sync::oneshot;

use vortex_l3_daemon::core::pairing::handshake::LocalDecision;

mod ble;
mod call;
mod call_log;
mod camera;
mod clipboard;
mod clipboard_hotkey;
mod clipboard_sync;
mod clipboard_window;
mod cmd_earbuds;
mod cmd_pairing;
mod contacts;
mod desktop_apps;
mod desktop_theme;
mod earbuds;
mod file_consent;
mod handoff;
mod ipc;
mod lan;
mod lan_state;
mod lan_wifi_direct;
mod laptop_cast;
mod live_activity;
mod media_remote;
mod mirror;
mod mirror_inject;
mod mirror_window;
mod notes;
mod notifications;
mod pairing;
mod proximity;
mod ring;
mod share;
mod sms;
mod transfers;
mod transfers_out;
mod tray;
mod universal_control;
mod virtual_display;
mod voice_settings;
mod worker;
mod worker_ctx;
mod worker_transfers;

pub(crate) use call::{
    CallWriter, CALL_CONTROL_SEQ, CALL_MIRROR_TX, CALL_WRITER, PENDING_CALL_CONTROL,
};
pub(crate) use clipboard_sync::{ClipboardImageWriter, ClipboardWriter};
pub(crate) use ipc::{app_state_to_dto, emit_peers, CmdChannel, UiCmd};
pub(crate) use notifications::{NotifWriter, ACTIVE_CHAT};

pub(crate) type SealedWriter = Arc<
    dyn Fn(
            u8,
            Vec<u8>,
        )
            -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<(), String>> + Send>>
        + Send
        + Sync,
>;

pub(crate) static MEDIA_WATCH: std::sync::OnceLock<
    Arc<vortex_l3_daemon::core::media_watch::MediaWatch>,
> = std::sync::OnceLock::new();

pub(crate) static SYNC_NUDGE: std::sync::OnceLock<Arc<tokio::sync::Notify>> =
    std::sync::OnceLock::new();

pub(crate) static BLE_RETRY_NUDGE: std::sync::OnceLock<Arc<tokio::sync::Notify>> =
    std::sync::OnceLock::new();

pub(crate) static PENDING_IMAGE_TOKEN: std::sync::OnceLock<Mutex<Option<String>>> =
    std::sync::OnceLock::new();

pub(crate) static PENDING_FILE_OFFERS: std::sync::OnceLock<
    Mutex<std::collections::VecDeque<(String, String, String, u64)>>,
> = std::sync::OnceLock::new();

#[derive(Default)]
pub(crate) struct PairDecisionState(pub(crate) Mutex<Option<oneshot::Sender<LocalDecision>>>);

pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let (cmd_tx, cmd_rx) = mpsc::channel::<UiCmd>();
    {
        let hb_tx = cmd_tx.clone();
        thread::spawn(move || loop {
            thread::sleep(std::time::Duration::from_secs(5));
            if hb_tx.send(UiCmd::RefreshLocalEarbuds).is_err() {
                break;
            }
        });
    }
    let cmd_channel = CmdChannel(cmd_tx);

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            use tauri::{Emitter, Manager};
            tracing::info!(?argv, "single-instance: second launch forwarded");
            if let Some(pos) = argv.iter().position(|a| a == "--share") {
                let paths: Vec<String> = argv[pos + 1..].to_vec();
                let _ = share::handle_share(app, paths);
            } else if let Some(pos) = argv.iter().position(|a| a == "--call") {
                if let Some(number) = argv.get(pos + 1).cloned() {
                    tauri::async_runtime::spawn(async move { call::dial(number).await });
                }
            } else if argv.iter().any(|a| a == "--call-answer") {
                tauri::async_runtime::spawn(async move { call::call_accept().await });
            } else if argv.iter().any(|a| a == "--call-decline") {
                tauri::async_runtime::spawn(async move { call::call_decline().await });
            } else if let Some(pos) = argv.iter().position(|a| a == "--sms-send") {
                if let (Some(number), Some(body)) =
                    (argv.get(pos + 1).cloned(), argv.get(pos + 2).cloned())
                {
                    if let Some(w) = app.get_webview_window("main") {
                        let _ = w.emit("vortex:open-sms", serde_json::json!({ "number": number }));
                        let _ = w.show();
                        let _ = w.set_focus();
                    }
                    tauri::async_runtime::spawn(async move { call::send_sms(number, body).await });
                }
            } else if let Some(pos) = argv.iter().position(|a| a == "--sms") {
                if let Some(number) = argv.get(pos + 1).cloned() {
                    if let Some(w) = app.get_webview_window("main") {
                        let _ = w.emit("vortex:open-sms", serde_json::json!({ "number": number }));
                        let _ = w.show();
                        let _ = w.set_focus();
                    }
                }
            } else if argv.iter().any(|a| a == "--clipboard") {
                clipboard_window::show_clipboard_window(app);
            } else if argv.iter().any(|a| a == "--mirror") {
                if let Some(ch) = app.try_state::<ipc::CmdChannel>() {
                    let _ = ch.0.send(ipc::UiCmd::StartMirror {
                        width: 720,
                        height: 1560,
                        fps: 60,
                        bitrate: 10_000_000,
                    });
                }
            } else if argv.iter().any(|a| a == "--camera") {
                camera::set_camera_request(true);
            } else if argv.iter().any(|a| a == "--camera-stop") {
                camera::set_camera_request(false);
            } else if argv.iter().any(|a| a == "--mirror-stop") {
                if let Some(ch) = app.try_state::<ipc::CmdChannel>() {
                    let _ = ch.0.send(ipc::UiCmd::StopMirror);
                }
            } else if let Some(w) = app.get_webview_window("main") {
                let _ = w.show();
                let _ = w.set_focus();
            }
        }))
        .plugin(
            tauri_plugin_window_state::Builder::default()
                .with_state_flags(tauri_plugin_window_state::StateFlags::POSITION)
                .build(),
        )
        .manage(cmd_channel)
        .manage(PairDecisionState::default())
        .setup(move |app| {
            let handle = app.handle().clone();
            let rx_holder: Arc<Mutex<Option<Receiver<UiCmd>>>> = Arc::new(Mutex::new(Some(cmd_rx)));
            let rx_holder_for_thread = rx_holder.clone();
            thread::spawn(move || {
                let rx = rx_holder_for_thread.lock().unwrap().take().unwrap();
                worker::run_worker(handle, rx);
            });

            tray::setup(app)?;

            {
                use tauri::Manager;
                let special = std::env::args()
                    .any(|a| a == "--hidden" || a == "--clipboard" || a == "--share");
                if !special {
                    if let Some(w) = app.get_webview_window("main") {
                        let _ = w.show();
                        let _ = w.set_focus();
                    }
                }
            }

            thread::spawn(|| {
                let _ = clipboard_hotkey::set_clipboard_hotkey("<Super>v".to_string());
            });

            universal_control::restore(app.handle().clone());

            if std::env::args().any(|a| a == "--clipboard") {
                clipboard_window::show_clipboard_window(app.handle());
            } else {
                let h = app.handle().clone();
                thread::spawn(move || {
                    thread::sleep(std::time::Duration::from_secs(3));
                    let inner = h.clone();
                    let _ = h.run_on_main_thread(move || {
                        clipboard_window::prewarm(&inner);
                    });
                });
            }

            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                let _ = window.hide();
                api.prevent_close();
            }
        })
        .invoke_handler(tauri::generate_handler![
            worker::start_scan,
            worker::refresh_state,
            ipc::get_peer_states,
            worker::start_screen_mirror,
            worker::stop_screen_mirror,
            pairing::start_pair,
            pairing::pair_decision,
            pairing::forget_peer,
            pairing::forget_all,
            earbuds::refresh_local_earbuds,
            earbuds::open_bluetooth_settings,
            earbuds::scan_bluetooth_devices,
            earbuds::save_earbuds,
            earbuds::clear_earbuds,
            earbuds::get_saved_earbuds,
            earbuds::request_earbuds_switch,
            earbuds::send_earbuds_claim,
            earbuds::set_smart_switch_enabled,
            earbuds::get_smart_switch_enabled,
            notifications::set_notif_mirror_show,
            notifications::get_notif_mirror_show,
            notifications::set_notif_mirror_send,
            notifications::get_notif_mirror_send,
            proximity::get_proximity_settings,
            proximity::set_proximity_settings,
            clipboard::clipboard_history,
            clipboard::clipboard_capture_now,
            clipboard_sync::set_clipboard_sync,
            clipboard_sync::get_clipboard_sync,
            file_consent::set_file_auto_accept,
            file_consent::get_file_auto_accept,
            clipboard::clipboard_get,
            clipboard_window::clipboard_set_preview,
            clipboard::clipboard_select,
            clipboard::clipboard_pin,
            clipboard::clipboard_delete,
            clipboard_window::clipboard_hide,
            contacts::get_contacts,
            call_log::get_call_log,
            call_log::get_call_log_history,
            sms::get_sms,
            sms::get_sms_history,
            notifications::set_active_chat,
            call::dial,
            call::send_sms,
            call::mark_sms_read,
            call::load_sms_thread,
            camera::set_camera_request,
            camera::set_camera_facing,
            ring::ring_phone,
            notes::get_notes,
            notes::upsert_note,
            notes::toggle_todo,
            notes::delete_note,
            voice_settings::set_voice_lang,
            universal_control::uc_start,
            universal_control::uc_stop,
            universal_control::uc_running,
            laptop_cast::set_extend_mode,
            laptop_cast::get_extend_mode,
            universal_control::uc_set_placement,
            universal_control::uc_get_placement,
            share::send_files,
            share::pick_and_send_files,
            desktop_theme::get_system_accent_color,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Vortex Tauri");
}


use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};

use tauri::{AppHandle, Manager};

const LIST_W: f64 = 462.0;
const PREVIEW_W: f64 = 440.0;
const WIDE_W: f64 = LIST_W + PREVIEW_W;

const H_FRACTION: f64 = 0.62;
const H_MIN: f64 = 560.0;
const H_MAX: f64 = 920.0;
const TOP_BIAS: f64 = 0.38;

static LIST_FOCUSED: AtomicBool = AtomicBool::new(false);

static POPUP_H: AtomicU64 = AtomicU64::new(0);

static CLIP_XID: AtomicU32 = AtomicU32::new(0);

fn popup_h() -> f64 {
    match POPUP_H.load(Ordering::Relaxed) {
        0 => H_MIN,
        bits => f64::from_bits(bits),
    }
}

fn hide_popup(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("clipboard") {
        let _ = w.hide();
    }
}

fn panel_geometry(app: &AppHandle) -> (f64, f64, f64) {
    let monitor = app
        .cursor_position()
        .ok()
        .and_then(|p| app.monitor_from_point(p.x, p.y).ok().flatten())
        .or_else(|| app.primary_monitor().ok().flatten());
    let Some(m) = monitor else {
        return (H_MIN, 0.0, 0.0);
    };
    let scale = m.scale_factor();
    let logical_h = m.size().height as f64 / scale;
    let logical_w = m.size().width as f64 / scale;
    let origin_x = m.position().x as f64 / scale;
    let origin_y = m.position().y as f64 / scale;
    let height = (logical_h * H_FRACTION)
        .clamp(H_MIN, H_MAX)
        .min(logical_h - 80.0);
    let x = origin_x + (logical_w - LIST_W) / 2.0;
    let y = origin_y + (logical_h - height) * TOP_BIAS;
    (height, x, y)
}

fn force_x11_focus() {
    std::thread::spawn(|| {
        use x11rb::connection::Connection;
        use x11rb::protocol::xproto::{
            AtomEnum, ConfigureWindowAux, ConnectionExt, InputFocus, MapState, StackMode,
        };

        fn find(conn: &impl Connection, win: u32, target: &str) -> Option<u32> {
            if let Ok(r) =
                conn.get_property(false, win, AtomEnum::WM_NAME, AtomEnum::STRING, 0, 1024)
            {
                if let Ok(r) = r.reply() {
                    if !r.value.is_empty()
                        && String::from_utf8_lossy(&r.value).contains(target)
                    {
                        return Some(win);
                    }
                }
            }
            let tree = conn.query_tree(win).ok()?.reply().ok()?;
            for child in tree.children {
                if let Some(w) = find(conn, child, target) {
                    return Some(w);
                }
            }
            None
        }

        fn owns(conn: &impl Connection, ancestor: u32, mut win: u32) -> bool {
            for _ in 0..8 {
                if win == ancestor {
                    return true;
                }
                let Some(parent) = conn
                    .query_tree(win)
                    .ok()
                    .and_then(|c| c.reply().ok())
                    .map(|r| r.parent)
                else {
                    return false;
                };
                if parent == 0 || parent == win {
                    return false;
                }
                win = parent;
            }
            false
        }

        let Ok((conn, screen)) = x11rb::connect(None) else {
            return;
        };
        let root = conn.setup().roots[screen].root;

        let mut win = CLIP_XID.load(Ordering::Relaxed);
        if win == 0 {
            for _ in 0..25 {
                std::thread::sleep(std::time::Duration::from_millis(40));
                if let Some(w) = find(&conn, root, "Vortex Clipboard") {
                    win = w;
                    CLIP_XID.store(w, Ordering::Relaxed);
                    break;
                }
            }
            if win == 0 {
                tracing::warn!("clipboard popup: X window never appeared; focus not forced");
                return;
            }
        }

        for attempt in 0..40 {
            if attempt > 0 {
                std::thread::sleep(std::time::Duration::from_millis(25));
            }
            let viewable = conn
                .get_window_attributes(win)
                .ok()
                .and_then(|c| c.reply().ok())
                .map(|a| a.map_state == MapState::VIEWABLE)
                .unwrap_or(false);
            if !viewable {
                continue;
            }
            let _ = conn
                .configure_window(win, &ConfigureWindowAux::new().stack_mode(StackMode::ABOVE));
            let _ = conn.set_input_focus(InputFocus::PARENT, win, x11rb::CURRENT_TIME);
            let _ = conn.flush();
            let focused = conn
                .get_input_focus()
                .ok()
                .and_then(|c| c.reply().ok())
                .map(|r| r.focus)
                .unwrap_or(0);
            if focused != 0 && owns(&conn, win, focused) {
                return;
            }
        }
        tracing::warn!("clipboard popup: X never confirmed keyboard focus");
    });
}

fn schedule_group_hide(app: &AppHandle) {
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(160));
        if !LIST_FOCUSED.load(Ordering::SeqCst) {
            hide_popup(&app);
        }
    });
}

fn build(app: &AppHandle, visible: bool) -> bool {
    match tauri::WebviewWindowBuilder::new(
        app,
        "clipboard",
        tauri::WebviewUrl::App("index.html#/clipboard".into()),
    )
    .title("Vortex Clipboard")
    .inner_size(LIST_W, popup_h())
    .min_inner_size(LIST_W, H_MIN)
    .max_inner_size(WIDE_W, H_MAX)
    .decorations(false)
    .transparent(true)
    .always_on_top(true)
    .skip_taskbar(true)
    .visible(visible)
    .focused(visible)
    .center()
    .build()
    {
        Ok(win) => {
            tracing::info!(visible, "clipboard popup: created");
            let app2 = app.clone();
            win.on_window_event(move |ev| {
                if let tauri::WindowEvent::Focused(f) = ev {
                    LIST_FOCUSED.store(*f, Ordering::SeqCst);
                    if !*f {
                        schedule_group_hide(&app2);
                    }
                }
            });
            true
        }
        Err(e) => {
            tracing::error!("clipboard popup: build failed: {e}");
            false
        }
    }
}

pub(crate) fn prewarm(app: &AppHandle) {
    if app.get_webview_window("clipboard").is_some() {
        return;
    }
    build(app, false);
}

pub(crate) fn show_clipboard_window(app: &AppHandle) {
    let (h, x, y) = panel_geometry(app);
    POPUP_H.store(h.to_bits(), Ordering::Relaxed);

    if app.get_webview_window("clipboard").is_none() && !build(app, true) {
        return;
    }
    let Some(w) = app.get_webview_window("clipboard") else {
        return;
    };
    let _ = w.unminimize();
    let _ = w.set_always_on_top(true);
    let _ = w.set_size(tauri::LogicalSize::new(LIST_W, h));
    let _ = w.set_position(tauri::LogicalPosition::new(x, y));
    let _ = w.show();
    let _ = w.set_focus();
    force_x11_focus();
    let _ = w.eval("window.__vortexRearm && window.__vortexRearm()");
    tracing::info!("clipboard popup: shown");
}

#[tauri::command]
pub fn clipboard_hide(app: AppHandle) {
    hide_popup(&app);
}

#[tauri::command]
pub fn clipboard_set_preview(app: AppHandle, visible: bool) {
    if let Some(w) = app.get_webview_window("clipboard") {
        let width = if visible { WIDE_W } else { LIST_W };
        let _ = w.set_size(tauri::LogicalSize::new(width, popup_h()));
    }
}

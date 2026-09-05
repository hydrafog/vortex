use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::time::Duration;

use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;
use tauri::{AppHandle, Emitter};
use tokio::sync::mpsc;

use vortex_l3_daemon::core::crypto::x25519::X25519SecBytes;
use vortex_l3_daemon::core::mirror_session::{start_mirror_session, MirrorHandle, MirrorStart};
use vortex_l3_daemon::core::{mirror_tcp, mirror_udp};

static MIRROR_HANDLE: std::sync::Mutex<Option<MirrorHandle>> = std::sync::Mutex::new(None);

static VIDEO_RX_TASK: std::sync::Mutex<Option<tokio::task::JoinHandle<()>>> =
    std::sync::Mutex::new(None);

fn set_video_rx_task(task: tokio::task::JoinHandle<()>) {
    if let Ok(mut g) = VIDEO_RX_TASK.lock() {
        if let Some(prev) = g.take() {
            prev.abort();
        }
        *g = Some(task);
    }
}

fn abort_video_rx_task() {
    let prev = VIDEO_RX_TASK.lock().ok().and_then(|mut g| g.take());
    if let Some(t) = prev {
        t.abort();
    }
}

static MIRROR_PIPELINE: std::sync::Mutex<Option<gst::Pipeline>> = std::sync::Mutex::new(None);

static MIRROR_TITLE: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

static INPUT_TX: std::sync::Mutex<Option<mpsc::Sender<Vec<u8>>>> = std::sync::Mutex::new(None);

static POINTER_DOWN: AtomicBool = AtomicBool::new(false);

static SCROLL_ACTIVE: AtomicBool = AtomicBool::new(false);
static SCROLL_FINGER_X: AtomicI32 = AtomicI32::new(0);
static SCROLL_FINGER_Y: AtomicI32 = AtomicI32::new(0);
static SCROLL_WATCHER: AtomicBool = AtomicBool::new(false);

static CTRL_HELD: AtomicBool = AtomicBool::new(false);
static PINCH_ACTIVE: AtomicBool = AtomicBool::new(false);
static PINCH_CX: AtomicI32 = AtomicI32::new(0);
static PINCH_CY: AtomicI32 = AtomicI32::new(0);
static PINCH_SPREAD: AtomicI32 = AtomicI32::new(0);
const PINCH_INIT: i32 = 5_000;
const PINCH_NOTCH: i32 = 1_600;
const PINCH_MIN: i32 = 800;
const PINCH_MAX: i32 = 30_000;

const NOTCH_STEP: i32 = 1_500;

const NOTCH_STEP_X: i32 = 6_000;

mod input_proto {
    pub const DOWN: u8 = 0;
    pub const MOVE: u8 = 1;
    pub const UP: u8 = 2;
    pub const BACK: u8 = 10;
    #[allow(dead_code)]
    pub const HOME: u8 = 11;
    #[allow(dead_code)]
    pub const RECENTS: u8 = 12;
}

fn scroll_step(delta: f64, notch: i32) -> i32 {
    (delta.clamp(-1.0, 1.0) * notch as f64) as i32
}

fn send_input(ty: u8, nx: u16, ny: u16) {
    if crate::mirror_inject::active() && crate::mirror_inject::touch_available() {
        let cmd = match ty {
            input_proto::DOWN => format!("D 0 {nx} {ny}"),
            input_proto::MOVE => format!("M 0 {nx} {ny}"),
            input_proto::UP => "U 0".to_string(),
            input_proto::BACK => "K back".to_string(),
            input_proto::HOME => "K home".to_string(),
            input_proto::RECENTS => "K recents".to_string(),
            _ => return,
        };
        crate::mirror_inject::send(&cmd);
        return;
    }
    let pkt = vec![ty, (nx >> 8) as u8, nx as u8, (ny >> 8) as u8, ny as u8];
    if let Ok(g) = INPUT_TX.lock() {
        if let Some(tx) = g.as_ref() {
            let _ = tx.try_send(pkt);
        }
    }
}

pub(crate) fn on_button(down: bool, nx: u16, ny: u16) {
    if down {
        flush_scroll();
        flush_pinch();
        POINTER_DOWN.store(true, Ordering::Relaxed);
        send_input(input_proto::DOWN, nx, ny);
    } else if POINTER_DOWN.swap(false, Ordering::Relaxed) {
        send_input(input_proto::UP, nx, ny);
    }
}

pub(crate) fn on_motion(nx: u16, ny: u16) {
    if POINTER_DOWN.load(Ordering::Relaxed) {
        send_input(input_proto::MOVE, nx, ny);
    }
}

pub(crate) fn on_scroll(nx: u16, ny: u16, dx: f64, dy: f64) {
    if CTRL_HELD.load(Ordering::Relaxed) {
        if dy != 0.0 {
            feed_pinch(nx, ny, if dy < 0.0 { 1 } else { -1 });
        }
        return;
    }
    if dx != 0.0 || dy != 0.0 {
        feed_scroll(nx, ny, scroll_step(dx, NOTCH_STEP_X), scroll_step(-dy, NOTCH_STEP));
    }
}

pub(crate) fn on_key(down: bool, key: &str) {
    if key == "Control_L" || key == "Control_R" {
        CTRL_HELD.store(down, Ordering::Relaxed);
    } else if down {
        inject_key(key);
    }
}

const LETTER_CODES: [u16; 26] = [
    30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45,
    21, 44,
];
const DIGIT_CODES: [u16; 10] = [11, 2, 3, 4, 5, 6, 7, 8, 9, 10];
const KEY_LEFTSHIFT: u16 = 42;

fn key_to_linux(key: &str) -> Option<(u16, bool)> {
    if key.chars().count() == 1 {
        let ch = key.chars().next().unwrap();
        if ch.is_ascii_alphabetic() {
            return Some((
                LETTER_CODES[(ch.to_ascii_lowercase() as u8 - b'a') as usize],
                ch.is_ascii_uppercase(),
            ));
        }
        if ch.is_ascii_digit() {
            return Some((DIGIT_CODES[(ch as u8 - b'0') as usize], false));
        }
        return match ch {
            ' ' => Some((57, false)),
            '.' => Some((52, false)),
            ',' => Some((51, false)),
            '-' => Some((12, false)),
            '=' => Some((13, false)),
            '/' => Some((53, false)),
            ';' => Some((39, false)),
            '\'' => Some((40, false)),
            '@' => Some((3, true)),
            '!' => Some((2, true)),
            '?' => Some((53, true)),
            ':' => Some((39, true)),
            '_' => Some((12, true)),
            _ => None,
        };
    }
    match key {
        "space" => Some((57, false)),
        "Return" | "KP_Enter" => Some((28, false)),
        "BackSpace" => Some((14, false)),
        "Tab" => Some((15, false)),
        "Left" => Some((105, false)),
        "Right" => Some((106, false)),
        "Up" => Some((103, false)),
        "Down" => Some((108, false)),
        "period" => Some((52, false)),
        "comma" => Some((51, false)),
        "minus" => Some((12, false)),
        _ => None,
    }
}

fn inject_key(key: &str) {
    if key == "Escape" {
        send_input(input_proto::BACK, 0, 0);
        return;
    }
    if !crate::mirror_inject::active() {
        return;
    }
    if let Some((code, shift)) = key_to_linux(key) {
        if shift {
            crate::mirror_inject::send(&format!("E {KEY_LEFTSHIFT} 1"));
        }
        crate::mirror_inject::send(&format!("E {code} 1"));
        crate::mirror_inject::send(&format!("E {code} 0"));
        if shift {
            crate::mirror_inject::send(&format!("E {KEY_LEFTSHIFT} 0"));
        }
    }
}

fn feed_scroll(nx: u16, ny: u16, step_x: i32, step_y: i32) {
    ensure_scroll_watcher();
    if !SCROLL_ACTIVE.swap(true, Ordering::Relaxed) {
        tracing::info!(nx, ny, "mirror: scroll-drag started");
        SCROLL_FINGER_X.store(nx as i32, Ordering::Relaxed);
        SCROLL_FINGER_Y.store(ny as i32, Ordering::Relaxed);
        send_input(input_proto::DOWN, nx, ny);
    }
    let new_x = (SCROLL_FINGER_X.load(Ordering::Relaxed) + step_x).clamp(0, 65_535);
    let new_y = (SCROLL_FINGER_Y.load(Ordering::Relaxed) + step_y).clamp(0, 65_535);
    SCROLL_FINGER_X.store(new_x, Ordering::Relaxed);
    SCROLL_FINGER_Y.store(new_y, Ordering::Relaxed);
}

fn flush_scroll() {
    if SCROLL_ACTIVE.swap(false, Ordering::Relaxed) {
        let fx = SCROLL_FINGER_X.load(Ordering::Relaxed).clamp(0, 65_535) as u16;
        let fy = SCROLL_FINGER_Y.load(Ordering::Relaxed).clamp(0, 65_535) as u16;
        send_input(input_proto::UP, fx, fy);
    }
}

fn feed_pinch(cx: u16, cy: u16, dir: i32) {
    if !crate::mirror_inject::active() {
        return;
    }
    ensure_scroll_watcher();
    if !PINCH_ACTIVE.swap(true, Ordering::Relaxed) {
        flush_scroll();
        PINCH_CX.store(cx as i32, Ordering::Relaxed);
        PINCH_CY.store(cy as i32, Ordering::Relaxed);
        PINCH_SPREAD.store(PINCH_INIT, Ordering::Relaxed);
        let top = (cy as i32 - PINCH_INIT).clamp(0, 65_535);
        let bot = (cy as i32 + PINCH_INIT).clamp(0, 65_535);
        crate::mirror_inject::send(&format!("D 0 {cx} {top}"));
        crate::mirror_inject::send(&format!("D 1 {cx} {bot}"));
    }
    let s = (PINCH_SPREAD.load(Ordering::Relaxed) + dir * PINCH_NOTCH).clamp(PINCH_MIN, PINCH_MAX);
    PINCH_SPREAD.store(s, Ordering::Relaxed);
}

fn flush_pinch() {
    if PINCH_ACTIVE.swap(false, Ordering::Relaxed) {
        crate::mirror_inject::send("U 0");
        crate::mirror_inject::send("U 1");
    }
}

fn ensure_scroll_watcher() {
    if SCROLL_WATCHER.swap(true, Ordering::Relaxed) {
        return;
    }
    std::thread::spawn(|| {
        const IDLE_TICKS: u32 = 12;
        let mut last_x = -1i32;
        let mut last_y = -1i32;
        let mut last_spread = i32::MIN;
        let mut idle = 0u32;
        loop {
            std::thread::sleep(Duration::from_millis(16));
            if SCROLL_ACTIVE.load(Ordering::Relaxed) {
                let tx = SCROLL_FINGER_X.load(Ordering::Relaxed);
                let ty = SCROLL_FINGER_Y.load(Ordering::Relaxed);
                if tx != last_x || ty != last_y {
                    send_input(
                        input_proto::MOVE,
                        tx.clamp(0, 65_535) as u16,
                        ty.clamp(0, 65_535) as u16,
                    );
                    last_x = tx;
                    last_y = ty;
                    idle = 0;
                } else {
                    idle += 1;
                    if idle >= IDLE_TICKS {
                        flush_scroll();
                        idle = 0;
                    }
                }
            } else if PINCH_ACTIVE.load(Ordering::Relaxed) {
                let sp = PINCH_SPREAD.load(Ordering::Relaxed);
                if sp != last_spread {
                    let cx = PINCH_CX.load(Ordering::Relaxed);
                    let cy = PINCH_CY.load(Ordering::Relaxed);
                    let top = (cy - sp).clamp(0, 65_535);
                    let bot = (cy + sp).clamp(0, 65_535);
                    crate::mirror_inject::send(&format!("M 0 {cx} {top}"));
                    crate::mirror_inject::send(&format!("M 1 {cx} {bot}"));
                    last_spread = sp;
                    idle = 0;
                } else {
                    idle += 1;
                    if idle >= IDLE_TICKS {
                        flush_pinch();
                        idle = 0;
                    }
                }
            } else {
                last_x = -1;
                last_y = -1;
                last_spread = i32::MIN;
                idle = 0;
            }
        }
    });
}

const PACE_LADDER: [i32; 7] = [24, 30, 40, 48, 60, 90, 120];

const PACE_RAISE_WINDOWS: usize = 3;

fn pace_grid_for(rate: f64) -> i32 {
    *PACE_LADDER.iter().rev().find(|&&g| (g as f64) <= rate + 2.0).unwrap_or(&PACE_LADDER[0])
}

fn next_pace_grid(current: i32, history: &[f64]) -> Option<i32> {
    let slowest = history.iter().cloned().fold(f64::INFINITY, f64::min);
    let want = pace_grid_for(slowest);
    if want < current {
        return Some(want);
    }
    if want > current
        && history.len() >= PACE_RAISE_WINDOWS
        && history.iter().all(|&r| pace_grid_for(r) >= want)
    {
        return Some(want);
    }
    None
}

fn set_pace_grid(pipeline: &gst::Pipeline, fps: i32) {
    let Some(pace) = pipeline.by_name("pacecaps") else { return };
    let caps =
        gst::Caps::builder("video/x-raw").field("framerate", gst::Fraction::new(fps, 1)).build();
    pace.set_property("caps", &caps);
    tracing::info!(fps, "mirror: pacing grid retuned to the phone's delivery rate");
}

fn attach_cadence_probe(pipeline: &gst::Pipeline) {
    let Some(vsink) = pipeline.by_name("vsink") else { return };
    let Some(pad) = vsink.static_pad("sink") else { return };
    let state = std::sync::Mutex::new((None::<std::time::Instant>, Vec::<f64>::new()));
    pad.add_probe(gst::PadProbeType::BUFFER, move |_pad, _info| {
        let now = std::time::Instant::now();
        if let Ok(mut g) = state.lock() {
            if let Some(prev) = g.0.replace(now) {
                g.1.push(now.duration_since(prev).as_secs_f64() * 1000.0);
            }
            if g.1.len() >= 150 {
                let gaps = std::mem::take(&mut g.1);
                let n = gaps.len() as f64;
                let mean = gaps.iter().sum::<f64>() / n;
                let sd = (gaps.iter().map(|g| (g - mean).powi(2)).sum::<f64>() / n).sqrt();
                let worst = gaps.iter().cloned().fold(0.0_f64, f64::max);
                tracing::debug!(
                    fps = format!("{:.1}", 1000.0 / mean),
                    gap_ms = format!("{mean:.1}"),
                    jitter_ms = format!("{sd:.1}"),
                    worst_ms = format!("{worst:.0}"),
                    "mirror: display cadence"
                );
            }
        }
        gst::PadProbeReturn::Ok
    });
}

fn has_drm_render_node() -> bool {
    std::fs::read_dir("/dev/dri")
        .map(|rd| rd.flatten().any(|e| e.file_name().to_string_lossy().starts_with("renderD")))
        .unwrap_or(false)
}

pub fn detect_decoder_backend() -> &'static str {
    let _ = gst::init();
    if std::process::Command::new("nvidia-smi")
        .arg("-L")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
        && gst::ElementFactory::find("nvh265dec").is_some()
    {
        return "nvdec";
    }
    if has_drm_render_node() && gst::ElementFactory::find("vah265dec").is_some() {
        return "vaapi";
    }
    "software"
}

fn ensure_video_sink() {
    if gst::ElementFactory::find("gtksink").is_some()
        || gst::ElementFactory::find("gtkwaylandsink").is_some()
    {
        return;
    }
    tracing::info!("mirror: gtksink/gtkwaylandsink not found; searching plugin directories");

    let registry = gst::Registry::get();
    let mut candidate_dirs: Vec<std::path::PathBuf> = Vec::new();

    if let Ok(paths) = std::env::var("GST_PLUGIN_SYSTEM_PATH_1_0") {
        for p in std::env::split_paths(&paths) {
            candidate_dirs.push(p);
        }
    }
    if let Ok(paths) = std::env::var("GST_PLUGIN_PATH_1_0") {
        for p in std::env::split_paths(&paths) {
            candidate_dirs.push(p);
        }
    }
    if let Ok(paths) = std::env::var("LD_LIBRARY_PATH") {
        for p in std::env::split_paths(&paths) {
            let gst_dir = p.join("gstreamer-1.0");
            if gst_dir.is_dir() {
                candidate_dirs.push(gst_dir);
            }
        }
    }

    candidate_dirs.push(std::path::PathBuf::from("/run/current-system/sw/lib/gstreamer-1.0"));
    candidate_dirs.push(std::path::PathBuf::from("/usr/lib/gstreamer-1.0"));
    candidate_dirs.push(std::path::PathBuf::from("/usr/lib/x86_64-linux-gnu/gstreamer-1.0"));
    candidate_dirs.push(std::path::PathBuf::from("/usr/local/lib/gstreamer-1.0"));

    if std::path::Path::new("/nix/store").is_dir() {
        if let Ok(entries) = std::fs::read_dir("/nix/store") {
            for entry in entries.flatten() {
                let path = entry.path();
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    if (name.contains("gst-plugins-good") || name.contains("gst-plugins-bad"))
                        && !name.ends_with(".drv")
                    {
                        let gst_dir = path.join("lib/gstreamer-1.0");
                        if gst_dir.join("libgstgtk.so").exists()
                            || gst_dir.join("libgstgtkwayland.so").exists()
                        {
                            candidate_dirs.push(gst_dir);
                        }
                    }
                }
            }
        }
    }

    for dir in candidate_dirs {
        if dir.is_dir() {
            let _ = registry.scan_path(&dir);
            if gst::ElementFactory::find("gtksink").is_some()
                || gst::ElementFactory::find("gtkwaylandsink").is_some()
            {
                tracing::info!(dir = ?dir, "mirror: loaded GTK video sink successfully");
                return;
            }
        }
    }

    tracing::warn!("mirror: no GTK video sink found after scanning candidate paths");
}

fn resolve_video_sink(paced: bool) -> String {
    ensure_video_sink();
    let (sink_elem, has_aspect) = if gst::ElementFactory::find("gtksink").is_some() {
        ("gtksink", true)
    } else if gst::ElementFactory::find("gtkwaylandsink").is_some() {
        ("gtkwaylandsink", false)
    } else {
        ("gtksink", true)
    };

    if paced {
        if has_aspect {
            format!("videorate ! capsfilter name=pacecaps caps=video/x-raw,framerate=30/1 ! {sink_elem} name=vsink sync=true force-aspect-ratio=true")
        } else {
            format!("videorate ! capsfilter name=pacecaps caps=video/x-raw,framerate=30/1 ! {sink_elem} name=vsink sync=true")
        }
    } else {
        if has_aspect {
            format!("{sink_elem} name=vsink sync=false force-aspect-ratio=true")
        } else {
            format!("{sink_elem} name=vsink sync=false")
        }
    }
}

fn pipeline_string_for_backend(backend: &str, _disp_w: u32, _disp_h: u32) -> String {
    let paced = !std::env::var("VORTEX_MIRROR_PACE").is_ok_and(|v| v == "0");
    let sink = resolve_video_sink(paced);
    match backend {
        "nvdec" => format!(
            "appsrc name=src is-live=true format=time block=true max-bytes=1048576 do-timestamp=true \
             caps=video/x-h265,stream-format=byte-stream,alignment=au ! \
             queue max-size-buffers=4 max-size-bytes=4194304 max-size-time=0 ! \
             h265parse name=parser config-interval=-1 ! \
             nvh265dec ! videoconvert ! \
             queue name=postq leaky=downstream max-size-buffers=1 max-size-bytes=0 max-size-time=0 ! \
             {sink}"
        ),
        "vaapi" => format!(
            "appsrc name=src is-live=true format=time block=true max-bytes=1048576 do-timestamp=true \
             caps=video/x-h265,stream-format=byte-stream,alignment=au ! \
             queue max-size-buffers=4 max-size-bytes=4194304 max-size-time=0 ! \
             h265parse name=parser config-interval=-1 ! \
             vah265dec ! videoconvert ! \
             queue name=postq leaky=downstream max-size-buffers=1 max-size-bytes=0 max-size-time=0 ! \
             {sink}"
        ),
        _ => format!(
            "appsrc name=src is-live=true format=time block=true max-bytes=1048576 do-timestamp=true \
             caps=video/x-h265,stream-format=byte-stream,alignment=au ! \
             queue max-size-buffers=4 max-size-bytes=4194304 max-size-time=0 ! \
             h265parse name=parser config-interval=-1 ! \
             avdec_h265 max-threads=4 ! videoconvert ! \
             queue name=postq leaky=downstream max-size-buffers=1 max-size-bytes=0 max-size-time=0 ! \
             {sink}"
        ),
    }
}

fn window_size(app: &AppHandle, vw: u32, vh: u32) -> (u32, u32) {
    use tauri::Manager;
    let logical_h = app
        .get_webview_window("main")
        .and_then(|w| w.current_monitor().ok().flatten())
        .or_else(|| app.primary_monitor().ok().flatten())
        .map(|m| m.size().height as f64 / m.scale_factor())
        .filter(|h| *h > 0.0)
        .unwrap_or(900.0);
    let max_h = (logical_h * 0.65).min(900.0).min(vh as f64).max(240.0);
    let scale = max_h / vh as f64;
    let mut w = (vw as f64 * scale).round() as u32;
    let mut h = max_h.round() as u32;
    w &= !1;
    h &= !1;
    (w.max(2), h.max(2))
}

fn cleanup_session() {
    flush_scroll();
    flush_pinch();
    crate::mirror_inject::stop();
    if let Ok(mut g) = INPUT_TX.lock() {
        *g = None;
    }
    POINTER_DOWN.store(false, Ordering::Relaxed);
    SCROLL_ACTIVE.store(false, Ordering::Relaxed);
    stop_pipeline();
    crate::mirror_window::close();
    if let Ok(mut g) = MIRROR_HANDLE.lock() {
        *g = None;
    }
}

pub(crate) fn request_stop() {
    tauri::async_runtime::spawn(async {
        stop_mirror().await;
    });
}

fn stop_pipeline() {
    if let Some(p) = MIRROR_PIPELINE.lock().ok().and_then(|mut g| g.take()) {
        let _ = p.set_state(gst::State::Null);
    }
}

fn spawn_gstreamer_player(
    app: &AppHandle,
    backend: &str,
    vw: u32,
    vh: u32,
) -> Result<(gst::Pipeline, gst_app::AppSrc), String> {
    gst::init().map_err(|e| format!("gst init: {e}"))?;

    let (disp_w, disp_h) = (vw, vh);
    tracing::info!(vw, vh, "mirror: decoding at source size, XVideo scales to fit");
    let pipeline_str = pipeline_string_for_backend(backend, disp_w, disp_h);
    let element = gst::parse::launch(&pipeline_str).map_err(|e| format!("gst parse: {e}"))?;
    let pipeline =
        element.downcast::<gst::Pipeline>().map_err(|_| "pipeline downcast".to_string())?;
    let appsrc = pipeline
        .by_name("src")
        .ok_or_else(|| "appsrc not found".to_string())?
        .downcast::<gst_app::AppSrc>()
        .map_err(|_| "appsrc cast".to_string())?;
    appsrc.set_is_live(true);
    appsrc.set_do_timestamp(true);
    appsrc.set_block(false);
    appsrc.set_format(gst::Format::Time);
    appsrc.set_max_bytes(4 * 1024 * 1024);

    attach_cadence_probe(&pipeline);

    crate::mirror_window::attach_video(pipeline.clone());

    let bus = pipeline.bus().ok_or_else(|| "gst bus unavailable".to_string())?;
    let app_bus = app.clone();
    let pipeline_bus = pipeline.clone();
    std::thread::spawn(move || {
        for msg in bus.iter_timed(gst::ClockTime::NONE) {
            use gst::MessageView;
            match msg.view() {
                MessageView::Error(err) => {
                    let s = err.error().to_string();
                    let m = if s.contains("Quit requested") {
                        "mirror window closed by user".to_string()
                    } else {
                        format!("gst error: {} ({})", err.error(), err.debug().unwrap_or_default())
                    };
                    tracing::warn!("mirror: bus ERROR → {m} — tearing down");
                    let _ = app_bus.emit("mirror-player", serde_json::json!({ "message": m }));
                    let _ = pipeline_bus.set_state(gst::State::Null);
                    cleanup_session();
                    break;
                }
                MessageView::Eos(..) => {
                    tracing::warn!("mirror: bus EOS — tearing down");
                    let _ =
                        app_bus.emit("mirror-player", serde_json::json!({ "message": "gst EOS" }));
                    let _ = pipeline_bus.set_state(gst::State::Null);
                    cleanup_session();
                    break;
                }
                _ => {}
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    });

    pipeline.set_state(gst::State::Playing).map_err(|e| format!("set Playing: {e:?}"))?;
    if let Ok(mut g) = MIRROR_PIPELINE.lock() {
        *g = Some(pipeline.clone());
    }
    Ok((pipeline, appsrc))
}

fn start_player(
    app: AppHandle,
    backend: &'static str,
    vw: u32,
    vh: u32,
    mut au_rx: mpsc::Receiver<Vec<u8>>,
) {
    tracing::info!(backend, "mirror: starting GStreamer player");
    tokio::spawn(async move {
        let (pipeline, appsrc) = match spawn_gstreamer_player(&app, backend, vw, vh) {
            Ok(v) => v,
            Err(e) => {
                tracing::error!("mirror: GStreamer start FAILED: {e}");
                let _ = app.emit(
                    "mirror-player",
                    serde_json::json!({ "message": format!("gst start failed: {e}") }),
                );
                return;
            }
        };
        tracing::info!("mirror: GStreamer pipeline Playing — window should open");
        let _ =
            app.emit("mirror-player", serde_json::json!({ "message": "mirror window opening" }));
        let mut first = true;
        let mut window_start = std::time::Instant::now();
        let mut window_frames = 0u32;
        let mut grid = 30i32;
        let mut rates: Vec<f64> = Vec::new();
        while let Some(au) = au_rx.recv().await {
            if first {
                first = false;
                tracing::info!(bytes = au.len(), "mirror: first AU → appsrc");
                crate::mirror_window::show_video();
                window_start = std::time::Instant::now();
            }
            window_frames += 1;
            let elapsed = window_start.elapsed().as_secs_f64();
            if elapsed >= 4.0 {
                let rate = window_frames as f64 / elapsed;
                window_frames = 0;
                window_start = std::time::Instant::now();
                rates.push(rate);
                if rates.len() > PACE_RAISE_WINDOWS {
                    rates.remove(0);
                }
                if let Some(want) = next_pace_grid(grid, &rates) {
                    grid = want;
                    set_pace_grid(&pipeline, grid);
                }
            }
            let mut buffer = match gst::Buffer::with_size(au.len()) {
                Ok(b) => b,
                Err(_) => break,
            };
            if let Some(bm) = buffer.get_mut() {
                if let Ok(mut map) = bm.map_writable() {
                    map.as_mut_slice().copy_from_slice(&au);
                }
            }
            if appsrc.push_buffer(buffer).is_err() {
                break;
            }
        }
        let _ = appsrc.end_of_stream();
        let _ = pipeline.set_state(gst::State::Null);
        let _ = app.emit("mirror-player", serde_json::json!({ "message": "mirror stopped" }));
    });
}

#[allow(clippy::too_many_arguments)]
pub async fn spawn_mirror(
    app: AppHandle,
    phone_addr: SocketAddr,
    static_priv: &X25519SecBytes,
    peer_pub: &[u8; 32],
    prs: &[u8; 32],
    local_counter: u64,
    width: u32,
    height: u32,
    fps: u32,
    bitrate: u32,
) -> Result<(), String> {
    let start = MirrorStart { w: width, h: height, fps, bitrate, udp_port: 0 };
    let handle =
        start_mirror_session(phone_addr, static_priv, peer_pub, prs, local_counter, start).await?;

    let key = mirror_udp::derive_media_key(&handle.handshake_hash);
    let (au_tx, au_rx) = mpsc::channel::<Vec<u8>>(8);
    set_video_rx_task(tokio::spawn(mirror_tcp::run_tcp_video_receiver(
        phone_addr.ip(),
        key,
        au_tx,
        Some(handle.keyframe_tx.clone()),
    )));

    let backend = detect_decoder_backend();
    start_player(app, backend, width, height, au_rx);

    std::thread::spawn(|| {
        crate::mirror_inject::start();
    });

    if let Ok(mut g) = INPUT_TX.lock() {
        *g = Some(handle.input_tx.clone());
    }
    if let Ok(mut g) = MIRROR_HANDLE.lock() {
        *g = Some(handle);
    }
    Ok(())
}

pub async fn stop_mirror() {
    flush_scroll();
    flush_pinch();
    crate::mirror_inject::stop();
    if let Ok(mut g) = INPUT_TX.lock() {
        *g = None;
    }
    POINTER_DOWN.store(false, Ordering::Relaxed);
    SCROLL_ACTIVE.store(false, Ordering::Relaxed);
    abort_video_rx_task();
    stop_pipeline();
    crate::mirror_window::close();
    let handle = MIRROR_HANDLE.lock().ok().and_then(|mut g| g.take());
    if let Some(h) = handle {
        h.stop().await;
    }
}

fn requested_fps(fallback: u32) -> u32 {
    match crate::lan::PEER_DISPLAY_HZ.load(Ordering::Relaxed) {
        0 => fallback,
        hz => hz.min(120),
    }
}

pub(crate) async fn handle_start_cmd(
    ctx: &crate::worker_ctx::WorkerCtx,
    width: u32,
    height: u32,
    fps: u32,
    bitrate: u32,
) {
    let fps = requested_fps(fps);
    tracing::info!(fps, "mirror: frame rate requested from the phone");
    stop_mirror().await;
    let peer = ctx.peer_store.list().ok().and_then(|l| l.into_iter().next());
    let Some(peer) = peer else {
        tracing::warn!("start mirror: no trusted peer");
        return;
    };
    let title = peer.peer_name.clone().unwrap_or_else(|| "Phone".to_string());
    if let Ok(mut g) = MIRROR_TITLE.lock() {
        *g = Some(title.clone());
    }
    let (win_w, win_h) = window_size(&ctx.app, width, height);
    crate::mirror_window::open(title, width, height, win_w as i32, win_h as i32);
    let counter = ctx.peer_store.load_counter(&peer.peer_static_pub).unwrap_or(0);
    let app_c = ctx.app.clone();
    let identity_c = ctx.identity.clone();
    tokio::spawn(async move {
        let mut tried: Option<SocketAddr> = None;
        let mut last_err: Option<String> = None;
        for fresh in [false, true] {
            let Some(addr) = crate::lan::resolve_peer_addr(fresh).await else {
                break;
            };
            if tried == Some(addr) {
                continue;
            }
            tried = Some(addr);
            tracing::info!(%addr, fresh, "start mirror → opening session");
            match spawn_mirror(
                app_c.clone(),
                addr,
                &identity_c.static_priv.0,
                &peer.peer_static_pub,
                &peer.prs,
                counter,
                width,
                height,
                fps,
                bitrate,
            )
            .await
            {
                Ok(()) => return,
                Err(e) => {
                    tracing::warn!(%addr, "start mirror failed: {e}{}", if fresh { "" } else { " — rediscovering + retrying" });
                    last_err = Some(e);
                }
            }
        }
        let msg = match last_err {
            Some(e) => format!("mirror failed: {e}"),
            None => "phone not reachable on LAN".to_string(),
        };
        crate::mirror_window::close();
        tracing::warn!("start mirror: {msg}");
        let _ = app_c.emit("mirror-player", serde_json::json!({ "message": msg }));
    });
}

pub(crate) fn handle_stop_cmd() {
    tokio::spawn(async {
        stop_mirror().await;
    });
}

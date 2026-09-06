use std::os::fd::AsRawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use ashpd::desktop::screencast::{CursorMode, Screencast, SourceType};
use ashpd::desktop::PersistMode;
use ashpd::WindowIdentifier;
use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;
use rand::RngCore;
use tokio::sync::mpsc;

use vortex_l3_daemon::core::appstate::LaptopCast;
use vortex_l3_daemon::core::mirror_tcp;

static CAST: Mutex<Option<CastHandle>> = Mutex::new(None);

static CAST_OFFER: Mutex<Option<LaptopCast>> = Mutex::new(None);

static CAST_ERROR: Mutex<Option<String>> = Mutex::new(None);

pub(crate) fn current_error() -> Option<String> {
    CAST_ERROR.lock().ok().and_then(|g| g.clone())
}

fn set_error(msg: Option<String>) {
    if let Ok(mut g) = CAST_ERROR.lock() {
        *g = msg;
    }
}

fn spawn_video_sender(phone_ip: std::net::IpAddr, key: [u8; 32], au_rx: mpsc::Receiver<Vec<u8>>) {
    tokio::spawn(async move {
        mirror_tcp::run_tcp_video_client(phone_ip, key, au_rx).await;
        if CAST.lock().map(|g| g.is_some()).unwrap_or(false) {
            tracing::warn!("laptop-cast: phone viewer unreachable — stopping the cast");
            set_error(Some("the phone's viewer stopped responding".to_string()));
            stop();
        }
    });
}

static REQ_WANTED: AtomicBool = AtomicBool::new(false);

static REQ_FALSE_MISSES: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
const REQ_FALSE_LIMIT: u32 = 3;

struct CastHandle {
    stop_tx: tokio::sync::oneshot::Sender<()>,
}

pub fn active() -> bool {
    CAST.lock().map(|g| g.is_some()).unwrap_or(false)
}

pub fn current_offer() -> Option<LaptopCast> {
    CAST_OFFER.lock().ok().and_then(|g| g.clone())
}

pub fn dispatch_request(req: bool, extend: Option<bool>) {
    if req {
        REQ_FALSE_MISSES.store(0, Ordering::SeqCst);
    } else if REQ_WANTED.load(Ordering::SeqCst)
        && REQ_FALSE_MISSES.fetch_add(1, Ordering::SeqCst) + 1 < REQ_FALSE_LIMIT
    {
        return;
    }
    if req && !REQ_WANTED.swap(true, Ordering::SeqCst) {
        let Some(phone_ip) = (match crate::lan::LAST_GOOD_PEER_IP.lock() {
            Ok(g) => *g,
            Err(_) => None,
        }) else {
            tracing::warn!("laptop-cast: no known phone IP yet, not starting");
            REQ_WANTED.store(false, Ordering::SeqCst);
            return;
        };
        let mut key = [0u8; 32];
        rand::rngs::OsRng.fill_bytes(&mut key);
        if let Ok(mut g) = CAST_OFFER.lock() {
            *g = Some(LaptopCast {
                ip: String::new(),
                port: mirror_tcp::LAPTOP_VIDEO_PORT,
                key: hex::encode(key),
            });
        }
        set_error(None);
        tokio::spawn(async move {
            if let Err(e) = start(phone_ip, key, extend).await {
                tracing::warn!("laptop-cast: start failed: {e}");
                if let Ok(mut g) = CAST_OFFER.lock() {
                    *g = None;
                }
                set_error(Some(e));
                REQ_WANTED.store(false, Ordering::SeqCst);
            }
        });
    } else if !req && REQ_WANTED.swap(false, Ordering::SeqCst) {
        REQ_FALSE_MISSES.store(0, Ordering::SeqCst);
        stop();
        if let Ok(mut g) = CAST_OFFER.lock() {
            *g = None;
        }
        set_error(None);
    }
}

pub async fn start(
    phone_ip: std::net::IpAddr,
    key: [u8; 32],
    extend: Option<bool>,
) -> Result<(), String> {
    stop();

    if extend.unwrap_or_else(extend_enabled) {
        match start_extend(phone_ip, key).await {
            Ok(()) => return Ok(()),
            Err(e) => {
                tracing::warn!(
                    "laptop-cast: Mutter virtual monitor unavailable ({e}); \
                     trying the ScreenCast portal's Virtual source instead"
                );
                return start_portal(phone_ip, key, SourceType::Virtual).await;
            }
        }
    }
    start_portal(phone_ip, key, SourceType::Monitor).await
}

async fn start_portal(
    phone_ip: std::net::IpAddr,
    key: [u8; 32],
    source: SourceType,
) -> Result<(), String> {
    let proxy = Screencast::new().await.map_err(|e| format!("portal connect: {e}"))?;
    let session = proxy.create_session().await.map_err(|e| format!("portal session: {e}"))?;
    proxy
        .select_sources(
            &session,
            CursorMode::Embedded,
            source.into(),
            false,
            None,
            PersistMode::DoNot,
        )
        .await
        .map_err(|e| format!("portal select_sources: {e}"))?;
    let streams = proxy
        .start(&session, &WindowIdentifier::default())
        .await
        .map_err(|e| format!("portal start (consent declined?): {e}"))?
        .response()
        .map_err(|e| format!("portal start response: {e}"))?;
    let stream =
        streams.streams().first().ok_or_else(|| "portal returned no stream".to_string())?;
    let node_id = stream.pipe_wire_node_id();
    let fd = proxy
        .open_pipe_wire_remote(&session)
        .await
        .map_err(|e| format!("portal open_pipe_wire_remote: {e}"))?;
    tracing::info!(node_id, size = ?stream.size(), "laptop-cast: portal stream ready");

    if let Err(e) = gst::init() {
        return Err(format!("gst init: {e}"));
    }
    ensure_cast_plugins();
    let encoder = resolve_h264_encoder();
    let raw_fd = fd.as_raw_fd();
    let desc = format!(
        "pipewiresrc fd={raw_fd} path={node_id} do-timestamp=true keepalive-time=1000 ! \
         videorate ! videoconvert ! videoscale ! \
         video/x-raw,width=1280,height=720,framerate=30/1 ! \
         videoconvert ! \
         {encoder} ! \
         h264parse config-interval=-1 ! \
         video/x-h264,stream-format=byte-stream,alignment=au ! \
         appsink name=vsink emit-signals=false max-buffers=3 drop=true sync=false"
    );
    let pipeline = gst::parse::launch(&desc)
        .map_err(|e| format!("build pipeline: {e}"))?
        .downcast::<gst::Pipeline>()
        .map_err(|_| "pipeline downcast".to_string())?;

    let (au_tx, au_rx) = mpsc::channel::<Vec<u8>>(8);
    let appsink = pipeline
        .by_name("vsink")
        .and_then(|e| e.downcast::<gst_app::AppSink>().ok())
        .ok_or_else(|| "appsink missing".to_string())?;
    appsink.set_callbacks(
        gst_app::AppSinkCallbacks::builder()
            .new_sample(move |sink| {
                let sample = sink.pull_sample().map_err(|_| gst::FlowError::Eos)?;
                if let Some(buf) = sample.buffer() {
                    if let Ok(map) = buf.map_readable() {
                        let _ = au_tx.try_send(map.as_slice().to_vec());
                    }
                }
                Ok(gst::FlowSuccess::Ok)
            })
            .build(),
    );

    spawn_video_sender(phone_ip, key, au_rx);

    pipeline.set_state(gst::State::Playing).map_err(|e| format!("pipeline play: {e}"))?;
    tracing::info!("laptop-cast: capturing + serving on {}", mirror_tcp::LAPTOP_VIDEO_PORT);

    let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
    {
        let mut g = CAST.lock().map_err(|_| "cast lock".to_string())?;
        *g = Some(CastHandle { stop_tx });
    }
    let bus = pipeline.bus();
    tokio::spawn(async move {
        let _keep = (proxy, fd);
        let mut stop_rx = stop_rx;
        loop {
            let mut fatal = false;
            if let Some(bus) = &bus {
                while let Some(msg) = bus.pop() {
                    match msg.view() {
                        gst::MessageView::Error(e) => {
                            tracing::warn!(
                                src = ?msg.src().map(|s| s.name()),
                                debug = ?e.debug(),
                                "laptop-cast: pipeline error: {}",
                                e.error()
                            );
                            fatal = true;
                        }
                        gst::MessageView::Eos(_) => fatal = true,
                        _ => {}
                    }
                }
            }
            if fatal {
                break;
            }
            tokio::select! {
                _ = &mut stop_rx => break,
                _ = tokio::time::sleep(std::time::Duration::from_millis(200)) => {}
            }
        }
        let _ = pipeline.set_state(gst::State::Null);
        if let Ok(mut g) = CAST.lock() {
            *g = None;
        }
        if let Ok(mut g) = CAST_OFFER.lock() {
            *g = None;
        }
        if let Err(e) = session.close().await {
            tracing::warn!("laptop-cast: portal session close failed: {e}");
        }
        tracing::info!("laptop-cast: stopped (capture + portal session closed)");
    });

    Ok(())
}

const EXTEND_W: u32 = 1560;
const EXTEND_H: u32 = 720;

fn extend_flag_path() -> Option<std::path::PathBuf> {
    let home = std::env::var_os("HOME")?;
    Some(std::path::PathBuf::from(home).join(".local/share/vortex/laptop_cast/extend"))
}

pub(crate) fn extend_enabled() -> bool {
    extend_flag_path().is_some_and(|p| p.exists())
}

#[tauri::command]
pub(crate) fn set_extend_mode(on: bool) -> Result<(), String> {
    let p = extend_flag_path().ok_or("no HOME")?;
    if on {
        if let Some(dir) = p.parent() {
            std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        }
        std::fs::write(&p, b"1").map_err(|e| e.to_string())?;
    } else {
        let _ = std::fs::remove_file(&p);
    }
    Ok(())
}

#[tauri::command]
pub(crate) fn get_extend_mode() -> bool {
    extend_enabled()
}

async fn start_extend(phone_ip: std::net::IpAddr, key: [u8; 32]) -> Result<(), String> {
    let monitor = crate::virtual_display::create().await?;
    let node_id = monitor.node_id;

    if let Err(e) = gst::init() {
        return Err(format!("gst init: {e}"));
    }
    ensure_cast_plugins();
    let encoder = resolve_h264_encoder();
    let cursor_stage = match crate::virtual_display::stage_cursor_image() {
        Some(p) => format!("gdkpixbufoverlay name=cursor location=\"{}\" alpha=0 ! ", p.display()),
        None => {
            tracing::warn!("laptop-cast: no cursor artwork — extending without a pointer");
            String::new()
        }
    };
    let desc = format!(
        "pipewiresrc path={node_id} do-timestamp=true keepalive-time=1000 ! \
         video/x-raw,width={EXTEND_W},height={EXTEND_H} ! \
         videorate ! videoconvert ! \
         video/x-raw,framerate=30/1 ! \
         {cursor_stage}\
         videoconvert ! \
         {encoder} ! \
         h264parse config-interval=-1 ! \
         video/x-h264,stream-format=byte-stream,alignment=au ! \
         appsink name=vsink emit-signals=false max-buffers=3 drop=true sync=false"
    );
    let pipeline = gst::parse::launch(&desc)
        .map_err(|e| format!("build pipeline: {e}"))?
        .downcast::<gst::Pipeline>()
        .map_err(|_| "pipeline downcast".to_string())?;

    let (au_tx, au_rx) = mpsc::channel::<Vec<u8>>(8);
    let appsink = pipeline
        .by_name("vsink")
        .and_then(|e| e.downcast::<gst_app::AppSink>().ok())
        .ok_or_else(|| "appsink missing".to_string())?;
    appsink.set_callbacks(
        gst_app::AppSinkCallbacks::builder()
            .new_sample(move |sink| {
                let sample = sink.pull_sample().map_err(|_| gst::FlowError::Eos)?;
                if let Some(buf) = sample.buffer() {
                    if let Ok(map) = buf.map_readable() {
                        let _ = au_tx.try_send(map.as_slice().to_vec());
                    }
                }
                Ok(gst::FlowSuccess::Ok)
            })
            .build(),
    );

    spawn_video_sender(phone_ip, key, au_rx);
    pipeline.set_state(gst::State::Playing).map_err(|e| format!("pipeline play: {e}"))?;
    tracing::info!(
        "laptop-cast: extending onto a new {EXTEND_W}x{EXTEND_H} monitor, serving on {}",
        mirror_tcp::LAPTOP_VIDEO_PORT
    );

    let cursor_alive = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(true));
    if let Some(overlay) = pipeline.by_name("cursor") {
        crate::virtual_display::spawn_cursor_overlay(overlay, cursor_alive.clone());
    }

    let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
    {
        let mut g = CAST.lock().map_err(|_| "cast lock".to_string())?;
        *g = Some(CastHandle { stop_tx });
    }
    let bus = pipeline.bus();
    tokio::spawn(async move {
        let mut stop_rx = stop_rx;
        loop {
            let mut fatal = false;
            if let Some(bus) = &bus {
                while let Some(msg) = bus.pop() {
                    match msg.view() {
                        gst::MessageView::Error(e) => {
                            tracing::warn!(
                                src = ?msg.src().map(|s| s.name()),
                                debug = ?e.debug(),
                                "laptop-cast: pipeline error: {}",
                                e.error()
                            );
                            fatal = true;
                        }
                        gst::MessageView::Eos(_) => fatal = true,
                        _ => {}
                    }
                }
            }
            if fatal {
                break;
            }
            tokio::select! {
                _ = &mut stop_rx => break,
                _ = tokio::time::sleep(std::time::Duration::from_millis(200)) => {}
            }
        }
        cursor_alive.store(false, std::sync::atomic::Ordering::Relaxed);
        let _ = pipeline.set_state(gst::State::Null);
        monitor.stop().await;
        if let Ok(mut g) = CAST.lock() {
            *g = None;
        }
        if let Ok(mut g) = CAST_OFFER.lock() {
            *g = None;
        }
        tracing::info!("laptop-cast: stopped (extra monitor removed)");
    });

    Ok(())
}

pub fn stop() {
    let taken = CAST.lock().ok().and_then(|mut g| g.take());
    if let Some(h) = taken {
        let _ = h.stop_tx.send(());
    }
}

fn ensure_cast_plugins() {
    let has_src = gst::ElementFactory::find("pipewiresrc").is_some();
    let has_enc = gst::ElementFactory::find("vah264enc").is_some()
        || gst::ElementFactory::find("x264enc").is_some()
        || gst::ElementFactory::find("openh264enc").is_some();

    if has_src && has_enc {
        return;
    }
    tracing::info!("laptop-cast: required elements missing (src={has_src}, enc={has_enc}); searching plugin directories");

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
                    if (name.contains("pipewire")
                        || name.contains("gst-plugins-bad")
                        || name.contains("gst-plugins-ugly"))
                        && !name.ends_with(".drv")
                    {
                        let gst_dir = path.join("lib/gstreamer-1.0");
                        if gst_dir.is_dir() {
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
            let s = gst::ElementFactory::find("pipewiresrc").is_some();
            let e = gst::ElementFactory::find("vah264enc").is_some()
                || gst::ElementFactory::find("x264enc").is_some()
                || gst::ElementFactory::find("openh264enc").is_some();
            if s && e {
                tracing::info!(dir = ?dir, "laptop-cast: loaded casting plugins successfully");
                return;
            }
        }
    }
}

fn resolve_h264_encoder() -> &'static str {
    ensure_cast_plugins();
    if gst::ElementFactory::find("vah264enc").is_some() {
        tracing::info!("laptop-cast: using hardware VA-API encoder (vah264enc)");
        return "vah264enc bitrate=4000 key-int-max=30 rate-control=cbr";
    }
    if gst::ElementFactory::find("x264enc").is_some() {
        tracing::info!("laptop-cast: using software x264 encoder (x264enc)");
        return "x264enc tune=zerolatency speed-preset=veryfast bitrate=4000 key-int-max=30";
    }
    if gst::ElementFactory::find("openh264enc").is_some() {
        tracing::info!("laptop-cast: using software OpenH264 encoder (openh264enc)");
        return "openh264enc usage-type=screen complexity=low rate-control=bitrate bitrate=4000000 gop-size=30";
    }
    tracing::warn!("laptop-cast: no preferred H264 encoder found; defaulting to x264enc");
    "x264enc tune=zerolatency speed-preset=veryfast bitrate=4000 key-int-max=30"
}

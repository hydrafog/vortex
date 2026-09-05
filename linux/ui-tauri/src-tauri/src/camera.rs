use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;
use tokio::sync::mpsc;

use vortex_l3_daemon::core::appstate::CameraOffer;
use vortex_l3_daemon::core::mirror_tcp;

pub static CAMERA_WANTED: AtomicBool = AtomicBool::new(false);

static ENGAGED: AtomicBool = AtomicBool::new(false);

static CAMERA_FACING: Mutex<&'static str> = Mutex::new("front");

pub fn camera_wanted() -> bool {
    CAMERA_WANTED.load(Ordering::SeqCst)
}

pub fn camera_facing() -> String {
    CAMERA_FACING.lock().map(|g| g.to_string()).unwrap_or_default()
}

#[tauri::command]
pub(crate) fn set_camera_request(on: bool) {
    tracing::info!(on, "continuity-camera: request toggled");
    set_wanted(on);
}

#[tauri::command]
pub(crate) fn set_camera_facing(facing: String) {
    let f = if facing == "back" { "back" } else { "front" };
    if let Ok(mut g) = CAMERA_FACING.lock() {
        *g = f;
    }
    tracing::info!(facing = f, "continuity-camera: lens flipped");
    if let Some(n) = crate::SYNC_NUDGE.get() {
        n.notify_waiters();
    }
}

pub fn set_wanted(on: bool) {
    CAMERA_WANTED.store(on, Ordering::SeqCst);
    if !on {
        ENGAGED.store(false, Ordering::SeqCst);
        stop();
    }
    if let Some(n) = crate::SYNC_NUDGE.get() {
        n.notify_waiters();
    }
}

static OFFER_MISSES: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
const OFFER_MISS_LIMIT: u32 = 4;

static CURRENT_ROT: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

pub fn dispatch_offer(offer: &Option<CameraOffer>, phone_ip: Option<IpAddr>) {
    match offer {
        Some(o) => {
            OFFER_MISSES.store(0, Ordering::SeqCst);
            if ENGAGED.load(Ordering::SeqCst) && o.rot as u32 != CURRENT_ROT.load(Ordering::SeqCst)
            {
                ENGAGED.store(false, Ordering::SeqCst);
                stop();
            }
            if camera_wanted()
                && ENGAGED.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_ok()
            {
                let (Some(ip), Some(key)) = (phone_ip, hex_to_key(&o.key)) else {
                    ENGAGED.store(false, Ordering::SeqCst);
                    return;
                };
                CURRENT_ROT.store(o.rot as u32, Ordering::SeqCst);
                if let Err(e) = start(ip, key, o.rot) {
                    tracing::warn!("continuity-camera: start failed: {e}");
                    ENGAGED.store(false, Ordering::SeqCst);
                }
            }
        }
        None => {
            if ENGAGED.load(Ordering::SeqCst)
                && OFFER_MISSES.fetch_add(1, Ordering::SeqCst) + 1 >= OFFER_MISS_LIMIT
            {
                OFFER_MISSES.store(0, Ordering::SeqCst);
                ENGAGED.store(false, Ordering::SeqCst);
                stop();
            }
        }
    }
}

fn hex_to_key(hex_str: &str) -> Option<[u8; 32]> {
    let bytes = hex::decode(hex_str).ok()?;
    if bytes.len() != 32 {
        return None;
    }
    let mut k = [0u8; 32];
    k.copy_from_slice(&bytes);
    Some(k)
}

const V4L2_DEVICE: &str = "/dev/video42";

const V4L2_CARD_LABEL: &str = "Vortex Camera";

fn resolve_v4l2_device() -> Result<String, String> {
    if let Ok(entries) = std::fs::read_dir("/sys/class/video4linux") {
        for entry in entries.flatten() {
            let name = std::fs::read_to_string(entry.path().join("name")).unwrap_or_default();
            if name.trim() == V4L2_CARD_LABEL {
                let dev = format!("/dev/{}", entry.file_name().to_string_lossy());
                if std::path::Path::new(&dev).exists() {
                    return Ok(dev);
                }
            }
        }
    }
    if std::path::Path::new(V4L2_DEVICE).exists() {
        return Ok(V4L2_DEVICE.to_string());
    }
    Err(format!(
        "Vortex Camera (v4l2loopback) not found. Load the module with:\n  \
         sudo modprobe v4l2loopback video_nr=42 card_label=\"{V4L2_CARD_LABEL}\" exclusive_caps=1"
    ))
}

static CAM: Mutex<Option<CamHandle>> = Mutex::new(None);

struct CamHandle {
    stop_tx: tokio::sync::oneshot::Sender<()>,
}

pub fn active() -> bool {
    CAM.lock().map(|g| g.is_some()).unwrap_or(false)
}

pub fn start(phone_ip: IpAddr, key: [u8; 32], rot: u16) -> Result<(), String> {
    stop();
    gst::init().map_err(|e| format!("gst init: {e}"))?;
    let v4l2_device = resolve_v4l2_device()?;

    let flip = match rot {
        90 => "videoflip method=clockwise ! ",
        180 => "videoflip method=rotate-180 ! ",
        270 => "videoflip method=counterclockwise ! ",
        _ => "",
    };

    let desc = format!(
        "appsrc name=src is-live=true format=time do-timestamp=true \
         max-bytes=4194304 caps=video/x-h264,stream-format=byte-stream,alignment=au ! \
         h264parse ! avdec_h264 ! videoconvert ! {flip}videoscale add-borders=true ! videorate ! \
         video/x-raw,format=YUY2,width=1280,height=720,framerate=30/1 ! \
         v4l2sink device={v4l2_device} sync=false"
    );
    let pipeline = gst::parse::launch(&desc)
        .map_err(|e| format!("build pipeline: {e}"))?
        .downcast::<gst::Pipeline>()
        .map_err(|_| "pipeline downcast".to_string())?;
    let appsrc = pipeline
        .by_name("src")
        .and_then(|e| e.downcast::<gst_app::AppSrc>().ok())
        .ok_or_else(|| "appsrc missing".to_string())?;
    appsrc.set_is_live(true);
    appsrc.set_format(gst::Format::Time);
    appsrc.set_max_bytes(4 * 1024 * 1024);

    let (au_tx, mut au_rx) = mpsc::channel::<Vec<u8>>(8);
    tokio::spawn(mirror_tcp::run_tcp_video_receiver_on(
        phone_ip,
        mirror_tcp::CAMERA_VIDEO_PORT,
        key,
        au_tx,
        None,
    ));

    pipeline.set_state(gst::State::Playing).map_err(|e| format!("pipeline play: {e}"))?;
    tracing::info!(%phone_ip, "continuity-camera: piping phone camera → {v4l2_device}");

    let (stop_tx, mut stop_rx) = tokio::sync::oneshot::channel::<()>();
    let bus = pipeline.bus();
    tokio::spawn(async move {
        let mut first = true;
        loop {
            let mut fatal = false;
            if let Some(bus) = &bus {
                while let Some(msg) = bus.pop() {
                    match msg.view() {
                        gst::MessageView::Error(e) => {
                            tracing::warn!("continuity-camera: pipeline error: {}", e.error());
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
                au = au_rx.recv() => {
                    match au {
                        Some(au) => {
                            if first {
                                tracing::info!("continuity-camera: first frame → webcam");
                                first = false;
                            }
                            let buffer = gst::Buffer::from_slice(au);
                            if appsrc.push_buffer(buffer).is_err() {
                                break;
                            }
                        }
                        None => break,
                    }
                }
            }
        }
        let _ = appsrc.end_of_stream();
        let _ = pipeline.set_state(gst::State::Null);
        if let Ok(mut g) = CAM.lock() {
            *g = None;
        }
        ENGAGED.store(false, Ordering::SeqCst);
        tracing::info!("continuity-camera: stopped");
    });

    if let Ok(mut g) = CAM.lock() {
        *g = Some(CamHandle { stop_tx });
    }
    Ok(())
}

pub fn stop() {
    let taken = CAM.lock().ok().and_then(|mut g| g.take());
    if let Some(h) = taken {
        let _ = h.stop_tx.send(());
    }
}

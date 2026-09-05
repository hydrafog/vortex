
use std::path::PathBuf;
use std::sync::Mutex;

use tauri::AppHandle;
use tokio::sync::mpsc::{self, UnboundedSender};

use vortex_l3_daemon::core::handoff::HandoffEvent;
use vortex_l3_daemon::core::icon_cache;
use vortex_l3_daemon::core::live_activity::LiveActivity;

const HANDOFF_PILL_KEY: &str = "vortex-handoff";

static CURRENT_URL: Mutex<String> = Mutex::new(String::new());

pub(crate) static HANDOFF_TX: std::sync::OnceLock<UnboundedSender<HandoffEvent>> =
    std::sync::OnceLock::new();

pub(crate) fn dispatch_appstate_handoff(handoff: &Option<HandoffEvent>) {
    if let (Some(ev), Some(tx)) = (handoff.as_ref(), HANDOFF_TX.get()) {
        let _ = tx.send(ev.clone());
    }
}

pub(crate) fn spawn_consumer(
    _app: AppHandle,
    live_tx: UnboundedSender<LiveActivity>,
) -> UnboundedSender<HandoffEvent> {
    let (tx, mut rx) = mpsc::unbounded_channel::<HandoffEvent>();
    tokio::spawn(async move {
        while let Some(ev) = rx.recv().await {
            crate::ble::touch_peer_contact();
            if ev.url.is_empty() {
                if let Ok(mut g) = CURRENT_URL.lock() {
                    g.clear();
                }
                let _ = live_tx.send(clear_pill());
                continue;
            }
            if !is_web_url(&ev.url) {
                tracing::warn!("handoff: ignoring non-http(s) url");
                continue;
            }
            if ev.open_now {
                open_url(&ev.url);
                continue;
            }
            let domain = domain_of(&ev.url).unwrap_or_default();
            if let Ok(mut g) = CURRENT_URL.lock() {
                *g = ev.url.clone();
            }
            let cached = cached_favicon(&domain);
            let _ = live_tx.send(handoff_pill(&ev, &domain, cached.clone()));
            if cached.is_none() && !domain.is_empty() {
                let live_tx = live_tx.clone();
                let ev = ev.clone();
                let domain2 = domain.clone();
                tokio::spawn(async move {
                    let id = tokio::task::spawn_blocking(move || ensure_favicon(&domain2))
                        .await
                        .ok()
                        .flatten();
                    if id.is_none() {
                        return;
                    }
                    let still_here =
                        CURRENT_URL.lock().map(|g| *g == ev.url).unwrap_or(false);
                    if still_here {
                        let d = domain_of(&ev.url).unwrap_or_default();
                        let _ = live_tx.send(handoff_pill(&ev, &d, id));
                    }
                });
            }
        }
    });
    tx
}

fn is_web_url(url: &str) -> bool {
    url.starts_with("https://") || url.starts_with("http://")
}

fn domain_of(url: &str) -> Option<String> {
    let after = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))?;
    let host = after
        .split('/')
        .next()?
        .rsplit('@')
        .next()?
        .split(':')
        .next()?;
    let host = host.strip_prefix("www.").unwrap_or(host);
    if host.is_empty() {
        None
    } else {
        Some(host.to_string())
    }
}

fn handoff_pill(ev: &HandoffEvent, domain: &str, favicon_id: Option<String>) -> LiveActivity {
    let headline = if ev.title.trim().is_empty() {
        ev.url
            .strip_prefix("https://")
            .or_else(|| ev.url.strip_prefix("http://"))
            .unwrap_or(&ev.url)
            .trim_end_matches('/')
            .to_string()
    } else {
        ev.title.clone()
    };
    let app_id = favicon_id.unwrap_or_else(|| ev.app_id.clone());
    LiveActivity {
        key: HANDOFF_PILL_KEY.to_string(),
        app: domain.to_string(),
        app_id,
        title: headline,
        text: domain.to_string(),
        sub: ev.url.clone(),
        progress: -1,
        started_at: 0,
        muted: false,
        speaker: false,
        has_earbuds: false,
        ended: false,
        playing: None,
    }
}

fn clear_pill() -> LiveActivity {
    LiveActivity {
        key: HANDOFF_PILL_KEY.to_string(),
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
    }
}

fn favicon_app_id(domain: &str) -> String {
    format!("handoff_{domain}")
}

fn cached_favicon(domain: &str) -> Option<String> {
    if domain.is_empty() {
        return None;
    }
    let app_id = favicon_app_id(domain);
    icon_cache::icon_path(&app_id)
        .filter(|p| p.exists())
        .map(|_| app_id)
}

fn ensure_favicon(domain: &str) -> Option<String> {
    let app_id = favicon_app_id(domain);
    let path: PathBuf = icon_cache::icon_path(&app_id)?;
    if path.exists() {
        return Some(app_id);
    }
    let url = format!("https://{domain}/favicon.ico");
    let out = std::process::Command::new("curl")
        .args(["-sfL", "--max-time", "4", &url])
        .output()
        .ok()?;
    if !out.status.success() || out.stdout.is_empty() {
        return None;
    }
    let img = image::load_from_memory(&out.stdout).ok()?;
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    img.save_with_format(&path, image::ImageFormat::Png).ok()?;
    Some(app_id)
}

fn open_url(url: &str) {
    match std::process::Command::new("xdg-open").arg(url).spawn() {
        Ok(_) => tracing::info!("handoff: opened a shared page in the browser"),
        Err(e) => tracing::warn!("handoff: xdg-open failed: {e}"),
    }
}

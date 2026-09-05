
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use tokio::sync::oneshot;

use vortex_l3_daemon::core::notification_display;

static AUTO_ACCEPT: AtomicBool = AtomicBool::new(false);
static AUTO_ACCEPT_LOADED: OnceLock<()> = OnceLock::new();

fn auto_accept_path() -> Option<PathBuf> {
    let home = std::env::var_os("HOME")?;
    Some(PathBuf::from(home).join(".local/share/vortex/file_auto_accept"))
}

fn auto_accept() -> bool {
    AUTO_ACCEPT_LOADED.get_or_init(|| {
        let on = auto_accept_path()
            .and_then(|p| std::fs::read_to_string(p).ok())
            .map(|s| s.trim() == "1")
            .unwrap_or(false);
        AUTO_ACCEPT.store(on, Ordering::Relaxed);
        tracing::info!(auto_accept = on, "file receive consent");
    });
    AUTO_ACCEPT.load(Ordering::Relaxed)
}

#[tauri::command]
pub fn get_file_auto_accept() -> bool {
    auto_accept()
}

#[tauri::command]
pub fn set_file_auto_accept(enabled: bool) -> Result<(), String> {
    let _ = auto_accept();
    AUTO_ACCEPT.store(enabled, Ordering::Relaxed);
    let path = auto_accept_path().ok_or("no HOME")?;
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let _ = std::fs::remove_file(&path);
    std::fs::write(&path, if enabled { "1" } else { "0" }).map_err(|e| e.to_string())?;
    tracing::info!(enabled, "file auto-accept setting changed");
    Ok(())
}

static REGISTRY: OnceLock<Mutex<HashMap<u32, oneshot::Sender<bool>>>> = OnceLock::new();

fn registry() -> &'static Mutex<HashMap<u32, oneshot::Sender<bool>>> {
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn fmt_bytes(n: u64) -> String {
    if n < 1024 {
        format!("{n} B")
    } else if n < 1024 * 1024 {
        format!("{} KB", n / 1024)
    } else if n < 1024 * 1024 * 1024 {
        format!("{:.1} MB", n as f64 / 1024.0 / 1024.0)
    } else {
        format!("{:.2} GB", n as f64 / 1024.0 / 1024.0 / 1024.0)
    }
}

pub(crate) async fn request(label: &str, count: usize, total: u64) -> bool {
    if auto_accept() {
        tracing::info!(count, bytes = total, "auto-accept on → file batch accepted without asking");
        return true;
    }
    let title = if count > 1 {
        format!("Phone wants to send {count} files")
    } else {
        "Phone wants to send a file".to_string()
    };
    let body = format!("{label} · {}", fmt_bytes(total));
    let actions = vec![
        ("fc:accept".to_string(), "Accept".to_string()),
        ("fc:decline".to_string(), "Decline".to_string()),
    ];
    let id = match notification_display::show_call_banner(&title, &body, "vortex", &actions, 0, true).await
    {
        Ok(id) => id,
        Err(e) => {
            tracing::warn!("file-consent banner failed ({e}); declining");
            return false;
        }
    };
    let (tx, rx) = oneshot::channel();
    if let Ok(mut g) = registry().lock() {
        g.insert(id, tx);
    }
    let decision = match tokio::time::timeout(Duration::from_secs(45), rx).await {
        Ok(Ok(d)) => d,
        _ => false,
    };
    if let Ok(mut g) = registry().lock() {
        g.remove(&id);
    }
    let _ = notification_display::close(id).await;
    decision
}

pub(crate) async fn watch() {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<(u32, String)>();
    tokio::spawn(notification_display::watch_actions(tx));
    while let Some((id, key)) = rx.recv().await {
        let accept = match key.as_str() {
            "fc:accept" => true,
            "fc:decline" => false,
            _ => continue,
        };
        let waiter = registry().lock().ok().and_then(|mut g| g.remove(&id));
        if let Some(sender) = waiter {
            let _ = sender.send(accept);
        }
    }
}

use std::time::Duration;

use tauri::{AppHandle, Emitter};

pub(crate) const WIFI_DIRECT_GO_IP: [u8; 4] = [192, 168, 49, 1];

pub(crate) struct WdState {
    saved_wifi: Option<String>,
}

pub(crate) static WIFI_DIRECT: std::sync::Mutex<Option<WdState>> = std::sync::Mutex::new(None);

pub(crate) fn wd_active() -> bool {
    WIFI_DIRECT.lock().map(|g| g.is_some()).unwrap_or(false)
}

async fn nmcli(args: &[&str]) -> Option<String> {
    let out = tokio::process::Command::new("nmcli").args(args).output().await.ok()?;
    if out.status.success() {
        Some(String::from_utf8_lossy(&out.stdout).trim().to_string())
    } else {
        None
    }
}

async fn current_wifi() -> Option<String> {
    let s = nmcli(&["-t", "-f", "TYPE,CONNECTION", "device", "status"]).await?;
    s.lines()
        .find_map(|l| l.strip_prefix("wifi:").map(str::to_string))
        .filter(|c| !c.is_empty() && c != "--")
}

async fn nmcli_err(args: &[&str]) -> Result<(), String> {
    match tokio::process::Command::new("nmcli").args(args).output().await {
        Ok(out) if out.status.success() => Ok(()),
        Ok(out) => Err(String::from_utf8_lossy(&out.stderr).trim().to_string()),
        Err(e) => Err(e.to_string()),
    }
}

async fn join_go(ssid: &str, pass: &str) -> bool {
    let _ = nmcli(&["con", "delete", ssid]).await;
    for attempt in 1..=5 {
        let _ = nmcli(&["dev", "wifi", "rescan"]).await;
        tokio::time::sleep(Duration::from_secs(4)).await;
        match nmcli_err(&["dev", "wifi", "connect", ssid, "password", pass]).await {
            Ok(()) => return true,
            Err(e) => {
                tracing::warn!(attempt, "Wi-Fi Direct join attempt failed: {e}");
                let _ = nmcli(&["con", "delete", ssid]).await;
            }
        }
    }
    false
}

pub(crate) async fn restore_wifi(app: &AppHandle) {
    let saved = WIFI_DIRECT.lock().ok().and_then(|mut g| g.take()).and_then(|s| s.saved_wifi);
    if let Some(name) = saved {
        let _ = nmcli(&["con", "up", &name]).await;
        tracing::info!(name = %name, "Wi-Fi Direct: Wi-Fi restored");
    } else {
        tracing::warn!("Wi-Fi Direct: no saved Wi-Fi to restore; cycling radio to auto-reconnect");
        let _ = nmcli(&["radio", "wifi", "off"]).await;
        let _ = nmcli(&["radio", "wifi", "on"]).await;
    }
    let _ = app.emit("vortex:wifi-direct", false);
}

pub(crate) fn on_wifi_direct_offer(app: AppHandle, ssid: String, pass: String) {
    let pending = crate::PENDING_FILE_OFFERS
        .get()
        .and_then(|m| m.lock().ok().map(|g| !g.is_empty()))
        .unwrap_or(false);
    if !pending || wd_active() {
        return;
    }
    tokio::spawn(async move {
        let saved = current_wifi().await;
        tracing::info!(?saved, %ssid, "Wi-Fi Direct: joining group for fast pull");
        if !join_go(&ssid, &pass).await {
            tracing::warn!("Wi-Fi Direct: join failed; staying on router path");
            return;
        }
        if let Ok(mut g) = WIFI_DIRECT.lock() {
            *g = Some(WdState { saved_wifi: saved });
        }
        let _ = app.emit("vortex:wifi-direct", true);
        if let Some(n) = crate::SYNC_NUDGE.get() {
            n.notify_one();
        }
        let app2 = app.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_secs(60)).await;
            if wd_active() {
                tracing::warn!("Wi-Fi Direct: watchdog timeout → force-restore Wi-Fi");
                restore_wifi(&app2).await;
            }
        });
    });
}

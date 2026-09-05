use std::sync::Arc;

use tauri::AppHandle;

use vortex_l3_daemon::core::live_activity::LiveActivity;

const DISCONNECT_CLEAR_MS: u64 = 35_000;

pub(crate) fn vortex_extension_enabled() -> bool {
    std::process::Command::new("gsettings")
        .args(["get", "org.gnome.shell", "enabled-extensions"])
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).contains("vortex-live@vortex"))
        .unwrap_or(false)
}

pub(crate) fn live_activities_json(
    active: &std::collections::HashMap<String, vortex_l3_daemon::core::live_activity::LiveActivity>,
) -> String {
    let arr: Vec<serde_json::Value> = active
        .values()
        .map(|la| {
            let icon = vortex_l3_daemon::core::icon_cache::icon_path(&la.app_id)
                .filter(|p| p.exists())
                .or_else(vortex_l3_daemon::core::icon_cache::ensure_generic)
                .and_then(|p| p.to_str().map(str::to_string))
                .unwrap_or_default();
            let mut v = serde_json::json!({
                "key": la.key, "app": la.app, "app_id": la.app_id,
                "title": la.title, "text": la.text, "sub": la.sub,
                "progress": la.progress, "icon": icon,
                "started_at": la.started_at,
                "muted": la.muted, "speaker": la.speaker, "has_earbuds": la.has_earbuds,
            });
            if let Some(playing) = la.playing {
                v["playing"] = serde_json::json!(playing);
            }
            v
        })
        .collect();
    serde_json::to_string(&arr).unwrap_or_else(|_| "[]".to_string())
}

/// NOTE: single-icon policy — no per-activity tray is ever created anymore.
pub(crate) fn live_tray_id(key: &str) -> String {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    key.hash(&mut h);
    format!("vortex-live-{:x}", h.finish())
}

fn live_label(live: &vortex_l3_daemon::core::live_activity::LiveActivity) -> String {
    let body = if !live.text.is_empty() {
        live.text.as_str()
    } else if !live.title.is_empty() {
        live.title.as_str()
    } else {
        live.app.as_str()
    };
    body.chars().take(28).collect()
}

pub(crate) fn sync_main_tray_title(
    app: &AppHandle,
    active: &std::collections::HashMap<String, vortex_l3_daemon::core::live_activity::LiveActivity>,
) {
    let Some(tray) = app.tray_by_id("vortex") else {
        return;
    };
    if active.is_empty() {
        let _ = tray.set_title(Option::<&str>::None);
        return;
    }
    let label = if let Some(call) = active.get(crate::call::CALL_PILL_KEY) {
        let extra = active.len().saturating_sub(1);
        let base = live_label(call);
        if extra > 0 {
            format!("{base} (+{extra})")
        } else {
            base
        }
    } else {
        let mut keys: Vec<&String> = active.keys().collect();
        keys.sort();
        let first = keys.first().and_then(|k| active.get(*k));
        match first {
            Some(live) => {
                let extra = active.len().saturating_sub(1);
                let base = live_label(live);
                if extra > 0 {
                    format!("{base} (+{extra})")
                } else {
                    base
                }
            }
            None => return,
        }
    };
    let _ = tray.set_title(Some(label.as_str()));
}

pub(crate) fn hide_legacy_live_tray(app: &AppHandle, key: &str) {
    let id = live_tray_id(key);
    if let Some(tray) = app.tray_by_id(&id) {
        let _ = tray.set_visible(false);
    }
}

pub(crate) fn update_live_tray(
    app: &AppHandle,
    live: &vortex_l3_daemon::core::live_activity::LiveActivity,
    active: &std::collections::HashMap<String, vortex_l3_daemon::core::live_activity::LiveActivity>,
) {
    hide_legacy_live_tray(app, &live.key);
    sync_main_tray_title(app, active);
}

pub(crate) async fn spawn_consumer(
    app: AppHandle,
    call_action_tx: tokio::sync::mpsc::UnboundedSender<String>,
) -> tokio::sync::mpsc::UnboundedSender<LiveActivity> {
    let (ble_live_tx, mut ble_live_rx) = tokio::sync::mpsc::unbounded_channel::<
        vortex_l3_daemon::core::live_activity::LiveActivity,
    >();
    {
        use vortex_l3_daemon::core::live_activity::LiveActivity;
        let app = app.clone();
        let live_dbus_tx =
            vortex_l3_daemon::core::live_activity_dbus::start(call_action_tx).await.ok();
        let ext_enabled = vortex_extension_enabled();
        if ext_enabled {
            tracing::info!("vortex GNOME extension enabled → live trays suppressed");
        }
        let active: Arc<tokio::sync::Mutex<std::collections::HashMap<String, LiveActivity>>> =
            Arc::new(tokio::sync::Mutex::new(std::collections::HashMap::new()));
        let live_last: Arc<
            tokio::sync::Mutex<std::collections::HashMap<String, std::time::Instant>>,
        > = Arc::new(tokio::sync::Mutex::new(std::collections::HashMap::new()));
        {
            let app = app.clone();
            let live_last = live_last.clone();
            let active = active.clone();
            let dbus = live_dbus_tx.clone();
            tokio::spawn(async move {
                loop {
                    tokio::time::sleep(std::time::Duration::from_secs(5)).await;
                    let disconnected = crate::ble::peer_contact_age_ms() > DISCONNECT_CLEAR_MS;
                    let stale: Vec<String> = {
                        let m = live_last.lock().await;
                        m.iter()
                            .filter(|(_, t)| {
                                disconnected || t.elapsed() > std::time::Duration::from_secs(90)
                            })
                            .map(|(k, _)| k.clone())
                            .collect()
                    };
                    for key in stale {
                        live_last.lock().await.remove(&key);
                        if key == crate::call::CALL_PILL_KEY {
                            crate::call::CALL_PILL_ACTIVE
                                .store(false, std::sync::atomic::Ordering::SeqCst);
                        }
                        let snapshot = {
                            let mut a = active.lock().await;
                            a.remove(&key);
                            if let Some(tx) = &dbus {
                                let _ = tx.send(live_activities_json(&a));
                            }
                            a.clone()
                        };
                        if !ext_enabled {
                            let app2 = app.clone();
                            let key_c = key.clone();
                            let _ = app.run_on_main_thread(move || {
                                hide_legacy_live_tray(&app2, &key_c);
                                sync_main_tray_title(&app2, &snapshot);
                            });
                        }
                        tracing::info!(
                            key = %key,
                            reason = if disconnected { "disconnected" } else { "stale" },
                            "live activity pill cleared",
                        );
                    }
                }
            });
        }
        tokio::spawn(async move {
            while let Some(live) = ble_live_rx.recv().await {
                tracing::info!(app = %live.app, ended = live.ended, "live activity update");
                let (snapshot, live_c) = {
                    let mut a = active.lock().await;
                    if live.ended {
                        a.remove(&live.key);
                        live_last.lock().await.remove(&live.key);
                    } else {
                        a.insert(live.key.clone(), live.clone());
                        live_last.lock().await.insert(live.key.clone(), std::time::Instant::now());
                    }
                    if let Some(tx) = &live_dbus_tx {
                        let _ = tx.send(live_activities_json(&a));
                    }
                    (a.clone(), live.clone())
                };
                if !ext_enabled {
                    let app2 = app.clone();
                    let _ = app.run_on_main_thread(move || {
                        update_live_tray(&app2, &live_c, &snapshot);
                    });
                }
            }
        });
    }
    ble_live_tx
}

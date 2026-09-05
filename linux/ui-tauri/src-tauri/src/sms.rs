
use std::path::PathBuf;

use tauri::{AppHandle, Emitter};

use vortex_l3_daemon::core::sms::{SmsAssembler, SmsMessage};

fn cache_path() -> Option<PathBuf> {
    let mut p = PathBuf::from(std::env::var_os("HOME")?);
    p.push(".cache/vortex/sms.json");
    Some(p)
}

pub(crate) fn deliver(app: &AppHandle, json: &[u8], source: &str) {
    match serde_json::from_slice::<Vec<SmsMessage>>(json) {
        Ok(messages) => {
            tracing::info!(count = messages.len(), source, "← sms assembled");
            let known = get_sms();
            if let Some(p) = cache_path() {
                let _ = vortex_l3_daemon::core::fs_private::write_private(&p, json);
            }
            offer_login_code(&known, &messages);
            let _ = app.emit("vortex:sms", messages);
        }
        Err(e) => tracing::warn!(source, "sms JSON invalid: {e}; dropping"),
    }
}

const OTP_FRESH_MS: i64 = 5 * 60 * 1000;

fn offer_login_code(known: &[SmsMessage], incoming: &[SmsMessage]) {
    if known.is_empty() {
        return;
    }
    let seen: std::collections::HashSet<&str> = known.iter().map(|m| m.id.as_str()).collect();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0);

    let Some((msg, code)) = incoming
        .iter()
        .filter(|m| m.r#type == 1 && !seen.contains(m.id.as_str()))
        .filter(|m| now - m.date < OTP_FRESH_MS)
        .filter_map(|m| vortex_l3_daemon::core::sms::extract_otp(&m.body).map(|c| (m, c)))
        .max_by_key(|(m, _)| m.date)
    else {
        return;
    };

    let sender = msg.address.clone();
    tracing::info!(from = %sender, "sms: login code → clipboard");
    if let Err(e) = crate::clipboard_sync::set_local_text(&code) {
        tracing::warn!("sms: could not put the login code on the clipboard: {e}");
        return;
    }
    tokio::spawn(async move {
        let Ok(n) = serde_json::from_value::<
            vortex_l3_daemon::core::notif_mirror::NotificationMirror,
        >(serde_json::json!({
            "app": "Vortex",
            "title": format!("Code {code} copied"),
            "text": format!("from {sender} — paste with Ctrl+V"),
        })) else {
            return;
        };
        let _ = vortex_l3_daemon::core::notification_display::show(&n, 0).await;
    });
}

pub(crate) fn clear(app: &AppHandle) {
    for p in [cache_path(), history_path(), history_since_path()]
        .into_iter()
        .flatten()
    {
        let _ = std::fs::remove_file(&p);
    }
    let _ = app.emit("vortex:sms", Vec::<SmsMessage>::new());
    let _ = app.emit("vortex:sms-history", Vec::<SmsMessage>::new());
}

pub(crate) fn cache_hash() -> String {
    use sha2::{Digest, Sha256};
    cache_path()
        .and_then(|p| std::fs::read(&p).ok())
        .map(|b| hex::encode(Sha256::digest(&b)))
        .unwrap_or_default()
}

pub(crate) async fn spawn_consumer(
    app: AppHandle,
) -> tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)> {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<(u16, u16, Vec<u8>)>();
    tokio::spawn(async move {
        let mut asm = SmsAssembler::default();
        while let Some((total, idx, data)) = rx.recv().await {
            let Some(json) = asm.add(total, idx, data) else {
                continue;
            };
            deliver(&app, &json, "BLE");
        }
    });
    tx
}

pub(crate) async fn spawn_thread_consumer(
    app: AppHandle,
) -> tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)> {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<(u16, u16, Vec<u8>)>();
    tokio::spawn(async move {
        let mut asm = SmsAssembler::default();
        while let Some((total, idx, data)) = rx.recv().await {
            let Some(json) = asm.add(total, idx, data) else {
                continue;
            };
            match serde_json::from_slice::<Vec<SmsMessage>>(&json) {
                Ok(messages) => {
                    tracing::info!(count = messages.len(), "← BLE sms-thread page assembled");
                    let _ = app.emit("vortex:sms-thread", messages);
                }
                Err(e) => tracing::warn!("sms-thread JSON invalid: {e}; dropping"),
            }
        }
    });
    tx
}

#[tauri::command]
pub(crate) fn get_sms() -> Vec<SmsMessage> {
    cache_path()
        .and_then(|p| std::fs::read(&p).ok())
        .and_then(|b| serde_json::from_slice::<Vec<SmsMessage>>(&b).ok())
        .unwrap_or_default()
}


fn history_path() -> Option<PathBuf> {
    let mut p = PathBuf::from(std::env::var_os("HOME")?);
    p.push(".cache/vortex/sms_history.json");
    Some(p)
}

fn history_since_path() -> Option<PathBuf> {
    let mut p = PathBuf::from(std::env::var_os("HOME")?);
    p.push(".cache/vortex/sms_history.since");
    Some(p)
}

pub(crate) fn history_since() -> i64 {
    history_since_path()
        .and_then(|p| std::fs::read_to_string(&p).ok())
        .and_then(|s| s.trim().parse::<i64>().ok())
        .unwrap_or(0)
}

pub(crate) fn merge_history(app: &AppHandle, json: &[u8]) {
    let batch: Vec<SmsMessage> = match serde_json::from_slice(json) {
        Ok(b) => b,
        Err(e) => {
            tracing::warn!("sms-history JSON invalid: {e}; dropping");
            return;
        }
    };
    if batch.is_empty() {
        return;
    }
    let mut store: Vec<SmsMessage> = history_path()
        .and_then(|p| std::fs::read(&p).ok())
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default();
    let mut by_id: std::collections::HashMap<String, SmsMessage> =
        store.drain(..).map(|m| (m.id.clone(), m)).collect();
    let batch_len = batch.len();
    for m in batch {
        by_id.insert(m.id.clone(), m);
    }
    let mut merged: Vec<SmsMessage> = by_id.into_values().collect();
    merged.sort_by_key(|m| m.date);
    let since = merged.last().map(|m| m.date).unwrap_or(0);
    if let Some(p) = history_path() {
        if let Ok(bytes) = serde_json::to_vec(&merged) {
            let _ = vortex_l3_daemon::core::fs_private::write_private(&p, &bytes);
        }
    }
    if let Some(p) = history_since_path() {
        let _ = vortex_l3_daemon::core::fs_private::write_private(&p, since.to_string().as_bytes());
    }
    tracing::info!(
        batch = batch_len,
        total = merged.len(),
        since,
        "← sms history merged (LAN bulk-sync)"
    );
    let _ = app.emit("vortex:sms-history", merged);
}

fn ids_json() -> Vec<u8> {
    let mut ids: Vec<i64> = get_sms_history()
        .iter()
        .filter_map(|m| m.id.parse::<i64>().ok())
        .collect();
    ids.sort_unstable();
    let strs: Vec<String> = ids.iter().map(|i| i.to_string()).collect();
    serde_json::to_vec(&strs).unwrap_or_else(|_| b"[]".to_vec())
}

pub(crate) fn ids_hash() -> String {
    use sha2::{Digest, Sha256};
    hex::encode(Sha256::digest(ids_json()))
}

pub(crate) fn reconcile_ids(app: &AppHandle, json: &[u8]) {
    let ids: Vec<String> = match serde_json::from_slice(json) {
        Ok(v) => v,
        Err(e) => {
            tracing::warn!("sms-ids JSON invalid: {e}; dropping");
            return;
        }
    };
    let keep: std::collections::HashSet<&str> = ids.iter().map(|s| s.as_str()).collect();
    let before = get_sms_history();
    let after: Vec<SmsMessage> = before
        .iter()
        .filter(|m| keep.contains(m.id.as_str()))
        .cloned()
        .collect();
    let pruned = before.len() - after.len();
    if pruned == 0 {
        return;
    }
    if let Some(p) = history_path() {
        if let Ok(bytes) = serde_json::to_vec(&after) {
            let _ = vortex_l3_daemon::core::fs_private::write_private(&p, &bytes);
        }
    }
    tracing::info!(pruned, total = after.len(), "sms history pruned (phone deletions)");
    let _ = app.emit("vortex:sms-history", after);
}

#[tauri::command]
pub(crate) fn get_sms_history() -> Vec<SmsMessage> {
    history_path()
        .and_then(|p| std::fs::read(&p).ok())
        .and_then(|b| serde_json::from_slice::<Vec<SmsMessage>>(&b).ok())
        .unwrap_or_default()
}

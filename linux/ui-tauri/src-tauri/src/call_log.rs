use std::path::PathBuf;

use tauri::{AppHandle, Emitter};

use vortex_l3_daemon::core::call_log::{CallLogAssembler, CallLogEntry};

fn cache_path() -> Option<PathBuf> {
    let mut p = PathBuf::from(std::env::var_os("HOME")?);
    p.push(".cache/vortex/call_log.json");
    Some(p)
}

pub(crate) fn deliver(app: &AppHandle, json: &[u8], source: &str) {
    match serde_json::from_slice::<Vec<CallLogEntry>>(json) {
        Ok(entries) => {
            tracing::info!(count = entries.len(), source, "← call log assembled");
            if let Some(p) = cache_path() {
                let _ = vortex_l3_daemon::core::fs_private::write_private(&p, json);
            }
            let _ = app.emit("vortex:call_log", entries);
        }
        Err(e) => tracing::warn!(source, "call-log JSON invalid: {e}; dropping"),
    }
}

pub(crate) fn clear(app: &AppHandle) {
    for p in [cache_path(), history_path(), history_since_path()].into_iter().flatten() {
        let _ = std::fs::remove_file(&p);
    }
    let _ = app.emit("vortex:call_log", Vec::<CallLogEntry>::new());
    let _ = app.emit("vortex:call-log-history", Vec::<CallLogEntry>::new());
}

pub(crate) fn cache_hash() -> String {
    use sha2::{Digest, Sha256};
    cache_path()
        .and_then(|p| std::fs::read(&p).ok())
        .map(|b| hex::encode(Sha256::digest(&b)))
        .unwrap_or_default()
}

fn history_path() -> Option<PathBuf> {
    let mut p = PathBuf::from(std::env::var_os("HOME")?);
    p.push(".cache/vortex/call_log_history.json");
    Some(p)
}

fn history_since_path() -> Option<PathBuf> {
    let mut p = PathBuf::from(std::env::var_os("HOME")?);
    p.push(".cache/vortex/call_log_history.since");
    Some(p)
}

pub(crate) fn history_since() -> i64 {
    history_since_path()
        .and_then(|p| std::fs::read_to_string(&p).ok())
        .and_then(|s| s.trim().parse::<i64>().ok())
        .unwrap_or(0)
}

pub(crate) fn merge_history(app: &AppHandle, json: &[u8]) {
    let batch: Vec<CallLogEntry> = match serde_json::from_slice(json) {
        Ok(b) => b,
        Err(e) => {
            tracing::warn!("call-log-history JSON invalid: {e}; dropping");
            return;
        }
    };
    if batch.is_empty() {
        return;
    }
    let mut store: Vec<CallLogEntry> = history_path()
        .and_then(|p| std::fs::read(&p).ok())
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default();
    let mut by_id: std::collections::HashMap<String, CallLogEntry> =
        store.drain(..).map(|e| (e.id.clone(), e)).collect();
    let batch_len = batch.len();
    for e in batch {
        by_id.insert(e.id.clone(), e);
    }
    let mut merged: Vec<CallLogEntry> = by_id.into_values().collect();
    merged.sort_by_key(|e| e.date);
    let since = merged.last().map(|e| e.date).unwrap_or(0);
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
        "← call-log history merged (LAN bulk-sync)"
    );
    let _ = app.emit("vortex:call-log-history", merged);
}

#[tauri::command]
pub(crate) fn get_call_log_history() -> Vec<CallLogEntry> {
    history_path()
        .and_then(|p| std::fs::read(&p).ok())
        .and_then(|b| serde_json::from_slice::<Vec<CallLogEntry>>(&b).ok())
        .unwrap_or_default()
}

pub(crate) async fn spawn_consumer(
    app: AppHandle,
) -> tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)> {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<(u16, u16, Vec<u8>)>();
    tokio::spawn(async move {
        let mut asm = CallLogAssembler::default();
        while let Some((total, idx, data)) = rx.recv().await {
            let Some(json) = asm.add(total, idx, data) else {
                continue;
            };
            deliver(&app, &json, "BLE");
        }
    });
    tx
}

#[tauri::command]
pub(crate) fn get_call_log() -> Vec<CallLogEntry> {
    cache_path()
        .and_then(|p| std::fs::read(&p).ok())
        .and_then(|b| serde_json::from_slice::<Vec<CallLogEntry>>(&b).ok())
        .unwrap_or_default()
}

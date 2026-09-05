use std::path::PathBuf;

use tauri::{AppHandle, Emitter};

use vortex_l3_daemon::core::contacts::{Contact, ContactsAssembler};

fn cache_path() -> Option<PathBuf> {
    let mut p = PathBuf::from(std::env::var_os("HOME")?);
    p.push(".cache/vortex/contacts.json");
    Some(p)
}

pub(crate) fn deliver(app: &AppHandle, json: &[u8], source: &str) {
    match serde_json::from_slice::<Vec<Contact>>(json) {
        Ok(contacts) => {
            tracing::info!(count = contacts.len(), source, "← contacts assembled");
            if let Some(p) = cache_path() {
                let _ = vortex_l3_daemon::core::fs_private::write_private(&p, json);
            }
            let _ = app.emit("vortex:contacts", contacts);
        }
        Err(e) => tracing::warn!(source, "contacts JSON invalid: {e}; dropping"),
    }
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
        let mut asm = ContactsAssembler::default();
        while let Some((total, idx, data)) = rx.recv().await {
            let Some(json) = asm.add(total, idx, data) else {
                continue;
            };
            deliver(&app, &json, "BLE");
        }
    });
    tx
}

pub(crate) fn clear(app: &AppHandle) {
    if let Some(p) = cache_path() {
        let _ = std::fs::remove_file(&p);
    }
    let _ = app.emit("vortex:contacts", Vec::<Contact>::new());
}

#[tauri::command]
pub(crate) fn get_contacts() -> Vec<Contact> {
    cache_path()
        .and_then(|p| std::fs::read(&p).ok())
        .and_then(|b| serde_json::from_slice::<Vec<Contact>>(&b).ok())
        .unwrap_or_default()
}

pub(crate) fn lookup_number_by_name(name: &str) -> Option<String> {
    let want = name.trim().to_lowercase();
    if want.is_empty() {
        return None;
    }
    get_contacts()
        .into_iter()
        .find(|c| c.name.trim().to_lowercase() == want)
        .and_then(|c| c.numbers.into_iter().find(|n| !n.trim().is_empty()))
}

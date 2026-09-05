use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Item {
    pub id: String,
    pub kind: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub done: bool,
    #[serde(default)]
    pub due_at: i64,
    pub updated_at: i64,
    #[serde(default)]
    pub deleted: bool,
}

pub(crate) fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn cache_path() -> Option<PathBuf> {
    let mut p = PathBuf::from(std::env::var_os("HOME")?);
    p.push(".cache/vortex/notes.json");
    Some(p)
}

fn load_store() -> Vec<Item> {
    cache_path()
        .and_then(|p| std::fs::read(&p).ok())
        .and_then(|b| serde_json::from_slice::<Vec<Item>>(&b).ok())
        .unwrap_or_default()
}

fn save_store(items: &[Item]) {
    if let (Some(p), Ok(bytes)) = (cache_path(), serde_json::to_vec(items)) {
        let _ = vortex_l3_daemon::core::fs_private::write_private(&p, &bytes);
    }
}

static NOTES: Mutex<Option<Vec<Item>>> = Mutex::new(None);

fn with_notes<R>(f: impl FnOnce(&mut Vec<Item>) -> R) -> R {
    let mut g = NOTES.lock().unwrap_or_else(|e| e.into_inner());
    let items = g.get_or_insert_with(load_store);
    let r = f(items);
    save_store(items);
    r
}

fn visible(items: &[Item]) -> Vec<Item> {
    items.iter().filter(|i| !i.deleted).cloned().collect()
}

fn emit(app: &AppHandle, items: &[Item]) {
    let _ = app.emit("vortex:notes", visible(items));
}

pub(crate) fn clear(app: &AppHandle) {
    {
        let mut g = NOTES.lock().unwrap_or_else(|e| e.into_inner());
        *g = Some(Vec::new());
    }
    if let Some(p) = cache_path() {
        let _ = std::fs::remove_file(&p);
    }
    let _ = app.emit("vortex:notes", Vec::<Item>::new());
}

#[tauri::command]
pub(crate) fn get_notes() -> Vec<Item> {
    with_notes(|items| visible(items))
}

#[tauri::command]
pub(crate) fn upsert_note(app: AppHandle, mut item: Item) {
    item.updated_at = now_ms();
    item.deleted = false;
    with_notes(|items| {
        match items.iter_mut().find(|i| i.id == item.id) {
            Some(existing) => *existing = item.clone(),
            None => items.push(item.clone()),
        }
        emit(&app, items);
    });
    mark_dirty();
}

#[tauri::command]
pub(crate) fn toggle_todo(app: AppHandle, id: String, done: bool) {
    with_notes(|items| {
        if let Some(it) = items.iter_mut().find(|i| i.id == id) {
            it.done = done;
            it.updated_at = now_ms();
        }
        emit(&app, items);
    });
    mark_dirty();
}

#[tauri::command]
pub(crate) fn delete_note(app: AppHandle, id: String) {
    with_notes(|items| {
        if let Some(it) = items.iter_mut().find(|i| i.id == id) {
            it.deleted = true;
            it.updated_at = now_ms();
        }
        emit(&app, items);
    });
    mark_dirty();
}

const NOTES_FRAME: u8 = 0x4D;
const CHUNK_DATA: usize = 400;

static NOTES_DIRTY: OnceLock<Arc<tokio::sync::Notify>> = OnceLock::new();
fn mark_dirty() {
    if let Some(n) = NOTES_DIRTY.get() {
        n.notify_one();
    }
}

fn snapshot() -> Vec<Item> {
    NOTES.lock().unwrap_or_else(|e| e.into_inner()).get_or_insert_with(load_store).clone()
}

fn merge(local: &[Item], remote: &[Item]) -> Vec<Item> {
    use std::collections::HashMap;
    let mut by_id: HashMap<&str, Item> = HashMap::new();
    for it in local.iter().chain(remote.iter()) {
        match by_id.get(it.id.as_str()) {
            Some(cur) if cur.updated_at >= it.updated_at => {}
            _ => {
                by_id.insert(it.id.as_str(), it.clone());
            }
        }
    }
    by_id.into_values().collect()
}

fn sig(items: &[Item]) -> String {
    let mut v: Vec<String> =
        items.iter().map(|i| format!("{}:{}:{}", i.id, i.updated_at, i.deleted)).collect();
    v.sort();
    v.join("|")
}

fn build_chunks(items: &[Item]) -> Vec<Vec<u8>> {
    let json = serde_json::to_vec(items).unwrap_or_else(|_| b"[]".to_vec());
    let total = json.len().div_ceil(CHUNK_DATA).max(1) as u16;
    json.chunks(CHUNK_DATA)
        .enumerate()
        .map(|(i, c)| {
            let mut f = Vec::with_capacity(4 + c.len());
            f.extend_from_slice(&total.to_be_bytes());
            f.extend_from_slice(&(i as u16).to_be_bytes());
            f.extend_from_slice(c);
            f
        })
        .collect()
}

fn parse_chunk(p: &[u8]) -> Option<(u16, u16, Vec<u8>)> {
    if p.len() < 4 {
        return None;
    }
    Some((u16::from_be_bytes([p[0], p[1]]), u16::from_be_bytes([p[2], p[3]]), p[4..].to_vec()))
}

#[derive(Default)]
struct Assembler {
    parts: std::collections::BTreeMap<u16, Vec<u8>>,
}
impl Assembler {
    fn add(&mut self, total: u16, idx: u16, data: Vec<u8>) -> Option<Vec<Item>> {
        if total == 0 || total > 4096 {
            return None;
        }
        self.parts.insert(idx, data);
        if self.parts.len() as u16 != total {
            return None;
        }
        let buf: Vec<u8> = std::mem::take(&mut self.parts).into_values().flatten().collect();
        serde_json::from_slice::<Vec<Item>>(&buf).ok()
    }
}

async fn send_full(writer: &Arc<tokio::sync::Mutex<Option<crate::SealedWriter>>>, items: &[Item]) {
    let w = { writer.lock().await.clone() };
    let Some(w) = w else { return };
    for chunk in build_chunks(items) {
        if w(NOTES_FRAME, chunk).await.is_err() {
            break;
        }
    }
}

pub(crate) fn spawn_sync(
    app: AppHandle,
    writer: Arc<tokio::sync::Mutex<Option<crate::SealedWriter>>>,
) -> tokio::sync::mpsc::UnboundedSender<(u8, Vec<u8>)> {
    let notify = Arc::new(tokio::sync::Notify::new());
    let _ = NOTES_DIRTY.set(notify.clone());

    {
        let writer = writer.clone();
        tokio::spawn(async move {
            loop {
                notify.notified().await;
                tokio::time::sleep(std::time::Duration::from_millis(300)).await;
                send_full(&writer, &snapshot()).await;
            }
        });
    }

    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<(u8, Vec<u8>)>();
    tokio::spawn(async move {
        let mut asm = Assembler::default();
        while let Some((ty, payload)) = rx.recv().await {
            if ty != NOTES_FRAME {
                continue;
            }
            let Some((total, idx, data)) = parse_chunk(&payload) else {
                continue;
            };
            let Some(remote) = asm.add(total, idx, data) else {
                continue;
            };
            let (merged, changed) = with_notes(|v| {
                let merged = merge(v, &remote);
                let changed = sig(&merged) != sig(v);
                if changed {
                    *v = merged.clone();
                }
                (merged, changed)
            });
            if changed {
                emit(&app, &merged);
                tracing::info!(
                    count = merged.iter().filter(|i| !i.deleted).count(),
                    "notes: merged peer set"
                );
            }
            if sig(&merged) != sig(&remote) {
                send_full(&writer, &merged).await;
            }
        }
    });
    tx
}

pub(crate) fn spawn_reminders() {
    tokio::spawn(async move {
        let mut last_scan = now_ms();
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(30)).await;
            let now = now_ms();
            for it in snapshot() {
                if it.kind == "todo"
                    && !it.done
                    && !it.deleted
                    && it.due_at > last_scan
                    && it.due_at <= now
                {
                    fire_reminder(&it).await;
                }
            }
            last_scan = now;
        }
    });
}

async fn fire_reminder(it: &Item) {
    let notif = vortex_l3_daemon::core::notif_mirror::NotificationMirror {
        app: "Reminder".to_string(),
        title: if it.title.is_empty() { "Todo".to_string() } else { it.title.clone() },
        text: "Reminder".to_string(),
        ..Default::default()
    };
    let _ = vortex_l3_daemon::core::notification_display::show(&notif, 0).await;
    tracing::info!("notes: fired todo reminder");
}

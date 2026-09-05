
use std::path::PathBuf;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter};

use crate::clipboard_sync::{
    img_sig, queue_clipboard_for_sync, queue_clipboard_image_for_sync, tidy_text,
    with_clip_setter,
};

const POLL_MS: u64 = 700;
const MAX_ENTRIES: usize = 1000;
const MAX_TOTAL_BYTES: u64 = 100 * 1024 * 1024;
const MAX_TEXT_CHARS: usize = 65_536;
const MAX_IMAGE_BYTES: usize = 20 * 1024 * 1024;


#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct ClipEntry {
    pub id: String,
    pub kind: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub file: Option<String>,
    pub bytes: u64,
    pub ts_ms: u64,
    #[serde(default)]
    pub pinned: bool,
}

fn clip_dir() -> Option<PathBuf> {
    let home = std::env::var_os("HOME")?;
    Some(PathBuf::from(home).join(".cache/vortex/clipboard"))
}

fn index_path() -> Option<PathBuf> {
    clip_dir().map(|d| d.join("index.json"))
}

fn load_index() -> Vec<ClipEntry> {
    index_path()
        .and_then(|p| std::fs::read(p).ok())
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn save_index(entries: &[ClipEntry]) {
    let Some(dir) = clip_dir() else { return };
    let _ = std::fs::create_dir_all(&dir);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700));
    }
    if let Ok(bytes) = serde_json::to_vec(entries) {
        let _ = vortex_l3_daemon::core::fs_private::write_private(&dir.join("index.json"), &bytes);
    }
}

static ENTRIES: Mutex<Option<Vec<ClipEntry>>> = Mutex::new(None);

fn with_entries<R>(f: impl FnOnce(&mut Vec<ClipEntry>) -> R) -> R {
    let mut g = ENTRIES.lock().unwrap_or_else(|e| e.into_inner());
    let entries = g.get_or_insert_with(load_index);
    let r = f(entries);
    save_index(entries);
    r
}

pub(crate) fn hash_id(bytes: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(bytes);
    hex::encode(h.finalize())[..16].to_string()
}

pub(crate) fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub(crate) fn store_capture(kind: &str, text: Option<String>, png: Option<Vec<u8>>) -> bool {
    let id = match (&text, &png) {
        (Some(t), _) => hash_id(t.as_bytes()),
        (_, Some(p)) => hash_id(p),
        _ => return false,
    };
    with_entries(|entries| {
        if let Some(pos) = entries.iter().position(|e| e.id == id) {
            if pos == 0 {
                return false;
            }
            let e = entries.remove(pos);
            entries.insert(0, e);
            return true;
        }
        let (file, bytes) = match &png {
            Some(p) => {
                let Some(dir) = clip_dir() else { return false };
                let _ = std::fs::create_dir_all(&dir);
                let name = format!("{id}.png");
                if vortex_l3_daemon::core::fs_private::write_private(&dir.join(&name), p).is_err() {
                    return false;
                }
                (Some(name), p.len() as u64)
            }
            None => (None, text.as_deref().map(|t| t.len() as u64).unwrap_or(0)),
        };
        entries.insert(
            0,
            ClipEntry {
                id,
                kind: kind.to_string(),
                text,
                file,
                bytes,
                ts_ms: now_ms(),
                pinned: false,
            },
        );
        evict(entries);
        true
    })
}

fn evict(entries: &mut Vec<ClipEntry>) {
    let dir = clip_dir();
    loop {
        let total: u64 = entries.iter().map(|e| e.bytes).sum();
        let over = entries.len() > MAX_ENTRIES || total > MAX_TOTAL_BYTES;
        if !over {
            return;
        }
        let Some(pos) = entries.iter().rposition(|e| !e.pinned) else {
            return;
        };
        let e = entries.remove(pos);
        if let (Some(d), Some(f)) = (&dir, &e.file) {
            let _ = std::fs::remove_file(d.join(f));
        }
    }
}


fn rgba_to_png(img: &arboard::ImageData) -> Option<Vec<u8>> {
    let buf: image::RgbaImage = image::RgbaImage::from_raw(
        img.width as u32,
        img.height as u32,
        img.bytes.clone().into_owned(),
    )?;
    let mut out = std::io::Cursor::new(Vec::new());
    buf.write_to(&mut out, image::ImageFormat::Png).ok()?;
    Some(out.into_inner())
}

fn poll_once(cb: &mut arboard::Clipboard, last_sig: &mut String, last_state: &mut u8) -> bool {
    match cb.get_text() {
        Ok(text) if !text.trim().is_empty() => {
            log_clip_state(last_state, 1, "text");
            let sig = format!("t:{}", hash_id(text.as_bytes()));
            if sig == *last_sig {
                return false;
            }
            *last_sig = sig;
            if clipboard_is_secret() {
                tracing::info!("clipboard: sensitive (password-manager) — skipped");
                return false;
            }
            let mut clean = tidy_text(&text);
            if clean.chars().count() > MAX_TEXT_CHARS {
                clean = clean.chars().take(MAX_TEXT_CHARS).collect();
            }
            tracing::info!(chars = clean.chars().count(), "clipboard: text captured");
            queue_clipboard_for_sync(&clean);
            return store_capture("text", Some(clean), None);
        }
        Ok(_) => {  }
        Err(arboard::Error::ContentNotAvailable) => {  }
        Err(e) => {
            log_clip_state(last_state, 3, &format!("text read error: {e}"));
        }
    }
    match cb.get_image() {
        Ok(img) => {
            log_clip_state(last_state, 2, "image");
            let sig = format!("i:{}x{}:{}", img.width, img.height, hash_id(&img.bytes));
            if sig == *last_sig {
                return false;
            }
            *last_sig = sig;
            let sync_sig = img_sig(img.width, img.height, &img.bytes);
            let Some(png) = rgba_to_png(&img) else {
                return false;
            };
            if png.len() > MAX_IMAGE_BYTES {
                tracing::warn!(bytes = png.len(), "clipboard: image too large, dropped");
                return false;
            }
            tracing::info!(w = img.width, h = img.height, "clipboard: image captured");
            queue_clipboard_image_for_sync(sync_sig, &png);
            return store_capture("image", None, Some(png));
        }
        Err(arboard::Error::ContentNotAvailable) => log_clip_state(last_state, 0, "empty"),
        Err(e) => log_clip_state(last_state, 3, &format!("image read error: {e}")),
    }
    false
}


fn wayland_clipboard_is_secret() -> Option<bool> {
    use wl_clipboard_rs::paste::{get_mime_types, ClipboardType, Seat};

    if std::env::var_os("WAYLAND_DISPLAY").is_none() {
        return None;
    }
    match get_mime_types(ClipboardType::Regular, Seat::Unspecified) {
        Ok(types) => Some(types.iter().any(|t| t == "x-kde-passwordManagerHint")),
        Err(wl_clipboard_rs::paste::Error::ClipboardEmpty) => Some(false),
        Err(_) => None,
    }
}

fn clipboard_is_secret() -> bool {
    if let Some(secret) = wayland_clipboard_is_secret() {
        return secret;
    }
    use x11rb::connection::Connection;
    use x11rb::protocol::xproto::{
        AtomEnum, ConnectionExt, CreateWindowAux, WindowClass,
    };
    use x11rb::protocol::Event;

    struct Probe {
        conn: x11rb::rust_connection::RustConnection,
        win: u32,
        clipboard: u32,
        hint: u32,
        prop: u32,
    }

    fn build() -> Option<Probe> {
        let (conn, screen_num) = x11rb::connect(None).ok()?;
        let root = conn.setup().roots.get(screen_num)?.root;
        let win = conn.generate_id().ok()?;
        conn.create_window(
            0, win, root, 0, 0, 1, 1, 0,
            WindowClass::INPUT_ONLY, 0, &CreateWindowAux::new(),
        )
        .ok()?
        .check()
        .ok()?;
        let clipboard = conn.intern_atom(false, b"CLIPBOARD").ok()?.reply().ok()?.atom;
        let hint = conn
            .intern_atom(false, b"x-kde-passwordManagerHint")
            .ok()?
            .reply()
            .ok()?
            .atom;
        let prop = conn
            .intern_atom(false, b"VORTEX_CLIP_SECRET")
            .ok()?
            .reply()
            .ok()?
            .atom;
        Some(Probe { conn, win, clipboard, hint, prop })
    }

    fn query(p: &Probe) -> Option<bool> {
        p.conn
            .convert_selection(p.win, p.clipboard, p.hint, p.prop, 0u32)
            .ok()?;
        p.conn.flush().ok()?;
        let deadline = std::time::Instant::now() + std::time::Duration::from_millis(60);
        loop {
            match p.conn.poll_for_event().ok()? {
                Some(Event::SelectionNotify(ev)) => {
                    if ev.property == 0 {
                        return Some(false);
                    }
                    let r = p
                        .conn
                        .get_property(false, p.win, p.prop, AtomEnum::ANY, 0, 64)
                        .ok()?
                        .reply()
                        .ok()?;
                    let val = String::from_utf8_lossy(&r.value);
                    let secret = val.trim().eq_ignore_ascii_case("secret");
                    let _ = p.conn.delete_property(p.win, p.prop);
                    let _ = p.conn.flush();
                    return Some(secret);
                }
                Some(_) => continue,
                None => {
                    if std::time::Instant::now() >= deadline {
                        return Some(false);
                    }
                    std::thread::sleep(std::time::Duration::from_millis(2));
                }
            }
        }
    }

    static PROBE: Mutex<Option<Option<Probe>>> = Mutex::new(None);
    let mut g = match PROBE.lock() {
        Ok(g) => g,
        Err(_) => return false,
    };
    if g.is_none() {
        *g = Some(build());
    }
    match g.as_ref().and_then(|o| o.as_ref()) {
        Some(p) => query(p).unwrap_or(false),
        None => false,
    }
}

fn log_clip_state(last_state: &mut u8, state: u8, label: &str) {
    if *last_state != state {
        *last_state = state;
        if state == 3 {
            tracing::warn!("clipboard read: {label}");
        } else {
            tracing::debug!("clipboard read: {label}");
        }
    }
}

#[tauri::command]
pub async fn clipboard_capture_now(app: AppHandle) {
    let changed = tokio::task::spawn_blocking(|| {
        let mut cb = match arboard::Clipboard::new() {
            Ok(c) => c,
            Err(e) => {
                tracing::warn!("clipboard capture-now: arboard unavailable: {e}");
                return false;
            }
        };
        let mut sig = String::new();
        let mut state = 255u8;
        poll_once(&mut cb, &mut sig, &mut state)
    })
    .await
    .unwrap_or(false);
    if changed {
        let _ = app.emit("vortex:clipboard", ());
    }
}

pub(crate) fn spawn_clipboard_watcher(app: AppHandle) {
    std::thread::spawn(move || {
        let mut cb = match arboard::Clipboard::new() {
            Ok(c) => c,
            Err(e) => {
                tracing::warn!("clipboard watcher unavailable: {e}");
                return;
            }
        };
        let mut last_sig = String::new();
        let mut last_state = 255u8;
        loop {
            if poll_once(&mut cb, &mut last_sig, &mut last_state) {
                let _ = app.emit("vortex:clipboard", ());
            }
            std::thread::sleep(std::time::Duration::from_millis(POLL_MS));
        }
    });
}


#[derive(Serialize)]
pub(crate) struct ClipEntryDto {
    id: String,
    kind: String,
    text: Option<String>,
    path: Option<String>,
    bytes: u64,
    ts_ms: u64,
    pinned: bool,
}

#[tauri::command]
pub fn clipboard_history() -> Vec<ClipEntryDto> {
    let dir = clip_dir();
    with_entries(|entries| {
        entries
            .iter()
            .map(|e| ClipEntryDto {
                id: e.id.clone(),
                kind: e.kind.clone(),
                text: e.text.as_ref().map(|t| t.chars().take(400).collect()),
                path: match (&dir, &e.file) {
                    (Some(d), Some(f)) => Some(d.join(f).to_string_lossy().into_owned()),
                    _ => None,
                },
                bytes: e.bytes,
                ts_ms: e.ts_ms,
                pinned: e.pinned,
            })
            .collect()
    })
}

#[tauri::command]
pub async fn clipboard_select(app: AppHandle, id: String) -> Result<(), String> {
    enum Payload {
        Text(String),
        Image(PathBuf),
    }
    let payload = with_entries(|entries| {
        entries.iter().find(|e| e.id == id).map(|e| {
            if let Some(t) = &e.text {
                Some(Payload::Text(t.clone()))
            } else {
                match (clip_dir(), &e.file) {
                    (Some(d), Some(f)) => Some(Payload::Image(d.join(f))),
                    _ => None,
                }
            }
        })
    })
    .flatten()
    .ok_or("entry not found")?;

    tokio::task::spawn_blocking(move || -> Result<(), String> {
        with_clip_setter(|cb| match payload {
            Payload::Text(t) => cb.set_text(t).map_err(|e| format!("set_text: {e}")),
            Payload::Image(path) => {
                let png = std::fs::read(&path).map_err(|e| format!("read png: {e}"))?;
                let img = image::load_from_memory_with_format(&png, image::ImageFormat::Png)
                    .map_err(|e| format!("decode png: {e}"))?
                    .into_rgba8();
                let (w, h) = img.dimensions();
                cb.set_image(arboard::ImageData {
                    width: w as usize,
                    height: h as usize,
                    bytes: std::borrow::Cow::Owned(img.into_raw()),
                })
                .map_err(|e| format!("set_image: {e}"))
            }
        })
    })
    .await
    .map_err(|e| format!("join: {e}"))??;

    with_entries(|entries| {
        if let Some(pos) = entries.iter().position(|e| e.id == id) {
            if pos != 0 {
                let e = entries.remove(pos);
                entries.insert(0, e);
            }
        }
    });
    let _ = app.emit("vortex:clipboard", ());
    Ok(())
}

#[tauri::command]
pub fn clipboard_pin(id: String, pinned: bool) {
    with_entries(|entries| {
        if let Some(e) = entries.iter_mut().find(|e| e.id == id) {
            e.pinned = pinned;
        }
    });
}

#[tauri::command]
pub fn clipboard_delete(id: String) {
    with_entries(|entries| {
        if let Some(pos) = entries.iter().position(|e| e.id == id) {
            let e = entries.remove(pos);
            if let (Some(d), Some(f)) = (clip_dir(), &e.file) {
                let _ = std::fs::remove_file(d.join(f));
            }
        }
    });
}

#[tauri::command]
pub fn clipboard_get(id: String) -> Option<ClipEntryDto> {
    let dir = clip_dir();
    with_entries(|entries| {
        entries.iter().find(|e| e.id == id).map(|e| ClipEntryDto {
            id: e.id.clone(),
            kind: e.kind.clone(),
            text: e.text.clone(),
            path: match (&dir, &e.file) {
                (Some(d), Some(f)) => Some(d.join(f).to_string_lossy().into_owned()),
                _ => None,
            },
            bytes: e.bytes,
            ts_ms: e.ts_ms,
            pinned: e.pinned,
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text_entry(id: &str, bytes: u64, pinned: bool) -> ClipEntry {
        ClipEntry {
            id: id.into(),
            kind: "text".into(),
            text: Some("x".into()),
            file: None,
            bytes,
            ts_ms: 0,
            pinned,
        }
    }

    #[test]
    fn evict_drops_oldest_unpinned_beyond_count() {
        let n = MAX_ENTRIES + 10;
        let mut v: Vec<ClipEntry> =
            (0..n).map(|i| text_entry(&format!("e{i}"), 1, false)).collect();
        evict(&mut v);
        assert_eq!(v.len(), MAX_ENTRIES);
        assert_eq!(v[0].id, "e0");
        assert_eq!(v.last().unwrap().id, format!("e{}", MAX_ENTRIES - 1));
    }

    #[test]
    fn evict_skips_pinned() {
        let n = MAX_ENTRIES + 10;
        let mut v: Vec<ClipEntry> =
            (0..n).map(|i| text_entry(&format!("e{i}"), 1, i == n - 1)).collect();
        evict(&mut v);
        assert!(
            v.iter().any(|e| e.id == format!("e{}", n - 1)),
            "pinned tail entry must survive"
        );
        assert_eq!(v.len(), MAX_ENTRIES);
    }

    #[test]
    fn evict_respects_byte_budget() {
        let mut v: Vec<ClipEntry> = (0..10)
            .map(|i| text_entry(&format!("e{i}"), MAX_TOTAL_BYTES / 4, false))
            .collect();
        evict(&mut v);
        let total: u64 = v.iter().map(|e| e.bytes).sum();
        assert!(total <= MAX_TOTAL_BYTES);
        assert_eq!(v[0].id, "e0");
    }
}

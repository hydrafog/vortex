
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};

use tauri::{AppHandle, Emitter};

use sha2::{Digest, Sha256};

use crate::clipboard::{hash_id, now_ms, store_capture};


pub(crate) type ClipboardWriter = std::sync::Arc<
    dyn Fn(vortex_l3_daemon::core::clipboard_mirror::ClipboardMirror)
            -> futures::future::BoxFuture<'static, Result<(), String>>
        + Send
        + Sync,
>;

const RETRY_EVERY: std::time::Duration = std::time::Duration::from_secs(2);

const PENDING_TTL: std::time::Duration = std::time::Duration::from_secs(300);

pub(crate) static CLIPBOARD_SYNC: AtomicBool = AtomicBool::new(true);

static LAST_SYNC_SIG: Mutex<String> = Mutex::new(String::new());

static CLIPBOARD_SEND_TX: std::sync::OnceLock<tokio::sync::mpsc::UnboundedSender<String>> =
    std::sync::OnceLock::new();

pub(crate) type ClipboardImageWriter = std::sync::Arc<
    dyn Fn(Vec<u8>) -> futures::future::BoxFuture<'static, Result<(), String>> + Send + Sync,
>;

static LAST_SYNC_IMG: Mutex<String> = Mutex::new(String::new());

static CLIPBOARD_IMG_SEND_TX: std::sync::OnceLock<
    tokio::sync::mpsc::UnboundedSender<(String, Vec<u8>)>,
> = std::sync::OnceLock::new();

pub(crate) fn sync_sig(text: &str) -> String {
    hash_id(text.as_bytes())
}

pub(crate) fn tidy_text(s: &str) -> String {
    let mut clean = s.trim_matches(|c: char| c == '\n' || c == '\r').to_string();
    while clean.contains("\n\n\n") {
        clean = clean.replace("\n\n\n", "\n\n");
    }
    clean
}

pub(crate) fn img_sig(w: usize, h: usize, rgba: &[u8]) -> String {
    let mut hsh = Sha256::new();
    hsh.update((w as u32).to_le_bytes());
    hsh.update((h as u32).to_le_bytes());
    hsh.update(rgba);
    hex::encode(hsh.finalize())[..16].to_string()
}

pub(crate) fn decode_png_rgba(png: &[u8]) -> Option<(usize, usize, Vec<u8>)> {
    let img = image::load_from_memory_with_format(png, image::ImageFormat::Png)
        .ok()?
        .into_rgba8();
    let (w, h) = img.dimensions();
    Some((w as usize, h as usize, img.into_raw()))
}

pub(crate) fn queue_clipboard_for_sync(text: &str) {
    if !CLIPBOARD_SYNC.load(Ordering::Relaxed) {
        return;
    }
    if let Some(tx) = CLIPBOARD_SEND_TX.get() {
        let _ = tx.send(text.to_string());
    }
}

pub(crate) fn queue_clipboard_image_for_sync(sig: String, png: &[u8]) {
    if !CLIPBOARD_SYNC.load(Ordering::Relaxed) {
        return;
    }
    if png.len() > vortex_l3_daemon::core::clipboard_mirror::MAX_BLE_IMAGE_BYTES {
        return;
    }
    if let Some(tx) = CLIPBOARD_IMG_SEND_TX.get() {
        let _ = tx.send((sig, png.to_vec()));
    }
}

static CLIP_SETTER: Mutex<Option<arboard::Clipboard>> = Mutex::new(None);

pub(crate) fn with_clip_setter<R>(
    f: impl FnOnce(&mut arboard::Clipboard) -> Result<R, String>,
) -> Result<R, String> {
    let mut g = CLIP_SETTER.lock().map_err(|_| "clip setter poisoned")?;
    if g.is_none() {
        *g = Some(arboard::Clipboard::new().map_err(|e| format!("clipboard: {e}"))?);
    }
    f(g.as_mut().unwrap())
}

pub(crate) fn set_system_image(png: &[u8]) -> Result<(), String> {
    let img = image::load_from_memory_with_format(png, image::ImageFormat::Png)
        .map_err(|e| format!("decode png: {e}"))?
        .into_rgba8();
    let (w, h) = img.dimensions();
    let data = arboard::ImageData {
        width: w as usize,
        height: h as usize,
        bytes: std::borrow::Cow::Owned(img.into_raw()),
    };
    with_clip_setter(|cb| cb.set_image(data).map_err(|e| format!("set_image: {e}")))
}

pub(crate) fn set_system_text(text: String) -> Result<(), String> {
    with_clip_setter(|cb| cb.set_text(text).map_err(|e| format!("set_text: {e}")))
}

pub(crate) fn set_local_text(text: &str) -> Result<(), String> {
    let text = tidy_text(text);
    if text.is_empty() {
        return Ok(());
    }
    if let Ok(mut g) = LAST_SYNC_SIG.lock() {
        *g = sync_sig(&text);
    }
    set_system_text(text.clone())?;
    crate::clipboard::store_capture("text", Some(text), None);
    Ok(())
}

#[allow(clippy::type_complexity)]
pub(crate) fn spawn_clipboard_sync(
    app: AppHandle,
) -> (
    tokio::sync::mpsc::UnboundedSender<vortex_l3_daemon::core::clipboard_mirror::ClipboardMirror>,
    std::sync::Arc<tokio::sync::Mutex<Option<ClipboardWriter>>>,
    tokio::sync::mpsc::UnboundedSender<(u16, u16, Vec<u8>)>,
    std::sync::Arc<tokio::sync::Mutex<Option<ClipboardImageWriter>>>,
) {
    use vortex_l3_daemon::core::clipboard_mirror::{ClipboardMirror, ImageAssembler};

    let writer: std::sync::Arc<tokio::sync::Mutex<Option<ClipboardWriter>>> =
        std::sync::Arc::new(tokio::sync::Mutex::new(None));
    let img_writer: std::sync::Arc<tokio::sync::Mutex<Option<ClipboardImageWriter>>> =
        std::sync::Arc::new(tokio::sync::Mutex::new(None));

    let (send_tx, mut send_rx) = tokio::sync::mpsc::unbounded_channel::<String>();
    let _ = CLIPBOARD_SEND_TX.set(send_tx);
    {
        let writer = writer.clone();
        tokio::spawn(async move {
            let mut pending: Option<(String, String, std::time::Instant)> = None;
            let mut retry = tokio::time::interval(RETRY_EVERY);
            retry.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                tokio::select! {
                    got = send_rx.recv() => {
                        let Some(text) = got else { break };
                        if !CLIPBOARD_SYNC.load(Ordering::Relaxed) {
                            continue;
                        }
                        let sig = sync_sig(&text);
                        if LAST_SYNC_SIG.lock().map(|g| *g == sig).unwrap_or(false) {
                            continue;
                        }
                        pending = Some((text, sig, std::time::Instant::now()));
                    }
                    _ = retry.tick() => {}
                }
                let Some((text, sig, queued_at)) = pending.clone() else { continue };
                if queued_at.elapsed() > PENDING_TTL {
                    tracing::info!("clipboard: copy too old to sync; dropped");
                    pending = None;
                    continue;
                }
                let Some(w) = writer.lock().await.clone() else { continue };
                match w(ClipboardMirror::new(text, now_ms())).await {
                    Ok(()) => {
                        pending = None;
                        if let Ok(mut g) = LAST_SYNC_SIG.lock() {
                            *g = sig;
                        }
                        tracing::debug!("→ clipboard synced to phone");
                    }
                    Err(e) => tracing::warn!("clipboard sync send failed (will retry): {e}"),
                }
            }
        });
    }

    let (recv_tx, mut recv_rx) = tokio::sync::mpsc::unbounded_channel::<ClipboardMirror>();
    {
        let app = app.clone();
        tokio::spawn(async move {
            while let Some(clip) = recv_rx.recv().await {
                if !CLIPBOARD_SYNC.load(Ordering::Relaxed) {
                    continue;
                }
                let text = tidy_text(&clip.text);
                if text.is_empty() {
                    continue;
                }
                if let Ok(mut g) = LAST_SYNC_SIG.lock() {
                    *g = sync_sig(&text);
                }
                let t2 = text.clone();
                let applied = match tokio::task::spawn_blocking(move || set_system_text(t2)).await {
                    Ok(Ok(())) => true,
                    Ok(Err(e)) => {
                        tracing::warn!("clipboard: phone text NOT applied: {e}");
                        false
                    }
                    Err(e) => {
                        tracing::warn!("clipboard: apply task join: {e}");
                        false
                    }
                };
                store_capture("text", Some(text), None);
                let _ = app.emit("vortex:clipboard", ());
                if applied {
                    tracing::info!("clipboard: synced from phone");
                }
            }
        });
    }

    let (img_send_tx, mut img_send_rx) =
        tokio::sync::mpsc::unbounded_channel::<(String, Vec<u8>)>();
    let _ = CLIPBOARD_IMG_SEND_TX.set(img_send_tx);
    {
        let img_writer = img_writer.clone();
        tokio::spawn(async move {
            let mut pending: Option<(String, Vec<u8>, std::time::Instant)> = None;
            let mut retry = tokio::time::interval(RETRY_EVERY);
            retry.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                tokio::select! {
                    got = img_send_rx.recv() => {
                        let Some((sig, png)) = got else { break };
                        if !CLIPBOARD_SYNC.load(Ordering::Relaxed) {
                            continue;
                        }
                        if LAST_SYNC_IMG.lock().map(|g| *g == sig).unwrap_or(false) {
                            continue;
                        }
                        pending = Some((sig, png, std::time::Instant::now()));
                    }
                    _ = retry.tick() => {}
                }
                let Some((sig, png, queued_at)) = pending.clone() else { continue };
                if queued_at.elapsed() > PENDING_TTL {
                    tracing::info!("clipboard: copied image too old to sync; dropped");
                    pending = None;
                    continue;
                }
                let Some(w) = img_writer.lock().await.clone() else { continue };
                match w(png).await {
                    Ok(()) => {
                        pending = None;
                        if let Ok(mut g) = LAST_SYNC_IMG.lock() {
                            *g = sig;
                        }
                        tracing::debug!("→ clipboard image synced to phone");
                    }
                    Err(e) => {
                        tracing::warn!("clipboard image sync send failed (will retry): {e}")
                    }
                }
            }
        });
    }

    let (img_recv_tx, mut img_recv_rx) =
        tokio::sync::mpsc::unbounded_channel::<(u16, u16, Vec<u8>)>();
    {
        let app = app.clone();
        tokio::spawn(async move {
            let mut asm = ImageAssembler::default();
            while let Some((total, idx, data)) = img_recv_rx.recv().await {
                if let Some(png) = asm.add(total, idx, data) {
                    apply_synced_image(&app, png).await;
                }
            }
        });
    }

    (recv_tx, writer, img_recv_tx, img_writer)
}

pub(crate) async fn apply_synced_image(app: &AppHandle, png: Vec<u8>) {
    if !CLIPBOARD_SYNC.load(Ordering::Relaxed) {
        return;
    }
    if let Some(sig) = decode_png_rgba(&png).map(|(w, h, rgba)| img_sig(w, h, &rgba)) {
        if let Ok(mut g) = LAST_SYNC_IMG.lock() {
            *g = sig;
        }
    }
    let p2 = png.clone();
    match tokio::task::spawn_blocking(move || set_system_image(&p2)).await {
        Ok(Ok(())) => {
            store_capture("image", None, Some(png));
            let _ = app.emit("vortex:clipboard", ());
            tracing::info!("clipboard: image synced from phone");
        }
        Ok(Err(e)) => tracing::warn!("clipboard image apply failed: {e}"),
        Err(e) => tracing::warn!("clipboard image task join: {e}"),
    }
}

pub(crate) fn downloads_dir() -> Option<PathBuf> {
    static DIR: OnceLock<Option<PathBuf>> = OnceLock::new();
    DIR.get_or_init(|| {
        let home = PathBuf::from(std::env::var_os("HOME")?);
        let dir = xdg_download_dir(&home).unwrap_or_else(|| home.join("Downloads"));
        tracing::info!("received files → {}", dir.display());
        Some(dir)
    })
    .clone()
}

pub(crate) fn downloads_label() -> String {
    downloads_dir()
        .and_then(|d| {
            d.file_name()
                .map(|n| n.to_string_lossy().to_string())
                .filter(|n| !n.is_empty())
        })
        .unwrap_or_else(|| "Downloads".to_string())
}

fn xdg_download_dir(home: &std::path::Path) -> Option<PathBuf> {
    if let Some(v) = std::env::var_os("XDG_DOWNLOAD_DIR") {
        if let Some(p) = expand_home(&v.to_string_lossy(), home) {
            return Some(p);
        }
    }
    let config = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .unwrap_or_else(|| home.join(".config"));
    let text = std::fs::read_to_string(config.join("user-dirs.dirs")).ok()?;
    expand_home(&parse_user_dirs(&text, "XDG_DOWNLOAD_DIR")?, home)
}

fn parse_user_dirs(text: &str, key: &str) -> Option<String> {
    let mut found = None;
    for line in text.lines() {
        let line = line.trim();
        if line.starts_with('#') {
            continue;
        }
        let Some((k, v)) = line.split_once('=') else {
            continue;
        };
        if k.trim() != key {
            continue;
        }
        let v = v.trim();
        let v = v
            .strip_prefix('"')
            .and_then(|s| s.strip_suffix('"'))
            .or_else(|| v.strip_prefix('\'').and_then(|s| s.strip_suffix('\'')))
            .unwrap_or(v);
        if !v.is_empty() {
            found = Some(v.to_string());
        }
    }
    found
}

fn expand_home(raw: &str, home: &std::path::Path) -> Option<PathBuf> {
    let raw = raw.trim();
    for prefix in ["$HOME", "${HOME}", "~"] {
        if let Some(rest) = raw.strip_prefix(prefix) {
            let rest = rest.trim_start_matches('/');
            return Some(if rest.is_empty() {
                home.to_path_buf()
            } else {
                home.join(rest)
            });
        }
    }
    let p = PathBuf::from(raw);
    p.is_absolute().then_some(p)
}

fn unique_path(dir: &std::path::Path, name: &str) -> PathBuf {
    let first = dir.join(name);
    if !first.exists() {
        return first;
    }
    let p = std::path::Path::new(name);
    let stem = p
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| name.to_string());
    let ext = p
        .extension()
        .map(|e| format!(".{}", e.to_string_lossy()))
        .unwrap_or_default();
    for n in 1..10_000 {
        let cand = dir.join(format!("{stem} ({n}){ext}"));
        if !cand.exists() {
            return cand;
        }
    }
    first
}

pub(crate) async fn apply_synced_file(
    _app: &AppHandle,
    name: &str,
    _mime: &str,
    bytes: Vec<u8>,
) -> Option<PathBuf> {
    let safe = std::path::Path::new(name)
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "vortex-file".to_string());
    let Some(dir) = downloads_dir() else {
        tracing::warn!("received file: no HOME — dropped");
        return None;
    };
    let size = bytes.len();
    let safe2 = safe.clone();
    let saved = tokio::task::spawn_blocking(move || -> std::io::Result<PathBuf> {
        std::fs::create_dir_all(&dir)?;
        let path = unique_path(&dir, &safe2);
        std::fs::write(&path, &bytes)?;
        Ok(path)
    })
    .await;
    let path = match saved {
        Ok(Ok(p)) => p,
        Ok(Err(e)) => {
            tracing::warn!("received file write failed: {e}");
            return None;
        }
        Err(e) => {
            tracing::warn!("received file task join: {e}");
            return None;
        }
    };
    tracing::info!(bytes = size, name = %safe, "file received from phone → {}", path.display());
    Some(path)
}

type Offer = vortex_l3_daemon::core::clipboard_mirror::ClipboardImageOffer;

async fn flush_file_batch(batch: Vec<Offer>) {
    if batch.is_empty() {
        return;
    }
    let batch: Vec<Offer> = {
        let queued: std::collections::HashSet<String> = crate::PENDING_FILE_OFFERS
            .get()
            .and_then(|m| m.lock().ok().map(|g| g.iter().map(|(t, ..)| t.clone()).collect()))
            .unwrap_or_default();
        let mut seen = std::collections::HashSet::new();
        batch
            .into_iter()
            .filter(|o| !queued.contains(&o.token) && seen.insert(o.token.clone()))
            .collect()
    };
    if batch.is_empty() {
        tracing::info!("phone re-announced file offer(s) already queued; ignoring");
        return;
    }
    let count = batch.len();
    let total: u64 = batch.iter().map(|o| o.bytes).sum();
    let label = if count == 1 {
        batch[0].name.clone()
    } else {
        format!("{count} files")
    };
    let accepted = crate::file_consent::request(&label, count, total).await;
    if !accepted {
        tracing::info!(count, "phone file offer(s) declined on laptop");
        return;
    }
    for offer in &batch {
        let id = crate::transfers::start(&offer.name, offer.bytes);
        if let Some(q) = crate::PENDING_FILE_OFFERS.get() {
            if let Ok(mut g) = q.lock() {
                g.push_back((offer.token.clone(), offer.name.clone(), offer.mime.clone(), id));
            }
        }
    }
    crate::lan::note_queue_progress();
    tracing::info!(count, "phone file offer(s) accepted → LAN pull nudged");
    if let Some(nudge) = crate::SYNC_NUDGE.get() {
        nudge.notify_one();
    }
}

pub(crate) fn spawn_image_offer_consumer() -> tokio::sync::mpsc::UnboundedSender<Offer> {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Offer>();
    tokio::spawn(async move {
        let mut file_buf: Vec<Offer> = Vec::new();
        loop {
            let next = if file_buf.is_empty() {
                rx.recv().await
            } else {
                match tokio::time::timeout(std::time::Duration::from_millis(1200), rx.recv()).await {
                    Ok(v) => v,
                    Err(_) => {
                        flush_file_batch(std::mem::take(&mut file_buf)).await;
                        continue;
                    }
                }
            };
            let offer = match next {
                Some(o) => o,
                None => {
                    flush_file_batch(std::mem::take(&mut file_buf)).await;
                    break;
                }
            };
            if !CLIPBOARD_SYNC.load(Ordering::Relaxed) {
                continue;
            }
            if offer.is_file() {
                file_buf.push(offer);
            } else {
                if let Some(slot) = crate::PENDING_IMAGE_TOKEN.get() {
                    if let Ok(mut g) = slot.lock() {
                        *g = Some(offer.token.clone());
                    }
                }
                tracing::info!(bytes = offer.bytes, "clipboard image offer → LAN pull nudged");
                if let Some(nudge) = crate::SYNC_NUDGE.get() {
                    nudge.notify_one();
                }
            }
        }
    });
    tx
}

#[tauri::command]
pub fn set_clipboard_sync(enabled: bool) {
    CLIPBOARD_SYNC.store(enabled, Ordering::Relaxed);
}

#[tauri::command]
pub fn get_clipboard_sync() -> bool {
    CLIPBOARD_SYNC.load(Ordering::Relaxed)
}

#[cfg(test)]
mod tests {
    use super::*;

    const FR: &str = r#"# This file is written by xdg-user-dirs-update
# If you want to change or add directories, just edit the line you're
XDG_DESKTOP_DIR="$HOME/Bureau"
XDG_DOWNLOAD_DIR="$HOME/Téléchargements"
XDG_DOCUMENTS_DIR="$HOME/Documents"
"#;

    #[test]
    fn parses_localised_download_dir() {
        let raw = parse_user_dirs(FR, "XDG_DOWNLOAD_DIR").expect("download dir");
        assert_eq!(raw, "$HOME/Téléchargements");
        assert_eq!(
            expand_home(&raw, std::path::Path::new("/home/cyril")),
            Some(PathBuf::from("/home/cyril/Téléchargements"))
        );
    }

    #[test]
    fn ignores_comments_and_other_keys() {
        assert_eq!(parse_user_dirs(FR, "XDG_MUSIC_DIR"), None);
        let text = "#XDG_DOWNLOAD_DIR=\"$HOME/nope\"\nXDG_DOWNLOAD_DIR=\"$HOME/yes\"\n";
        assert_eq!(
            parse_user_dirs(text, "XDG_DOWNLOAD_DIR"),
            Some("$HOME/yes".to_string())
        );
    }

    #[test]
    fn last_assignment_wins_like_a_shell() {
        let text = "XDG_DOWNLOAD_DIR=\"$HOME/first\"\nXDG_DOWNLOAD_DIR=\"$HOME/second\"\n";
        assert_eq!(
            parse_user_dirs(text, "XDG_DOWNLOAD_DIR"),
            Some("$HOME/second".to_string())
        );
    }

    #[test]
    fn expands_home_forms_and_rejects_relative() {
        let home = std::path::Path::new("/home/cyril");
        for raw in ["$HOME/Dl", "${HOME}/Dl", "~/Dl"] {
            assert_eq!(expand_home(raw, home), Some(PathBuf::from("/home/cyril/Dl")));
        }
        assert_eq!(expand_home("$HOME/", home), Some(home.to_path_buf()));
        assert_eq!(expand_home("/data/dl", home), Some(PathBuf::from("/data/dl")));
        assert_eq!(expand_home("Downloads", home), None);
        assert_eq!(expand_home("", home), None);
    }

    #[test]
    fn handles_unquoted_and_single_quoted() {
        assert_eq!(
            parse_user_dirs("XDG_DOWNLOAD_DIR=$HOME/Dl\n", "XDG_DOWNLOAD_DIR"),
            Some("$HOME/Dl".to_string())
        );
        assert_eq!(
            parse_user_dirs("XDG_DOWNLOAD_DIR='$HOME/Dl'\n", "XDG_DOWNLOAD_DIR"),
            Some("$HOME/Dl".to_string())
        );
    }
}


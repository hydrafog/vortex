use std::path::Path;

use tauri::AppHandle;
use vortex_l3_daemon::core::outgoing_share::{enqueue_batch, OutgoingFile};

pub(crate) fn handle_share(_app: &AppHandle, paths: Vec<String>) -> Result<usize, String> {
    let mut batch: Vec<OutgoingFile> = Vec::new();
    for p in &paths {
        let path = Path::new(p);
        let meta = match std::fs::metadata(path) {
            Ok(m) => m,
            Err(e) => {
                tracing::warn!(path = %p, "share: cannot stat: {e}");
                continue;
            }
        };
        let prepared = if meta.is_dir() { zip_folder(path) } else { read_file(path) };
        match prepared {
            Some(f) if f.size > 0 => {
                tracing::info!(name = %f.name, size = f.size, "share: added to batch");
                batch.push(f);
            }
            Some(f) => {
                tracing::warn!(name = %f.name, size = f.size, "share: empty file; skipping");
            }
            None => {}
        }
    }
    if batch.is_empty() {
        tracing::warn!("share: nothing to send");
        return Err("No valid files selected".to_string());
    }
    let count = batch.len();
    if enqueue_batch(batch) {
        tracing::info!(count, "share: batch queued for push to phone");
        if let Some(n) = crate::SYNC_NUDGE.get() {
            n.notify_one();
        }
        Ok(count)
    } else {
        tracing::warn!(count, "share: batch rejected");
        Err("Batch rejected: could not queue files".to_string())
    }
}

pub(crate) async fn pick_files() -> Option<Vec<String>> {
    let candidates: [(&str, Vec<&str>); 2] = [
        (
            "zenity",
            vec![
                "--file-selection",
                "--multiple",
                "--separator=\n",
                "--title=Select files to send to phone",
            ],
        ),
        (
            "kdialog",
            vec![
                "--getopenfilename",
                "--multiple",
                "--separate-output",
                "--title=Select files to send to phone",
            ],
        ),
    ];
    for (bin, args) in candidates {
        match tokio::process::Command::new(bin).args(&args).output().await {
            Ok(out) => {
                if !out.status.success() {
                    return None;
                }
                let text = String::from_utf8_lossy(&out.stdout);
                let files: Vec<String> =
                    text.lines().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect();
                return if files.is_empty() { None } else { Some(files) };
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(_) => return None,
        }
    }
    None
}

#[tauri::command]
pub async fn send_files(app: AppHandle, paths: Vec<String>) -> Result<usize, String> {
    handle_share(&app, paths)
}

#[tauri::command]
pub async fn pick_and_send_files(app: AppHandle) -> Result<usize, String> {
    if let Some(paths) = pick_files().await {
        if !paths.is_empty() {
            return handle_share(&app, paths);
        }
    }
    Ok(0)
}

fn read_file(path: &Path) -> Option<OutgoingFile> {
    OutgoingFile::from_path(path)
}

fn zip_folder(dir: &Path) -> Option<OutgoingFile> {
    let folder_name = dir
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "folder".to_string());

    let tmp_path =
        std::env::temp_dir().join(format!("vortex_{}_{}.zip", folder_name, std::process::id()));
    let file = std::fs::File::create(&tmp_path).ok()?;
    let mut zw = zip::ZipWriter::new(file);
    let opts: zip::write::FileOptions<()> =
        zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Deflated);

    let mut count = 0usize;
    for entry in walkdir::WalkDir::new(dir).into_iter().filter_map(|e| e.ok()) {
        let path = entry.path();
        let rel = match path.strip_prefix(dir) {
            Ok(r) => r,
            Err(_) => continue,
        };
        if rel.as_os_str().is_empty() {
            continue;
        }
        let arc_name = rel.to_string_lossy().to_string();
        if entry.file_type().is_dir() {
            if zw.add_directory(format!("{arc_name}/"), opts).is_err() {
                tracing::warn!(folder = %folder_name, "share: zip add_directory failed");
                let _ = std::fs::remove_file(&tmp_path);
                return None;
            }
        } else if entry.file_type().is_file() {
            let mut f = match std::fs::File::open(path) {
                Ok(f) => f,
                Err(e) => {
                    tracing::warn!(path = %path.display(), "share: zip skip unreadable: {e}");
                    continue;
                }
            };
            if zw.start_file(&arc_name, opts).is_err() {
                let _ = std::fs::remove_file(&tmp_path);
                return None;
            }
            if std::io::copy(&mut f, &mut zw).is_err() {
                let _ = std::fs::remove_file(&tmp_path);
                return None;
            }
            count += 1;
        }
    }

    if zw.finish().is_err() || count == 0 {
        let _ = std::fs::remove_file(&tmp_path);
        tracing::warn!(folder = %folder_name, "share: folder empty or finish failed; skipping");
        return None;
    }
    let meta = std::fs::metadata(&tmp_path).ok()?;
    tracing::info!(folder = %folder_name, files = count, size = meta.len(), "share: folder zipped to disk");
    Some(OutgoingFile {
        name: format!("{folder_name}.zip"),
        mime: "application/zip".to_string(),
        size: meta.len(),
        path: Some(tmp_path),
        bytes: Vec::new(),
        extract: true,
    })
}

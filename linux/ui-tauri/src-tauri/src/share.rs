
use std::io::{Cursor, Read, Write};
use std::path::Path;

use tauri::AppHandle;
use vortex_l3_daemon::core::outgoing_share::{enqueue_batch, OutgoingFile, MAX_PUSH_BYTES};

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
        let prepared = if meta.is_dir() {
            zip_folder(path)
        } else {
            read_file(path)
        };
        match prepared {
            Some(f) if !f.bytes.is_empty() && f.bytes.len() <= MAX_PUSH_BYTES => {
                tracing::info!(name = %f.name, bytes = f.bytes.len(), "share: added to batch");
                batch.push(f);
            }
            Some(f) => {
                tracing::warn!(name = %f.name, len = f.bytes.len(), "share: empty or over cap; skipping");
            }
            None => {}
        }
    }
    if batch.is_empty() {
        tracing::warn!("share: nothing to send");
        return Err("No valid files selected or files exceed size limit".to_string());
    }
    let count = batch.len();
    if enqueue_batch(batch) {
        tracing::info!(count, "share: batch queued for push to phone");
        if let Some(n) = crate::SYNC_NUDGE.get() {
            n.notify_one();
        }
        Ok(count)
    } else {
        tracing::warn!(count, "share: batch rejected (empty or over batch cap)");
        Err("Batch rejected: exceeds total batch size limit".to_string())
    }
}

pub(crate) async fn pick_files() -> Option<Vec<String>> {
    let candidates: [(&str, Vec<&str>); 2] = [
        ("zenity", vec!["--file-selection", "--multiple", "--separator=\n", "--title=Select files to send to phone"]),
        ("kdialog", vec!["--getopenfilename", "--multiple", "--separate-output", "--title=Select files to send to phone"]),
    ];
    for (bin, args) in candidates {
        match tokio::process::Command::new(bin).args(&args).output().await {
            Ok(out) => {
                if !out.status.success() {
                    return None;
                }
                let text = String::from_utf8_lossy(&out.stdout);
                let files: Vec<String> = text
                    .lines()
                    .map(|l| l.trim().to_string())
                    .filter(|l| !l.is_empty())
                    .collect();
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
    let bytes = match std::fs::read(path) {
        Ok(b) => b,
        Err(e) => {
            tracing::warn!(path = %path.display(), "share: cannot read: {e}");
            return None;
        }
    };
    let name = path
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "vortex-file".to_string());
    Some(OutgoingFile {
        name,
        mime: "application/octet-stream".to_string(),
        bytes,
        extract: false,
    })
}

fn zip_folder(dir: &Path) -> Option<OutgoingFile> {
    let folder_name = dir
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "folder".to_string());

    let mut zw = zip::ZipWriter::new(Cursor::new(Vec::<u8>::new()));
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
                return None;
            }
            let mut buf = Vec::new();
            if f.read_to_end(&mut buf).is_err() || zw.write_all(&buf).is_err() {
                return None;
            }
            count += 1;
        }
    }

    let bytes = zw.finish().ok()?.into_inner();
    if count == 0 {
        tracing::warn!(folder = %folder_name, "share: folder empty; skipping");
        return None;
    }
    tracing::info!(folder = %folder_name, files = count, bytes = bytes.len(), "share: folder zipped (in-process)");
    Some(OutgoingFile {
        name: format!("{folder_name}.zip"),
        mime: "application/zip".to_string(),
        bytes,
        extract: true,
    })
}

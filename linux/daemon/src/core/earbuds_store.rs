use std::path::PathBuf;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SavedEarbuds {
    pub address: String,
    pub name: String,
}

fn config_dir() -> Option<PathBuf> {
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))?;
    Some(base.join("vortex"))
}

fn config_path() -> Option<PathBuf> {
    Some(config_dir()?.join("earbuds.json"))
}

fn autodetect_marker_path() -> Option<PathBuf> {
    Some(config_dir()?.join("earbuds_autodetect.done"))
}

pub fn autodetect_done() -> bool {
    autodetect_marker_path().map(|p| p.exists()).unwrap_or(false)
}

pub fn mark_autodetect_done() -> std::io::Result<()> {
    let path = autodetect_marker_path().ok_or_else(|| std::io::Error::other("no config dir"))?;
    crate::core::fs_private::write_private(&path, b"1")
}

pub fn load() -> Option<SavedEarbuds> {
    let path = config_path()?;
    let bytes = std::fs::read(&path).ok()?;
    serde_json::from_slice(&bytes).ok()
}

pub fn save(value: &SavedEarbuds) -> std::io::Result<()> {
    let path = config_path().ok_or_else(|| std::io::Error::other("no config dir"))?;
    let bytes = serde_json::to_vec_pretty(value).map_err(std::io::Error::other)?;
    crate::core::fs_private::write_private(&path, &bytes)
}

pub fn clear() -> std::io::Result<()> {
    if let Some(path) = config_path() {
        match std::fs::remove_file(&path) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e),
        }
    } else {
        Ok(())
    }
}

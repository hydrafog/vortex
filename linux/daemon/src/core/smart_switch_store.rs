use std::path::PathBuf;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub struct SmartSwitch {
    pub enabled: bool,
    pub changed_at: u64,
}

impl Default for SmartSwitch {
    fn default() -> Self {
        SmartSwitch { enabled: true, changed_at: 0 }
    }
}

fn config_path() -> Option<PathBuf> {
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))?;
    Some(base.join("vortex").join("smart_switch.json"))
}

pub fn load() -> SmartSwitch {
    config_path()
        .and_then(|p| std::fs::read(&p).ok())
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

pub fn save(value: &SmartSwitch) -> std::io::Result<()> {
    let path = config_path().ok_or_else(|| std::io::Error::other("no config dir"))?;
    let bytes = serde_json::to_vec_pretty(value).map_err(std::io::Error::other)?;
    crate::core::fs_private::write_private(&path, &bytes)
}

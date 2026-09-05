
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::OnceLock;

fn norm(s: &str) -> String {
    s.chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .flat_map(|c| c.to_lowercase())
        .collect()
}

fn app_dirs() -> Vec<PathBuf> {
    let mut dirs: Vec<PathBuf> = Vec::new();
    let home = std::env::var("HOME").unwrap_or_default();
    let data_home = std::env::var("XDG_DATA_HOME")
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| format!("{home}/.local/share"));
    dirs.push(PathBuf::from(format!("{data_home}/applications")));
    let data_dirs = std::env::var("XDG_DATA_DIRS")
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "/usr/local/share:/usr/share".to_string());
    for d in data_dirs.split(':').filter(|s| !s.is_empty()) {
        dirs.push(PathBuf::from(format!("{d}/applications")));
    }
    dirs.push(PathBuf::from("/var/lib/flatpak/exports/share/applications"));
    dirs.push(PathBuf::from(format!(
        "{data_home}/flatpak/exports/share/applications"
    )));
    dirs.push(PathBuf::from("/var/lib/snapd/desktop/applications"));
    dirs
}

fn index() -> &'static HashMap<String, PathBuf> {
    static INDEX: OnceLock<HashMap<String, PathBuf>> = OnceLock::new();
    INDEX.get_or_init(|| {
        let mut map: HashMap<String, PathBuf> = HashMap::new();
        for dir in app_dirs() {
            let Ok(entries) = std::fs::read_dir(&dir) else {
                continue;
            };
            for entry in entries.flatten() {
                let path = entry.path();
                if path.extension().and_then(|e| e.to_str()) != Some("desktop") {
                    continue;
                }
                let Ok(text) = std::fs::read_to_string(&path) else {
                    continue;
                };
                let mut in_main = false;
                let mut keys: Vec<String> = Vec::new();
                let mut hidden = false;
                for line in text.lines() {
                    let line = line.trim();
                    if line.starts_with('[') {
                        in_main = line == "[Desktop Entry]";
                        continue;
                    }
                    if !in_main {
                        continue;
                    }
                    if let Some(v) = line.strip_prefix("Name=") {
                        keys.push(norm(v));
                    } else if let Some(v) = line.strip_prefix("StartupWMClass=") {
                        keys.push(norm(v));
                    } else if line == "NoDisplay=true" || line == "Hidden=true" {
                        hidden = true;
                    }
                }
                if hidden {
                    continue;
                }
                for k in keys {
                    if !k.is_empty() {
                        map.entry(k).or_insert_with(|| path.clone());
                    }
                }
            }
        }
        map
    })
}

pub(crate) fn match_label(label: &str) -> Option<PathBuf> {
    let want = norm(label);
    if want.len() < 3 {
        return None;
    }
    let idx = index();
    if let Some(p) = idx.get(&want) {
        return Some(p.clone());
    }
    idx.iter()
        .find(|(k, _)| k.contains(&want) || want.contains(k.as_str()))
        .map(|(_, p)| p.clone())
}

pub(crate) fn launch(desktop: &std::path::Path) {
    if tokio::process::Command::new("gio")
        .arg("launch")
        .arg(desktop)
        .spawn()
        .is_ok()
    {
        return;
    }
    if let Some(id) = desktop.file_stem().and_then(|s| s.to_str()) {
        let _ = tokio::process::Command::new("gtk-launch").arg(id).spawn();
    }
}

#[cfg(test)]
mod tests {
    use super::norm;

    #[test]
    fn norm_collapses_variants() {
        assert_eq!(norm("Telegram Desktop"), "telegramdesktop");
        assert_eq!(norm("TelegramDesktop"), "telegramdesktop");
        assert_eq!(norm("Signal"), "signal");
        assert_eq!(norm(""), "");
    }
}

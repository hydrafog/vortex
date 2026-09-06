#[tauri::command]
pub(crate) fn get_system_accent_color() -> Result<Option<String>, String> {
    let home = std::env::var("HOME").unwrap_or_default();
    let cache_dir = std::env::var("XDG_CACHE_HOME").unwrap_or_else(|_| format!("{home}/.cache"));
    let ricelin_colors = std::path::Path::new(&cache_dir).join("ricelin/colors.json");
    if ricelin_colors.is_file() {
        if let Ok(contents) = std::fs::read_to_string(&ricelin_colors) {
            if let Ok(val) = serde_json::from_str::<serde_json::Value>(&contents) {
                if let Some(primary) = val.get("primary").and_then(|v| v.as_str()) {
                    let hex = primary.trim().to_string();
                    if !hex.is_empty() {
                        return Ok(Some(hex));
                    }
                }
            }
        }
    }

    if let Ok(output) = std::process::Command::new("gsettings")
        .args(["get", "org.gnome.desktop.interface", "accent-color"])
        .output()
    {
        if output.status.success() {
            let s = String::from_utf8_lossy(&output.stdout)
                .trim()
                .trim_matches('\'')
                .trim_matches('"')
                .to_string();
            if !s.is_empty() && s != "@as []" {
                return Ok(Some(s));
            }
        }
    }

    for kread in ["kreadconfig6", "kreadconfig5"] {
        if let Ok(output) = std::process::Command::new(kread)
            .args(["--group", "General", "--key", "AccentColor"])
            .output()
        {
            if output.status.success() {
                let s = String::from_utf8_lossy(&output.stdout).trim().to_string();
                if !s.is_empty() {
                    return Ok(Some(s));
                }
            }
        }
    }
    Ok(None)
}

#[tauri::command]
pub(crate) fn get_local_device_name() -> Result<String, String> {
    if let Ok(hostname) = std::fs::read_to_string("/proc/sys/kernel/hostname") {
        let h = hostname.trim();
        if !h.is_empty() {
            return Ok(h.to_string());
        }
    }
    if let Ok(hostname) = std::env::var("HOSTNAME") {
        let h = hostname.trim();
        if !h.is_empty() {
            return Ok(h.to_string());
        }
    }
    Ok("Linux".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_get_local_device_name_returns_valid_string() {
        let name = get_local_device_name().expect("must succeed");
        assert!(!name.is_empty());
    }

    #[test]
    fn test_get_system_accent_color_executes_safely() {
        let res = get_system_accent_color();
        assert!(res.is_ok());
    }
}

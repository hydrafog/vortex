#[tauri::command]
pub(crate) fn get_system_accent_color() -> Result<Option<String>, String> {
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

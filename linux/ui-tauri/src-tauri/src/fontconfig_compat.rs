pub fn setup_fontconfig_compat() {
    if let Ok(existing) = std::env::var("FONTCONFIG_FILE") {
        if existing != "/etc/fonts/fonts.conf" && std::path::Path::new(&existing).exists() {
            return;
        }
    }

    let conf_d = std::path::Path::new("/etc/fonts/conf.d");
    if !conf_d.is_dir() {
        return;
    }

    let guess_family = conf_d.join("48-guessfamily.conf");
    let sans_serif = conf_d.join("49-sansserif.conf");
    if !guess_family.exists() && !sans_serif.exists() {
        return;
    }

    let base_fonts_conf = std::path::Path::new("/etc/fonts/fonts.conf");
    let Ok(base_content) = std::fs::read_to_string(base_fonts_conf) else {
        return;
    };

    let target_include = "<include ignore_missing=\"yes\">/etc/fonts/conf.d</include>";
    if !base_content.contains(target_include) {
        return;
    }

    let Ok(entries) = std::fs::read_dir(conf_d) else {
        return;
    };

    let mut conf_files = Vec::new();
    for entry in entries.flatten() {
        let p = entry.path();
        if let Some(ext) = p.extension() {
            if ext == "conf" {
                let file_name = p.file_name().and_then(|n| n.to_str()).unwrap_or_default();
                if file_name != "48-guessfamily.conf" && file_name != "49-sansserif.conf" {
                    conf_files.push(p);
                }
            }
        }
    }
    conf_files.sort();

    let mut replacement = String::new();
    for f in conf_files {
        replacement
            .push_str(&format!("  <include ignore_missing=\"yes\">{}</include>\n", f.display()));
    }

    let sanitized_content = base_content.replace(target_include, &replacement);

    let cache_dir = std::env::var_os("XDG_CACHE_HOME")
        .map(std::path::PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| std::path::PathBuf::from(h).join(".cache")))
        .unwrap_or_else(|| std::path::PathBuf::from("/tmp"))
        .join("vortex");

    if let Err(e) = std::fs::create_dir_all(&cache_dir) {
        tracing::debug!("fontconfig-compat: failed to create cache dir: {e}");
        return;
    }

    let sanitized_path = cache_dir.join("fonts.conf");

    let needs_write = match std::fs::read_to_string(&sanitized_path) {
        Ok(curr) => curr != sanitized_content,
        Err(_) => true,
    };

    if needs_write {
        if let Err(e) = std::fs::write(&sanitized_path, &sanitized_content) {
            tracing::debug!("fontconfig-compat: failed to write fonts.conf: {e}");
            return;
        }
    }

    std::env::set_var("FONTCONFIG_FILE", &sanitized_path);
    tracing::debug!(path = ?sanitized_path, "fontconfig-compat: loaded sanitized font configuration");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_setup_fontconfig_compat() {
        setup_fontconfig_compat();
        if std::path::Path::new("/etc/fonts/conf.d/49-sansserif.conf").exists() {
            assert!(std::env::var("FONTCONFIG_FILE").is_ok());
        }
    }
}

use std::collections::HashMap;
use std::path::PathBuf;

pub fn parse_chunk(plain: &[u8]) -> Option<(String, u16, u16, Vec<u8>)> {
    let id_len = *plain.first()? as usize;
    let mut o = 1;
    if plain.len() < o + id_len + 4 {
        return None;
    }
    let app_id = String::from_utf8(plain[o..o + id_len].to_vec()).ok()?;
    o += id_len;
    let total = u16::from_be_bytes([plain[o], plain[o + 1]]);
    o += 2;
    let idx = u16::from_be_bytes([plain[o], plain[o + 1]]);
    o += 2;
    Some((app_id, total, idx, plain[o..].to_vec()))
}

fn cache_dir() -> Option<PathBuf> {
    let mut p = PathBuf::from(std::env::var_os("HOME")?);
    p.push(".cache/vortex/icons");
    Some(p)
}

fn sanitize(app_id: &str) -> String {
    app_id
        .chars()
        .map(
            |c| {
                if c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-' {
                    c
                } else {
                    '_'
                }
            },
        )
        .collect()
}

const GENERIC_ICON_PNG: &[u8] = include_bytes!("../assets/generic_icon.png");

pub fn ensure_generic() -> Option<PathBuf> {
    let mut p = cache_dir()?;
    let _ = crate::core::fs_private::create_private_dir(&p);
    p.push("_generic.png");
    if !p.exists() {
        let _ = crate::core::fs_private::write_private(&p, GENERIC_ICON_PNG);
    }
    Some(p)
}

const VORTEX_ICON_PNG: &[u8] = include_bytes!("../assets/vortex_icon.png");

pub fn ensure_vortex() -> Option<PathBuf> {
    let mut p = cache_dir()?;
    let _ = crate::core::fs_private::create_private_dir(&p);
    p.push("vortex.png");
    if !p.exists() {
        let _ = crate::core::fs_private::write_private(&p, VORTEX_ICON_PNG);
    }
    Some(p)
}

pub fn icon_path(app_id: &str) -> Option<PathBuf> {
    if app_id.is_empty() {
        return None;
    }
    let mut p = cache_dir()?;
    p.push(format!("{}.png", sanitize(app_id)));
    Some(p)
}

pub const MAX_CHUNKS: u16 = 512;

pub const MAX_IN_FLIGHT: usize = 32;

#[derive(Default)]
pub struct IconAssembler {
    partial: HashMap<String, (u16, Vec<Option<Vec<u8>>>)>,
}

impl IconAssembler {
    pub fn add(&mut self, app_id: String, total: u16, idx: u16, data: Vec<u8>) -> Option<PathBuf> {
        if total == 0 || total > MAX_CHUNKS || idx >= total {
            return None;
        }
        if self.partial.len() >= MAX_IN_FLIGHT && !self.partial.contains_key(&app_id) {
            return None;
        }
        let entry = self
            .partial
            .entry(app_id.clone())
            .or_insert_with(|| (total, vec![None; total as usize]));
        if entry.0 != total {
            *entry = (total, vec![None; total as usize]);
        }
        entry.1[idx as usize] = Some(data);
        if entry.1.iter().any(|c| c.is_none()) {
            return None;
        }
        let mut bytes = Vec::new();
        for c in &entry.1 {
            bytes.extend_from_slice(c.as_ref().unwrap());
        }
        self.partial.remove(&app_id);
        let path = icon_path(&app_id)?;
        crate::core::fs_private::write_private(&path, &bytes).ok().map(|_| path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_total_above_cap() {
        let mut asm = IconAssembler::default();
        assert!(asm.add("com.app".into(), MAX_CHUNKS + 1, 0, b"x".to_vec()).is_none());
        assert!(asm.partial.is_empty());
    }

    #[test]
    fn caps_in_flight_app_ids() {
        let mut asm = IconAssembler::default();
        for i in 0..MAX_IN_FLIGHT {
            assert!(asm.add(format!("app{i}"), 2, 0, b"x".to_vec()).is_none());
        }
        assert_eq!(asm.partial.len(), MAX_IN_FLIGHT);
        assert!(asm.add("one-too-many".into(), 2, 0, b"x".to_vec()).is_none());
        assert_eq!(asm.partial.len(), MAX_IN_FLIGHT);
        assert!(asm.partial.contains_key("app0"));
    }
}

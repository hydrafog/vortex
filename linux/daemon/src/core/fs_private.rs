use std::fs;
use std::io;
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt};
use std::path::Path;

pub fn create_private_dir(dir: &Path) -> io::Result<()> {
    match fs::DirBuilder::new().recursive(true).mode(0o700).create(dir) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {}
        Err(e) => return Err(e),
    }
    fs::set_permissions(dir, fs::Permissions::from_mode(0o700))
}

pub fn write_private(path: &Path, bytes: &[u8]) -> io::Result<()> {
    if let Some(parent) = path.parent() {
        create_private_dir(parent)?;
    }
    use std::io::Write;
    let mut f =
        fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(path)?;
    f.set_permissions(fs::Permissions::from_mode(0o600))?;
    f.write_all(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn mode_of(p: &Path) -> u32 {
        fs::metadata(p).unwrap().permissions().mode() & 0o777
    }

    #[test]
    fn dir_and_file_are_owner_only() {
        let base = std::env::temp_dir().join(format!("vortex-fsp-{}", std::process::id()));
        let dir = base.join("nested");
        let file = dir.join("data.json");
        write_private(&file, b"x").unwrap();
        assert_eq!(mode_of(&dir), 0o700);
        assert_eq!(mode_of(&file), 0o600);
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn repairs_existing_loose_permissions() {
        let base = std::env::temp_dir().join(format!("vortex-fsp-fix-{}", std::process::id()));
        fs::create_dir_all(&base).unwrap();
        fs::set_permissions(&base, fs::Permissions::from_mode(0o755)).unwrap();
        let file = base.join("data.json");
        fs::write(&file, b"old").unwrap();
        fs::set_permissions(&file, fs::Permissions::from_mode(0o644)).unwrap();

        write_private(&file, b"new").unwrap();
        assert_eq!(mode_of(&base), 0o700);
        assert_eq!(mode_of(&file), 0o600);
        assert_eq!(fs::read(&file).unwrap(), b"new");
        let _ = fs::remove_dir_all(&base);
    }
}

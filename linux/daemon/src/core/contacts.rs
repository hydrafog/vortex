use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Contact {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub numbers: Vec<String>,
}

pub fn parse_chunk(plain: &[u8]) -> Option<(u16, u16, Vec<u8>)> {
    if plain.len() < 4 {
        return None;
    }
    let total = u16::from_be_bytes([plain[0], plain[1]]);
    let idx = u16::from_be_bytes([plain[2], plain[3]]);
    Some((total, idx, plain[4..].to_vec()))
}

pub const MAX_CHUNKS: u16 = 2048;

#[derive(Default)]
pub struct ContactsAssembler {
    total: u16,
    chunks: Vec<Option<Vec<u8>>>,
}

impl ContactsAssembler {
    pub fn add(&mut self, total: u16, idx: u16, data: Vec<u8>) -> Option<Vec<u8>> {
        if total == 0 || total > MAX_CHUNKS || idx >= total {
            return None;
        }
        if self.total != total {
            self.total = total;
            self.chunks = vec![None; total as usize];
        }
        self.chunks[idx as usize] = Some(data);
        if self.chunks.iter().any(|c| c.is_none()) {
            return None;
        }
        let mut bytes = Vec::new();
        for c in &self.chunks {
            bytes.extend_from_slice(c.as_ref().unwrap());
        }
        self.total = 0;
        self.chunks = Vec::new();
        Some(bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn assembles_in_any_order() {
        let mut asm = ContactsAssembler::default();
        assert!(asm.add(2, 1, b"b".to_vec()).is_none());
        assert_eq!(asm.add(2, 0, b"a".to_vec()), Some(b"ab".to_vec()));
    }

    #[test]
    fn rejects_total_above_cap() {
        let mut asm = ContactsAssembler::default();
        assert!(asm.add(MAX_CHUNKS + 1, 0, b"x".to_vec()).is_none());
        assert!(asm.add(u16::MAX, 0, b"x".to_vec()).is_none());
        assert_eq!(asm.add(1, 0, b"ok".to_vec()), Some(b"ok".to_vec()));
    }
}

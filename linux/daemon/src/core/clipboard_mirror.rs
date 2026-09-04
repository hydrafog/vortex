use serde::{Deserialize, Serialize};

pub const MAX_CLIPBOARD_TEXT_CHARS: usize = 65_536;

pub const MAX_SINGLE_FRAME_TEXT_BYTES: usize = 400;

pub const MAX_FILE_BYTES: u64 = 64 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ClipboardImageOffer {
    #[serde(default)]
    pub token: String,
    #[serde(default)]
    pub bytes: u64,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub mime: String,
}

impl ClipboardImageOffer {
    pub fn is_file(&self) -> bool {
        !self.name.is_empty()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ClipboardMirror {
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub ts: u64,
}

impl ClipboardMirror {
    pub fn new(text: impl Into<String>, ts: u64) -> Self {
        let mut text = text.into();
        if text.chars().count() > MAX_CLIPBOARD_TEXT_CHARS {
            text = text.chars().take(MAX_CLIPBOARD_TEXT_CHARS).collect();
        }
        Self { text, ts }
    }
}

pub const IMAGE_CHUNK_BYTES: usize = 454;

pub const MAX_BLE_IMAGE_BYTES: usize = 1_048_576;

const MAX_IMAGE_CHUNKS: u16 = 4096;

pub fn build_image_chunks(png: &[u8]) -> Vec<Vec<u8>> {
    let total = png.len().div_ceil(IMAGE_CHUNK_BYTES).max(1) as u16;
    png.chunks(IMAGE_CHUNK_BYTES)
        .enumerate()
        .map(|(idx, chunk)| {
            let mut out = Vec::with_capacity(4 + chunk.len());
            out.extend_from_slice(&total.to_be_bytes());
            out.extend_from_slice(&(idx as u16).to_be_bytes());
            out.extend_from_slice(chunk);
            out
        })
        .collect()
}

pub const TEXT_CHUNK_BYTES: usize = IMAGE_CHUNK_BYTES;

pub fn build_text_chunks(text: &str) -> Vec<Vec<u8>> {
    let mut groups: Vec<&str> = Vec::new();
    let mut start = 0usize;
    for (i, ch) in text.char_indices() {
        if i > start && i - start + ch.len_utf8() > TEXT_CHUNK_BYTES {
            groups.push(&text[start..i]);
            start = i;
        }
    }
    if start < text.len() {
        groups.push(&text[start..]);
    }
    if groups.is_empty() {
        groups.push("");
    }
    let total = groups.len().max(1) as u16;
    groups
        .into_iter()
        .enumerate()
        .map(|(idx, g)| {
            let b = g.as_bytes();
            let mut out = Vec::with_capacity(4 + b.len());
            out.extend_from_slice(&total.to_be_bytes());
            out.extend_from_slice(&(idx as u16).to_be_bytes());
            out.extend_from_slice(b);
            out
        })
        .collect()
}

#[derive(Default)]
pub struct ImageAssembler {
    total: u16,
    chunks: Vec<Option<Vec<u8>>>,
}

impl ImageAssembler {
    pub fn add(&mut self, total: u16, idx: u16, data: Vec<u8>) -> Option<Vec<u8>> {
        if total == 0 || total > MAX_IMAGE_CHUNKS || idx >= total {
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
mod chunk_tests {
    use super::*;

    fn parse(c: &[u8]) -> (u16, u16, Vec<u8>) {
        (u16::from_be_bytes([c[0], c[1]]), u16::from_be_bytes([c[2], c[3]]), c[4..].to_vec())
    }

    fn reassemble(chunks: &[Vec<u8>]) -> Option<Vec<u8>> {
        let mut a = ImageAssembler::default();
        let mut out = None;
        for c in chunks {
            let (t, i, d) = parse(c);
            out = a.add(t, i, d);
        }
        out
    }

    #[test]
    fn image_chunks_roundtrip_and_header() {
        let png: Vec<u8> = (0..(IMAGE_CHUNK_BYTES * 2 + 7)).map(|i| (i % 251) as u8).collect();
        let chunks = build_image_chunks(&png);
        assert_eq!(chunks.len(), 3);
        for (idx, c) in chunks.iter().enumerate() {
            let (t, i, _) = parse(c);
            assert_eq!(t as usize, chunks.len());
            assert_eq!(i as usize, idx);
        }
        assert_eq!(reassemble(&chunks), Some(png));
    }

    #[test]
    fn text_chunks_never_split_a_codepoint() {
        let text = "Салом дунё 🚀".repeat(4000);
        let chunks = build_text_chunks(&text);
        assert!(chunks.len() > 1, "must actually span multiple chunks");
        for c in &chunks {
            assert!(std::str::from_utf8(&c[4..]).is_ok());
        }
        let bytes = reassemble(&chunks).unwrap();
        assert_eq!(String::from_utf8(bytes).unwrap(), text);
    }

    #[test]
    fn text_empty_yields_one_empty_chunk() {
        let chunks = build_text_chunks("");
        assert_eq!(chunks.len(), 1);
        assert_eq!(reassemble(&chunks), Some(Vec::new()));
    }

    #[test]
    fn assembler_rejects_malformed_indices() {
        let mut a = ImageAssembler::default();
        assert_eq!(a.add(0, 0, vec![1]), None);
        assert_eq!(a.add(MAX_IMAGE_CHUNKS + 1, 0, vec![1]), None);
        assert_eq!(a.add(2, 2, vec![1]), None);
        assert_eq!(a.add(2, 5, vec![1]), None);
    }

    #[test]
    fn assembler_handles_out_of_order_and_duplicate() {
        let mut a = ImageAssembler::default();
        assert_eq!(a.add(2, 1, vec![0xBB]), None);
        assert_eq!(a.add(2, 1, vec![0xBB]), None);
        assert_eq!(a.add(2, 0, vec![0xAA]), Some(vec![0xAA, 0xBB]));
    }
}

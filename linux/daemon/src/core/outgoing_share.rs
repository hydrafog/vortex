use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};

pub const MAX_PUSH_BYTES: u64 = u64::MAX;

pub const MAX_BATCH_BYTES: u64 = u64::MAX;

pub const PUSH_CHUNK_BYTES: usize = 60 * 1024;

#[derive(Clone, Debug)]
pub struct OutgoingFile {
    pub name: String,
    pub mime: String,
    pub size: u64,
    pub path: Option<std::path::PathBuf>,
    pub bytes: Vec<u8>,
    pub extract: bool,
}

impl OutgoingFile {
    pub fn from_path(path: impl AsRef<std::path::Path>) -> Option<Self> {
        let p = path.as_ref();
        let meta = std::fs::metadata(p).ok()?;
        if !meta.is_file() {
            return None;
        }
        let name = p
            .file_name()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| "vortex-file".to_string());
        Some(Self {
            name,
            mime: "application/octet-stream".to_string(),
            size: meta.len(),
            path: Some(p.to_path_buf()),
            bytes: Vec::new(),
            extract: false,
        })
    }

    pub fn from_bytes(name: String, mime: String, bytes: Vec<u8>, extract: bool) -> Self {
        let size = bytes.len() as u64;
        Self { name, mime, size, path: None, bytes, extract }
    }
}

static QUEUE: Mutex<VecDeque<Vec<OutgoingFile>>> = Mutex::new(VecDeque::new());

pub fn enqueue_batch(files: Vec<OutgoingFile>) -> bool {
    if files.is_empty() {
        return false;
    }
    if let Ok(mut q) = QUEUE.lock() {
        q.push_back(files);
        true
    } else {
        false
    }
}

pub fn take_batch() -> Option<Vec<OutgoingFile>> {
    QUEUE.lock().ok().and_then(|mut q| q.pop_front())
}

pub fn pending() -> bool {
    QUEUE.lock().map(|q| !q.is_empty()).unwrap_or(false)
}

pub enum OutProgress {
    Start { label: String, count: u32, total: u64 },
    Accepted,
    Declined,
    Progress { sent: u64, total: u64 },
    Done,
    Fail,
}

type ProgHook = Box<dyn Fn(OutProgress) + Send + Sync>;
static PROG_HOOK: OnceLock<ProgHook> = OnceLock::new();

pub fn set_progress_hook(hook: ProgHook) {
    let _ = PROG_HOOK.set(hook);
}

pub fn report_progress(ev: OutProgress) {
    if let Some(h) = PROG_HOOK.get() {
        h(ev);
    }
}

pub fn encode_chunk_header(total_chunks: u64, chunk_idx: u64) -> Vec<u8> {
    if total_chunks <= 0xFFFE {
        let mut out = Vec::with_capacity(4);
        out.extend_from_slice(&(total_chunks as u16).to_be_bytes());
        out.extend_from_slice(&(chunk_idx as u16).to_be_bytes());
        out
    } else {
        let mut out = Vec::with_capacity(10);
        out.extend_from_slice(&0xFFFFu16.to_be_bytes());
        out.extend_from_slice(&(total_chunks as u32).to_be_bytes());
        out.extend_from_slice(&(chunk_idx as u32).to_be_bytes());
        out
    }
}

pub fn build_chunks(bytes: &[u8]) -> Vec<Vec<u8>> {
    let total = bytes.len().div_ceil(PUSH_CHUNK_BYTES).max(1) as u64;
    if bytes.is_empty() {
        let out = encode_chunk_header(1, 0);
        return vec![out];
    }
    bytes
        .chunks(PUSH_CHUNK_BYTES)
        .enumerate()
        .map(|(idx, chunk)| {
            let mut out = encode_chunk_header(total, idx as u64);
            out.extend_from_slice(chunk);
            out
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(c: &[u8]) -> (u16, u16, Vec<u8>) {
        (u16::from_be_bytes([c[0], c[1]]), u16::from_be_bytes([c[2], c[3]]), c[4..].to_vec())
    }

    #[test]
    fn build_chunks_roundtrip_and_header() {
        let bytes: Vec<u8> = (0..(2 * PUSH_CHUNK_BYTES + 11)).map(|i| (i % 251) as u8).collect();
        let chunks = build_chunks(&bytes);
        assert_eq!(chunks.len(), 3);

        let mut reassembled = Vec::new();
        for (idx, c) in chunks.iter().enumerate() {
            let (total, i, data) = parse(c);
            assert_eq!(total, 3, "every chunk carries the true total");
            assert_eq!(i as usize, idx, "idx is monotonic from 0");
            reassembled.extend_from_slice(&data);
        }
        assert_eq!(reassembled, bytes, "byte-exact reassembly");
    }

    #[test]
    fn build_chunks_empty_yields_one_empty_chunk() {
        let chunks = build_chunks(&[]);
        assert_eq!(chunks.len(), 1);
        let (total, idx, data) = parse(&chunks[0]);
        assert_eq!((total, idx), (1, 0));
        assert!(data.is_empty());
    }

    #[test]
    fn build_chunks_exact_multiple_has_no_phantom_tail() {
        let bytes = vec![7u8; 2 * PUSH_CHUNK_BYTES];
        let chunks = build_chunks(&bytes);
        assert_eq!(chunks.len(), 2);
        assert_eq!(parse(&chunks[0]).0, 2);
        assert_eq!(parse(&chunks[1]).1, 1);
    }

    #[test]
    fn queue_is_fifo_and_bounded() {
        while take_batch().is_some() {}

        let f = |n: &str| {
            OutgoingFile::from_bytes(
                n.into(),
                "application/octet-stream".into(),
                vec![0u8; 8],
                false,
            )
        };

        assert!(!enqueue_batch(vec![]));
        assert!(!pending());

        assert!(enqueue_batch(vec![f("a"), f("b")]));
        assert!(enqueue_batch(vec![f("c")]));
        assert!(pending());

        let first = take_batch().expect("first batch");
        assert_eq!(first.len(), 2);
        assert_eq!(first[0].name, "a");
        let second = take_batch().expect("second batch");
        assert_eq!(second[0].name, "c");

        assert!(take_batch().is_none(), "drained");
        assert!(!pending());
    }

    #[test]
    fn encode_chunk_header_large() {
        let header = encode_chunk_header(100_000, 50_000);
        assert_eq!(header.len(), 10);
        assert_eq!(&header[0..2], &[0xFF, 0xFF]);
        let total = u32::from_be_bytes([header[2], header[3], header[4], header[5]]);
        let idx = u32::from_be_bytes([header[6], header[7], header[8], header[9]]);
        assert_eq!(total, 100_000);
        assert_eq!(idx, 50_000);
    }
}

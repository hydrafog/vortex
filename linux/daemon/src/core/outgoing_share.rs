use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};

pub const MAX_PUSH_BYTES: usize = 2 * 1024 * 1024 * 1024;

pub const MAX_BATCH_BYTES: usize = 4 * 1024 * 1024 * 1024;

pub const PUSH_CHUNK_BYTES: usize = 60 * 1024;

#[derive(Clone)]
pub struct OutgoingFile {
    pub name: String,
    pub mime: String,
    pub bytes: Vec<u8>,
    pub extract: bool,
}

static QUEUE: Mutex<VecDeque<Vec<OutgoingFile>>> = Mutex::new(VecDeque::new());

pub fn enqueue_batch(files: Vec<OutgoingFile>) -> bool {
    if files.is_empty() {
        return false;
    }
    let total: usize = files.iter().map(|f| f.bytes.len()).sum();
    if total > MAX_BATCH_BYTES {
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

pub fn build_chunks(bytes: &[u8]) -> Vec<Vec<u8>> {
    let total = bytes.len().div_ceil(PUSH_CHUNK_BYTES).max(1) as u16;
    if bytes.is_empty() {
        return vec![{
            let mut out = Vec::with_capacity(4);
            out.extend_from_slice(&1u16.to_be_bytes());
            out.extend_from_slice(&0u16.to_be_bytes());
            out
        }];
    }
    bytes
        .chunks(PUSH_CHUNK_BYTES)
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

        let f = |n: &str| OutgoingFile {
            name: n.into(),
            mime: "application/octet-stream".into(),
            bytes: vec![0u8; 8],
            extract: false,
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
    fn enqueue_rejects_over_batch_cap() {
        let big = OutgoingFile {
            name: "huge".into(),
            mime: "application/octet-stream".into(),
            bytes: vec![0u8; MAX_BATCH_BYTES + 1],
            extract: false,
        };
        assert!(!enqueue_batch(vec![big]), "over MAX_BATCH_BYTES rejected");
    }
}

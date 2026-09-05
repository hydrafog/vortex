use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static RING_SEQ: AtomicU64 = AtomicU64::new(0);

pub fn ring_seq() -> u64 {
    RING_SEQ.load(Ordering::SeqCst)
}

#[tauri::command]
pub(crate) fn ring_phone() {
    let now_ms =
        SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
    let next = RING_SEQ
        .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |prev| Some(now_ms.max(prev + 1)))
        .map(|prev| now_ms.max(prev + 1))
        .unwrap_or(now_ms);
    tracing::info!(seq = next, "ring: requested phone ring");
    if let Some(n) = crate::SYNC_NUDGE.get() {
        n.notify_waiters();
    }
}

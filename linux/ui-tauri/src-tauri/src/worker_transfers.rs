use tokio::sync::mpsc::UnboundedSender;
use vortex_l3_daemon::core::live_activity::LiveActivity;

pub(crate) fn wire_transfer_indicators(ble_live_tx: UnboundedSender<LiveActivity>) {
    crate::transfers::init(ble_live_tx.clone());
    vortex_l3_daemon::core::file_progress::set_hook(Box::new(move |rc, tc| {
        let step = (tc / 10).max(1);
        if rc % step != 0 && rc != tc {
            return;
        }
        let id = crate::PENDING_FILE_OFFERS
            .get()
            .and_then(|m| m.lock().ok().and_then(|g| g.front().map(|e| e.3)));
        if let Some(id) = id {
            crate::transfers::set_progress_chunks(id, rc, tc);
        }
    }));

    crate::transfers_out::init(ble_live_tx);
    let last_pct = std::sync::Arc::new(std::sync::atomic::AtomicI64::new(-1));
    vortex_l3_daemon::core::outgoing_share::set_progress_hook(Box::new(move |ev| {
        use std::sync::atomic::Ordering;
        use vortex_l3_daemon::core::outgoing_share::OutProgress;
        match ev {
            OutProgress::Start { label, total, .. } => {
                last_pct.store(-1, Ordering::Relaxed);
                crate::transfers_out::start(&label, total);
            }
            OutProgress::Accepted => crate::transfers_out::accepted(),
            OutProgress::Declined => crate::transfers_out::declined(),
            OutProgress::Progress { sent, total } => {
                let pct = if total > 0 { (sent * 100 / total) as i64 } else { 0 };
                if pct != last_pct.load(Ordering::Relaxed) || sent == total {
                    last_pct.store(pct, Ordering::Relaxed);
                    crate::transfers_out::set_progress(sent, total);
                }
            }
            OutProgress::Done => crate::transfers_out::complete(),
            OutProgress::Fail => crate::transfers_out::fail(),
        }
    }));

    tokio::spawn(crate::file_consent::watch());
}

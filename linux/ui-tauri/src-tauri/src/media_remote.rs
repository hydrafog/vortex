use std::sync::atomic::{AtomicU64, Ordering};

use vortex_l3_daemon::core::media_runtime::session_conn;

pub(crate) async fn fill_now_playing(state: &mut vortex_l3_daemon::core::appstate::AppState) {
    let Some(c) = session_conn().await else { return };
    if let Some(np) = vortex_l3_daemon::core::media_runtime::now_playing(c).await {
        state.media_title = np.title;
        state.media_artist = np.artist;
        state.media_app = np.app;
        state.media_art_url = np.art_url;
        state.media_np_playing = np.playing;
    }
}

static LAST_MEDIA_SEQ: AtomicU64 = AtomicU64::new(0);

pub(crate) fn dispatch_media_command(state: &vortex_l3_daemon::core::appstate::AppState) {
    let cmd = state.media_control.clone();
    let seq = state.media_control_seq;
    if cmd.is_empty() || seq == 0 || seq <= LAST_MEDIA_SEQ.load(Ordering::Relaxed) {
        return;
    }
    LAST_MEDIA_SEQ.store(seq, Ordering::Relaxed);
    tokio::spawn(async move {
        if let Some(c) = session_conn().await {
            vortex_l3_daemon::core::media_runtime::media_control(c, &cmd).await;
            tokio::time::sleep(std::time::Duration::from_millis(400)).await;
            if let Some(n) = crate::SYNC_NUDGE.get() {
                n.notify_one();
            }
        }
    });
}

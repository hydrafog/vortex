use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use bluer::{Adapter, Address};
use tracing::{info, warn};
use zbus::Connection;

use crate::core::audio_orchestrator::SwitchOrchestrator;
use crate::core::audio_switch::{audio_active, disconnect_audio_initiate};
use crate::core::media_runtime::{
    clear as clear_call_pause, pause_all_playing, play_players, playing_players, MediaStateStore,
};
use crate::core::storage::peers::PeerStore;

const GRAB_COOLDOWN: Duration = Duration::from_millis(4_000);
const LOSS_SUPPRESS: Duration = Duration::from_millis(4_000);
const PEER_FRESH: Duration = Duration::from_millis(30_000);
const POLL: Duration = Duration::from_millis(200);
const RETURN_DELAY: Duration = Duration::from_millis(2_000);
const RETURN_GRACE: Duration = Duration::from_millis(3_000);
const RESUME_TIMEOUT: Duration = Duration::from_millis(8_000);
const LOSS_RESUME_TIMEOUT: Duration = Duration::from_secs(90);
const LATE_RESUME_WINDOW: Duration = Duration::from_millis(15_000);

const LAPTOP_AUTO_GRAB: bool = true;
const LAPTOP_PUSH_ON_STOP: bool = false;

pub struct MediaWatch {
    pub enabled: AtomicBool,
    pub enabled_changed_at: std::sync::atomic::AtomicU64,
    pub playing: AtomicBool,
    pub play_epoch_mono: std::sync::atomic::AtomicU64,
    pub peer_play_epoch_mono: std::sync::atomic::AtomicU64,
    pub peer_playing: AtomicBool,
    pub claim_peer: AtomicBool,
    pub peer_holds_buds_seen: std::sync::Mutex<Option<Instant>>,
}

impl MediaWatch {
    pub fn new() -> Arc<Self> {
        let saved = crate::core::smart_switch_store::load();
        Arc::new(Self {
            enabled: AtomicBool::new(saved.enabled),
            enabled_changed_at: std::sync::atomic::AtomicU64::new(saved.changed_at),
            playing: AtomicBool::new(false),
            play_epoch_mono: std::sync::atomic::AtomicU64::new(0),
            peer_play_epoch_mono: std::sync::atomic::AtomicU64::new(0),
            peer_playing: AtomicBool::new(false),
            claim_peer: AtomicBool::new(false),
            peer_holds_buds_seen: std::sync::Mutex::new(None),
        })
    }

    pub fn apply_setting(&self, enabled: bool, changed_at: u64) -> bool {
        use std::sync::atomic::Ordering;
        if changed_at <= self.enabled_changed_at.load(Ordering::Relaxed) {
            return false;
        }
        self.enabled.store(enabled, Ordering::Relaxed);
        self.enabled_changed_at.store(changed_at, Ordering::Relaxed);
        let _ =
            crate::core::smart_switch_store::save(&crate::core::smart_switch_store::SmartSwitch {
                enabled,
                changed_at,
            });
        true
    }
}

pub fn mono_ms() -> u64 {
    use std::sync::OnceLock;
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_millis() as u64
}

pub fn spawn(
    watch: Arc<MediaWatch>,
    orchestrator: Arc<SwitchOrchestrator>,
    adapter: Adapter,
    peer_store: Arc<dyn PeerStore>,
    in_call: Arc<AtomicBool>,
    call_pause_store: MediaStateStore,
) {
    tokio::spawn(async move {
        let conn = match Connection::session().await {
            Ok(c) => c,
            Err(e) => {
                warn!("media-watch: session bus unavailable: {e}; auto-follow disabled");
                return;
            }
        };
        let mut last_playing = false;
        let mut last_own = false;
        let mut play_epoch_mono: u64 = 0;
        let mut last_grab: Option<Instant> = None;
        let mut suppress_until: Option<Instant> = None;
        let mut paused: Vec<String> = Vec::new();
        let mut have_paused = false;
        let mut grabbing = false;
        let mut grab_late = false;
        let mut paused_at: Option<Instant> = None;
        let mut return_at: Option<Instant> = None;
        let mut last_playing_set: Vec<String> = Vec::new();
        let mut gained_at: Option<Instant> = None;

        loop {
            tokio::time::sleep(POLL).await;
            let now = Instant::now();

            let mac = match crate::core::earbuds_store::load() {
                Some(s) => s.address,
                None => continue,
            };
            let addr: Address = match mac.parse() {
                Ok(a) => a,
                Err(_) => continue,
            };

            let playing_set = playing_players(&conn).await;
            let playing = !playing_set.is_empty();
            let own = audio_active(&adapter, addr).await;

            if last_own && !own {
                suppress_until = Some(now + LOSS_SUPPRESS);
                if last_playing && !have_paused {
                    let _ = pause_all_playing(&conn).await;
                    paused = last_playing_set.clone();
                    info!(
                        "media-watch: buds left laptop while playing → remember {} player(s)",
                        paused.len()
                    );
                    have_paused = true;
                    paused_at = Some(now);
                }
                grabbing = false;
            }
            if !last_own && own {
                gained_at = Some(now);
            }
            last_own = own;

            if playing && !last_playing && !have_paused {
                play_epoch_mono = mono_ms();
                watch.play_epoch_mono.store(play_epoch_mono, Ordering::Relaxed);
            } else if !playing && !have_paused {
                play_epoch_mono = 0;
                watch.play_epoch_mono.store(0, Ordering::Relaxed);
            }

            if have_paused && !own && playing {
                let _ = pause_all_playing(&conn).await;
            }

            if have_paused {
                if !grabbing && !own {
                    let peer_still_winner = watch.peer_playing.load(Ordering::Relaxed) && {
                        let pe = watch.peer_play_epoch_mono.load(Ordering::Relaxed);
                        pe != 0 && (play_epoch_mono == 0 || pe > play_epoch_mono)
                    };
                    if peer_still_winner {
                        paused_at = Some(now);
                    }
                }
                let limit = if grabbing {
                    RESUME_TIMEOUT
                } else if grab_late {
                    LATE_RESUME_WINDOW
                } else {
                    LOSS_RESUME_TIMEOUT
                };
                let timed_out = paused_at.map(|p| now.duration_since(p) > limit).unwrap_or(true);
                if own {
                    let to_resume = std::mem::take(&mut paused);
                    have_paused = false;
                    grabbing = false;
                    grab_late = false;
                    clear_call_pause(&call_pause_store).await;
                    info!("media-watch: buds back; resuming {} player(s)", to_resume.len());
                    if !to_resume.is_empty() {
                        let mac_c = mac.clone();
                        let conn_c = conn.clone();
                        let adapter_c = adapter.clone();
                        let addr_c = addr;
                        tokio::spawn(async move {
                            let outcome = crate::core::audio_route::wait_for_route(&mac_c).await;
                            if let Some(sink) = outcome.sink {
                                crate::core::audio_route::spawn_sink_keepalive(
                                    sink,
                                    Duration::from_millis(3_000),
                                );
                                tokio::time::sleep(Duration::from_millis(350)).await;
                            }
                            let deltas: [u64; 11] =
                                [0, 300, 300, 400, 500, 700, 800, 1000, 1000, 1000, 1000];
                            let mut elapsed_ms = 0u64;
                            for delta in deltas {
                                if delta > 0 {
                                    tokio::time::sleep(Duration::from_millis(delta)).await;
                                    elapsed_ms += delta;
                                }
                                if !audio_active(&adapter_c, addr_c).await {
                                    info!(
                                        "media-watch resume safety-net: buds left laptop — aborting re-play (sound stays in earbuds)"
                                    );
                                    break;
                                }
                                let mut replayed: Vec<String> = Vec::new();
                                for p in &to_resume {
                                    if !crate::core::media_runtime::player_is_playing(&conn_c, p)
                                        .await
                                        .unwrap_or(false)
                                    {
                                        play_players(&conn_c, std::slice::from_ref(p)).await;
                                        replayed.push(p.clone());
                                    }
                                }
                                if !replayed.is_empty() {
                                    info!(
                                        checkpoint_ms = elapsed_ms,
                                        ?replayed,
                                        "media-watch resume safety-net: re-played paused player(s)"
                                    );
                                }
                            }
                        });
                    }
                } else if timed_out {
                    if grabbing {
                        grabbing = false;
                        grab_late = true;
                        paused_at = Some(now);
                        tracing::warn!(
                            players = paused.len(),
                            "media-watch: buds slow to arrive; holding pause for a late resume"
                        );
                    } else {
                        let n = paused.len();
                        paused.clear();
                        have_paused = false;
                        grab_late = false;
                        tracing::warn!(
                            players = n,
                            "media-watch: buds never arrived; staying paused (sound only in earbuds)"
                        );
                    }
                }
            }

            if LAPTOP_AUTO_GRAB && playing && !own {
                let suppressed = suppress_until.map(|s| now < s).unwrap_or(false);
                let cooling =
                    last_grab.map(|g| now.duration_since(g) < GRAB_COOLDOWN).unwrap_or(false);
                let peer_has_buds = watch
                    .peer_holds_buds_seen
                    .lock()
                    .ok()
                    .and_then(|g| *g)
                    .map(|t| t.elapsed() < PEER_FRESH)
                    .unwrap_or(false);
                let peer_played_last = watch.peer_playing.load(Ordering::Relaxed) && {
                    let pe = watch.peer_play_epoch_mono.load(Ordering::Relaxed);
                    pe != 0 && play_epoch_mono != 0 && pe > play_epoch_mono
                };
                if peer_played_last {
                    if !have_paused {
                        paused = pause_all_playing(&conn).await;
                        have_paused = true;
                        paused_at = Some(now);
                        grabbing = false;
                        info!("media-watch: peer played more recently → yield buds + pause local media");
                    } else {
                        for p in &playing_set {
                            if !paused.contains(p) {
                                paused.push(p.clone());
                            }
                        }
                    }
                } else if watch.enabled.load(Ordering::Relaxed)
                    && !in_call.load(Ordering::Relaxed)
                    && !suppressed
                    && !cooling
                    && peer_has_buds
                {
                    let peer_pub =
                        peer_store.list().ok().and_then(|v| v.first().map(|p| p.peer_static_pub));
                    if let Some(peer_pub) = peer_pub {
                        if !have_paused {
                            paused = pause_all_playing(&conn).await;
                            have_paused = true;
                        } else {
                            for p in &playing_set {
                                if !paused.contains(p) {
                                    paused.push(p.clone());
                                }
                            }
                        }
                        grabbing = true;
                        grab_late = false;
                        paused_at = Some(now);
                        match orchestrator.request(peer_pub, mac.clone()).await {
                            Ok(()) => {
                                last_grab = Some(now);
                                info!("media-watch: media on laptop & buds elsewhere → pause + grab to laptop");
                            }
                            Err(e) => {
                                tracing::debug!("media-watch grab busy/err (retry next tick): {e}");
                            }
                        }
                    }
                }
            }

            if playing {
                return_at = None;
            } else if LAPTOP_PUSH_ON_STOP
                && own
                && last_playing
                && gained_at.map(|g| now.duration_since(g) >= RETURN_GRACE).unwrap_or(true)
            {
                return_at = Some(now);
            }
            if let Some(t) = return_at {
                if own
                    && !playing
                    && now.duration_since(t) >= RETURN_DELAY
                    && watch.enabled.load(Ordering::Relaxed)
                    && !in_call.load(Ordering::Relaxed)
                {
                    return_at = None;
                    info!(
                        "media-watch: media stopped {}ms ago → hand buds back to phone",
                        RETURN_DELAY.as_millis()
                    );
                    let peer_pub =
                        peer_store.list().ok().and_then(|v| v.first().map(|p| p.peer_static_pub));
                    let a = adapter.clone();
                    let m = mac.clone();
                    let o = orchestrator.clone();
                    tokio::spawn(async move {
                        let _ = disconnect_audio_initiate(&a, &m).await;
                        if let Some(pp) = peer_pub {
                            o.send_claim(pp, m).await;
                        }
                    });
                    watch.claim_peer.store(true, Ordering::Relaxed);
                }
            }
            last_playing = playing;
            last_playing_set = playing_set;

            let advertised = grabbing || (playing && own);
            watch.playing.store(advertised, Ordering::Relaxed);
        }
    });
}

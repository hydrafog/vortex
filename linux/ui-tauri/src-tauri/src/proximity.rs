
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use serde::{Deserialize, Serialize};

pub(crate) const AWAY_GRACE_MS: u64 = 25_000;
pub(crate) const CONFIRM_SCAN_MS: u64 = 2_000;
pub(crate) const IDLE_GATE_MS: u64 = 30_000;
pub(crate) const WAKE_WINDOW_MS: u64 = 20_000;
pub(crate) const UNLOCK_COOLDOWN_MS: u64 = 30_000;
pub(crate) const RELOCK_IDLE_MS: u64 = 90_000;


#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize)]
pub(crate) struct ProximitySettings {
    #[serde(default)]
    pub auto_lock: bool,
    #[serde(default)]
    pub auto_unlock: bool,
}

static AUTO_LOCK_ON: AtomicBool = AtomicBool::new(false);
static AUTO_UNLOCK_ON: AtomicBool = AtomicBool::new(false);

static LAST_PHONE_UNLOCKED: AtomicBool = AtomicBool::new(false);

pub(crate) fn note_phone_unlocked(unlocked: Option<bool>) {
    LAST_PHONE_UNLOCKED.store(unlocked == Some(true), Ordering::Relaxed);
}

fn settings_path() -> Option<PathBuf> {
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))?;
    Some(base.join("vortex").join("proximity.json"))
}

pub(crate) fn load_settings_into_statics() {
    let s: ProximitySettings = settings_path()
        .and_then(|p| std::fs::read(p).ok())
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default();
    AUTO_LOCK_ON.store(s.auto_lock, Ordering::Relaxed);
    AUTO_UNLOCK_ON.store(s.auto_unlock, Ordering::Relaxed);
}

#[tauri::command]
pub fn get_proximity_settings() -> ProximitySettings {
    ProximitySettings {
        auto_lock: AUTO_LOCK_ON.load(Ordering::Relaxed),
        auto_unlock: AUTO_UNLOCK_ON.load(Ordering::Relaxed),
    }
}

#[tauri::command]
pub fn set_proximity_settings(auto_lock: bool, auto_unlock: bool) -> Result<(), String> {
    AUTO_LOCK_ON.store(auto_lock, Ordering::Relaxed);
    AUTO_UNLOCK_ON.store(auto_unlock, Ordering::Relaxed);
    let s = ProximitySettings { auto_lock, auto_unlock };
    let path = settings_path().ok_or("no config dir")?;
    let bytes = serde_json::to_vec_pretty(&s).map_err(|e| e.to_string())?;
    vortex_l3_daemon::core::fs_private::write_private(&path, &bytes)
        .map_err(|e| e.to_string())
}


#[derive(Debug, Default, Clone, Copy)]
pub(crate) struct ProxState {
    present: bool,
    lock_armed: bool,
    unlock_armed: bool,
    wake_until_ms: u64,
    last_locked: Option<bool>,
    we_locked: bool,
    last_auto_unlock_ms: u64,
    last_link_live: bool,
    eager_at_ms: u64,
    require_wake: bool,
}

pub(crate) struct Inputs {
    pub now_ms: u64,
    pub link_live: bool,
    pub last_presence_ms: u64,
    pub locked: Option<bool>,
    pub idle_ms: Option<u64>,
    pub adapter_on: bool,
    pub auto_lock_on: bool,
    pub auto_unlock_on: bool,
    pub user_active: bool,
    pub phone_unlocked: bool,
}

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub(crate) enum Action {
    None,
    Lock,
    Unlock,
    RelockIdle,
}

pub(crate) fn step(s: &mut ProxState, i: &Inputs) -> Action {
    if !i.adapter_on {
        s.last_locked = i.locked;
        s.last_link_live = false;
        return Action::None;
    }
    let link_dropped = s.last_link_live && !i.link_live;
    s.last_link_live = i.link_live;

    let present = i.link_live
        || (i.last_presence_ms != 0
            && i.now_ms.saturating_sub(i.last_presence_ms) < AWAY_GRACE_MS);

    if i.user_active {
        s.eager_at_ms = 0;
    }

    if let (Some(prev), Some(cur)) = (s.last_locked, i.locked) {
        if !prev && cur {
            s.unlock_armed = s.we_locked || !present;
            s.we_locked = false;
            s.eager_at_ms = 0;
        }
        if prev && !cur {
            s.unlock_armed = false;
            s.wake_until_ms = 0;
            s.we_locked = false;
            s.require_wake = false;
        }
    }
    s.last_locked = i.locked;

    if present {
        if !s.present {
            s.require_wake = false;
            if i.locked == Some(true) {
                s.unlock_armed = true;
            }
        }
        s.present = true;
        s.lock_armed = true;
    } else {
        s.present = false;
    }

    if s.eager_at_ms != 0
        && i.locked == Some(false)
        && i.now_ms.saturating_sub(s.eager_at_ms) >= RELOCK_IDLE_MS
        && i.idle_ms.map(|v| v >= RELOCK_IDLE_MS).unwrap_or(false)
    {
        s.eager_at_ms = 0;
        s.require_wake = true;
        s.we_locked = true;
        return Action::RelockIdle;
    }

    if i.auto_lock_on
        && link_dropped
        && s.lock_armed
        && i.locked == Some(false)
        && i.idle_ms.map(|v| v >= IDLE_GATE_MS).unwrap_or(false)
    {
        s.lock_armed = false;
        s.we_locked = true;
        return Action::Lock;
    }

    if i.auto_lock_on
        && !present
        && s.lock_armed
        && i.locked == Some(false)
        && i.idle_ms.map(|v| v >= IDLE_GATE_MS).unwrap_or(false)
    {
        s.lock_armed = false;
        s.we_locked = true;
        return Action::Lock;
    }

    if i.auto_unlock_on && i.locked == Some(true) && s.unlock_armed && i.phone_unlocked {
        let cooled =
            i.now_ms.saturating_sub(s.last_auto_unlock_ms) >= UNLOCK_COOLDOWN_MS;
        if i.link_live && cooled && (!s.require_wake || i.user_active) {
            s.last_auto_unlock_ms = i.now_ms;
            s.unlock_armed = false;
            s.wake_until_ms = 0;
            if !i.user_active {
                s.eager_at_ms = i.now_ms;
            }
            return Action::Unlock;
        }
        if s.require_wake {
            if i.user_active && !i.link_live {
                s.wake_until_ms = i.now_ms + WAKE_WINDOW_MS;
            } else if s.wake_until_ms != 0
                && i.now_ms <= s.wake_until_ms
                && i.link_live
                && cooled
            {
                s.last_auto_unlock_ms = i.now_ms;
                s.unlock_armed = false;
                s.wake_until_ms = 0;
                return Action::Unlock;
            }
        }
    }

    Action::None
}


pub(crate) fn nudge() -> &'static tokio::sync::Notify {
    static NUDGE: std::sync::OnceLock<tokio::sync::Notify> = std::sync::OnceLock::new();
    NUDGE.get_or_init(tokio::sync::Notify::new)
}

async fn desktop_notify(title: &str, text: &str) {
    let Ok(n) = serde_json::from_value::<
        vortex_l3_daemon::core::notif_mirror::NotificationMirror,
    >(serde_json::json!({ "app": "Vortex", "title": title, "text": text })) else {
        return;
    };
    let _ = vortex_l3_daemon::core::notification_display::show(&n, 0).await;
}

pub(crate) fn spawn_proximity_watch(
    ble_writers: vortex_l3_daemon::core::audio_lan_session::SessionWriterMap,
    adapter: bluer::Adapter,
    peer_store: Arc<dyn vortex_l3_daemon::core::storage::peers::PeerStore>,
) {
    load_settings_into_statics();
    tokio::spawn(async move {
        use vortex_l3_daemon::core::session_lock;

        let active_flag = Arc::new(AtomicBool::new(false));
        {
            let af = active_flag.clone();
            if let Err(e) = session_lock::watch_user_active(move || {
                af.store(true, Ordering::Relaxed);
                nudge().notify_one();
            })
            .await
            {
                tracing::warn!("proximity: user-active watch unavailable: {e} (auto-unlock degraded)");
            }
        }

        let mut st = ProxState::default();
        loop {
            let auto_lock_on = AUTO_LOCK_ON.load(Ordering::Relaxed);
            let auto_unlock_on = AUTO_UNLOCK_ON.load(Ordering::Relaxed);
            let user_active = active_flag.swap(false, Ordering::Relaxed);
            if auto_lock_on || auto_unlock_on {
                let now_ms = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis() as u64)
                    .unwrap_or(0);
                let link_live = !ble_writers.lock().await.is_empty();
                let last_presence_ms =
                    crate::ble::LAST_PRESENCE_MS.load(Ordering::Relaxed);
                let adapter_on = adapter.is_powered().await.unwrap_or(false);
                let locked = session_lock::locked_hint().await;
                let idle_ms = if auto_lock_on {
                    session_lock::idle_ms().await
                } else {
                    None
                };
                let action = step(
                    &mut st,
                    &Inputs {
                        now_ms,
                        link_live,
                        last_presence_ms,
                        locked,
                        idle_ms,
                        adapter_on,
                        auto_lock_on,
                        auto_unlock_on,
                        user_active,
                        phone_unlocked: LAST_PHONE_UNLOCKED.load(Ordering::Relaxed),
                    },
                );
                match action {
                    Action::Lock => {
                        let presence_before =
                            crate::ble::LAST_PRESENCE_MS.load(Ordering::Relaxed);
                        let found = crate::ble::find_trusted_presence_peer(
                            &adapter,
                            &peer_store,
                            std::time::Duration::from_millis(CONFIRM_SCAN_MS),
                        )
                        .await
                        .is_some()
                            || crate::ble::LAST_PRESENCE_MS.load(Ordering::Relaxed)
                                > presence_before;
                        if found {
                            tracing::info!(
                                "proximity: confirm-scan still sees the phone — not locking"
                            );
                            st.lock_armed = true;
                            st.we_locked = false;
                        } else {
                            tracing::info!("proximity: phone away + idle — locking session");
                            match session_lock::lock().await {
                                Ok(()) => {
                                    desktop_notify(
                                        "Locked",
                                        "Phone out of range — session locked.",
                                    )
                                    .await;
                                }
                                Err(e) => tracing::warn!("proximity lock failed: {e}"),
                            }
                        }
                    }
                    Action::Unlock => {
                        tracing::info!("proximity: phone present — unlocking");
                        match session_lock::unlock().await {
                            Ok(()) => {
                                desktop_notify(
                                    "Unlocked",
                                    "Welcome back — unlocked via phone presence.",
                                )
                                .await;
                            }
                            Err(e) => tracing::warn!("proximity unlock failed: {e}"),
                        }
                    }
                    Action::RelockIdle => {
                        tracing::info!(
                            "proximity: eager unlock unused for {}s — locking again",
                            RELOCK_IDLE_MS / 1000
                        );
                        if let Err(e) = session_lock::lock().await {
                            tracing::warn!("proximity re-lock failed: {e}");
                        }
                    }
                    Action::None => {}
                }
            }
            tokio::select! {
                _ = tokio::time::sleep(std::time::Duration::from_secs(2)) => {}
                _ = nudge().notified() => {}
            }
        }
    });
}


#[cfg(test)]
mod tests {
    use super::*;

    fn base(now_ms: u64) -> Inputs {
        Inputs {
            now_ms,
            link_live: false,
            last_presence_ms: 0,
            locked: Some(false),
            idle_ms: Some(IDLE_GATE_MS),
            adapter_on: true,
            auto_lock_on: true,
            auto_unlock_on: true,
            user_active: false,
            phone_unlocked: true,
        }
    }

    fn present_state(now: u64) -> ProxState {
        let mut s = ProxState::default();
        let mut i = base(now);
        i.last_presence_ms = now;
        assert_eq!(step(&mut s, &i), Action::None);
        s
    }

    #[test]
    fn no_lock_without_ever_seeing_the_phone() {
        let mut s = ProxState::default();
        let i = base(1_000_000);
        let mut s2 = s;
        assert_eq!(step(&mut s2, &i), Action::None);
        assert_eq!(step(&mut s, &i), Action::None);
    }

    #[test]
    fn locks_once_after_grace_and_idle() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + AWAY_GRACE_MS / 2);
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::None);
        let mut i = base(t0 + AWAY_GRACE_MS + 1);
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::Lock);
        assert_eq!(step(&mut s, &i), Action::None);
    }

    #[test]
    fn idle_gate_blocks_lock_while_user_is_typing() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + AWAY_GRACE_MS + 1);
        i.last_presence_ms = t0;
        i.idle_ms = Some(1_000);
        assert_eq!(step(&mut s, &i), Action::None);
        i.idle_ms = Some(IDLE_GATE_MS);
        assert_eq!(step(&mut s, &i), Action::Lock);
    }

    #[test]
    fn adapter_off_freezes_everything() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + AWAY_GRACE_MS * 10);
        i.last_presence_ms = t0;
        i.adapter_on = false;
        assert_eq!(step(&mut s, &i), Action::None);
    }

    #[test]
    fn auto_lock_then_return_then_wake_unlocks() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + AWAY_GRACE_MS + 1);
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::Lock);
        let t1 = t0 + AWAY_GRACE_MS + 5_000;
        let mut i = base(t1);
        i.last_presence_ms = t0;
        i.locked = Some(true);
        assert_eq!(step(&mut s, &i), Action::None);
        let t2 = t1 + 60_000;
        let mut i = base(t2);
        i.locked = Some(true);
        i.link_live = true;
        i.last_presence_ms = t2;
        i.user_active = true;
        assert_eq!(step(&mut s, &i), Action::Unlock);
        assert_eq!(step(&mut s, &i), Action::None);
    }

    #[test]
    fn auto_unlock_gated_on_phone_unlocked() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + AWAY_GRACE_MS + 1);
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::Lock);
        let t1 = t0 + AWAY_GRACE_MS + 5_000;
        let mut i = base(t1);
        i.last_presence_ms = t0;
        i.locked = Some(true);
        assert_eq!(step(&mut s, &i), Action::None);
        let t2 = t1 + 60_000;
        let mut i = base(t2);
        i.locked = Some(true);
        i.link_live = true;
        i.last_presence_ms = t2;
        i.user_active = true;
        i.phone_unlocked = false;
        assert_eq!(step(&mut s, &i), Action::None);
        i.phone_unlocked = true;
        assert_eq!(step(&mut s, &i), Action::Unlock);
    }

    #[test]
    fn manual_lock_with_phone_present_is_respected() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + 2_000);
        i.link_live = true;
        i.last_presence_ms = t0 + 2_000;
        i.locked = Some(true);
        assert_eq!(step(&mut s, &i), Action::None);
        let mut i2 = i;
        i2.now_ms = t0 + 4_000;
        i2.user_active = true;
        assert_eq!(step(&mut s, &i2), Action::None);
    }

    #[test]
    fn manual_lock_then_away_then_return_arms_unlock() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + 2_000);
        i.link_live = true;
        i.last_presence_ms = t0 + 2_000;
        i.locked = Some(true);
        assert_eq!(step(&mut s, &i), Action::None);
        let t1 = t0 + 2_000 + AWAY_GRACE_MS + 1_000;
        let mut i = base(t1);
        i.last_presence_ms = t0 + 2_000;
        i.locked = Some(true);
        assert_eq!(step(&mut s, &i), Action::None);
        let t2 = t1 + 10_000;
        let mut i = base(t2);
        i.locked = Some(true);
        i.link_live = true;
        i.last_presence_ms = t2;
        i.user_active = true;
        assert_eq!(step(&mut s, &i), Action::Unlock);
    }

    #[test]
    fn wake_window_unlocks_when_link_lands_late() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + AWAY_GRACE_MS + 1);
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::Lock);
        let t1 = t0 + AWAY_GRACE_MS + 5_000;
        let mut i = base(t1);
        i.last_presence_ms = t0;
        i.locked = Some(true);
        assert_eq!(step(&mut s, &i), Action::None);
        let t2 = t1 + 600_000;
        let mut i = base(t2);
        i.locked = Some(true);
        i.user_active = true;
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::None);
        let mut i = base(t2 + 4_000);
        i.locked = Some(true);
        i.link_live = true;
        i.last_presence_ms = t2 + 4_000;
        assert_eq!(step(&mut s, &i), Action::Unlock);
        assert_eq!(step(&mut s, &i), Action::None);
    }

    #[test]
    fn eager_unlock_fires_on_arrival_without_any_wake() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + AWAY_GRACE_MS + 1);
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::Lock);
        let t1 = t0 + AWAY_GRACE_MS + 5_000;
        let mut i = base(t1);
        i.last_presence_ms = t0;
        i.locked = Some(true);
        assert_eq!(step(&mut s, &i), Action::None);
        let t2 = t1 + 120_000;
        let mut i = base(t2);
        i.locked = Some(true);
        i.link_live = true;
        i.last_presence_ms = t2;
        assert_eq!(step(&mut s, &i), Action::Unlock);
    }

    #[test]
    fn unused_eager_unlock_relocks_then_needs_a_wake() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + AWAY_GRACE_MS + 1);
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::Lock);
        let t1 = t0 + AWAY_GRACE_MS + 5_000;
        let mut i = base(t1);
        i.last_presence_ms = t0;
        i.locked = Some(true);
        step(&mut s, &i);
        let t2 = t1 + 60_000;
        let mut i = base(t2);
        i.locked = Some(true);
        i.link_live = true;
        i.last_presence_ms = t2;
        assert_eq!(step(&mut s, &i), Action::Unlock);
        let t3 = t2 + 5_000;
        let mut i = base(t3);
        i.locked = Some(false);
        i.link_live = true;
        i.last_presence_ms = t3;
        i.idle_ms = Some(0);
        assert_eq!(step(&mut s, &i), Action::None);
        let t4 = t2 + RELOCK_IDLE_MS + 1_000;
        let mut i = base(t4);
        i.locked = Some(false);
        i.link_live = true;
        i.last_presence_ms = t4;
        i.idle_ms = Some(RELOCK_IDLE_MS);
        assert_eq!(step(&mut s, &i), Action::RelockIdle);
        let t5 = t4 + 5_000;
        let mut i = base(t5);
        i.locked = Some(true);
        i.link_live = true;
        i.last_presence_ms = t5;
        assert_eq!(step(&mut s, &i), Action::None);
        let t6 = t5 + 5_000;
        let mut i = base(t6);
        i.locked = Some(true);
        i.link_live = true;
        i.last_presence_ms = t6;
        i.user_active = true;
        assert_eq!(step(&mut s, &i), Action::Unlock);
    }

    #[test]
    fn input_during_eager_window_prevents_relock() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + AWAY_GRACE_MS + 1);
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::Lock);
        let t1 = t0 + AWAY_GRACE_MS + 5_000;
        let mut i = base(t1);
        i.last_presence_ms = t0;
        i.locked = Some(true);
        step(&mut s, &i);
        let t2 = t1 + 60_000;
        let mut i = base(t2);
        i.locked = Some(true);
        i.link_live = true;
        i.last_presence_ms = t2;
        assert_eq!(step(&mut s, &i), Action::Unlock);
        let t3 = t2 + 10_000;
        let mut i = base(t3);
        i.locked = Some(false);
        i.link_live = true;
        i.last_presence_ms = t3;
        i.user_active = true;
        i.idle_ms = Some(0);
        assert_eq!(step(&mut s, &i), Action::None);
        let t4 = t2 + RELOCK_IDLE_MS + 10_000;
        let mut i = base(t4);
        i.locked = Some(false);
        i.link_live = true;
        i.last_presence_ms = t4;
        i.idle_ms = Some(RELOCK_IDLE_MS);
        assert_eq!(step(&mut s, &i), Action::None);
    }

    #[test]
    fn link_drop_locks_fast_without_waiting_grace() {
        let t0 = 1_000_000;
        let mut s = ProxState::default();
        let mut i = base(t0);
        i.link_live = true;
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::None);
        let mut i = base(t0 + 2_000);
        i.link_live = false;
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::Lock);
        let mut i = base(t0 + 4_000);
        i.link_live = false;
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::None);
    }

    #[test]
    fn link_drop_fast_path_respects_idle_gate() {
        let t0 = 1_000_000;
        let mut s = ProxState::default();
        let mut i = base(t0);
        i.link_live = true;
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::None);
        let mut i = base(t0 + 2_000);
        i.link_live = false;
        i.last_presence_ms = t0;
        i.idle_ms = Some(1_000);
        assert_eq!(step(&mut s, &i), Action::None);
        let mut i = base(t0 + AWAY_GRACE_MS + 1_000);
        i.link_live = false;
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::Lock);
    }

    #[test]
    fn adapter_power_off_is_not_a_link_drop() {
        let t0 = 1_000_000;
        let mut s = ProxState::default();
        let mut i = base(t0);
        i.link_live = true;
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::None);
        let mut i = base(t0 + 2_000);
        i.adapter_on = false;
        i.link_live = false;
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::None);
        let mut i = base(t0 + 4_000);
        i.link_live = false;
        i.last_presence_ms = t0;
        assert_eq!(step(&mut s, &i), Action::None);
    }

    #[test]
    fn disabled_toggles_do_nothing() {
        let t0 = 1_000_000;
        let mut s = present_state(t0);
        let mut i = base(t0 + AWAY_GRACE_MS + 1);
        i.last_presence_ms = t0;
        i.auto_lock_on = false;
        assert_eq!(step(&mut s, &i), Action::None);
    }
}

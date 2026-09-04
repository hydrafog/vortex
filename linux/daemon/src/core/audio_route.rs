use std::time::Duration;

use tokio::process::Command;
use tokio::time::Instant;
use tracing::{debug, info, warn};

const SINK_APPEAR_TIMEOUT: Duration = Duration::from_millis(2_500);

const SINK_READY_BEST_EFFORT: Duration = Duration::from_millis(60);

const POLL: Duration = Duration::from_millis(20);

#[derive(Debug, Clone)]
pub struct RouteOutcome {
    pub sink: Option<String>,
    pub elapsed: Duration,
    pub ready: bool,
    pub routed: bool,
}

pub async fn wait_for_route(mac: &str) -> RouteOutcome {
    let started = Instant::now();
    if !pactl_available().await {
        debug!("audio-route: pactl unavailable; skipping wait");
        return RouteOutcome {
            sink: None,
            elapsed: started.elapsed(),
            ready: false,
            routed: false,
        };
    }
    let underscored = mac.replace(':', "_");
    let colon_form = mac.to_string();

    let mut sink: Option<String> = None;
    let appear_deadline = started + SINK_APPEAR_TIMEOUT;
    let mut rounds: u32 = 0;
    let mut a2dp_forced = false;
    while Instant::now() < appear_deadline {
        if let Some(found) = find_sink_for(&[underscored.as_str(), colon_form.as_str()]).await {
            sink = Some(found);
            break;
        }
        rounds += 1;
        if rounds == 8 && !a2dp_forced {
            a2dp_forced = true;
            let _ = force_a2dp_card_profile(&underscored).await;
        }
        tokio::time::sleep(POLL).await;
    }
    // NOTE: do NOT force A2DP when the sink already appeared on its
    let Some(sink_name) = sink.clone() else {
        warn!(
            elapsed_ms = started.elapsed().as_millis() as u64,
            mac, "audio-route: timed out waiting for bluez sink to appear"
        );
        return RouteOutcome {
            sink: None,
            elapsed: started.elapsed(),
            ready: false,
            routed: false,
        };
    };

    let mut ready = sink_is_ready(&sink_name).await;
    if !ready {
        let settle_deadline = Instant::now() + SINK_READY_BEST_EFFORT;
        while Instant::now() < settle_deadline {
            if sink_is_ready(&sink_name).await {
                ready = true;
                break;
            }
            tokio::time::sleep(POLL).await;
        }
        if !ready {
            force_sink_unsuspended(&sink_name).await;
            ready = sink_is_ready(&sink_name).await;
        }
    }

    let already_default = current_default_sink().await.as_deref() == Some(sink_name.as_str());
    if already_default {
        let move_ok = move_all_inputs_to(&sink_name).await;
        if move_ok {
            tokio::time::sleep(Duration::from_millis(20)).await;
            let _ = move_all_inputs_to(&sink_name).await;
        }
        let elapsed = started.elapsed();
        info!(
            sink = %sink_name,
            elapsed_ms = elapsed.as_millis() as u64,
            ready,
            "audio-route: ready (pre-set as default)"
        );
        return RouteOutcome { sink: Some(sink_name), elapsed, ready, routed: true };
    }

    let move_ok = move_all_inputs_to(&sink_name).await;
    let set_default_ok = set_default_sink(&sink_name).await;
    let move_ok2 = if move_ok {
        tokio::time::sleep(Duration::from_millis(30)).await;
        move_all_inputs_to(&sink_name).await
    } else {
        true
    };
    let move_ok = move_ok || move_ok2;

    let elapsed = started.elapsed();
    info!(
        sink = %sink_name,
        elapsed_ms = elapsed.as_millis() as u64,
        ready,
        moved = move_ok,
        defaulted = set_default_ok,
        "audio-route: ready"
    );
    RouteOutcome { sink: Some(sink_name), elapsed, ready, routed: move_ok && set_default_ok }
}

async fn pactl_available() -> bool {
    Command::new("pactl")
        .arg("--version")
        .output()
        .await
        .map(|o| o.status.success())
        .unwrap_or(false)
}

async fn find_sink_for(needles: &[&str]) -> Option<String> {
    let out = Command::new("pactl").args(["list", "short", "sinks"]).output().await.ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    for line in text.lines() {
        let Some(name) = line.split('\t').nth(1) else { continue };
        if !name.starts_with("bluez") {
            continue;
        }
        if needles.iter().any(|n| name.contains(n)) {
            return Some(name.to_string());
        }
    }
    None
}

async fn sink_is_ready(name: &str) -> bool {
    let Ok(out) = Command::new("pactl").args(["list", "short", "sinks"]).output().await else {
        return false;
    };
    if !out.status.success() {
        return false;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    for line in text.lines() {
        let mut parts = line.split('\t');
        let _id = parts.next();
        let sink_name = match parts.next() {
            Some(n) => n,
            None => continue,
        };
        if sink_name != name {
            continue;
        }
        let state = parts.next_back().unwrap_or("").trim();
        return matches!(state, "RUNNING" | "IDLE");
    }
    false
}

async fn move_all_inputs_to(sink: &str) -> bool {
    let Ok(out) = Command::new("pactl").args(["list", "short", "sink-inputs"]).output().await
    else {
        return false;
    };
    if !out.status.success() {
        return false;
    }
    let mut ok = true;
    for line in String::from_utf8_lossy(&out.stdout).lines() {
        let Some(id) = line.split('\t').next() else { continue };
        let id = id.trim();
        if id.is_empty() {
            continue;
        }
        let res = Command::new("pactl").args(["move-sink-input", id, sink]).output().await;
        if res.map(|o| !o.status.success()).unwrap_or(true) {
            ok = false;
        }
    }
    ok
}

async fn force_sink_unsuspended(name: &str) -> bool {
    let _ = Command::new("pactl").args(["suspend-sink", name, "0"]).output().await;
    let probe = Command::new("timeout")
        .args([
            "0.12",
            "paplay",
            "--device",
            name,
            "--raw",
            "--rate=44100",
            "--format=s16le",
            "--channels=2",
            "--volume=0",
            "/dev/zero",
        ])
        .output()
        .await;
    probe.is_ok()
}

pub fn spawn_sink_keepalive(name: String, hold: Duration) {
    let secs = format!("{:.2}", hold.as_secs_f32());
    tokio::spawn(async move {
        let _ = Command::new("timeout")
            .args([
                secs.as_str(),
                "paplay",
                "--device",
                name.as_str(),
                "--raw",
                "--rate=44100",
                "--format=s16le",
                "--channels=2",
                "--volume=0",
                "/dev/zero",
            ])
            .output()
            .await;
    });
}

async fn force_a2dp_card_profile(underscored_mac: &str) -> bool {
    let card = format!("bluez_card.{}", underscored_mac);
    for profile in ["a2dp-sink", "a2dp_sink", "a2dp-sink-aac"] {
        let out = Command::new("pactl").args(["set-card-profile", &card, profile]).output().await;
        if let Ok(o) = out {
            if o.status.success() {
                debug!(card = %card, profile, "forced A2DP profile");
                return true;
            }
        }
    }
    false
}

async fn current_default_sink() -> Option<String> {
    let out = Command::new("pactl").args(["get-default-sink"]).output().await.ok()?;
    if !out.status.success() {
        return None;
    }
    let name = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if name.is_empty() {
        None
    } else {
        Some(name)
    }
}

async fn set_default_sink(name: &str) -> bool {
    Command::new("pactl")
        .args(["set-default-sink", name])
        .output()
        .await
        .map(|o| o.status.success())
        .unwrap_or(false)
}

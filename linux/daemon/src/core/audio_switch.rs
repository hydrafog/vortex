use std::time::Duration;

use bluer::{Adapter, Address};
use tokio::time::sleep;
use tracing::{debug, info, warn};
use uuid::Uuid;

const A2DP_SINK_UUID: Uuid = Uuid::from_u128(0x0000_110b_0000_1000_8000_0080_5f9b_34fb);

const HFP_AG_UUID: Uuid = Uuid::from_u128(0x0000_111f_0000_1000_8000_0080_5f9b_34fb);

#[derive(Debug, thiserror::Error)]
pub enum SwitchError {
    #[error("bad MAC address: {0}")]
    BadAddress(String),
    #[error("bluer: {0}")]
    Bluer(#[from] bluer::Error),
    #[error("device not paired with this adapter")]
    NotPaired,
    #[error("operation timed out after {0:?}")]
    Timeout(Duration),
    #[error("internal: {0}")]
    Internal(String),
}

pub async fn disconnect_audio(adapter: &Adapter, mac: &str) -> Result<(), SwitchError> {
    let addr: Address = mac.parse().map_err(|_| SwitchError::BadAddress(mac.into()))?;
    let device = adapter.device(addr)?;
    if !device.is_paired().await.unwrap_or(false) {
        return Err(SwitchError::NotPaired);
    }

    match device.disconnect().await {
        Ok(()) => debug!(%addr, "device disconnect ok"),
        Err(e) if is_not_connected(&e) => {
            debug!(%addr, "device already disconnected — treating as ok");
        }
        Err(e) => warn!(%addr, "device disconnect failed: {e}"),
    }

    if !wait_audio_disconnected(adapter, addr, DISCONNECT_TIMEOUT).await {
        return Err(SwitchError::Timeout(DISCONNECT_TIMEOUT));
    }
    info!(%addr, "audio device disconnected");
    Ok(())
}

pub async fn disconnect_audio_initiate(adapter: &Adapter, mac: &str) -> Result<(), SwitchError> {
    let addr: Address = mac.parse().map_err(|_| SwitchError::BadAddress(mac.into()))?;
    let device = adapter.device(addr)?;
    if !device.is_paired().await.unwrap_or(false) {
        return Err(SwitchError::NotPaired);
    }
    match device.disconnect().await {
        Ok(()) => {
            debug!(%addr, "device disconnect accepted (initiate)");
            Ok(())
        }
        Err(e) if is_not_connected(&e) => {
            debug!(%addr, "device already disconnected — treating as ok");
            Ok(())
        }
        Err(e) => {
            warn!(%addr, "device disconnect failed: {e}");
            Err(SwitchError::Internal(e.to_string()))
        }
    }
}

pub async fn confirm_audio_disconnected(adapter: &Adapter, mac: &str, timeout: Duration) -> bool {
    let Ok(addr) = mac.parse::<Address>() else { return false };
    wait_audio_disconnected(adapter, addr, timeout).await
}

pub async fn connect_audio(adapter: &Adapter, mac: &str) -> Result<(), SwitchError> {
    let addr: Address = mac.parse().map_err(|_| SwitchError::BadAddress(mac.into()))?;
    let device = adapter.device(addr)?;
    if !device.is_paired().await.unwrap_or(false) {
        return Err(SwitchError::NotPaired);
    }

    // NOTE: validated only against single-point hardware so far (the
    if audio_active(adapter, addr).await && ensure_card_on_a2dp(adapter, addr, mac).await {
        info!(%addr, "A2DP already live (multipoint/reclaim) — fast route, no reconnect");
        return Ok(());
    }

    if device.is_connected().await.unwrap_or(false) {
        for uuid in [A2DP_SINK_UUID, HFP_AG_UUID] {
            let _ = device.disconnect_profile(&uuid).await;
        }
        let _ = wait_audio_disconnected(adapter, addr, Duration::from_millis(500)).await;
    }

    let _ = force_card_to_a2dp(mac).await;

    let t_attempt = tokio::time::Instant::now();

    let t_profile_start = tokio::time::Instant::now();
    let mut a2dp = connect_profile_bounded(&device, &A2DP_SINK_UUID).await;
    let mut a2dp_tries = 0u8;
    while a2dp_tries < A2DP_TRANSIENT_RETRIES
        && matches!(&a2dp, ProfileOutcome::Err(e) if is_transient_connect(e))
    {
        a2dp_tries += 1;
        warn!(%addr, "A2DP transient connect error; retry {a2dp_tries}/{A2DP_TRANSIENT_RETRIES}");
        sleep(A2DP_TRANSIENT_PAUSE).await;
        a2dp = connect_profile_bounded(&device, &A2DP_SINK_UUID).await;
    }
    let bluez_ms = t_profile_start.elapsed().as_millis();
    let a2dp_busy = matches!(a2dp, ProfileOutcome::Busy);
    if matches!(a2dp, ProfileOutcome::Ok | ProfileOutcome::Busy) {
        let t_wait_start = tokio::time::Instant::now();
        if wait_audio_connected(adapter, addr, CONNECT_SETTLE).await {
            let wait_ms = t_wait_start.elapsed().as_millis();
            if ensure_card_on_a2dp(adapter, addr, mac).await {
                let connect_ms = t_attempt.elapsed().as_millis();
                info!(
                    %addr,
                    connect_ms,
                    bluez_ms,
                    wait_ms,
                    a2dp_busy,
                    "A2DP connected"
                );
                return Ok(());
            }
            warn!(%addr, "audio sink is up but the card would not take an A2DP profile");
        }
    }
    let mut last_err: Option<SwitchError> = match a2dp {
        ProfileOutcome::Err(e) => {
            info!("A2DP connect_profile error (will fall back to HFP): {e}");
            Some(SwitchError::Bluer(e))
        }
        ProfileOutcome::TimedOut => {
            warn!(%addr, "A2DP connect_profile timed out ({PROFILE_CONNECT_TIMEOUT:?}); falling back to HFP");
            Some(SwitchError::Timeout(PROFILE_CONNECT_TIMEOUT))
        }
        ProfileOutcome::Ok | ProfileOutcome::Busy => None,
    };

    let hfp = connect_profile_bounded(&device, &HFP_AG_UUID).await;
    let hfp_busy = matches!(hfp, ProfileOutcome::Busy);
    if matches!(hfp, ProfileOutcome::Ok | ProfileOutcome::Busy)
        && wait_audio_connected(adapter, addr, CONNECT_SETTLE).await
    {
        info!(%addr, hfp_busy, "HFP connected (A2DP unavailable)");
        return Ok(());
    }
    match hfp {
        ProfileOutcome::Err(e) => {
            info!("HFP connect_profile error: {e}");
            last_err = Some(SwitchError::Bluer(e));
        }
        ProfileOutcome::TimedOut => {
            warn!(%addr, "HFP connect_profile timed out ({PROFILE_CONNECT_TIMEOUT:?})");
            last_err = Some(SwitchError::Timeout(PROFILE_CONNECT_TIMEOUT));
        }
        ProfileOutcome::Ok | ProfileOutcome::Busy => {}
    }
    Err(last_err.unwrap_or(SwitchError::Timeout(CONNECT_SETTLE)))
}

async fn wait_audio_disconnected(adapter: &Adapter, addr: Address, timeout: Duration) -> bool {
    let deadline = tokio::time::Instant::now() + timeout;
    while tokio::time::Instant::now() < deadline {
        if !audio_active(adapter, addr).await {
            return true;
        }
        sleep(POLL_INTERVAL).await;
    }
    false
}

async fn wait_audio_connected(adapter: &Adapter, addr: Address, timeout: Duration) -> bool {
    if audio_active(adapter, addr).await {
        return true;
    }

    let mut child = match tokio::process::Command::new("pactl")
        .args(["subscribe"])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .stdin(std::process::Stdio::null())
        .kill_on_drop(true)
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            warn!("pactl subscribe spawn failed: {e}; falling back to polling");
            return wait_audio_connected_polling(adapter, addr, timeout).await;
        }
    };
    let stdout = match child.stdout.take() {
        Some(s) => s,
        None => return wait_audio_connected_polling(adapter, addr, timeout).await,
    };
    use tokio::io::AsyncBufReadExt;
    let mut lines = tokio::io::BufReader::new(stdout).lines();
    let deadline = tokio::time::Instant::now() + timeout;

    loop {
        tokio::select! {
            biased;
            _ = tokio::time::sleep_until(deadline) => {
                debug!("wait_audio_connected: timeout via event stream");
                return false;
            }
            line = lines.next_line() => {
                match line {
                    Ok(Some(s)) => {
                        let interesting =
                            s.contains("on sink") || s.contains("on card");
                        if !interesting {
                            continue;
                        }
                        if audio_active(adapter, addr).await {
                            return true;
                        }
                    }
                    Ok(None) | Err(_) => {
                        let remaining = deadline
                            .saturating_duration_since(tokio::time::Instant::now());
                        if remaining.is_zero() {
                            return false;
                        }
                        warn!("pactl subscribe ended unexpectedly; polling remainder");
                        return wait_audio_connected_polling(adapter, addr, remaining).await;
                    }
                }
            }
        }
    }
}

async fn wait_audio_connected_polling(adapter: &Adapter, addr: Address, timeout: Duration) -> bool {
    let deadline = tokio::time::Instant::now() + timeout;
    while tokio::time::Instant::now() < deadline {
        if audio_active(adapter, addr).await {
            return true;
        }
        sleep(POLL_INTERVAL).await;
    }
    false
}

pub async fn audio_active(adapter: &Adapter, addr: Address) -> bool {
    let device = match adapter.device(addr) {
        Ok(d) => d,
        Err(_) => return false,
    };
    if !device.is_connected().await.unwrap_or(false) {
        return false;
    }
    let needle_under = addr.to_string().replace(':', "_");
    let needle_colon = addr.to_string();
    crate::core::audio_sink_cache::has_bluez_sink_for(&[&needle_under, &needle_colon]).await
}

async fn force_card_to_a2dp(mac: &str) -> bool {
    let card = card_name(mac);
    let mut candidates = card_a2dp_profiles(mac).await;
    for fallback in ["a2dp-sink", "a2dp_sink", "a2dp-sink-aac"] {
        if !candidates.iter().any(|p| p == fallback) {
            candidates.push(fallback.to_string());
        }
    }
    for profile in candidates {
        let res = tokio::process::Command::new("pactl")
            .args(["set-card-profile", &card, &profile])
            .output()
            .await;
        if let Ok(o) = res {
            if o.status.success() {
                debug!(%card, %profile, "card pushed to A2DP");
                return true;
            }
        }
    }
    false
}

fn card_name(mac: &str) -> String {
    format!("bluez_card.{}", mac.replace(':', "_"))
}

async fn card_block(mac: &str) -> Vec<String> {
    let Ok(out) = tokio::process::Command::new("pactl").args(["list", "cards"]).output().await
    else {
        return Vec::new();
    };
    let text = String::from_utf8_lossy(&out.stdout);
    let want = card_name(mac);
    let mut block = Vec::new();
    let mut inside = false;
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("Card #") {
            if inside {
                break;
            }
            continue;
        }
        if let Some(name) = trimmed.strip_prefix("Name: ") {
            inside = name.trim() == want;
            if inside {
                continue;
            }
        }
        if inside {
            block.push(line.to_string());
        }
    }
    block
}

async fn card_active_profile(mac: &str) -> Option<String> {
    parse_active_profile(&card_block(mac).await)
}

fn parse_active_profile(block: &[String]) -> Option<String> {
    block
        .iter()
        .find_map(|l| l.trim().strip_prefix("Active Profile: ").map(|p| p.trim().to_string()))
}

async fn card_a2dp_profiles(mac: &str) -> Vec<String> {
    parse_a2dp_profiles(&card_block(mac).await)
}

fn parse_a2dp_profiles(block: &[String]) -> Vec<String> {
    let mut found: Vec<(i64, String)> = block
        .iter()
        .filter_map(|l| {
            let t = l.trim();
            let name = t.split(':').next()?.trim();
            if !(name.starts_with("a2dp-") || name.starts_with("a2dp_")) {
                return None;
            }
            if field_of(t, "available: ").is_some_and(|v| v == "no") {
                return None;
            }
            let priority =
                field_of(t, "priority: ").and_then(|v| v.parse::<i64>().ok()).unwrap_or(0);
            Some((priority, name.to_string()))
        })
        .collect();
    found.sort_by(|a, b| b.0.cmp(&a.0));
    found.into_iter().map(|(_, name)| name).collect()
}

fn field_of(line: &str, key: &str) -> Option<String> {
    let rest = line.split(key).nth(1)?;
    let end = rest.find([',', ')']).unwrap_or(rest.len());
    Some(rest[..end].trim().to_string())
}

pub async fn a2dp_card_active(mac: &str) -> bool {
    card_active_profile(mac).await.is_some_and(|p| p.starts_with("a2dp"))
}

async fn settle_until_a2dp(mac: &str, timeout: Duration) -> bool {
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        if a2dp_card_active(mac).await {
            return true;
        }
        if tokio::time::Instant::now() >= deadline {
            return false;
        }
        sleep(CARD_PROFILE_POLL).await;
    }
}

async fn device_advertises_a2dp(adapter: &Adapter, addr: Address) -> bool {
    let Ok(device) = adapter.device(addr) else { return false };
    device.uuids().await.ok().flatten().is_some_and(|set| set.contains(&A2DP_SINK_UUID))
}

async fn ensure_card_on_a2dp(adapter: &Adapter, addr: Address, mac: &str) -> bool {
    if a2dp_card_active(mac).await {
        return true;
    }

    if !card_a2dp_profiles(mac).await.is_empty() {
        if force_card_to_a2dp(mac).await && settle_until_a2dp(mac, CARD_PROFILE_SETTLE).await {
            info!(%mac, "card moved off the headset profile onto A2DP");
            return true;
        }
    }

    if !device_advertises_a2dp(adapter, addr).await {
        debug!(%mac, "device advertises no A2DP sink — HFP-only buds");
        return false;
    }
    let active_now = card_active_profile(mac).await;
    warn!(
        %mac,
        active = ?active_now,
        "buds are connected but their PipeWire card exposes no A2DP profile \
         (lost endpoint) — recycling the link to force a re-probe"
    );
    if let Ok(device) = adapter.device(addr) {
        match device.disconnect().await {
            Ok(()) | Err(_) => {}
        }
        let _ = wait_audio_disconnected(adapter, addr, DISCONNECT_TIMEOUT).await;
        sleep(RECYCLE_PAUSE).await;
        let _ = tokio::time::timeout(PROFILE_CONNECT_TIMEOUT, device.connect()).await;
        let _ = wait_audio_connected(adapter, addr, CONNECT_SETTLE).await;
    }

    if settle_until_a2dp(mac, CARD_PROFILE_SETTLE).await
        || (force_card_to_a2dp(mac).await && settle_until_a2dp(mac, CARD_PROFILE_SETTLE).await)
    {
        info!(%mac, "A2DP endpoint recovered after the link recycle");
        return true;
    }
    warn!(
        %mac,
        "A2DP still unavailable after a link recycle — PipeWire needs a \
         `systemctl --user restart wireplumber` to rebuild this card"
    );
    false
}

enum ProfileOutcome {
    Ok,
    Busy,
    Err(bluer::Error),
    TimedOut,
}

async fn connect_profile_bounded(device: &bluer::Device, uuid: &Uuid) -> ProfileOutcome {
    match tokio::time::timeout(PROFILE_CONNECT_TIMEOUT, device.connect_profile(uuid)).await {
        Ok(Ok(())) => ProfileOutcome::Ok,
        Ok(Err(e)) if is_busy_or_in_progress(&e) => ProfileOutcome::Busy,
        Ok(Err(e)) => ProfileOutcome::Err(e),
        Err(_) => ProfileOutcome::TimedOut,
    }
}

fn is_busy_or_in_progress(e: &bluer::Error) -> bool {
    let s = e.to_string().to_ascii_lowercase();
    s.contains("already in progress")
        || s.contains("br-connection-busy")
        || s.contains("connection-busy")
        || s.contains("operation already in progress")
}

fn is_transient_connect(e: &bluer::Error) -> bool {
    let s = e.to_string().to_ascii_lowercase();
    s.contains("create-socket")
        || s.contains("connection-canceled")
        || s.contains("br-connection-canceled")
        || s.contains("page-timeout")
        || s.contains("page timeout")
        || s.contains("connection refused")
        || s.contains("host is down")
        || s.contains("connection timed out")
}

fn is_not_connected(e: &bluer::Error) -> bool {
    let s = e.to_string().to_ascii_lowercase();
    s.contains("not connected") || s.contains("invalid arguments")
}

const CONNECT_SETTLE: Duration = Duration::from_millis(4000);

const PROFILE_CONNECT_TIMEOUT: Duration = Duration::from_millis(6000);

const CARD_PROFILE_SETTLE: Duration = Duration::from_millis(1500);

const CARD_PROFILE_POLL: Duration = Duration::from_millis(120);

const RECYCLE_PAUSE: Duration = Duration::from_millis(600);

const A2DP_TRANSIENT_RETRIES: u8 = 2;
const A2DP_TRANSIENT_PAUSE: Duration = Duration::from_millis(220);

const DISCONNECT_TIMEOUT: Duration = Duration::from_millis(1000);

const POLL_INTERVAL: Duration = Duration::from_millis(40);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uuids_match_sig_assignments() {
        assert_eq!(A2DP_SINK_UUID.to_string(), "0000110b-0000-1000-8000-00805f9b34fb");
        assert_eq!(HFP_AG_UUID.to_string(), "0000111f-0000-1000-8000-00805f9b34fb");
    }

    fn healthy_card_block() -> Vec<String> {
        [
            "\tProfiles:",
            "\t\toff: Off (sinks: 0, sources: 0, priority: 0, available: yes)",
            "\t\ta2dp-sink-sbc: High Fidelity Playback (A2DP Sink, codec SBC) (sinks: 1, sources: 1, priority: 132, available: yes)",
            "\t\ta2dp-sink-sbc_xq: High Fidelity Playback (A2DP Sink, codec SBC-XQ) (sinks: 1, sources: 1, priority: 131, available: yes)",
            "\t\ta2dp-sink: High Fidelity Playback (A2DP Sink, codec AAC) (sinks: 1, sources: 1, priority: 133, available: yes)",
            "\t\theadset-head-unit-cvsd: Headset Head Unit (HSP/HFP, codec CVSD) (sinks: 1, sources: 1, priority: 5, available: yes)",
            "\t\theadset-head-unit: Headset Head Unit (HSP/HFP, codec MSBC) (sinks: 1, sources: 1, priority: 6, available: yes)",
            "\tActive Profile: a2dp-sink",
            "\tPorts:",
            "\t\theadset-output: Headphones (type: Headset, priority: 0, latency offset: 0 usec, available)",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect()
    }

    fn lost_endpoint_card_block() -> Vec<String> {
        [
            "\tProfiles:",
            "\t\toff: Off (sinks: 0, sources: 0, priority: 0, available: yes)",
            "\t\theadset-head-unit-cvsd: Headset Head Unit (HSP/HFP, codec CVSD) (sinks: 1, sources: 1, priority: 2, available: yes)",
            "\t\theadset-head-unit: Headset Head Unit (HSP/HFP, codec MSBC) (sinks: 1, sources: 1, priority: 3, available: yes)",
            "\tActive Profile: headset-head-unit",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect()
    }

    #[test]
    fn a2dp_profiles_are_ordered_best_codec_first() {
        assert_eq!(
            parse_a2dp_profiles(&healthy_card_block()),
            vec!["a2dp-sink", "a2dp-sink-sbc", "a2dp-sink-sbc_xq"]
        );
    }

    #[test]
    fn headset_and_port_lines_are_not_mistaken_for_a2dp() {
        let profiles = parse_a2dp_profiles(&healthy_card_block());
        assert!(profiles.iter().all(|p| p.starts_with("a2dp")));
    }

    #[test]
    fn lost_endpoint_card_offers_no_a2dp() {
        assert!(parse_a2dp_profiles(&lost_endpoint_card_block()).is_empty());
        assert_eq!(
            parse_active_profile(&lost_endpoint_card_block()).as_deref(),
            Some("headset-head-unit")
        );
    }

    #[test]
    fn active_profile_distinguishes_a2dp_from_headset() {
        let healthy = parse_active_profile(&healthy_card_block()).unwrap();
        let broken = parse_active_profile(&lost_endpoint_card_block()).unwrap();
        assert!(healthy.starts_with("a2dp"));
        assert!(!broken.starts_with("a2dp"));
    }

    #[test]
    fn unavailable_profiles_are_skipped() {
        let block: Vec<String> = [
            "\t\ta2dp-sink: High Fidelity Playback (A2DP Sink, codec AAC) (sinks: 1, sources: 1, priority: 133, available: no)",
            "\t\ta2dp-sink-sbc: High Fidelity Playback (A2DP Sink, codec SBC) (sinks: 1, sources: 1, priority: 132, available: yes)",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        assert_eq!(parse_a2dp_profiles(&block), vec!["a2dp-sink-sbc"]);
    }

    #[test]
    fn field_of_reads_pactl_trailers() {
        let line = "a2dp-sink: Playback (sinks: 1, sources: 1, priority: 133, available: yes)";
        assert_eq!(field_of(line, "priority: ").as_deref(), Some("133"));
        assert_eq!(field_of(line, "available: ").as_deref(), Some("yes"));
        assert_eq!(field_of(line, "nonexistent: "), None);
    }

    #[test]
    fn bad_mac_yields_clear_error() {
        let parse_result: Result<bluer::Address, _> = "not-a-mac".parse();
        assert!(parse_result.is_err());
    }
}

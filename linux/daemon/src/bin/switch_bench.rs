use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use tokio::time::sleep;
use tracing::{info, warn};

use vortex_l3_daemon::core::audio_lan_session::{
    new_session_writer_map, send_one_shot, start_initiator_session,
};
use vortex_l3_daemon::core::audio_op::{AudioOp, AudioOpFrame};
use vortex_l3_daemon::core::audio_orchestrator::{
    Acceptance, BluerBt, SwitchOrchestrator, SwitchState,
};
use vortex_l3_daemon::core::earbuds::scan_local_earbuds;
use vortex_l3_daemon::core::earbuds_store;
use vortex_l3_daemon::core::identity::Platform;
use vortex_l3_daemon::core::lan::discovery::discover_first;
use vortex_l3_daemon::core::storage::load_or_generate;
use vortex_l3_daemon::core::storage::peers::{PeerStore, SecretServicePeerStore};
use vortex_l3_daemon::core::storage::secret_service::SecretServiceIdentityStore;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            std::env::var("RUST_LOG").unwrap_or_else(|_| "info,vortex_l3_daemon=info".to_string()),
        )
        .init();

    let cycles: usize = std::env::args().nth(1).and_then(|s| s.parse().ok()).unwrap_or(10);

    info!(cycles, "switch-bench: starting");

    let id_store = SecretServiceIdentityStore::new().map_err(|e| format!("identity store: {e}"))?;
    let identity = load_or_generate(&id_store, Platform::Linux)?;
    let peer_store: Arc<dyn PeerStore> =
        Arc::new(SecretServicePeerStore::new().map_err(|e| format!("peer store: {e}"))?);
    let peer = peer_store.list()?.into_iter().next().ok_or("no trusted peer; run pairing first")?;
    let saved = earbuds_store::load().ok_or("no saved earbuds; pick one in the UI first")?;
    let mac = saved.address;

    let session = bluer::Session::new().await?;
    let adapter = session.default_adapter().await?;
    let _ = adapter.set_powered(true).await;

    let session_writers = new_session_writer_map();
    let orch: Arc<SwitchOrchestrator> = {
        let writers = session_writers.clone();
        Arc::new(SwitchOrchestrator::new(
            Arc::new(BluerBt::new(adapter.clone())),
            peer_store.clone(),
            Arc::new(move |peer_pub, frame| {
                let writers = writers.clone();
                Box::pin(async move {
                    let w = writers.lock().await.get(&peer_pub).cloned();
                    match w {
                        Some(writer) => writer(frame).await,
                        None => Err("no active session".to_string()),
                    }
                })
            }),
            Arc::new(|| Acceptance::Allow),
        ))
    };

    let cand = discover_first(Duration::from_secs(6)).await?.ok_or("peer not found on LAN")?;
    let ip = cand
        .addresses
        .iter()
        .find(|a| matches!(a, std::net::IpAddr::V4(_)))
        .copied()
        .or_else(|| cand.addresses.first().copied())
        .ok_or("candidate had no IP")?;
    let addr = std::net::SocketAddr::new(ip, cand.port);
    info!(%addr, "peer addr resolved");

    let local_now = scan_local_earbuds(&adapter).await;
    if local_now.map(|e| e.connected).unwrap_or(false) {
        info!("seed: buds on laptop — pushing to phone first");
        let nonce = peer_store.next_audio_out_nonce(&peer.peer_static_pub).unwrap_or(0);
        let local_counter = peer_store.load_counter(&peer.peer_static_pub).unwrap_or(0);
        let _ = send_one_shot(
            addr,
            &identity.static_priv.0,
            &peer.peer_static_pub,
            &peer.prs,
            local_counter,
            AudioOpFrame { nonce, op: AudioOp::Claim, mac: mac.clone(), ts: now_secs() },
        )
        .await;
        wait_until_disconnected(&adapter, Duration::from_secs(6)).await;
        sleep(Duration::from_millis(1500)).await;
    }

    let mut p2l_perceived: Vec<Duration> = Vec::with_capacity(cycles);
    let mut p2l_full: Vec<Duration> = Vec::with_capacity(cycles);
    let mut l2p: Vec<Duration> = Vec::with_capacity(cycles);
    let mut failures = 0usize;

    for i in 1..=cycles {
        info!(cycle = i, "═══ cycle start ═══");

        let mut state_rx = orch.state();
        let _ = state_rx.borrow_and_update();
        let t_start = Instant::now();
        let local_counter = peer_store.load_counter(&peer.peer_static_pub).unwrap_or(0);
        let orch_c = orch.clone();
        let writers_c = session_writers.clone();
        let identity_c = identity.static_priv.0.clone();
        let peer_pub_c = peer.peer_static_pub;
        let prs_c = peer.prs;
        let mac_c = mac.clone();
        let bg = tokio::spawn(async move {
            start_initiator_session(
                addr,
                &identity_c,
                &peer_pub_c,
                &prs_c,
                local_counter,
                mac_c,
                orch_c,
                writers_c,
            )
            .await
        });

        let mut t_perceived: Option<Instant> = None;
        let mut t_idle: Option<Instant> = None;
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline {
            if state_rx.changed().await.is_err() {
                break;
            }
            let s = state_rx.borrow().clone();
            match s {
                SwitchState::AlmostDone if t_perceived.is_none() => {
                    t_perceived = Some(Instant::now());
                }
                SwitchState::Idle => {
                    t_idle = Some(Instant::now());
                    break;
                }
                SwitchState::Failed(_) => break,
                _ => {}
            }
        }
        let _ = bg.await;

        match (t_perceived, t_idle) {
            (Some(tp), Some(ti)) => {
                let perc = tp.duration_since(t_start);
                let full = ti.duration_since(t_start);
                info!(
                    cycle = i,
                    perceived_ms = perc.as_millis(),
                    full_ms = full.as_millis(),
                    "P→L ok"
                );
                p2l_perceived.push(perc);
                p2l_full.push(full);
            }
            _ => {
                warn!(cycle = i, "P→L: no Idle within deadline");
                failures += 1;
            }
        }

        sleep(Duration::from_millis(800)).await;

        let t_start = Instant::now();
        let nonce = peer_store.next_audio_out_nonce(&peer.peer_static_pub).unwrap_or(0);
        let local_counter = peer_store.load_counter(&peer.peer_static_pub).unwrap_or(0);
        let send_res = send_one_shot(
            addr,
            &identity.static_priv.0,
            &peer.peer_static_pub,
            &peer.prs,
            local_counter,
            AudioOpFrame { nonce, op: AudioOp::Claim, mac: mac.clone(), ts: now_secs() },
        )
        .await;
        if let Err(e) = send_res {
            warn!(cycle = i, "L→P: send_one_shot failed: {e}");
            failures += 1;
            continue;
        }
        let arrived = wait_until_disconnected(&adapter, Duration::from_secs(8)).await;
        if !arrived {
            warn!(cycle = i, "L→P: buds didn't leave laptop in time");
            failures += 1;
        } else {
            let dt = t_start.elapsed();
            info!(cycle = i, ms = dt.as_millis(), "L→P ok");
            l2p.push(dt);
        }

        sleep(Duration::from_millis(1200)).await;
    }

    print_stats("Phone → Laptop  (perceived, Released)", &p2l_perceived);
    print_stats("Phone → Laptop  (BT settled, A2DP up)", &p2l_full);
    print_stats("Laptop → Phone  (buds left laptop)   ", &l2p);
    println!("\nTotal failures: {failures}");

    let max_failures = env_usize("VORTEX_BENCH_MAX_FAILURES", 0);
    let min_samples = env_usize("VORTEX_BENCH_MIN_SAMPLES", 1);
    let budget_perceived = env_u128("VORTEX_BENCH_P2L_PERCEIVED_P95", 3_000);
    let budget_full = env_u128("VORTEX_BENCH_P2L_FULL_P95", 9_000);
    let budget_l2p = env_u128("VORTEX_BENCH_L2P_P95", 9_000);

    let mut violations: Vec<String> = Vec::new();
    if failures > max_failures {
        violations.push(format!(
            "failures {failures} > allowed {max_failures} (stuck-in-switching / no-settle)"
        ));
    }
    check_direction(
        "P→L perceived",
        &p2l_perceived,
        min_samples,
        budget_perceived,
        &mut violations,
    );
    check_direction("P→L full", &p2l_full, min_samples, budget_full, &mut violations);
    check_direction("L→P", &l2p, min_samples, budget_l2p, &mut violations);

    println!("\n=========== BENCH VERDICT ===========");
    if violations.is_empty() {
        println!("  PASS — no regressions detected");
        Ok(())
    } else {
        for v in &violations {
            println!("  FAIL — {v}");
        }
        std::process::exit(1);
    }
}

fn check_direction(
    label: &str,
    samples: &[Duration],
    min_samples: usize,
    budget_ms: u128,
    out: &mut Vec<String>,
) {
    if samples.len() < min_samples {
        out.push(format!("{label}: only {} sample(s), need ≥{min_samples}", samples.len()));
        return;
    }
    if let Some(p95) = p95_ms(samples) {
        if p95 > budget_ms {
            out.push(format!("{label}: p95 {p95}ms > budget {budget_ms}ms"));
        }
    }
}

fn p95_ms(ms: &[Duration]) -> Option<u128> {
    if ms.is_empty() {
        return None;
    }
    let mut sorted: Vec<u128> = ms.iter().map(|d| d.as_millis()).collect();
    sorted.sort_unstable();
    Some(sorted[(sorted.len() * 95 / 100).min(sorted.len() - 1)])
}

fn env_usize(key: &str, default: usize) -> usize {
    std::env::var(key).ok().and_then(|s| s.parse().ok()).unwrap_or(default)
}

fn env_u128(key: &str, default: u128) -> u128 {
    std::env::var(key).ok().and_then(|s| s.parse().ok()).unwrap_or(default)
}

async fn wait_until_disconnected(adapter: &bluer::Adapter, max: Duration) -> bool {
    let deadline = Instant::now() + max;
    while Instant::now() < deadline {
        let snap = scan_local_earbuds(adapter).await;
        let still_here = snap.map(|e| e.connected).unwrap_or(false);
        if !still_here {
            return true;
        }
        sleep(Duration::from_millis(150)).await;
    }
    false
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn print_stats(label: &str, ms: &[Duration]) {
    println!("\n=========== {label} ===========");
    if ms.is_empty() {
        println!("  no successful samples");
        return;
    }
    let mut sorted: Vec<u128> = ms.iter().map(|d| d.as_millis()).collect();
    sorted.sort_unstable();
    let n = sorted.len();
    let min = sorted[0];
    let max = sorted[n - 1];
    let mean: u128 = sorted.iter().sum::<u128>() / n as u128;
    let p50 = sorted[n / 2];
    let p95 = sorted[(n * 95 / 100).min(n - 1)];
    println!("  samples:       {n}");
    println!("  min:           {min:>5} ms");
    println!("  p50 (median):  {p50:>5} ms");
    println!("  mean:          {mean:>5} ms");
    println!("  p95:           {p95:>5} ms");
    println!("  max:           {max:>5} ms");
    print!("  all:");
    for m in ms {
        print!(" {}", m.as_millis());
    }
    println!();
}

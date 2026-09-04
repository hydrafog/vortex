use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock, RwLock};
use std::time::{Duration, Instant};

use tracing::{debug, warn};

const BACKSTOP: Duration = Duration::from_secs(5);
const DEBOUNCE: Duration = Duration::from_millis(80);
const RESUBSCRIBE_BACKOFF: Duration = Duration::from_millis(500);
const SPAWN_FAIL_BACKOFF: Duration = Duration::from_secs(10);

struct SinkCache {
    sinks: RwLock<Vec<String>>,
    healthy: AtomicBool,
}

fn cache() -> &'static Arc<SinkCache> {
    static CACHE: OnceLock<Arc<SinkCache>> = OnceLock::new();
    CACHE.get_or_init(|| {
        let c =
            Arc::new(SinkCache { sinks: RwLock::new(Vec::new()), healthy: AtomicBool::new(false) });
        spawn_updater(c.clone());
        c
    })
}

pub async fn has_bluez_sink_for(needles: &[&str]) -> bool {
    let c = cache();
    if c.healthy.load(Ordering::Acquire) {
        let sinks = c.sinks.read().unwrap_or_else(|e| e.into_inner());
        return sinks.iter().any(|name| needles.iter().any(|n| name.contains(n)));
    }
    probe_bluez_sinks()
        .await
        .unwrap_or_default()
        .iter()
        .any(|name| needles.iter().any(|n| name.contains(n)))
}

async fn probe_bluez_sinks() -> Option<Vec<String>> {
    let out = tokio::process::Command::new("pactl")
        .args(["list", "short", "sinks"])
        .output()
        .await
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    let mut names = Vec::new();
    for line in text.lines() {
        if let Some(name) = line.split('\t').nth(1) {
            if name.starts_with("bluez") {
                names.push(name.to_string());
            }
        }
    }
    Some(names)
}

fn store(c: &SinkCache, names: Vec<String>) {
    *c.sinks.write().unwrap_or_else(|e| e.into_inner()) = names;
    c.healthy.store(true, Ordering::Release);
}

fn spawn_updater(c: Arc<SinkCache>) {
    tokio::spawn(async move {
        loop {
            if let Some(names) = probe_bluez_sinks().await {
                store(&c, names);
            }

            let mut child = match tokio::process::Command::new("pactl")
                .args(["subscribe"])
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::null())
                .stdin(std::process::Stdio::null())
                .kill_on_drop(true)
                .spawn()
            {
                Ok(ch) => ch,
                Err(e) => {
                    warn!("sink-cache: pactl subscribe spawn failed: {e}; readers fork directly");
                    c.healthy.store(false, Ordering::Release);
                    tokio::time::sleep(SPAWN_FAIL_BACKOFF).await;
                    continue;
                }
            };
            let Some(stdout) = child.stdout.take() else {
                c.healthy.store(false, Ordering::Release);
                tokio::time::sleep(SPAWN_FAIL_BACKOFF).await;
                continue;
            };

            use tokio::io::AsyncBufReadExt;
            let mut lines = tokio::io::BufReader::new(stdout).lines();
            let mut pending: Option<tokio::time::Instant> = None;
            let mut last_refresh = Instant::now();

            'stream: loop {
                tokio::select! {
                    biased;
                    line = lines.next_line() => {
                        match line {
                            Ok(Some(l)) => {
                                if l.contains("sink") {
                                    pending = Some(tokio::time::Instant::now() + DEBOUNCE);
                                }
                            }
                            Ok(None) => { debug!("sink-cache: subscribe stream ended; resubscribing"); break 'stream; }
                            Err(e) => { warn!("sink-cache: subscribe read error: {e}; resubscribing"); break 'stream; }
                        }
                    }
                    _ = async {
                        match pending {
                            Some(t) => tokio::time::sleep_until(t).await,
                            None => std::future::pending::<()>().await,
                        }
                    } => {
                        pending = None;
                        if let Some(names) = probe_bluez_sinks().await {
                            store(&c, names);
                            last_refresh = Instant::now();
                        }
                    }
                    _ = tokio::time::sleep(BACKSTOP) => {
                        if last_refresh.elapsed() >= BACKSTOP {
                            if let Some(names) = probe_bluez_sinks().await {
                                store(&c, names);
                            }
                            last_refresh = Instant::now();
                        }
                    }
                }
            }

            c.healthy.store(false, Ordering::Release);
            tokio::time::sleep(RESUBSCRIBE_BACKOFF).await;
        }
    });
}

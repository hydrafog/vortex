use std::collections::HashMap;
use std::time::{Duration, Instant};

use tokio::io::AsyncBufReadExt;
use tokio::sync::mpsc::UnboundedSender;
use tracing::{info, warn};

use crate::core::notif_mirror::NotificationMirror;

const DEDUP_WINDOW: Duration = Duration::from_millis(4_000);
const MAX_TITLE: usize = 120;
const MAX_TEXT: usize = 280;
const RESPAWN_BACKOFF: Duration = Duration::from_secs(5);

pub fn spawn(tx: UnboundedSender<NotificationMirror>) {
    tokio::spawn(async move {
        let mut recent: HashMap<String, Instant> = HashMap::new();
        loop {
            let mut child = match tokio::process::Command::new("dbus-monitor")
                .args(["--session", "interface='org.freedesktop.Notifications',member='Notify'"])
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::null())
                .stdin(std::process::Stdio::null())
                .kill_on_drop(true)
                .spawn()
            {
                Ok(c) => c,
                Err(e) => {
                    warn!("notif-capture: dbus-monitor spawn failed: {e}; laptop→phone notifications off");
                    tokio::time::sleep(RESPAWN_BACKOFF).await;
                    continue;
                }
            };
            let Some(stdout) = child.stdout.take() else {
                tokio::time::sleep(RESPAWN_BACKOFF).await;
                continue;
            };
            info!("notif-capture: watching desktop Notify calls");
            let mut lines = tokio::io::BufReader::new(stdout).lines();

            let mut collecting = false;
            let mut strs: Vec<String> = Vec::new();
            while let Ok(Some(line)) = lines.next_line().await {
                let t = line.trim_start();
                if t.starts_with("method call") {
                    collecting = true;
                    strs.clear();
                    continue;
                }
                if !collecting {
                    continue;
                }
                if t.starts_with("array ") {
                    finalize(&strs, &tx, &mut recent);
                    collecting = false;
                    strs.clear();
                    continue;
                }
                if let Some(s) = parse_string_line(t) {
                    strs.push(s);
                }
            }
            warn!("notif-capture: dbus-monitor stream ended; respawning");
            let _ = child.kill().await;
            tokio::time::sleep(RESPAWN_BACKOFF).await;
        }
    });
}

fn parse_string_line(t: &str) -> Option<String> {
    let rest = t.strip_prefix("string \"")?;
    Some(rest.strip_suffix('"').unwrap_or(rest).to_string())
}

fn finalize(
    strs: &[String],
    tx: &UnboundedSender<NotificationMirror>,
    recent: &mut HashMap<String, Instant>,
) {
    if strs.len() < 4 {
        return;
    }
    let app = strs[0].trim();
    let app_icon = strs.get(1).map(|s| s.as_str()).unwrap_or("");
    if app.eq_ignore_ascii_case("vortex") || app_icon.contains("/.cache/vortex/") {
        return;
    }
    let title = normalize(&strs[2]);
    let text = normalize(&strs[3]);
    if title.is_empty() && text.is_empty() {
        return;
    }

    let now = Instant::now();
    let key = format!("{app}|{title}|{text}");
    recent.retain(|_, t| now.duration_since(*t) < DEDUP_WINDOW);
    if let Some(prev) = recent.get(&key) {
        if now.duration_since(*prev) < DEDUP_WINDOW {
            return;
        }
    }
    recent.insert(key, now);

    let notif = NotificationMirror {
        app: app.to_string(),
        title: title.chars().take(MAX_TITLE).collect(),
        text: text.chars().take(MAX_TEXT).collect(),
        ts: now_ms(),
        ..Default::default()
    };
    info!(app = %notif.app, "notif-capture: desktop notification → phone");
    let _ = tx.send(notif);
}

fn normalize(s: &str) -> String {
    s.replace("\\n", " ").split_whitespace().collect::<Vec<_>>().join(" ")
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

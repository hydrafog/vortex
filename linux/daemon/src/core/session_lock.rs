use std::os::unix::fs::MetadataExt;

use zbus::zvariant::OwnedObjectPath;
use zbus::{Connection, Proxy};

const LOGIN1: &str = "org.freedesktop.login1";

async fn graphical_session_path(conn: &Connection) -> Result<OwnedObjectPath, String> {
    let mgr = Proxy::new(conn, LOGIN1, "/org/freedesktop/login1", "org.freedesktop.login1.Manager")
        .await
        .map_err(|e| format!("login1 manager proxy: {e}"))?;
    let sessions: Vec<(String, u32, String, String, OwnedObjectPath)> =
        mgr.call("ListSessions", &()).await.map_err(|e| format!("ListSessions: {e}"))?;
    let my_uid = std::fs::metadata("/proc/self").map_err(|e| format!("uid lookup: {e}"))?.uid();
    sessions
        .iter()
        .find(|(_, uid, _, seat, _)| *uid == my_uid && !seat.is_empty())
        .or_else(|| sessions.iter().find(|(_, uid, ..)| *uid == my_uid))
        .map(|s| s.4.clone())
        .ok_or_else(|| "no logind session for this user".to_string())
}

async fn session_proxy(conn: &Connection) -> Result<Proxy<'static>, String> {
    let path = graphical_session_path(conn).await?;
    Proxy::new(conn, LOGIN1, path, "org.freedesktop.login1.Session")
        .await
        .map_err(|e| format!("login1 session proxy: {e}"))
}

pub async fn lock() -> Result<(), String> {
    if let Ok(conn) = Connection::session().await {
        if let Ok(p) = Proxy::new(
            &conn,
            "org.gnome.ScreenSaver",
            "/org/gnome/ScreenSaver",
            "org.gnome.ScreenSaver",
        )
        .await
        {
            if p.call::<_, _, ()>("Lock", &()).await.is_ok() {
                return Ok(());
            }
        }
    }
    let conn = Connection::system().await.map_err(|e| format!("system bus: {e}"))?;
    let session = session_proxy(&conn).await?;
    session.call::<_, _, ()>("Lock", &()).await.map_err(|e| format!("logind Lock: {e}"))
}

pub async fn unlock() -> Result<(), String> {
    let conn = Connection::system().await.map_err(|e| format!("system bus: {e}"))?;
    let session = session_proxy(&conn).await?;
    session.call::<_, _, ()>("Unlock", &()).await.map_err(|e| {
        format!("logind Unlock: {e} (one-time polkit rule missing? see session_lock.rs doc)")
    })
}

pub async fn poweroff() -> Result<(), String> {
    if let Ok(conn) = Connection::system().await {
        if let Ok(mgr) =
            Proxy::new(&conn, LOGIN1, "/org/freedesktop/login1", "org.freedesktop.login1.Manager")
                .await
        {
            if mgr.call::<_, _, ()>("PowerOff", &(true)).await.is_ok() {
                return Ok(());
            }
        }
    }
    tokio::task::spawn_blocking(|| {
        let out = std::process::Command::new("systemctl")
            .arg("poweroff")
            .output()
            .map_err(|e| format!("spawn systemctl poweroff: {e}"))?;
        if out.status.success() {
            Ok(())
        } else {
            Err(format!(
                "systemctl poweroff exit {}: {}",
                out.status,
                String::from_utf8_lossy(&out.stderr)
            ))
        }
    })
    .await
    .map_err(|e| format!("spawn_blocking error: {e}"))?
}

pub async fn suspend() -> Result<(), String> {
    if let Ok(conn) = Connection::system().await {
        if let Ok(mgr) =
            Proxy::new(&conn, LOGIN1, "/org/freedesktop/login1", "org.freedesktop.login1.Manager")
                .await
        {
            if mgr.call::<_, _, ()>("Suspend", &(true)).await.is_ok() {
                return Ok(());
            }
        }
    }
    tokio::task::spawn_blocking(|| {
        let out = std::process::Command::new("systemctl")
            .arg("suspend")
            .output()
            .map_err(|e| format!("spawn systemctl suspend: {e}"))?;
        if out.status.success() {
            Ok(())
        } else {
            Err(format!(
                "systemctl suspend exit {}: {}",
                out.status,
                String::from_utf8_lossy(&out.stderr)
            ))
        }
    })
    .await
    .map_err(|e| format!("spawn_blocking error: {e}"))?
}

pub async fn locked_hint() -> Option<bool> {
    let conn = Connection::system().await.ok()?;
    let session = session_proxy(&conn).await.ok()?;
    session.get_property::<bool>("LockedHint").await.ok()
}

const IDLE_MONITOR_DEST: &str = "org.gnome.Mutter.IdleMonitor";
const IDLE_MONITOR_PATH: &str = "/org/gnome/Mutter/IdleMonitor/Core";
const IDLE_MONITOR_IFACE: &str = "org.gnome.Mutter.IdleMonitor";

pub async fn idle_ms() -> Option<u64> {
    if let Some(ms) = mutter_idle_ms().await {
        return Some(ms);
    }
    if let Some(ms) = freedesktop_idle_ms().await {
        return Some(ms);
    }
    logind_idle_ms().await
}

async fn mutter_idle_ms() -> Option<u64> {
    let conn = Connection::session().await.ok()?;
    let p =
        Proxy::new(&conn, IDLE_MONITOR_DEST, IDLE_MONITOR_PATH, IDLE_MONITOR_IFACE).await.ok()?;
    p.call::<_, _, u64>("GetIdletime", &()).await.ok()
}

async fn freedesktop_idle_ms() -> Option<u64> {
    let conn = Connection::session().await.ok()?;
    for path in ["/org/freedesktop/ScreenSaver", "/ScreenSaver"] {
        if let Ok(p) =
            Proxy::new(&conn, "org.freedesktop.ScreenSaver", path, "org.freedesktop.ScreenSaver")
                .await
        {
            if let Ok(secs) = p.call::<_, _, u32>("GetSessionIdleTime", &()).await {
                return Some(secs as u64 * 1000);
            }
        }
    }
    None
}

async fn logind_idle_ms() -> Option<u64> {
    let conn = Connection::system().await.ok()?;
    let session = session_proxy(&conn).await.ok()?;
    if !session.get_property::<bool>("IdleHint").await.ok()? {
        return Some(0);
    }
    let since_us = session.get_property::<u64>("IdleSinceHint").await.ok()?;
    let now_us =
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).ok()?.as_micros() as u64;
    Some(now_us.saturating_sub(since_us) / 1000)
}

pub async fn watch_user_active(on_active: impl Fn() + Send + 'static) -> Result<(), String> {
    if mutter_idle_ms().await.is_some() {
        watch_user_active_mutter(on_active).await
    } else {
        watch_user_active_poll(on_active).await
    }
}

async fn watch_user_active_mutter(on_active: impl Fn() + Send + 'static) -> Result<(), String> {
    use futures::StreamExt;
    let conn = Connection::session().await.map_err(|e| format!("session bus: {e}"))?;
    let proxy = Proxy::new(&conn, IDLE_MONITOR_DEST, IDLE_MONITOR_PATH, IDLE_MONITOR_IFACE)
        .await
        .map_err(|e| format!("idle-monitor proxy: {e}"))?;
    let rule = zbus::MatchRule::builder()
        .msg_type(zbus::message::Type::Signal)
        .interface(IDLE_MONITOR_IFACE)
        .and_then(|b| b.member("WatchFired"))
        .and_then(|b| b.path(IDLE_MONITOR_PATH))
        .map_err(|e| format!("match rule: {e}"))?
        .build();
    let mut stream = zbus::MessageStream::for_match_rule(rule, &conn, None)
        .await
        .map_err(|e| format!("subscribe: {e}"))?;
    let mut watch_id: u32 = proxy
        .call("AddUserActiveWatch", &())
        .await
        .map_err(|e| format!("AddUserActiveWatch: {e}"))?;
    tokio::spawn(async move {
        while let Some(Ok(msg)) = stream.next().await {
            let Ok(id) = msg.body().deserialize::<u32>() else {
                continue;
            };
            if id != watch_id {
                continue;
            }
            on_active();
            match proxy.call("AddUserActiveWatch", &()).await {
                Ok(new_id) => watch_id = new_id,
                Err(e) => {
                    tracing::warn!("user-active watch re-arm failed: {e}");
                    break;
                }
            }
        }
        tracing::warn!("user-active watch stream ended (session bus dropped?)");
    });
    Ok(())
}

pub async fn watch_locked_hint(on_change: impl Fn(bool) + Send + 'static) -> Result<(), String> {
    use futures::StreamExt;
    let conn = Connection::system().await.map_err(|e| format!("system bus: {e}"))?;
    let path = graphical_session_path(&conn).await?;
    let rule = zbus::MatchRule::builder()
        .msg_type(zbus::message::Type::Signal)
        .interface("org.freedesktop.DBus.Properties")
        .and_then(|b| b.member("PropertiesChanged"))
        .and_then(|b| b.path(path.clone()))
        .map_err(|e| format!("match rule: {e}"))?
        .build();
    let mut stream = zbus::MessageStream::for_match_rule(rule, &conn, None)
        .await
        .map_err(|e| format!("subscribe: {e}"))?;
    tokio::spawn(async move {
        let _conn = conn;
        while let Some(Ok(msg)) = stream.next().await {
            let Ok((iface, changed, _invalidated)) = msg.body().deserialize::<(
                String,
                std::collections::HashMap<String, zbus::zvariant::OwnedValue>,
                Vec<String>,
            )>() else {
                continue;
            };
            if iface != "org.freedesktop.login1.Session" {
                continue;
            }
            if let Some(v) = changed.get("LockedHint") {
                if let Ok(locked) = bool::try_from(v) {
                    on_change(locked);
                }
            }
        }
        tracing::warn!("locked-hint watch stream ended (system bus dropped?)");
    });
    Ok(())
}

async fn watch_user_active_poll(on_active: impl Fn() + Send + 'static) -> Result<(), String> {
    if idle_ms().await.is_none() {
        return Err("no idle source available for user-active polling".to_string());
    }
    tracing::info!("user-active watch: polling idle fallback (non-GNOME desktop)");
    tokio::spawn(async move {
        let mut prev = idle_ms().await.unwrap_or(0);
        loop {
            tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
            let cur = idle_ms().await.unwrap_or(prev);
            if prev >= 3000 && cur < 1500 {
                on_active();
            }
            prev = cur;
        }
    });
    Ok(())
}

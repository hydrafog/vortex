
use std::collections::HashMap;
use std::time::Duration;

use futures::StreamExt;
use zbus::zvariant::{OwnedObjectPath, Value};

const BUS: &str = "org.gnome.Mutter.ScreenCast";
const PATH: &str = "/org/gnome/Mutter/ScreenCast";

const CURSOR_PNG: &[u8] = include_bytes!("../assets/cursor.png");

pub(crate) fn stage_cursor_image() -> Option<std::path::PathBuf> {
    let p = std::env::temp_dir().join("vortex-cursor.png");
    std::fs::write(&p, CURSOR_PNG).ok()?;
    Some(p)
}

pub(crate) fn spawn_cursor_overlay(
    overlay: gstreamer::Element,
    alive: std::sync::Arc<std::sync::atomic::AtomicBool>,
) {
    use gstreamer::prelude::*;
    use std::sync::atomic::Ordering;

    tokio::spawn(async move {
        let Ok(conn) = zbus::Connection::session().await else {
            return;
        };
        let shell = match zbus::Proxy::new(
            &conn,
            "org.vortex.Shell",
            "/org/vortex/Shell",
            "org.vortex.Shell1",
        )
        .await
        {
            Ok(p) => p,
            Err(_) => {
                tracing::info!(
                    "virtual-display: shell pointer service absent — no cursor on the phone \
                     (the GNOME extension needs a re-login)"
                );
                return;
            }
        };

        let mut tick = tokio::time::interval(Duration::from_millis(33));
        let mut shown = false;
        while alive.load(Ordering::Relaxed) {
            tick.tick().await;
            let Ok((x, y, on)) = shell
                .call::<_, _, (i32, i32, bool)>("GetVirtualPointer", &())
                .await
            else {
                continue;
            };
            if on {
                overlay.set_property("offset-x", x);
                overlay.set_property("offset-y", y);
            }
            if on != shown {
                overlay.set_property("alpha", if on { 1.0f64 } else { 0.0f64 });
                shown = on;
            }
        }
        overlay.set_property("alpha", 0.0f64);
    });
}

pub(crate) struct VirtualMonitor {
    conn: zbus::Connection,
    session: OwnedObjectPath,
    pub(crate) node_id: u32,
}

pub(crate) async fn create() -> Result<VirtualMonitor, String> {
    let conn = zbus::Connection::session()
        .await
        .map_err(|e| format!("session bus: {e}"))?;

    let screencast = zbus::Proxy::new(&conn, BUS, PATH, BUS)
        .await
        .map_err(|e| format!("mutter screencast unavailable (not GNOME?): {e}"))?;
    let empty: HashMap<&str, Value> = HashMap::new();
    let session: OwnedObjectPath = screencast
        .call("CreateSession", &(empty,))
        .await
        .map_err(|e| format!("CreateSession: {e}"))?;

    let session_proxy = zbus::Proxy::new(&conn, BUS, session.clone(), format!("{BUS}.Session"))
        .await
        .map_err(|e| format!("session proxy: {e}"))?;

    let mut props: HashMap<&str, Value> = HashMap::new();
    props.insert("cursor-mode", Value::U32(2));
    let stream: OwnedObjectPath = session_proxy
        .call("RecordVirtual", &(props,))
        .await
        .map_err(|e| format!("RecordVirtual: {e}"))?;

    let stream_proxy = zbus::Proxy::new(&conn, BUS, stream, format!("{BUS}.Stream"))
        .await
        .map_err(|e| format!("stream proxy: {e}"))?;
    let mut added = stream_proxy
        .receive_signal("PipeWireStreamAdded")
        .await
        .map_err(|e| format!("subscribe: {e}"))?;

    session_proxy
        .call::<_, _, ()>("Start", &())
        .await
        .map_err(|e| format!("Start: {e}"))?;

    let msg = tokio::time::timeout(Duration::from_secs(5), added.next())
        .await
        .map_err(|_| "timed out waiting for the PipeWire node".to_string())?
        .ok_or_else(|| "stream closed before announcing a node".to_string())?;
    let node_id: u32 = msg
        .body()
        .deserialize()
        .map_err(|e| format!("node id: {e}"))?;

    if let Ok(mut closed) = session_proxy.receive_signal("Closed").await {
        tokio::spawn(async move {
            if closed.next().await.is_some() {
                tracing::warn!("virtual-display: MUTTER closed the session (not us)");
            }
        });
    }

    tracing::info!(node_id, "virtual-display: monitor session up");
    Ok(VirtualMonitor {
        conn,
        session,
        node_id,
    })
}

impl VirtualMonitor {
    pub(crate) async fn stop(&self) {
        let Ok(proxy) = zbus::Proxy::new(
            &self.conn,
            BUS,
            self.session.clone(),
            format!("{BUS}.Session"),
        )
        .await
        else {
            return;
        };
        if let Err(e) = proxy.call::<_, _, ()>("Stop", &()).await {
            tracing::debug!("virtual-display: stop: {e}");
        }
        tracing::info!("virtual-display: monitor removed");
    }
}

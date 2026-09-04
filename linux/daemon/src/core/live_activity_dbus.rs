use zbus::interface;

pub struct LiveActivities {
    json: String,
    call_action_tx: tokio::sync::mpsc::UnboundedSender<String>,
}

#[interface(name = "org.vortex.LiveActivities1")]
impl LiveActivities {
    #[zbus(property)]
    async fn activities(&self) -> String {
        self.json.clone()
    }

    async fn call_action(&self, action: String) {
        tracing::info!(action = %action, "call-card action from extension");
        let _ = self.call_action_tx.send(action);
    }
}

pub async fn start(
    call_action_tx: tokio::sync::mpsc::UnboundedSender<String>,
) -> Result<tokio::sync::mpsc::UnboundedSender<String>, String> {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<String>();
    let conn = zbus::connection::Builder::session()
        .map_err(|e| format!("session bus: {e}"))?
        .name("org.vortex.LiveActivities")
        .map_err(|e| format!("request name: {e}"))?
        .serve_at(
            "/org/vortex/LiveActivities",
            LiveActivities { json: "[]".to_string(), call_action_tx },
        )
        .map_err(|e| format!("serve_at: {e}"))?
        .build()
        .await
        .map_err(|e| format!("build: {e}"))?;
    tracing::info!("live-activity D-Bus publisher up (org.vortex.LiveActivities)");
    tokio::spawn(async move {
        let conn = conn;
        let iface_ref = match conn
            .object_server()
            .interface::<_, LiveActivities>("/org/vortex/LiveActivities")
            .await
        {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("live-activity dbus: interface lookup: {e}");
                return;
            }
        };
        while let Some(json) = rx.recv().await {
            let mut iface = iface_ref.get_mut().await;
            iface.json = json;
            let _ = iface.activities_changed(iface_ref.signal_emitter()).await;
        }
    });
    Ok(tx)
}

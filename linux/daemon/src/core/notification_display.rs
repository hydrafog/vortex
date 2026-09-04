use std::collections::HashMap;

use zbus::zvariant::Value;
use zbus::{Connection, Proxy};

use crate::core::notif_mirror::NotificationMirror;

fn gvariant_string(s: &str) -> String {
    let escaped = s.replace('\\', "\\\\").replace('"', "\\\"");
    format!("\"{escaped}\"")
}

static CONN: tokio::sync::OnceCell<Connection> = tokio::sync::OnceCell::const_new();

async fn conn() -> Result<&'static Connection, String> {
    CONN.get_or_try_init(|| async {
        Connection::session().await.map_err(|e| format!("session bus: {e}"))
    })
    .await
}

async fn notifications_proxy() -> Result<Proxy<'static>, String> {
    Proxy::new(
        conn().await?,
        "org.freedesktop.Notifications",
        "/org/freedesktop/Notifications",
        "org.freedesktop.Notifications",
    )
    .await
    .map_err(|e| format!("notifications proxy: {e}"))
}

async fn post_via_gdbus_child() -> bool {
    static VIA_CHILD: tokio::sync::OnceCell<bool> = tokio::sync::OnceCell::const_new();
    *VIA_CHILD
        .get_or_init(|| async {
            let info = match notifications_proxy().await {
                Ok(p) => p
                    .call::<_, _, (String, String, String, String)>("GetServerInformation", &())
                    .await
                    .map_err(|e| format!("GetServerInformation: {e}")),
                Err(e) => Err(e),
            };
            match info {
                Ok((name, vendor, version, _spec)) => {
                    let gnome = format!("{name} {vendor}").to_lowercase().contains("gnome");
                    tracing::info!(
                        server = %name, vendor = %vendor, %version, via_gdbus_child = gnome,
                        "notification server probed"
                    );
                    if let Ok(p) = notifications_proxy().await {
                        if let Ok(caps) = p.call::<_, _, Vec<String>>("GetCapabilities", &()).await
                        {
                            if !caps.iter().any(|c| c == "actions") {
                                tracing::warn!(
                                    ?caps,
                                    "notification server does NOT support actions — banners with \
                                     Accept/Decline (incoming file shares, calls) will have no \
                                     buttons; those prompts will time out as declined"
                                );
                            }
                        }
                    }
                    gnome
                }
                Err(e) => {
                    tracing::warn!("notification server probe failed ({e}); assuming GNOME");
                    true
                }
            }
        })
        .await
}

#[allow(clippy::too_many_arguments)]
async fn notify(
    app_name: &str,
    replaces_id: u32,
    app_icon: &str,
    summary: &str,
    body: &str,
    actions: &[String],
    urgency: Option<u8>,
    category: Option<&str>,
    expire_timeout: i32,
) -> Result<u32, String> {
    if !post_via_gdbus_child().await {
        let mut hints: HashMap<&str, Value<'_>> = HashMap::new();
        if let Some(u) = urgency {
            hints.insert("urgency", Value::U8(u));
        }
        if let Some(c) = category {
            hints.insert("category", Value::from(c));
        }
        return notifications_proxy()
            .await?
            .call::<_, _, u32>(
                "Notify",
                &(app_name, replaces_id, app_icon, summary, body, actions, hints, expire_timeout),
            )
            .await
            .map_err(|e| format!("Notify: {e}"));
    }

    let actions_arg =
        format!("[{}]", actions.iter().map(|a| gvariant_string(a)).collect::<Vec<_>>().join(", "));
    let mut hint_parts: Vec<String> = Vec::new();
    if let Some(u) = urgency {
        hint_parts.push(format!("'urgency': <byte {u}>"));
    }
    if let Some(c) = category {
        hint_parts.push(format!("'category': <{}>", gvariant_string(c)));
    }
    let hints_arg = if hint_parts.is_empty() {
        "@a{sv} {}".to_string()
    } else {
        format!("{{{}}}", hint_parts.join(", "))
    };
    let output = tokio::process::Command::new("gdbus")
        .arg("call")
        .arg("--session")
        .arg("--dest")
        .arg("org.freedesktop.Notifications")
        .arg("--object-path")
        .arg("/org/freedesktop/Notifications")
        .arg("--method")
        .arg("org.freedesktop.Notifications.Notify")
        .arg(gvariant_string(app_name))
        .arg(replaces_id.to_string())
        .arg(gvariant_string(app_icon))
        .arg(gvariant_string(summary))
        .arg(gvariant_string(body))
        .arg(&actions_arg)
        .arg(&hints_arg)
        .arg(expire_timeout.to_string())
        .output()
        .await
        .map_err(|e| format!("gdbus spawn: {e}"))?;
    if !output.status.success() {
        return Err(format!(
            "gdbus Notify failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    stdout
        .replace("uint32", " ")
        .chars()
        .skip_while(|c| !c.is_ascii_digit())
        .take_while(|c| c.is_ascii_digit())
        .collect::<String>()
        .parse()
        .map_err(|_| format!("gdbus Notify: unparseable id {stdout:?}"))
}

pub async fn show(notif: &NotificationMirror, replaces_id: u32) -> Result<u32, String> {
    let summary = if notif.title.is_empty() { notif.app.clone() } else { notif.title.clone() };

    let mut actions: Vec<String> = vec!["default".to_string(), "Open".to_string()];
    for (i, label) in notif.actions.iter().enumerate() {
        actions.push(format!("act:{i}"));
        actions.push(label.clone());
    }
    let expire_timeout: i32 = if notif.actions.is_empty() { 8000 } else { 0 };

    let app_icon = crate::core::icon_cache::icon_path(&notif.app_id)
        .filter(|p| p.exists())
        .or_else(crate::core::icon_cache::ensure_generic)
        .and_then(|p| p.to_str().map(str::to_string))
        .unwrap_or_else(|| "phone-symbolic".to_string());
    let app_name = if notif.app.is_empty() { "Phone".to_string() } else { notif.app.clone() };

    let body = notif.text.replace('\n', "  ·  ");
    notify(&app_name, replaces_id, &app_icon, &summary, &body, &actions, None, None, expire_timeout)
        .await
}

pub async fn close(id: u32) -> Result<(), String> {
    notifications_proxy()
        .await?
        .call::<_, _, ()>("CloseNotification", &(id,))
        .await
        .map_err(|e| format!("CloseNotification: {e}"))?;
    Ok(())
}

pub async fn show_call_banner(
    title: &str,
    body: &str,
    app_id: &str,
    actions: &[(String, String)],
    replaces_id: u32,
    critical: bool,
) -> Result<u32, String> {
    let icon = crate::core::icon_cache::icon_path(app_id)
        .filter(|p| p.exists())
        .and_then(|p| p.to_str().map(str::to_string))
        .unwrap_or_else(|| "call-start-symbolic".to_string());
    let mut flat: Vec<String> = Vec::with_capacity(actions.len() * 2);
    for (key, label) in actions {
        flat.push(key.clone());
        flat.push(label.clone());
    }

    let urgency = if critical { 2u8 } else { 1u8 };

    notify("Phone", replaces_id, &icon, title, body, &flat, Some(urgency), Some("call.incoming"), 0)
        .await
}

fn signal_rule(member: &'static str) -> zbus::Result<zbus::MatchRule<'static>> {
    Ok(zbus::MatchRule::builder()
        .msg_type(zbus::message::Type::Signal)
        .interface("org.freedesktop.Notifications")?
        .member(member)?
        .path("/org/freedesktop/Notifications")?
        .build())
}

pub async fn watch_closed(tx: tokio::sync::mpsc::UnboundedSender<(u32, u32)>) {
    use futures::StreamExt;
    let conn = match Connection::session().await {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!("notif-closed watch: session bus: {e}");
            return;
        }
    };
    let rule = match signal_rule("NotificationClosed") {
        Ok(r) => r,
        Err(e) => {
            tracing::warn!("notif-closed watch: rule: {e}");
            return;
        }
    };
    let mut stream = match zbus::MessageStream::for_match_rule(rule, &conn, None).await {
        Ok(s) => s,
        Err(e) => {
            tracing::warn!("notif-closed watch: subscribe: {e}");
            return;
        }
    };
    tracing::info!("notif-closed watch: subscribed (sender-less rule)");
    while let Some(Ok(msg)) = stream.next().await {
        if let Ok((id, reason)) = msg.body().deserialize::<(u32, u32)>() {
            tracing::info!(id, reason, "notif-closed: NotificationClosed signal");
            let _ = tx.send((id, reason));
        }
    }
}

pub async fn watch_actions(tx: tokio::sync::mpsc::UnboundedSender<(u32, String)>) {
    use futures::StreamExt;
    let conn = match Connection::session().await {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!("notif-action watch: session bus: {e}");
            return;
        }
    };
    let rule = match signal_rule("ActionInvoked") {
        Ok(r) => r,
        Err(e) => {
            tracing::warn!("notif-action watch: rule: {e}");
            return;
        }
    };
    let mut stream = match zbus::MessageStream::for_match_rule(rule, &conn, None).await {
        Ok(s) => s,
        Err(e) => {
            tracing::warn!("notif-action watch: subscribe: {e}");
            return;
        }
    };
    tracing::info!("notif-action watch: subscribed (sender-less rule)");
    while let Some(Ok(msg)) = stream.next().await {
        if let Ok((id, key)) = msg.body().deserialize::<(u32, String)>() {
            tracing::info!(id, action = %key, "notif-action: ActionInvoked signal");
            let _ = tx.send((id, key));
        }
    }
}

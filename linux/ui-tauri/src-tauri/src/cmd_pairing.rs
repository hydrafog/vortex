
use std::time::Duration;

use tauri::Emitter;

use vortex_l3_daemon::core::ble::scanner::run_filtered_scan;

use crate::ipc::{emit_peers, PairingResultDto, PairingStartedDto, ScanHitDto};
use crate::pairing::{do_pair, send_revoke_to_peer};
use crate::worker_ctx::WorkerCtx;

fn purge_peer_cache(app: &tauri::AppHandle) {
    crate::contacts::clear(app);
    crate::call_log::clear(app);
    crate::sms::clear(app);
    crate::notes::clear(app);
    crate::lan::clear_last_peer_ip();
}

pub(crate) fn scan(ctx: &WorkerCtx, active_scan: &mut Option<tokio::task::JoinHandle<()>>) {
    if let Some(prev) = active_scan.take() {
        prev.abort();
    }
    let app_c = ctx.app.clone();
    let adapter_c = ctx.adapter.clone();
    *active_scan = Some(tokio::spawn(async move {
        let _ = app_c.emit("vortex:busy", true);
        let app_for_cb = app_c.clone();
        let _ = tokio::time::timeout(
            Duration::from_secs(8),
            run_filtered_scan(adapter_c, move |c| {
                if !c.payload.flags.is_pairable() {
                    return;
                }
                let hit = ScanHitDto {
                    addr: c.address.to_string(),
                    rssi: c.rssi.unwrap_or(0),
                    instance: hex::encode(c.payload.payload_8),
                    name: c.local_name.clone(),
                };
                tracing::info!(
                    addr = %hit.addr,
                    rssi = hit.rssi,
                    instance = %hit.instance,
                    name = ?hit.name,
                    "scan hit"
                );
                let _ = app_for_cb.emit("vortex:scan_result", hit);
            }),
        )
        .await;
        let _ = app_c.emit::<Option<()>>("vortex:scan_done", None);
        let _ = app_c.emit("vortex:busy", false);
    }));
}

pub(crate) async fn pair(
    ctx: &WorkerCtx,
    addr_str: String,
    active_scan: &mut Option<tokio::task::JoinHandle<()>>,
) {
    let app = &ctx.app;
    let _ = app.emit(
        "vortex:pairing_started",
        PairingStartedDto { peer_addr: addr_str.clone() },
    );
    if let Some(h) = active_scan.take() {
        h.abort();
        let _ = h.await;
    }
    {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        while ctx.adapter.is_discovering().await.unwrap_or(false) {
            if tokio::time::Instant::now() >= deadline {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }
    let result = do_pair(app, &ctx.adapter, &addr_str, &ctx.identity, ctx.peer_store.clone()).await;
    match result {
        Ok(_) => {
            let _ = app.emit(
                "vortex:pairing_result",
                PairingResultDto::Ok {
                    ok: true,
                    message: format!("trust persisted with {addr_str}"),
                },
            );
            emit_peers(app, ctx.peer_store.clone()).await;
        }
        Err(err) => {
            tracing::warn!(peer = %addr_str, "pairing failed: {err}");
            let _ = app.emit(
                "vortex:pairing_result",
                PairingResultDto::Err { ok: false, error: err },
            );
        }
    }
}

pub(crate) async fn forget_peer(ctx: &WorkerCtx, hex_str: String) {
    let Ok(bytes) = hex::decode(&hex_str) else { return };
    if bytes.len() != 32 {
        return;
    }
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&bytes);
    let ps = ctx.peer_store.clone();
    let arr_load = arr;
    let peer_for_revoke = tokio::task::spawn_blocking(move || ps.load(&arr_load).ok())
        .await
        .unwrap_or(None);
    let ps = ctx.peer_store.clone();
    let arr_load = arr;
    let counter_for_revoke =
        tokio::task::spawn_blocking(move || ps.load_counter(&arr_load).unwrap_or(0))
            .await
            .unwrap_or(0);
    let ps = ctx.peer_store.clone();
    let arr_forget = arr;
    let forget_result = tokio::task::spawn_blocking(move || ps.forget(&arr_forget)).await;
    match forget_result {
        Ok(Ok(())) => tracing::info!("peer_store.forget OK for {}", hex::encode(&arr[..8])),
        Ok(Err(e)) => {
            tracing::warn!("peer_store.forget FAILED for {}: {}", hex::encode(&arr[..8]), e)
        }
        Err(e) => tracing::warn!("peer_store.forget JOIN ERROR: {}", e),
    }
    purge_peer_cache(&ctx.app);
    emit_peers(&ctx.app, ctx.peer_store.clone()).await;
    if let Some(peer) = peer_for_revoke {
        let identity_c = ctx.identity.clone();
        let arr_c = arr;
        tokio::spawn(async move {
            let deadline = std::time::Instant::now() + Duration::from_secs(60);
            let mut attempt: u32 = 0;
            while std::time::Instant::now() < deadline {
                attempt += 1;
                let counter = counter_for_revoke.saturating_add(attempt as u64);
                match send_revoke_to_peer(&identity_c, &peer, &arr_c, counter).await {
                    Ok(()) => {
                        tracing::info!(attempt, "revoke delivered to {}", hex::encode(&arr_c[..8]));
                        return;
                    }
                    Err(e) => {
                        tracing::debug!(attempt, "revoke attempt failed: {e}; will retry");
                        tokio::time::sleep(Duration::from_secs(5)).await;
                    }
                }
            }
            tracing::warn!("revoke retries exhausted for {} after 60s", hex::encode(&arr_c[..8]));
        });
    }
}

pub(crate) async fn forget_all(ctx: &WorkerCtx) {
    let ps = ctx.peer_store.clone();
    let _ = tokio::task::spawn_blocking(move || {
        if let Ok(list) = ps.list() {
            for p in list {
                if let Err(e) = ps.forget(&p.peer_static_pub) {
                    tracing::warn!(
                        "ForgetAll: forget failed for {}: {}",
                        hex::encode(&p.peer_static_pub[..8]),
                        e
                    );
                }
            }
        }
    })
    .await;
    purge_peer_cache(&ctx.app);
    emit_peers(&ctx.app, ctx.peer_store.clone()).await;
}

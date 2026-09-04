use std::collections::{HashMap, HashSet};
use std::sync::{Mutex, OnceLock};

use bluer::{Adapter, Address, DiscoveryFilter, DiscoveryTransport};
use uuid::Uuid;

use crate::core::appstate::EarbudsInfo;
use crate::core::earbuds_store;

fn battery_cache() -> &'static Mutex<HashMap<String, u8>> {
    static CACHE: OnceLock<Mutex<HashMap<String, u8>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn battery_with_fallback(addr: &str, fresh: Option<u8>) -> Option<u8> {
    let mut cache = match battery_cache().lock() {
        Ok(c) => c,
        Err(p) => p.into_inner(),
    };
    match fresh {
        Some(pct) => {
            cache.insert(addr.to_string(), pct);
            Some(pct)
        }
        None => cache.get(addr).copied(),
    }
}

const AUDIO_SERVICE_UUIDS: &[&str] = &[
    "0000110a-0000-1000-8000-00805f9b34fb",
    "0000110b-0000-1000-8000-00805f9b34fb",
    "0000110d-0000-1000-8000-00805f9b34fb",
    "0000110e-0000-1000-8000-00805f9b34fb",
    "0000111e-0000-1000-8000-00805f9b34fb",
    "0000111f-0000-1000-8000-00805f9b34fb",
    "00001108-0000-1000-8000-00805f9b34fb",
    "00001112-0000-1000-8000-00805f9b34fb",
];

fn audio_uuid_set() -> HashSet<Uuid> {
    AUDIO_SERVICE_UUIDS.iter().filter_map(|s| Uuid::parse_str(s).ok()).collect()
}

pub async fn scan_local_earbuds(adapter: &Adapter) -> Option<EarbudsInfo> {
    let saved = earbuds_store::load()?;
    Some(resolve_saved(adapter, &saved).await)
}

async fn resolve_saved(adapter: &Adapter, saved: &earbuds_store::SavedEarbuds) -> EarbudsInfo {
    let addr: Address = match saved.address.parse() {
        Ok(a) => a,
        Err(_) => return offline_card(saved),
    };
    let device = match adapter.device(addr) {
        Ok(d) => d,
        Err(_) => return offline_card(saved),
    };
    let connected = device.is_connected().await.unwrap_or(false);
    let fresh = device.battery_percentage().await.ok().flatten();
    let battery = battery_with_fallback(&saved.address, fresh);
    let name = device.name().await.ok().flatten().unwrap_or_else(|| saved.name.clone());
    EarbudsInfo { name, address: saved.address.clone(), battery, connected }
}

fn offline_card(saved: &earbuds_store::SavedEarbuds) -> EarbudsInfo {
    EarbudsInfo {
        name: saved.name.clone(),
        address: saved.address.clone(),
        battery: battery_with_fallback(&saved.address, None),
        connected: false,
    }
}

pub async fn detect_connected_earbud(adapter: &Adapter) -> Option<earbuds_store::SavedEarbuds> {
    list_known_devices(adapter)
        .await
        .into_iter()
        .find(|d| d.is_audio && d.connected)
        .map(|d| earbuds_store::SavedEarbuds { address: d.address, name: d.name })
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct BluetoothDevice {
    pub address: String,
    pub name: String,
    pub rssi: Option<i16>,
    pub connected: bool,
    pub is_audio: bool,
}

pub async fn list_known_devices(adapter: &Adapter) -> Vec<BluetoothDevice> {
    let audio = audio_uuid_set();
    let addresses = match adapter.device_addresses().await {
        Ok(a) => a,
        Err(_) => return Vec::new(),
    };
    let mut out = Vec::with_capacity(addresses.len());
    for addr in addresses {
        let device = match adapter.device(addr) {
            Ok(d) => d,
            Err(_) => continue,
        };
        let name = device.name().await.ok().flatten().unwrap_or_else(|| addr.to_string());
        let rssi = device.rssi().await.ok().flatten();
        let connected = device.is_connected().await.unwrap_or(false);
        let uuids: HashSet<Uuid> = device.uuids().await.ok().flatten().unwrap_or_default();
        let is_audio = !uuids.is_disjoint(&audio);
        out.push(BluetoothDevice { address: addr.to_string(), name, rssi, connected, is_audio });
    }
    out.sort_by(|a, b| {
        b.is_audio
            .cmp(&a.is_audio)
            .then_with(|| b.connected.cmp(&a.connected))
            .then_with(|| b.rssi.unwrap_or(-127).cmp(&a.rssi.unwrap_or(-127)))
    });
    out
}

pub async fn start_brief_discovery(adapter: &Adapter, duration: std::time::Duration) {
    use futures::StreamExt;
    let _ = adapter
        .set_discovery_filter(DiscoveryFilter {
            transport: DiscoveryTransport::Auto,
            ..Default::default()
        })
        .await;
    if let Ok(stream) = adapter.discover_devices().await {
        let _ = tokio::time::timeout(duration, async move {
            futures::pin_mut!(stream);
            while stream.next().await.is_some() {}
        })
        .await;
    }
    let _ = adapter
        .set_discovery_filter(DiscoveryFilter {
            transport: DiscoveryTransport::Le,
            ..Default::default()
        })
        .await;
}

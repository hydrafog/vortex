use std::time::SystemTime;

use bluer::{
    Adapter, AdapterEvent, Address, Device, DeviceEvent, DeviceProperty, DiscoveryFilter,
    DiscoveryTransport, Result as BluerResult,
};
use futures::stream::{SelectAll, StreamExt};
use futures::{pin_mut, Stream};
use tracing::{debug, info};

use super::{AdvPayload, ADV_PAYLOAD_LEN, VORTEX_SERVICE_UUID};

#[derive(Debug, Clone)]
pub struct VortexCandidate {
    pub address: Address,
    pub rssi: Option<i16>,
    pub local_name: Option<String>,
    pub payload: AdvPayload,
    pub observed_at: SystemTime,
}

pub async fn run_filtered_scan<F>(adapter: Adapter, mut on_candidate: F) -> BluerResult<()>
where
    F: FnMut(VortexCandidate),
{
    info!(
        adapter = %adapter.name(),
        service = %VORTEX_SERVICE_UUID,
        "starting Vortex BLE scan"
    );

    if let Err(e) = adapter
        .set_discovery_filter(DiscoveryFilter {
            transport: DiscoveryTransport::Le,
            ..Default::default()
        })
        .await
    {
        debug!("LE-only discovery filter not re-applied ({e}); proceeding");
    }

    let adapter_events = adapter.discover_devices().await?;
    pin_mut!(adapter_events);

    let mut device_events: SelectAll<DeviceEventStream> = SelectAll::new();

    // NOTE: BlueZ only emits DeviceAdded for newly seen devices. A phone that
    if let Ok(known) = adapter.device_addresses().await {
        for address in known {
            try_emit(&adapter, address, &mut on_candidate).await;
            if let Ok(device) = adapter.device(address) {
                if let Ok(stream) = device.events().await {
                    device_events.push(tag_with_address(address, stream));
                }
            }
        }
    }

    loop {
        tokio::select! {
            evt = adapter_events.next() => {
                let Some(evt) = evt else { break };
                if let AdapterEvent::DeviceAdded(address) = evt {
                    try_emit(&adapter, address, &mut on_candidate).await;
                    if let Ok(device) = adapter.device(address) {
                        if let Ok(stream) = device.events().await {
                            device_events.push(tag_with_address(address, stream));
                        } else {
                            debug!(%address, "device.events() failed; only initial parse");
                        }
                    }
                }
            }
            Some((address, DeviceEvent::PropertyChanged(prop))) = device_events.next() => {
                if matches!(prop, DeviceProperty::ServiceData(_) | DeviceProperty::Name(_)) {
                    try_emit(&adapter, address, &mut on_candidate).await;
                }
            }
            else => break,
        }
    }

    Ok(())
}

type DeviceEventStream = std::pin::Pin<Box<dyn Stream<Item = (Address, DeviceEvent)> + Send>>;

fn tag_with_address(
    address: Address,
    stream: impl Stream<Item = DeviceEvent> + Send + 'static,
) -> DeviceEventStream {
    Box::pin(stream.map(move |e| (address, e)))
}

async fn try_emit<F>(adapter: &Adapter, address: Address, on_candidate: &mut F)
where
    F: FnMut(VortexCandidate),
{
    let device: Device = match adapter.device(address) {
        Ok(d) => d,
        Err(err) => {
            debug!(?err, %address, "could not open device handle");
            return;
        }
    };

    let service_data = match device.service_data().await {
        Ok(Some(sd)) => sd,
        Ok(None) => return,
        Err(err) => {
            debug!(?err, %address, "service_data lookup failed");
            return;
        }
    };

    let Some(bytes) = service_data.get(&VORTEX_SERVICE_UUID) else {
        return;
    };
    if bytes.len() != ADV_PAYLOAD_LEN {
        debug!(%address, len = bytes.len(), "service data has wrong length");
        return;
    }

    match AdvPayload::decode(bytes) {
        Ok(payload) => {
            let rssi = device.rssi().await.ok().flatten();
            let local_name = device.name().await.ok().flatten();
            on_candidate(VortexCandidate {
                address,
                rssi,
                local_name,
                payload,
                observed_at: SystemTime::now(),
            });
        }
        Err(err) => debug!(?err, %address, "Vortex payload decode failed; dropping"),
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn smoke() {}

    #[allow(dead_code)]
    fn is_send<T: Send>() {}

    #[test]
    fn vortex_candidate_is_send() {
        is_send::<super::VortexCandidate>();
    }
}

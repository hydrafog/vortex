use std::time::Duration;

use bluer::gatt::remote::{Characteristic, CharacteristicWriteRequest, Service};
use bluer::gatt::WriteOp;
use bluer::{Adapter, Address, Device, Result as BluerResult};
use futures::future::try_join_all;
use futures::{pin_mut, StreamExt};
use tokio::time::timeout;
use tracing::{debug, info};

use super::frame::{Frame, FrameDecodeError};
use super::{
    AdvDecodeError, AUDIO_SIGNAL_UUID, CAPABILITY_UUID, PAIRING_CONTROL_UUID,
    RECONNECT_CONTROL_UUID, VORTEX_SERVICE_UUID,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CapabilityResponse {
    pub version: u8,
    pub capability_bits: u16,
}

#[derive(Debug)]
pub enum ClientError {
    Bluer(bluer::Error),
    Timeout(&'static str),
    NoVortexService,
    NoCharacteristic(&'static str),
    BadCapabilityResponse { len: usize },
    UnsupportedVersion(u8),
    InvalidPayload(AdvDecodeError),
    FrameDecode(FrameDecodeError),
    ClassicBearerOnly,
}

impl std::fmt::Display for ClientError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Bluer(e) => write!(f, "bluer: {e}"),
            Self::Timeout(what) => write!(f, "timeout: {what}"),
            Self::NoVortexService => write!(f, "Vortex Service UUID not found on peer"),
            Self::NoCharacteristic(name) => write!(f, "{name} characteristic not found"),
            Self::BadCapabilityResponse { len } => {
                write!(f, "Capability response wrong length: {len} (expected 3)")
            }
            Self::UnsupportedVersion(v) => write!(f, "unsupported V1 version byte: {v:#04x}"),
            Self::InvalidPayload(e) => write!(f, "invalid advertisement payload: {e:?}"),
            Self::FrameDecode(e) => write!(f, "frame decode: {e}"),
            Self::ClassicBearerOnly => write!(
                f,
                "BlueZ kept the classic (BR/EDR) bearer, so no GATT service is reachable. \
                 This phone is also paired to this laptop as a Bluetooth *audio* device, and \
                 BlueZ always prefers the bonded bearer. Unpair it as an audio device \
                 (`bluetoothctl remove <addr>`), then pair Vortex."
            ),
        }
    }
}

impl std::error::Error for ClientError {}

impl From<bluer::Error> for ClientError {
    fn from(e: bluer::Error) -> Self {
        Self::Bluer(e)
    }
}

pub struct VortexClient {
    pub address: Address,
    pub service: Service,
    pub capability: Characteristic,
    pub pairing_control: Characteristic,
    pub reconnect_control: Characteristic,
    pub audio_signal: Option<Characteristic>,
}

impl VortexClient {
    pub async fn connect(adapter: &Adapter, address: Address) -> Result<Self, ClientError> {
        let device = adapter.device(address)?;
        let t0 = std::time::Instant::now();

        let mut connect_err: Option<ClientError> = None;

        if gatt_link_state(&device).await == GattLink::Absent {
            info!(%address, "GATT connect");
            connect_err = connect_round(&device).await;
            settle_link(&device, Duration::from_secs(3)).await;
        }

        if gatt_link_state(&device).await == GattLink::ClassicOnly {
            info!(%address, "classic-only link; asking BlueZ to add the LE bearer");
            connect_err = connect_round(&device).await;
            settle_link(&device, Duration::from_secs(3)).await;
        }

        if let Some(e) = connect_err {
            if gatt_link_state(&device).await == GattLink::Absent {
                return Err(e);
            }
        }
        let connect_ms = t0.elapsed().as_millis();

        let t1 = std::time::Instant::now();
        let discovery = timeout(Duration::from_secs(15), async {
            loop {
                if device.is_services_resolved().await.unwrap_or(false) {
                    match device.services().await {
                        Ok(svcs) => match find_vortex_service(svcs).await {
                            Ok(Some(s)) => return Ok::<Service, bluer::Error>(s),
                            Ok(None) => {}
                            Err(e) => debug!(%address, "service UUID read not ready: {e}"),
                        },
                        Err(e) => debug!(%address, "services() not ready: {e}"),
                    }
                } else {
                    debug!(%address, "waiting for services to resolve");
                }
                tokio::time::sleep(Duration::from_millis(250)).await;
            }
        })
        .await;
        let service: Service = match discovery {
            Ok(res) => res.map_err(ClientError::Bluer)?,
            Err(_) => {
                let link = gatt_link_state(&device).await;
                let is_conn = device.is_connected().await.unwrap_or(false);
                return Err(if link == GattLink::ClassicOnly || is_conn {
                    ClientError::ClassicBearerOnly
                } else {
                    ClientError::Timeout("service discovery")
                });
            }
        };
        info!(
            %address,
            connect_ms,
            discovery_ms = t1.elapsed().as_millis(),
            "GATT connect+discovery timing"
        );
        let chars = service.characteristics().await?;

        let capability = find_char(&chars, CAPABILITY_UUID, "Capability").await?;
        let pairing_control = find_char(&chars, PAIRING_CONTROL_UUID, "PairingControl").await?;
        let reconnect_control =
            find_char(&chars, RECONNECT_CONTROL_UUID, "ReconnectControl").await?;
        let audio_signal = find_char_opt(&chars, AUDIO_SIGNAL_UUID).await;

        Ok(Self { address, service, capability, pairing_control, reconnect_control, audio_signal })
    }

    pub async fn read_capability(&self) -> Result<CapabilityResponse, ClientError> {
        let bytes = self.capability.read().await?;
        debug!(len = bytes.len(), "capability bytes");
        if bytes.len() < 3 {
            return Err(ClientError::BadCapabilityResponse { len: bytes.len() });
        }
        let version = bytes[0];
        if version != 0x01 {
            return Err(ClientError::UnsupportedVersion(version));
        }
        let capability_bits = u16::from_be_bytes([bytes[1], bytes[2]]);
        Ok(CapabilityResponse { version, capability_bits })
    }

    pub async fn write_pairing_control(&self, frame: &Frame) -> Result<(), ClientError> {
        let bytes = frame.encode();
        let req = CharacteristicWriteRequest {
            offset: 0,
            op_type: WriteOp::Command,
            prepare_authorize: false,
            ..Default::default()
        };
        self.pairing_control.write_ext(&bytes, &req).await?;
        Ok(())
    }

    pub async fn write_reconnect_control(&self, frame: &Frame) -> Result<(), ClientError> {
        let bytes = frame.encode();
        let req = CharacteristicWriteRequest {
            offset: 0,
            op_type: WriteOp::Command,
            prepare_authorize: false,
            ..Default::default()
        };
        self.reconnect_control.write_ext(&bytes, &req).await?;
        Ok(())
    }

    pub async fn echo_round_trip(
        &self,
        payload: Vec<u8>,
        wait: Duration,
    ) -> Result<Frame, ClientError> {
        let notifies = self.pairing_control.notify().await?;
        pin_mut!(notifies);
        let request = Frame::echo_request(payload);
        self.write_pairing_control(&request).await?;

        let bytes = timeout(wait, notifies.next())
            .await
            .map_err(|_| ClientError::Timeout("echo notify"))?
            .ok_or(ClientError::Timeout("notify stream closed"))?;
        let response = Frame::decode(&bytes).map_err(ClientError::FrameDecode)?;
        Ok(response)
    }

    pub async fn disconnect(self) -> BluerResult<()> {
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum GattLink {
    Up,
    ClassicOnly,
    Absent,
}

async fn gatt_link_state(device: &Device) -> GattLink {
    if !device.is_connected().await.unwrap_or(false) {
        return GattLink::Absent;
    }
    if device.is_services_resolved().await.unwrap_or(false) {
        if let Ok(svcs) = device.services().await {
            if !svcs.is_empty() {
                return GattLink::Up;
            }
        }
        return GattLink::ClassicOnly;
    }
    if device.is_paired().await.unwrap_or(false) {
        return GattLink::ClassicOnly;
    }
    GattLink::Absent
}

async fn connect_round(device: &Device) -> Option<ClientError> {
    match timeout(Duration::from_secs(15), device.connect()).await {
        Ok(Ok(())) => None,
        Ok(Err(e)) => Some(ClientError::Bluer(e)),
        Err(_) => Some(ClientError::Timeout("connect")),
    }
}

async fn settle_link(device: &Device, budget: Duration) {
    let deadline = tokio::time::Instant::now() + budget;
    while tokio::time::Instant::now() < deadline {
        if device.is_connected().await.unwrap_or(false)
            && device.is_services_resolved().await.unwrap_or(false)
        {
            return;
        }
        tokio::time::sleep(Duration::from_millis(150)).await;
    }
}

async fn find_char(
    chars: &[Characteristic],
    uuid: uuid::Uuid,
    name: &'static str,
) -> Result<Characteristic, ClientError> {
    for c in chars {
        if c.uuid().await? == uuid {
            return Ok(c.clone());
        }
    }
    Err(ClientError::NoCharacteristic(name))
}

async fn find_char_opt(chars: &[Characteristic], uuid: uuid::Uuid) -> Option<Characteristic> {
    for c in chars {
        if c.uuid().await.ok()? == uuid {
            return Some(c.clone());
        }
    }
    None
}

async fn find_vortex_service(services: Vec<Service>) -> Result<Option<Service>, bluer::Error> {
    if services.is_empty() {
        return Ok(None);
    }
    let uuids = try_join_all(services.iter().map(|s| s.uuid())).await?;
    Ok(services.into_iter().zip(uuids).find(|(_, u)| *u == VORTEX_SERVICE_UUID).map(|(s, _)| s))
}

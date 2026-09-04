use std::time::Duration;

use serde::{Deserialize, Serialize};
use snow::TransportState;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;
use tracing::warn;

use crate::core::ble::frame::{ty, Frame, FrameDecodeError, FRAME_HEADER_LEN, MAX_FRAME_PAYLOAD};

pub const APPSTATE_SCHEMA_V: u8 = 1;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum DeviceClass {
    Unknown,
    Laptop,
    Phone,
    Tablet,
    Earbuds,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EarbudsInfo {
    pub name: String,
    #[serde(default)]
    pub address: String,
    #[serde(default, deserialize_with = "deserialize_battery_pct")]
    pub battery: Option<u8>,
    pub connected: bool,
}

fn deserialize_battery_pct<'de, D>(deserializer: D) -> Result<Option<u8>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    use serde::Deserialize;
    let raw = Option::<u8>::deserialize(deserializer)?;
    Ok(raw.filter(|&b| b <= 100))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppState {
    #[serde(default = "default_v")]
    pub v: u8,
    #[serde(default, deserialize_with = "deserialize_battery_pct")]
    pub battery: Option<u8>,
    pub class: DeviceClass,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub locale: Option<String>,
    #[serde(default)]
    pub locale_changed_at: u64,
    #[serde(default)]
    pub theme: Option<String>,
    #[serde(default)]
    pub theme_changed_at: u64,
    #[serde(default)]
    pub earbuds: Option<EarbudsInfo>,
    #[serde(default)]
    pub revoked: bool,
    #[serde(default)]
    pub audio_claim_request: bool,
    #[serde(default)]
    pub call_phase: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub call: Option<crate::core::call_event::CallEvent>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub call_control: Option<crate::core::call_event::CallControl>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub notif_invoke: Option<crate::core::notif_mirror::NotificationMirror>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub handoff: Option<crate::core::handoff::HandoffEvent>,
    #[serde(default)]
    pub media_playing: bool,
    #[serde(default)]
    pub media_play_age_ms: u64,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub media_title: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub media_artist: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub media_app: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub media_art_url: String,
    #[serde(default)]
    pub media_np_playing: bool,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub media_control: String,
    #[serde(default)]
    pub media_control_seq: u64,
    #[serde(default = "default_true")]
    pub smart_switch_enabled: bool,
    #[serde(default)]
    pub smart_switch_changed_at: u64,
    #[serde(default)]
    pub charging: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub locked: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub unlocked: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lock_command: Option<String>,
    #[serde(default)]
    pub lock_command_seq: u64,
    #[serde(default)]
    pub laptop_mirror_req: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub laptop_mirror_extend: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub laptop_cast: Option<LaptopCast>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub laptop_cast_error: Option<String>,
    #[serde(default)]
    pub camera_req: bool,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub camera_facing: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub camera_offer: Option<CameraOffer>,
    #[serde(default)]
    pub ring_seq: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub wifi_ip: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub display_hz: Option<u32>,
    #[serde(default)]
    pub ts: u64,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, PartialEq, Eq)]
pub struct LaptopCast {
    pub ip: String,
    pub port: u16,
    pub key: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, PartialEq, Eq)]
pub struct CameraOffer {
    pub port: u16,
    pub key: String,
    #[serde(default)]
    pub rot: u16,
}

fn default_v() -> u8 {
    APPSTATE_SCHEMA_V
}

fn default_true() -> bool {
    true
}

impl AppState {
    pub fn now_laptop() -> Self {
        let battery = crate::core::status::read_local_battery().0;
        let charging = crate::core::status::read_local_charging();
        let name = std::fs::read_to_string("/proc/sys/kernel/hostname")
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty());
        let ts = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        AppState {
            v: APPSTATE_SCHEMA_V,
            battery,
            class: DeviceClass::Laptop,
            name,
            locale: None,
            locale_changed_at: 0,
            theme: None,
            theme_changed_at: 0,
            earbuds: None,
            revoked: false,
            audio_claim_request: false,
            call_phase: None,
            call: None,
            call_control: None,
            notif_invoke: None,
            handoff: None,
            media_playing: false,
            media_play_age_ms: 0,
            media_title: String::new(),
            media_artist: String::new(),
            media_app: String::new(),
            media_art_url: String::new(),
            media_np_playing: false,
            media_control: String::new(),
            media_control_seq: 0,
            smart_switch_enabled: true,
            smart_switch_changed_at: 0,
            charging,
            locked: None,
            unlocked: None,
            lock_command: None,
            lock_command_seq: 0,
            laptop_mirror_req: false,
            laptop_mirror_extend: None,
            laptop_cast: None,
            laptop_cast_error: None,
            camera_req: false,
            camera_facing: String::new(),
            camera_offer: None,
            ring_seq: 0,
            wifi_ip: None,
            display_hz: None,
            ts,
        }
    }
}

pub fn system_locale() -> String {
    let raw = std::env::var("LC_ALL")
        .or_else(|_| std::env::var("LANG"))
        .unwrap_or_default()
        .to_ascii_lowercase();
    if raw.starts_with("uz") {
        "uz".into()
    } else if raw.starts_with("ru") {
        "ru".into()
    } else {
        "en".into()
    }
}

#[derive(Debug)]
pub enum AppStateError {
    Snow(snow::Error),
    Io(std::io::Error),
    Timeout(&'static str),
    Frame(FrameDecodeError),
    UnexpectedFrame { ty: u8, sub: u8 },
    Json(serde_json::Error),
    UnsupportedVersion(u8),
    OversizeFrame(usize),
}

impl std::fmt::Display for AppStateError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Snow(e) => write!(f, "noise transport: {e}"),
            Self::Io(e) => write!(f, "io: {e}"),
            Self::Timeout(what) => write!(f, "timeout: {what}"),
            Self::Frame(e) => write!(f, "frame decode: {e}"),
            Self::UnexpectedFrame { ty, sub } => {
                write!(f, "unexpected frame type=0x{ty:02x} sub=0x{sub:02x}")
            }
            Self::Json(e) => write!(f, "app-state json: {e}"),
            Self::UnsupportedVersion(v) => write!(f, "unsupported app-state schema v={v}"),
            Self::OversizeFrame(n) => write!(f, "app-state frame too large: {n}"),
        }
    }
}

impl std::error::Error for AppStateError {}

impl From<snow::Error> for AppStateError {
    fn from(e: snow::Error) -> Self {
        Self::Snow(e)
    }
}
impl From<std::io::Error> for AppStateError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}
impl From<serde_json::Error> for AppStateError {
    fn from(e: serde_json::Error) -> Self {
        Self::Json(e)
    }
}

pub async fn exchange_app_state(
    stream: &mut TcpStream,
    transport: &mut TransportState,
    local: &AppState,
    wait: Duration,
) -> Result<AppState, AppStateError> {
    let json = serde_json::to_vec(local)?;
    let mut ct = vec![0u8; json.len() + 16];
    let ct_len = transport.write_message(&json, &mut ct)?;
    ct.truncate(ct_len);
    let frame = Frame::new(ty::TRANSPORT_APP_DATA, 0x01, ct);
    let frame_bytes = frame.encode();
    stream.write_all(&frame_bytes).await?;
    stream.flush().await?;

    let peer_frame = timeout(wait, read_frame(stream))
        .await
        .map_err(|_| AppStateError::Timeout("peer app-state"))??;
    if peer_frame.ty != ty::TRANSPORT_APP_DATA {
        return Err(AppStateError::UnexpectedFrame { ty: peer_frame.ty, sub: peer_frame.sub });
    }
    let mut pt = vec![0u8; peer_frame.payload.len()];
    let pt_len = transport.read_message(&peer_frame.payload, &mut pt)?;
    pt.truncate(pt_len);
    let state: AppState = serde_json::from_slice(&pt)?;
    if state.v > APPSTATE_SCHEMA_V {
        warn!("peer sent app-state v={} (we know v={})", state.v, APPSTATE_SCHEMA_V);
    }
    Ok(state)
}

async fn read_frame(stream: &mut TcpStream) -> Result<Frame, AppStateError> {
    let mut header = [0u8; FRAME_HEADER_LEN];
    stream.read_exact(&mut header).await?;
    let length = u16::from_be_bytes([header[2], header[3]]) as usize;
    if length > MAX_FRAME_PAYLOAD {
        return Err(AppStateError::OversizeFrame(length));
    }
    let mut full = vec![0u8; FRAME_HEADER_LEN + length];
    full[..FRAME_HEADER_LEN].copy_from_slice(&header);
    if length > 0 {
        stream.read_exact(&mut full[FRAME_HEADER_LEN..]).await?;
    }
    Frame::decode(&full).map_err(AppStateError::Frame)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_json() {
        let a = AppState {
            v: 1,
            battery: Some(75),
            class: DeviceClass::Laptop,
            name: Some("zoyirjon-Blade".into()),
            locale: Some("uz".into()),
            locale_changed_at: 1_700_000_100,
            theme: Some("dark".into()),
            theme_changed_at: 0,
            earbuds: Some(EarbudsInfo {
                name: "AirPods Pro".into(),
                address: "AA:BB:CC:DD:EE:FF".into(),
                battery: Some(60),
                connected: true,
            }),
            revoked: false,
            audio_claim_request: false,
            call_phase: None,
            call: None,
            call_control: None,
            notif_invoke: None,
            handoff: None,
            media_playing: false,
            media_play_age_ms: 0,
            media_title: "Bohemian Rhapsody".into(),
            media_artist: "Queen".into(),
            media_app: "Spotify".into(),
            media_art_url: "https://i.scdn.co/image/ab67616d0000b273".into(),
            media_np_playing: true,
            media_control: "media_play_pause".into(),
            media_control_seq: 3,
            smart_switch_enabled: true,
            smart_switch_changed_at: 0,
            charging: false,
            locked: Some(false),
            unlocked: Some(true),
            lock_command: Some("lock".into()),
            lock_command_seq: 7,
            laptop_mirror_req: false,
            laptop_mirror_extend: None,
            laptop_cast: None,
            laptop_cast_error: None,
            camera_req: false,
            camera_facing: String::new(),
            camera_offer: None,
            ring_seq: 0,
            wifi_ip: Some("192.168.1.42".into()),
            display_hz: Some(120),
            ts: 1_700_000_000,
        };
        let json = serde_json::to_vec(&a).unwrap();
        let b: AppState = serde_json::from_slice(&json).unwrap();
        assert_eq!(a.wifi_ip, b.wifi_ip);
        assert_eq!(a.display_hz, b.display_hz);
        assert_eq!(a.battery, b.battery);
        assert_eq!(a.class, b.class);
        assert_eq!(a.name, b.name);
        assert_eq!(a.locale, b.locale);
        assert_eq!(a.locale_changed_at, b.locale_changed_at);
        assert_eq!(a.theme, b.theme);
        assert_eq!(a.earbuds.unwrap().name, b.earbuds.unwrap().name);
        assert_eq!(a.locked, b.locked);
        assert_eq!(a.lock_command, b.lock_command);
        assert_eq!(a.lock_command_seq, b.lock_command_seq);
        assert_eq!(a.media_title, b.media_title);
        assert_eq!(a.media_artist, b.media_artist);
        assert_eq!(a.media_app, b.media_app);
        assert_eq!(a.media_art_url, b.media_art_url);
        assert_eq!(a.media_control, b.media_control);
        assert_eq!(a.media_control_seq, b.media_control_seq);
    }

    #[test]
    fn unknown_field_ignored() {
        let s = r#"{"v":1,"class":"phone","new_field":42}"#;
        let a: AppState = serde_json::from_str(s).unwrap();
        assert_eq!(a.class, DeviceClass::Phone);
        assert!(a.battery.is_none());
    }

    #[test]
    fn out_of_range_battery_drops_to_none() {
        let s = r#"{"v":1,"class":"phone","battery":150}"#;
        let a: AppState = serde_json::from_str(s).unwrap();
        assert!(a.battery.is_none(), "battery > 100 must deserialize to None");

        let s2 = r#"{"v":1,"class":"phone","battery":100}"#;
        let a2: AppState = serde_json::from_str(s2).unwrap();
        assert_eq!(a2.battery, Some(100), "battery == 100 must pass through");

        let s3 = r#"{"v":1,"class":"phone","earbuds":{"name":"x","battery":200,"connected":true}}"#;
        let a3: AppState = serde_json::from_str(s3).unwrap();
        assert!(a3.earbuds.unwrap().battery.is_none(), "earbuds battery > 100 must be None");
    }
}

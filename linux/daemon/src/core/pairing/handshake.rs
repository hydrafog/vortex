use std::time::Duration;

use futures::{pin_mut, StreamExt};
use snow::{params::NoiseParams, Builder, HandshakeState};
use tokio::time::timeout;
use tracing::{debug, info};

use crate::core::ble::client::{ClientError, VortexClient};
use crate::core::ble::frame::{ty, Frame, FrameDecodeError};
use crate::core::crypto::derive::derive_prs;
use crate::core::crypto::noise::{NOISE_XX, PROLOGUE_XX};
use crate::core::crypto::sas::derive_sas;
use crate::core::crypto::x25519::X25519SecBytes;

#[derive(Debug, Clone)]
pub struct XxOutcome {
    pub transcript_hash: Vec<u8>,
    pub peer_static_pub: [u8; 32],
    pub sas_string: String,
    pub sas_value: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LocalDecision {
    Approve,
    Reject,
}

#[derive(Debug, Clone)]
pub struct PairingOutcome {
    pub xx: XxOutcome,
    pub prs: [u8; 32],
    pub peer_name: Option<String>,
}

#[derive(Debug)]
pub enum HandshakeError {
    Snow(snow::Error),
    Client(ClientError),
    UnexpectedFrame { ty: u8, sub: u8 },
    FrameDecode(FrameDecodeError),
    Timeout(&'static str),
    NoPeerStatic,
    LocalRejected,
    PeerRejected,
}

impl std::fmt::Display for HandshakeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Snow(e) => write!(f, "noise: {e}"),
            Self::Client(e) => write!(f, "ble client: {e}"),
            Self::UnexpectedFrame { ty, sub } => {
                write!(f, "unexpected frame type=0x{ty:02x} sub=0x{sub:02x}")
            }
            Self::FrameDecode(e) => write!(f, "frame decode: {e}"),
            Self::Timeout(what) => write!(f, "timeout: {what}"),
            Self::NoPeerStatic => write!(f, "noise XX did not yield peer static public key"),
            Self::LocalRejected => write!(f, "local user rejected the pairing"),
            Self::PeerRejected => write!(f, "peer rejected the pairing"),
        }
    }
}

impl std::error::Error for HandshakeError {}

impl From<snow::Error> for HandshakeError {
    fn from(e: snow::Error) -> Self {
        Self::Snow(e)
    }
}

impl From<ClientError> for HandshakeError {
    fn from(e: ClientError) -> Self {
        Self::Client(e)
    }
}

fn build_initiator(static_priv: &X25519SecBytes) -> Result<HandshakeState, snow::Error> {
    let params: NoiseParams = NOISE_XX.parse()?;
    Builder::new(params).local_private_key(static_priv)?.prologue(PROLOGUE_XX)?.build_initiator()
}

pub async fn run_xx_initiator(
    client: &VortexClient,
    static_priv: &X25519SecBytes,
    wait_per_step: Duration,
) -> Result<XxOutcome, HandshakeError> {
    let mut handshake = build_initiator(static_priv)?;
    let mut buffer = vec![0u8; 1024];
    let mut payload_scratch = vec![0u8; 1024];

    let notifies = client.pairing_control.notify().await.map_err(ClientError::from)?;
    pin_mut!(notifies);

    let n = handshake.write_message(&[], &mut buffer)?;
    debug!(bytes = n, "noise xx msg1");
    let frame = Frame::new(ty::PAIRING_HANDSHAKE, 0x01, buffer[..n].to_vec());
    client.write_pairing_control(&frame).await?;
    info!("→ msg1 sent ({} bytes)", n);

    let raw = timeout(wait_per_step, notifies.next())
        .await
        .map_err(|_| HandshakeError::Timeout("msg2 notify"))?
        .ok_or(HandshakeError::Timeout("notify stream closed"))?;
    let msg2 = Frame::decode(&raw).map_err(HandshakeError::FrameDecode)?;
    if msg2.ty != ty::PAIRING_HANDSHAKE || msg2.sub != 0x02 {
        return Err(HandshakeError::UnexpectedFrame { ty: msg2.ty, sub: msg2.sub });
    }
    handshake.read_message(&msg2.payload, &mut payload_scratch)?;
    info!("← msg2 received ({} bytes)", msg2.payload.len());

    let n = handshake.write_message(&[], &mut buffer)?;
    debug!(bytes = n, "noise xx msg3");
    let frame = Frame::new(ty::PAIRING_HANDSHAKE, 0x03, buffer[..n].to_vec());
    client.write_pairing_control(&frame).await?;
    info!("→ msg3 sent ({} bytes)", n);

    let transcript_hash = handshake.get_handshake_hash().to_vec();
    let peer_static_pub_slice =
        handshake.get_remote_static().ok_or(HandshakeError::NoPeerStatic)?;
    let mut peer_static_pub = [0u8; 32];
    peer_static_pub.copy_from_slice(peer_static_pub_slice);

    let (sas_value, sas_string) = derive_sas(&transcript_hash);

    Ok(XxOutcome { transcript_hash, peer_static_pub, sas_string, sas_value })
}

pub async fn run_pairing_initiator<F, Fut>(
    client: &VortexClient,
    static_priv: &X25519SecBytes,
    wait_per_step: Duration,
    decide: F,
    local_name: Option<&str>,
) -> Result<PairingOutcome, HandshakeError>
where
    F: FnOnce(&str) -> Fut,
    Fut: std::future::Future<Output = LocalDecision>,
{
    let notifies = client.pairing_control.notify().await.map_err(ClientError::from)?;
    pin_mut!(notifies);

    let mut handshake = build_initiator(static_priv)?;
    let mut buffer = vec![0u8; 1024];
    let mut payload_scratch = vec![0u8; 1024];

    let n = handshake.write_message(&[], &mut buffer)?;
    let frame = Frame::new(ty::PAIRING_HANDSHAKE, 0x01, buffer[..n].to_vec());
    client.write_pairing_control(&frame).await?;
    info!("→ msg1 sent ({} bytes)", n);

    let raw = timeout(wait_per_step, notifies.next())
        .await
        .map_err(|_| HandshakeError::Timeout("msg2 notify"))?
        .ok_or(HandshakeError::Timeout("notify stream closed"))?;
    let msg2 = Frame::decode(&raw).map_err(HandshakeError::FrameDecode)?;
    if msg2.ty != ty::PAIRING_HANDSHAKE || msg2.sub != 0x02 {
        return Err(HandshakeError::UnexpectedFrame { ty: msg2.ty, sub: msg2.sub });
    }
    handshake.read_message(&msg2.payload, &mut payload_scratch)?;
    info!("← msg2 received ({} bytes)", msg2.payload.len());

    let n = handshake.write_message(&[], &mut buffer)?;
    let frame = Frame::new(ty::PAIRING_HANDSHAKE, 0x03, buffer[..n].to_vec());
    client.write_pairing_control(&frame).await?;
    info!("→ msg3 sent ({} bytes)", n);

    let transcript_hash = handshake.get_handshake_hash().to_vec();
    let peer_static_pub_slice =
        handshake.get_remote_static().ok_or(HandshakeError::NoPeerStatic)?;
    let mut peer_static_pub = [0u8; 32];
    peer_static_pub.copy_from_slice(peer_static_pub_slice);

    let mut transport = handshake.into_transport_mode()?;

    let (sas_value, sas_string) = derive_sas(&transcript_hash);
    let xx = XxOutcome {
        transcript_hash: transcript_hash.clone(),
        peer_static_pub,
        sas_string: sas_string.clone(),
        sas_value,
    };

    let local = decide(&sas_string).await;
    let approval_sub = if local == LocalDecision::Approve { 0x01 } else { 0x02 };
    let approval_plain = if local == LocalDecision::Approve {
        sanitize_peer_name(local_name.unwrap_or("")).into_bytes()
    } else {
        Vec::new()
    };
    let mut approval_ct = vec![0u8; approval_plain.len() + 16];
    let approval_ct_len = transport.write_message(&approval_plain, &mut approval_ct)?;
    approval_ct.truncate(approval_ct_len);
    client
        .write_pairing_control(&Frame::new(ty::PAIRING_APPROVAL, approval_sub, approval_ct))
        .await?;
    info!(
        "→ approval sent ({} ct bytes): {}",
        approval_ct_len,
        if local == LocalDecision::Approve { "approve" } else { "reject" },
    );

    if local == LocalDecision::Reject {
        return Err(HandshakeError::LocalRejected);
    }

    let raw = timeout(wait_per_step, notifies.next())
        .await
        .map_err(|_| HandshakeError::Timeout("peer approval"))?
        .ok_or(HandshakeError::Timeout("notify stream closed"))?;
    let peer_decision = Frame::decode(&raw).map_err(HandshakeError::FrameDecode)?;
    if peer_decision.ty != ty::PAIRING_APPROVAL {
        return Err(HandshakeError::UnexpectedFrame {
            ty: peer_decision.ty,
            sub: peer_decision.sub,
        });
    }
    let mut peer_pt = vec![0u8; peer_decision.payload.len()];
    let peer_pt_len = transport.read_message(&peer_decision.payload, &mut peer_pt)?;
    peer_pt.truncate(peer_pt_len);
    info!(
        "← peer approval ({} pt bytes): {}",
        peer_pt_len,
        if peer_decision.sub == 0x01 { "approve" } else { "reject" },
    );
    if peer_decision.sub != 0x01 {
        return Err(HandshakeError::PeerRejected);
    }

    let peer_name =
        std::str::from_utf8(&peer_pt).ok().map(sanitize_peer_name).filter(|s| !s.is_empty());

    let prs = derive_prs(&transcript_hash);
    Ok(PairingOutcome { xx, prs, peer_name })
}

const PEER_NAME_MAX_CHARS: usize = 64;

pub(crate) fn sanitize_peer_name(input: &str) -> String {
    let cleaned: String = input
        .chars()
        .filter(|c| {
            if (*c as u32) < 0x20 || (*c as u32) == 0x7F {
                return false;
            }
            if (0x80..=0x9F).contains(&(*c as u32)) {
                return false;
            }
            matches!(
                *c as u32,
                0x202A..=0x202E | 0x2066..=0x2069
            )
            .then(|| false)
            .unwrap_or(true)
        })
        .take(PEER_NAME_MAX_CHARS)
        .collect();
    cleaned.trim().to_string()
}

#[cfg(test)]
mod sanitize_tests {
    use super::sanitize_peer_name;

    #[test]
    fn passes_simple_name() {
        assert_eq!(sanitize_peer_name("zoyirjon-Blade"), "zoyirjon-Blade");
    }

    #[test]
    fn trims_whitespace() {
        assert_eq!(sanitize_peer_name("  My Phone  "), "My Phone");
    }

    #[test]
    fn rejects_control_chars() {
        let name = "evil\x00\x07\x1bhost";
        assert_eq!(sanitize_peer_name(name), "evilhost");
    }

    #[test]
    fn rejects_bidi_override() {
        let name = "safe\u{202E}name";
        assert_eq!(sanitize_peer_name(name), "safename");
    }

    #[test]
    fn caps_length() {
        let name = "a".repeat(300);
        assert_eq!(sanitize_peer_name(&name).chars().count(), 64);
    }

    #[test]
    fn returns_empty_when_fully_filtered() {
        assert_eq!(sanitize_peer_name("\x00\x01\x02"), "");
    }
}

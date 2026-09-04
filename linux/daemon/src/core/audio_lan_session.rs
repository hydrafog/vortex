use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use rand::RngCore;
use snow::TransportState;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::time::timeout;
use tracing::{debug, info, warn};

use crate::core::audio_op::AudioOpFrame;
use crate::core::audio_orchestrator::{SwitchOrchestrator, SwitchState};
use crate::core::ble::frame::{ty, Frame, FRAME_HEADER_LEN, MAX_FRAME_PAYLOAD};
use crate::core::crypto::x25519::X25519SecBytes;

pub type SessionWriter = Arc<
    dyn Fn(AudioOpFrame) -> futures::future::BoxFuture<'static, Result<(), String>> + Send + Sync,
>;

pub type SessionWriterMap = Arc<Mutex<HashMap<[u8; 32], SessionWriter>>>;

pub fn new_session_writer_map() -> SessionWriterMap {
    Arc::new(Mutex::new(HashMap::new()))
}

const IK_STEP_TIMEOUT: Duration = Duration::from_secs(8);

const FLOW_HARD_CAP: Duration = Duration::from_secs(15);

pub async fn start_initiator_session(
    addr: SocketAddr,
    static_priv: &X25519SecBytes,
    peer_static_pub: &[u8; 32],
    prs: &[u8; 32],
    local_counter: u64,
    mac: String,
    orchestrator: Arc<SwitchOrchestrator>,
    session_writers: SessionWriterMap,
) -> Result<(), String> {
    info!(%addr, "audio-op: opening LAN session");
    let mut stream = timeout(IK_STEP_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| "tcp connect timeout".to_string())?
        .map_err(|e| format!("tcp connect: {e}"))?;

    let mut handshake =
        crate::core::lan::tcp_client::build_ik_initiator(static_priv, peer_static_pub, prs)
            .map_err(|e| format!("noise build: {e}"))?;
    let mut buf = vec![0u8; 1024];
    let mut tmp = vec![0u8; 1024];

    let counter_bytes = local_counter.to_be_bytes();
    let n = handshake
        .write_message(&counter_bytes, &mut buf)
        .map_err(|e| format!("noise write msg1: {e}"))?;
    let msg1 = Frame::new(ty::RECONNECT_HANDSHAKE, 0x01, buf[..n].to_vec());
    write_frame(&mut stream, &msg1).await?;

    let msg2 = timeout(IK_STEP_TIMEOUT, read_frame_capped(&mut stream, 128))
        .await
        .map_err(|_| "msg2 timeout".to_string())??;
    if msg2.ty != ty::RECONNECT_HANDSHAKE || msg2.sub != 0x02 {
        return Err(format!("unexpected msg2 ty=0x{:02x} sub=0x{:02x}", msg2.ty, msg2.sub));
    }
    handshake.read_message(&msg2.payload, &mut tmp).map_err(|e| format!("noise read msg2: {e}"))?;

    let observed =
        handshake.get_remote_static().ok_or_else(|| "no remote static after IK".to_string())?;
    if observed != peer_static_pub {
        return Err("peer static mismatch".to_string());
    }

    let transport = Arc::new(Mutex::new(
        handshake.into_transport_mode().map_err(|e| format!("transport mode: {e}"))?,
    ));

    let (mut reader, writer_half) = stream.into_split();
    let writer_half = Arc::new(Mutex::new(writer_half));

    let peer = *peer_static_pub;
    {
        let transport_w = transport.clone();
        let writer_w = writer_half.clone();
        let writer_fn: SessionWriter = Arc::new(move |frame: AudioOpFrame| {
            let transport_w = transport_w.clone();
            let writer_w = writer_w.clone();
            Box::pin(async move {
                let plain = frame.to_json().map_err(|e| format!("frame json: {e}"))?;
                let mut out = vec![0u8; plain.len() + 16];
                let n = {
                    let mut t = transport_w.lock().await;
                    t.write_message(&plain, &mut out).map_err(|e| format!("aead seal: {e}"))?
                };
                let frame = Frame::new(ty::AUDIO_OP, 0x01, out[..n].to_vec());
                let bytes = frame.encode();
                let mut w = writer_w.lock().await;
                w.write_all(&bytes).await.map_err(|e| format!("tcp write: {e}"))?;
                w.flush().await.map_err(|e| format!("tcp flush: {e}"))?;
                Ok(())
            })
        });
        session_writers.lock().await.insert(peer, writer_fn);
    }

    if let Err(e) = orchestrator.request(peer, mac).await {
        session_writers.lock().await.remove(&peer);
        return Err(format!("orchestrator.request: {e}"));
    }

    let mut state_rx = orchestrator.state();
    let deadline = tokio::time::Instant::now() + FLOW_HARD_CAP;
    let result = loop {
        tokio::select! {
            biased;
            _ = tokio::time::sleep_until(deadline) => {
                break Err("flow hard-cap timeout".to_string());
            }
            changed = state_rx.changed() => {
                if changed.is_err() {
                    break Err("state channel closed".to_string());
                }
                let state = state_rx.borrow().clone();
                match state {
                    SwitchState::Idle => break Ok(()),
                    SwitchState::Failed(reason) => break Err(reason),
                    _ => continue,
                }
            }
            frame = read_frame_audio_op(&mut reader, &transport) => {
                match frame {
                    Ok(Some(af)) => {
                        debug!(?af.op, "← audio-op");
                        let orch = orchestrator.clone();
                        let peer_copy = peer;
                        tokio::spawn(async move {
                            let _ = orch.on_incoming(peer_copy, af).await;
                        });
                    }
                    Ok(None) => break Ok(()),
                    Err(e) => break Err(e),
                }
            }
        }
    };

    session_writers.lock().await.remove(&peer);
    drop(writer_half);
    info!(?result, "audio-op: LAN session closed");
    result
}

async fn write_frame(stream: &mut TcpStream, frame: &Frame) -> Result<(), String> {
    let bytes = frame.encode();
    stream.write_all(&bytes).await.map_err(|e| format!("tcp write: {e}"))?;
    stream.flush().await.map_err(|e| format!("tcp flush: {e}"))?;
    Ok(())
}

async fn read_frame_capped(stream: &mut TcpStream, cap: usize) -> Result<Frame, String> {
    let cap = cap.min(MAX_FRAME_PAYLOAD);
    let mut header = [0u8; FRAME_HEADER_LEN];
    stream.read_exact(&mut header).await.map_err(|e| format!("tcp read header: {e}"))?;
    let length = u16::from_be_bytes([header[2], header[3]]) as usize;
    if length > cap {
        return Err(format!("oversize frame {length}"));
    }
    let mut full = vec![0u8; FRAME_HEADER_LEN + length];
    full[..FRAME_HEADER_LEN].copy_from_slice(&header);
    if length > 0 {
        stream
            .read_exact(&mut full[FRAME_HEADER_LEN..])
            .await
            .map_err(|e| format!("tcp read body: {e}"))?;
    }
    Frame::decode(&full).map_err(|e| format!("frame decode: {e}"))
}

async fn read_frame_audio_op(
    reader: &mut tokio::net::tcp::OwnedReadHalf,
    transport: &Arc<Mutex<TransportState>>,
) -> Result<Option<AudioOpFrame>, String> {
    let mut header = [0u8; FRAME_HEADER_LEN];
    match reader.read_exact(&mut header).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(format!("tcp read header: {e}")),
    }
    let length = u16::from_be_bytes([header[2], header[3]]) as usize;
    if length > MAX_FRAME_PAYLOAD {
        return Err(format!("oversize frame {length}"));
    }
    let mut body = vec![0u8; length];
    if length > 0 {
        reader.read_exact(&mut body).await.map_err(|e| format!("tcp read body: {e}"))?;
    }
    let mut wire = Vec::with_capacity(FRAME_HEADER_LEN + length);
    wire.extend_from_slice(&header);
    wire.extend_from_slice(&body);
    let frame = Frame::decode(&wire).map_err(|e| format!("frame decode: {e}"))?;
    if frame.ty != ty::AUDIO_OP {
        warn!("unexpected post-IK frame type 0x{:02x}; ignoring", frame.ty);
        return Ok(None);
    }
    let mut plain = vec![0u8; frame.payload.len()];
    let n = {
        let mut t = transport.lock().await;
        t.read_message(&frame.payload, &mut plain).map_err(|e| format!("aead open: {e}"))?
    };
    AudioOpFrame::from_json(&plain[..n]).map(Some).map_err(|e| format!("audio-op json: {e}"))
}

pub async fn send_one_shot(
    addr: SocketAddr,
    static_priv: &X25519SecBytes,
    peer_static_pub: &[u8; 32],
    prs: &[u8; 32],
    local_counter: u64,
    frame: AudioOpFrame,
) -> Result<(), String> {
    info!(%addr, op = ?frame.op, "audio-op one-shot: opening LAN session");
    let mut stream = timeout(IK_STEP_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| "tcp connect timeout".to_string())?
        .map_err(|e| format!("tcp connect: {e}"))?;

    let mut handshake =
        crate::core::lan::tcp_client::build_ik_initiator(static_priv, peer_static_pub, prs)
            .map_err(|e| format!("noise build: {e}"))?;
    let mut buf = vec![0u8; 1024];
    let mut tmp = vec![0u8; 1024];

    let counter_bytes = local_counter.to_be_bytes();
    let n = handshake
        .write_message(&counter_bytes, &mut buf)
        .map_err(|e| format!("noise write msg1: {e}"))?;
    let msg1 = Frame::new(ty::RECONNECT_HANDSHAKE, 0x01, buf[..n].to_vec());
    write_frame(&mut stream, &msg1).await?;

    let msg2 = timeout(IK_STEP_TIMEOUT, read_frame_capped(&mut stream, 128))
        .await
        .map_err(|_| "msg2 timeout".to_string())??;
    if msg2.ty != ty::RECONNECT_HANDSHAKE || msg2.sub != 0x02 {
        return Err(format!("unexpected msg2 ty=0x{:02x} sub=0x{:02x}", msg2.ty, msg2.sub));
    }
    handshake.read_message(&msg2.payload, &mut tmp).map_err(|e| format!("noise read msg2: {e}"))?;

    let observed =
        handshake.get_remote_static().ok_or_else(|| "no remote static after IK".to_string())?;
    if observed != peer_static_pub {
        return Err("peer static mismatch".to_string());
    }

    let mut transport =
        handshake.into_transport_mode().map_err(|e| format!("transport mode: {e}"))?;

    let plain = frame.to_json().map_err(|e| format!("frame json: {e}"))?;
    let mut out = vec![0u8; plain.len() + 16];
    let n = transport.write_message(&plain, &mut out).map_err(|e| format!("aead seal: {e}"))?;
    let wire = Frame::new(ty::AUDIO_OP, 0x01, out[..n].to_vec());
    write_frame(&mut stream, &wire).await?;
    debug!("audio-op one-shot: frame sent, closing");
    let _ = stream.shutdown().await;
    Ok(())
}

#[allow(dead_code)]
fn _force_link_rng() {
    let mut b = [0u8; 8];
    rand::rngs::OsRng.fill_bytes(&mut b);
}

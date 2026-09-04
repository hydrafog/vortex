use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use snow::TransportState;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Mutex};
use tokio::time::timeout;
use tracing::{debug, info, warn};

use crate::core::ble::frame::{ty as wire_ty, Frame, FRAME_HEADER_LEN, MAX_FRAME_PAYLOAD};
use crate::core::crypto::x25519::X25519SecBytes;

pub mod mty {
    pub const SCREEN_MIRROR_INPUT: u8 = 0x46;
    pub const SCREEN_MIRROR_CONTROL: u8 = 0x47;
}

pub mod ctrl {
    pub const START: u8 = 0x01;
    pub const STOP: u8 = 0x02;
    pub const REQUEST_KEYFRAME: u8 = 0x03;
    pub const PING: u8 = 0x04;
    pub const PONG: u8 = 0x05;
}

const IK_STEP_TIMEOUT: Duration = Duration::from_secs(8);

const KEEPALIVE: Duration = Duration::from_secs(20);
const KEYFRAME_REQ_MIN_GAP: Duration = Duration::from_millis(300);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MirrorStart {
    pub w: u32,
    pub h: u32,
    pub fps: u32,
    pub bitrate: u32,
    pub udp_port: u16,
}

pub struct MirrorHandle {
    pub input_tx: mpsc::Sender<Vec<u8>>,
    pub keyframe_tx: mpsc::Sender<()>,
    pub handshake_hash: Vec<u8>,
    stop_tx: mpsc::Sender<()>,
}

impl MirrorHandle {
    pub async fn stop(&self) {
        let _ = self.stop_tx.send(()).await;
    }
}

pub async fn start_mirror_session(
    addr: SocketAddr,
    static_priv: &X25519SecBytes,
    peer_static_pub: &[u8; 32],
    prs: &[u8; 32],
    local_counter: u64,
    start: MirrorStart,
) -> Result<MirrorHandle, String> {
    info!(%addr, ?start, "mirror: opening LAN session");
    let mut stream = timeout(IK_STEP_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| "tcp connect timeout".to_string())?
        .map_err(|e| format!("tcp connect: {e}"))?;
    stream.set_nodelay(true).ok();

    let mut handshake =
        crate::core::lan::tcp_client::build_ik_initiator(static_priv, peer_static_pub, prs)
            .map_err(|e| format!("noise build: {e}"))?;
    let mut buf = vec![0u8; 1024];
    let mut tmp = vec![0u8; 1024];

    let counter_bytes = local_counter.to_be_bytes();
    let n = handshake
        .write_message(&counter_bytes, &mut buf)
        .map_err(|e| format!("noise write msg1: {e}"))?;
    let msg1 = Frame::new(wire_ty::RECONNECT_HANDSHAKE, 0x01, buf[..n].to_vec());
    write_frame(&mut stream, &msg1).await?;

    let msg2 = timeout(IK_STEP_TIMEOUT, read_frame_capped(&mut stream, 128))
        .await
        .map_err(|_| "msg2 timeout".to_string())??;
    if msg2.ty != wire_ty::RECONNECT_HANDSHAKE || msg2.sub != 0x02 {
        return Err(format!("unexpected msg2 ty=0x{:02x} sub=0x{:02x}", msg2.ty, msg2.sub));
    }
    handshake.read_message(&msg2.payload, &mut tmp).map_err(|e| format!("noise read msg2: {e}"))?;

    let observed =
        handshake.get_remote_static().ok_or_else(|| "no remote static after IK".to_string())?;
    if observed != peer_static_pub {
        return Err("peer static mismatch".to_string());
    }

    let handshake_hash = handshake.get_handshake_hash().to_vec();

    let transport = Arc::new(Mutex::new(
        handshake.into_transport_mode().map_err(|e| format!("transport mode: {e}"))?,
    ));

    let start_json = serde_json::to_vec(&start).map_err(|e| format!("start json: {e}"))?;
    seal_and_write(&mut stream, &transport, mty::SCREEN_MIRROR_CONTROL, ctrl::START, &start_json)
        .await?;

    let (reader, writer_half) = stream.into_split();
    let writer_half = Arc::new(Mutex::new(writer_half));

    let (input_tx, input_rx) = mpsc::channel::<Vec<u8>>(256);
    let (keyframe_tx, keyframe_rx) = mpsc::channel::<()>(8);
    let (stop_tx, stop_rx) = mpsc::channel::<()>(1);

    {
        let transport = transport.clone();
        let writer_half = writer_half.clone();
        tokio::spawn(writer_loop(transport, writer_half, input_rx, keyframe_rx, stop_rx));
    }

    {
        let transport = transport.clone();
        tokio::spawn(reader_loop(reader, transport));
    }

    info!("mirror: session established (control plane up)");
    Ok(MirrorHandle { input_tx, keyframe_tx, handshake_hash, stop_tx })
}

async fn writer_loop(
    transport: Arc<Mutex<TransportState>>,
    writer_half: Arc<Mutex<tokio::net::tcp::OwnedWriteHalf>>,
    mut input_rx: mpsc::Receiver<Vec<u8>>,
    mut keyframe_rx: mpsc::Receiver<()>,
    mut stop_rx: mpsc::Receiver<()>,
) {
    let mut ping = tokio::time::interval(KEEPALIVE);
    ping.tick().await;
    let mut last_keyframe_req = tokio::time::Instant::now() - KEYFRAME_REQ_MIN_GAP;
    loop {
        tokio::select! {
            _ = stop_rx.recv() => {
                let _ = seal_and_write_half(
                    &writer_half, &transport,
                    mty::SCREEN_MIRROR_CONTROL, ctrl::STOP, &[],
                ).await;
                break;
            }
            maybe = input_rx.recv() => {
                match maybe {
                    Some(pkt) => {
                        if let Err(e) = seal_and_write_half(
                            &writer_half, &transport,
                            mty::SCREEN_MIRROR_INPUT, 0x01, &pkt,
                        ).await {
                            warn!("mirror: input write failed: {e}");
                            break;
                        }
                    }
                    None => break,
                }
            }
            maybe = keyframe_rx.recv() => {
                if maybe.is_none() { break; }
                let now = tokio::time::Instant::now();
                if now.duration_since(last_keyframe_req) < KEYFRAME_REQ_MIN_GAP {
                    continue;
                }
                last_keyframe_req = now;
                if let Err(e) = seal_and_write_half(
                    &writer_half, &transport,
                    mty::SCREEN_MIRROR_CONTROL, ctrl::REQUEST_KEYFRAME, &[],
                ).await {
                    warn!("mirror: keyframe-req write failed: {e}");
                    break;
                }
            }
            _ = ping.tick() => {
                if let Err(e) = seal_and_write_half(
                    &writer_half, &transport,
                    mty::SCREEN_MIRROR_CONTROL, ctrl::PING, &[],
                ).await {
                    debug!("mirror: keepalive ping failed (peer gone?): {e}");
                    break;
                }
            }
        }
    }
    debug!("mirror: writer loop ended");
}

async fn reader_loop(
    mut reader: tokio::net::tcp::OwnedReadHalf,
    transport: Arc<Mutex<TransportState>>,
) {
    loop {
        match read_sealed_frame(&mut reader, &transport).await {
            Ok(Some((ty, sub, _payload))) => {
                if ty == mty::SCREEN_MIRROR_CONTROL && sub == ctrl::PONG {
                    debug!("mirror: ← pong");
                } else {
                    debug!(ty = ty, sub = sub, "mirror: ← control frame");
                }
            }
            Ok(None) => {
                debug!("mirror: control socket EOF");
                break;
            }
            Err(e) => {
                warn!("mirror: reader error: {e}");
                break;
            }
        }
    }
}

async fn write_frame(stream: &mut TcpStream, frame: &Frame) -> Result<(), String> {
    let bytes = frame.encode();
    stream.write_all(&bytes).await.map_err(|e| format!("tcp write: {e}"))?;
    stream.flush().await.map_err(|e| format!("tcp flush: {e}"))?;
    Ok(())
}

async fn seal_and_write(
    stream: &mut TcpStream,
    transport: &Arc<Mutex<TransportState>>,
    ty: u8,
    sub: u8,
    plain: &[u8],
) -> Result<(), String> {
    let mut out = vec![0u8; plain.len() + 16];
    let n = {
        let mut t = transport.lock().await;
        t.write_message(plain, &mut out).map_err(|e| format!("aead seal: {e}"))?
    };
    let frame = Frame::new(ty, sub, out[..n].to_vec());
    write_frame(stream, &frame).await
}

async fn seal_and_write_half(
    writer_half: &Arc<Mutex<tokio::net::tcp::OwnedWriteHalf>>,
    transport: &Arc<Mutex<TransportState>>,
    ty: u8,
    sub: u8,
    plain: &[u8],
) -> Result<(), String> {
    let mut out = vec![0u8; plain.len() + 16];
    let n = {
        let mut t = transport.lock().await;
        t.write_message(plain, &mut out).map_err(|e| format!("aead seal: {e}"))?
    };
    let frame = Frame::new(ty, sub, out[..n].to_vec());
    let bytes = frame.encode();
    let mut w = writer_half.lock().await;
    w.write_all(&bytes).await.map_err(|e| format!("tcp write: {e}"))?;
    w.flush().await.map_err(|e| format!("tcp flush: {e}"))?;
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

async fn read_sealed_frame(
    reader: &mut tokio::net::tcp::OwnedReadHalf,
    transport: &Arc<Mutex<TransportState>>,
) -> Result<Option<(u8, u8, Vec<u8>)>, String> {
    let mut header = [0u8; FRAME_HEADER_LEN];
    match reader.read_exact(&mut header).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(format!("tcp read header: {e}")),
    }
    let ty = header[0];
    let sub = header[1];
    let length = u16::from_be_bytes([header[2], header[3]]) as usize;
    if length > MAX_FRAME_PAYLOAD {
        return Err(format!("oversize frame {length}"));
    }
    let mut body = vec![0u8; length];
    if length > 0 {
        reader.read_exact(&mut body).await.map_err(|e| format!("tcp read body: {e}"))?;
    }
    let mut plain = vec![0u8; body.len()];
    let n = {
        let mut t = transport.lock().await;
        t.read_message(&body, &mut plain).map_err(|e| format!("aead open: {e}"))?
    };
    plain.truncate(n);
    Ok(Some((ty, sub, plain)))
}

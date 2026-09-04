use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;

pub const VIDEO_PORT: u16 = 51822;

pub const LAPTOP_VIDEO_PORT: u16 = 51823;

pub const CAMERA_VIDEO_PORT: u16 = 51824;

const MAX_AU_LEN: usize = 8 * 1024 * 1024;

#[inline]
fn nonce_from_counter(counter: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[4..].copy_from_slice(&counter.to_be_bytes());
    n
}

pub async fn run_tcp_video_receiver(
    phone_ip: IpAddr,
    key: [u8; 32],
    au_tx: mpsc::Sender<Vec<u8>>,
    keyframe_tx: Option<mpsc::Sender<()>>,
) {
    run_tcp_video_receiver_on(phone_ip, VIDEO_PORT, key, au_tx, keyframe_tx).await;
}

pub async fn run_tcp_video_receiver_on(
    phone_ip: IpAddr,
    port: u16,
    key: [u8; 32],
    au_tx: mpsc::Sender<Vec<u8>>,
    keyframe_tx: Option<mpsc::Sender<()>>,
) {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(&key));
    let addr = SocketAddr::new(phone_ip, port);

    let mut stream = None;
    for attempt in 0..120u32 {
        match TcpStream::connect(addr).await {
            Ok(s) => {
                let _ = s.set_nodelay(true);
                stream = Some(s);
                break;
            }
            Err(e) => {
                if attempt == 0 {
                    tracing::info!(%addr, "mirror tcp: waiting for phone video server ({e})");
                }
                tokio::time::sleep(Duration::from_millis(500)).await;
            }
        }
    }
    let Some(mut stream) = stream else {
        tracing::warn!(%addr, "mirror tcp: phone video server never came up");
        return;
    };
    tracing::info!(%addr, "mirror tcp: video connected");

    let mut len_buf = [0u8; 4];
    let mut aus: u64 = 0;
    let mut dropped: u64 = 0;
    loop {
        if let Err(e) = stream.read_exact(&mut len_buf).await {
            tracing::info!("mirror tcp: video stream ended ({e})");
            break;
        }
        let msg_len = u32::from_be_bytes(len_buf) as usize;
        if msg_len < 8 + 16 || msg_len > MAX_AU_LEN {
            tracing::warn!(msg_len, "mirror tcp: bad frame length — closing");
            break;
        }
        let mut msg = vec![0u8; msg_len];
        if let Err(e) = stream.read_exact(&mut msg).await {
            tracing::info!("mirror tcp: short read ({e})");
            break;
        }
        let counter = u64::from_be_bytes(msg[..8].try_into().unwrap());
        let nonce = nonce_from_counter(counter);
        let au = match cipher
            .decrypt(Nonce::from_slice(&nonce), Payload { msg: &msg[8..], aad: &msg[..8] })
        {
            Ok(p) => p,
            Err(_) => {
                tracing::warn!(counter, "mirror tcp: AEAD open failed — closing");
                break;
            }
        };
        aus += 1;
        if aus == 1 {
            tracing::info!(bytes = au.len(), "mirror tcp: FIRST access unit → GStreamer");
        }
        if aus % 120 == 0 {
            tracing::info!(aus, "mirror tcp: streaming");
        }
        match au_tx.try_send(au) {
            Ok(()) => {}
            Err(mpsc::error::TrySendError::Closed(_)) => {
                tracing::warn!("mirror tcp: AU consumer gone — stopping");
                break;
            }
            Err(mpsc::error::TrySendError::Full(_)) => {
                dropped += 1;
                if dropped % 30 == 1 {
                    tracing::warn!(dropped, "mirror tcp: decoder behind — dropping frames");
                }
                if let Some(kf) = keyframe_tx.as_ref() {
                    let _ = kf.try_send(());
                }
            }
        }
    }
    tracing::info!(aus, "mirror tcp: video receiver ended");
}

pub struct MirrorTcpSealer {
    cipher: ChaCha20Poly1305,
    counter: u64,
}

impl MirrorTcpSealer {
    pub fn new(key: &[u8; 32]) -> Self {
        Self { cipher: ChaCha20Poly1305::new(Key::from_slice(key)), counter: 0 }
    }

    pub fn seal(&mut self, au: &[u8]) -> Vec<u8> {
        let counter = self.counter;
        self.counter = self.counter.wrapping_add(1);
        let counter_be = counter.to_be_bytes();
        let nonce = nonce_from_counter(counter);
        let ct = self
            .cipher
            .encrypt(Nonce::from_slice(&nonce), Payload { msg: au, aad: &counter_be })
            .expect("chacha seal");
        let msg_len = (8 + ct.len()) as u32;
        let mut out = Vec::with_capacity(4 + 8 + ct.len());
        out.extend_from_slice(&msg_len.to_be_bytes());
        out.extend_from_slice(&counter_be);
        out.extend_from_slice(&ct);
        out
    }
}

pub async fn run_tcp_video_client(
    phone_ip: IpAddr,
    key: [u8; 32],
    mut au_rx: mpsc::Receiver<Vec<u8>>,
) {
    let addr = SocketAddr::new(phone_ip, LAPTOP_VIDEO_PORT);
    let mut sealer = MirrorTcpSealer::new(&key);

    'session: loop {
        let mut stream = None;
        for attempt in 0..120u32 {
            match TcpStream::connect(addr).await {
                Ok(s) => {
                    let _ = s.set_nodelay(true);
                    stream = Some(s);
                    break;
                }
                Err(e) => {
                    if attempt == 0 {
                        tracing::info!(%addr, "laptop-cast: waiting for phone viewer server ({e})");
                    }
                    tokio::time::sleep(Duration::from_millis(500)).await;
                    if au_rx.try_recv().is_err() && au_rx.is_closed() {
                        return;
                    }
                }
            }
        }
        let Some(mut stream) = stream else {
            tracing::warn!(%addr, "laptop-cast: phone viewer never came up");
            return;
        };
        tracing::info!(%addr, "laptop-cast: connected to phone viewer — streaming");

        while au_rx.try_recv().is_ok() {}

        let mut sent: u64 = 0;
        loop {
            match au_rx.recv().await {
                Some(au) => {
                    let msg = sealer.seal(&au);
                    if let Err(e) = stream.write_all(&msg).await {
                        tracing::info!(
                            sent,
                            "laptop-cast: phone viewer dropped ({e}) — reconnecting"
                        );
                        continue 'session; // self-heal: dial again
                    }
                    sent += 1;
                }
                None => {
                    tracing::info!(sent, "laptop-cast: cast stopped — video push ended");
                    return;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::mirror_udp::derive_laptop_media_key;

    #[test]
    fn sealer_roundtrips_through_open() {
        let key = derive_laptop_media_key(b"test-handshake-hash");
        let mut sealer = MirrorTcpSealer::new(&key);
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&key));

        for (i, au) in [b"first-access-unit".as_ref(), b"second", b""].iter().enumerate() {
            let msg = sealer.seal(au);
            let msg_len = u32::from_be_bytes(msg[..4].try_into().unwrap()) as usize;
            assert_eq!(msg_len, msg.len() - 4);
            let body = &msg[4..];
            let counter = u64::from_be_bytes(body[..8].try_into().unwrap());
            assert_eq!(counter, i as u64, "counter must be monotonic from 0");
            let nonce = nonce_from_counter(counter);
            let opened = cipher
                .decrypt(Nonce::from_slice(&nonce), Payload { msg: &body[8..], aad: &body[..8] })
                .expect("open must succeed");
            assert_eq!(&opened, au);
        }
    }

    #[test]
    fn laptop_key_differs_from_phone_key() {
        let h = b"same-handshake";
        assert_ne!(super::super::mirror_udp::derive_media_key(h), derive_laptop_media_key(h),);
    }
}

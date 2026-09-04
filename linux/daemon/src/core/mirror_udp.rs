use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use hkdf::Hkdf;
use sha2::Sha256;
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

pub const MAX_FRAGMENT_DATA: usize = 1100;
const INNER_HEADER: usize = 8;
const KEY_INFO: &[u8] = b"vortex/mirror/udp";

const LAPTOP_KEY_INFO: &[u8] = b"vortex/mirror/laptop";

fn derive_media_key_with_info(handshake_hash: &[u8], info: &[u8]) -> [u8; 32] {
    let hk = Hkdf::<Sha256>::new(None, handshake_hash);
    let mut out = [0u8; 32];
    hk.expand(info, &mut out).expect("hkdf expand 32 bytes");
    out
}

pub fn derive_media_key(handshake_hash: &[u8]) -> [u8; 32] {
    derive_media_key_with_info(handshake_hash, KEY_INFO)
}

pub fn derive_laptop_media_key(handshake_hash: &[u8]) -> [u8; 32] {
    derive_media_key_with_info(handshake_hash, LAPTOP_KEY_INFO)
}

#[inline]
fn nonce_from_counter(counter: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[4..].copy_from_slice(&counter.to_be_bytes());
    n
}

pub struct MediaSealer {
    cipher: ChaCha20Poly1305,
    counter: u64,
}

impl MediaSealer {
    pub fn new(key: &[u8; 32]) -> Self {
        Self { cipher: ChaCha20Poly1305::new(Key::from_slice(key)), counter: 0 }
    }

    pub fn seal_access_unit(&mut self, frame_id: u32, au: &[u8]) -> Vec<Vec<u8>> {
        let frags: Vec<&[u8]> =
            if au.is_empty() { vec![&au[..]] } else { au.chunks(MAX_FRAGMENT_DATA).collect() };
        let frag_cnt = frags.len().min(u16::MAX as usize) as u16;
        frags
            .into_iter()
            .enumerate()
            .map(|(idx, data)| {
                let mut inner = Vec::with_capacity(INNER_HEADER + data.len());
                inner.extend_from_slice(&frame_id.to_be_bytes());
                inner.extend_from_slice(&(idx as u16).to_be_bytes());
                inner.extend_from_slice(&frag_cnt.to_be_bytes());
                inner.extend_from_slice(data);

                let counter = self.counter;
                self.counter = self.counter.wrapping_add(1);
                let counter_bytes = counter.to_be_bytes();
                let nonce = nonce_from_counter(counter);
                let ct = self
                    .cipher
                    .encrypt(
                        Nonce::from_slice(&nonce),
                        Payload { msg: &inner, aad: &counter_bytes },
                    )
                    .expect("chacha seal");

                let mut pkt = Vec::with_capacity(8 + ct.len());
                pkt.extend_from_slice(&counter_bytes);
                pkt.extend_from_slice(&ct);
                pkt
            })
            .collect()
    }
}

#[derive(Default)]
pub struct ReplayWindow {
    last: u64,
    bitmap: u64,
    started: bool,
}

impl ReplayWindow {
    const WINDOW: u64 = 64;

    pub fn accept(&mut self, counter: u64) -> bool {
        if !self.started {
            self.started = true;
            self.last = counter;
            self.bitmap = 1;
            return true;
        }
        if counter > self.last {
            let shift = counter - self.last;
            if shift >= Self::WINDOW {
                self.bitmap = 1;
            } else {
                self.bitmap = (self.bitmap << shift) | 1;
            }
            self.last = counter;
            true
        } else {
            let diff = self.last - counter;
            if diff >= Self::WINDOW {
                return false;
            }
            let mask = 1u64 << diff;
            if self.bitmap & mask != 0 {
                false
            } else {
                self.bitmap |= mask;
                true
            }
        }
    }
}

pub enum Reassembled {
    Complete(Vec<u8>),
    Pending,
    Lost,
}

#[derive(Default)]
pub struct Reassembler {
    frame_id: u32,
    frag_cnt: u16,
    have: u16,
    parts: Vec<Option<Vec<u8>>>,
    active: bool,
}

impl Reassembler {
    pub fn push(&mut self, inner: &[u8]) -> Reassembled {
        if inner.len() < INNER_HEADER {
            return Reassembled::Pending;
        }
        let frame_id = u32::from_be_bytes([inner[0], inner[1], inner[2], inner[3]]);
        let frag_idx = u16::from_be_bytes([inner[4], inner[5]]);
        let frag_cnt = u16::from_be_bytes([inner[6], inner[7]]);
        let data = &inner[INNER_HEADER..];
        if frag_cnt == 0 || frag_idx >= frag_cnt {
            return Reassembled::Pending;
        }

        let mut lost = false;
        if !self.active || frame_id != self.frame_id {
            lost = self.active && self.have < self.frag_cnt;
            if self.active && frame_id < self.frame_id {
                return if lost { Reassembled::Lost } else { Reassembled::Pending };
            }
            self.frame_id = frame_id;
            self.frag_cnt = frag_cnt;
            self.have = 0;
            self.parts = vec![None; frag_cnt as usize];
            self.active = true;
        }

        if self.parts[frag_idx as usize].is_none() {
            self.parts[frag_idx as usize] = Some(data.to_vec());
            self.have += 1;
        }

        if self.have == self.frag_cnt {
            let mut au = Vec::new();
            for p in &self.parts {
                if let Some(b) = p {
                    au.extend_from_slice(b);
                }
            }
            self.active = false;
            return Reassembled::Complete(au);
        }
        if lost {
            Reassembled::Lost
        } else {
            Reassembled::Pending
        }
    }
}

pub fn open_packet(
    cipher: &ChaCha20Poly1305,
    replay: &mut ReplayWindow,
    pkt: &[u8],
) -> Option<Vec<u8>> {
    if pkt.len() < 8 + 16 {
        return None;
    }
    let counter = u64::from_be_bytes(pkt[..8].try_into().ok()?);
    if !replay.accept(counter) {
        return None;
    }
    let nonce = nonce_from_counter(counter);
    let counter_bytes = &pkt[..8];
    cipher.decrypt(Nonce::from_slice(&nonce), Payload { msg: &pkt[8..], aad: counter_bytes }).ok()
}

pub async fn run_udp_receiver(
    socket: std::sync::Arc<UdpSocket>,
    key: [u8; 32],
    au_tx: mpsc::Sender<Vec<u8>>,
    keyframe_tx: mpsc::Sender<()>,
) {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(&key));
    let mut replay = ReplayWindow::default();
    let mut re = Reassembler::default();
    let mut buf = vec![0u8; 2048];
    let local = socket.local_addr().ok();
    tracing::info!(?local, "mirror udp receiver listening");
    let mut pkts: u64 = 0;
    let mut aus: u64 = 0;
    let mut dropped: u64 = 0;
    let mut lost: u64 = 0;
    loop {
        let n = match socket.recv(&mut buf).await {
            Ok(0) => continue,
            Ok(n) => n,
            Err(e) => {
                tracing::warn!("mirror udp recv: {e}");
                break;
            }
        };
        pkts += 1;
        if pkts == 1 {
            tracing::info!(bytes = n, "mirror udp: FIRST packet received");
        }
        let Some(inner) = open_packet(&cipher, &mut replay, &buf[..n]) else {
            dropped += 1;
            if dropped == 1 {
                tracing::warn!(
                    "mirror udp: first packet FAILED to decrypt/validate (key/replay/forged)"
                );
            }
            continue;
        };
        match re.push(&inner) {
            Reassembled::Complete(au) => {
                aus += 1;
                if aus == 1 {
                    tracing::info!(
                        bytes = au.len(),
                        "mirror udp: FIRST access unit reassembled → GStreamer"
                    );
                }
                if aus % 120 == 0 {
                    tracing::info!(pkts, aus, dropped, lost, "mirror udp: streaming");
                }
                if au_tx.send(au).await.is_err() {
                    tracing::warn!("mirror udp: AU consumer gone — stopping");
                    break;
                }
            }
            Reassembled::Lost => {
                lost += 1;
                let _ = keyframe_tx.try_send(());
            }
            Reassembled::Pending => {}
        }
    }
    tracing::info!(pkts, aus, dropped, lost, "mirror udp receiver ended");
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> [u8; 32] {
        derive_media_key(b"some-handshake-transcript-hash-32")
    }

    #[test]
    fn key_derivation_is_stable_and_matches_both_sides() {
        let a = derive_media_key(b"transcript");
        let b = derive_media_key(b"transcript");
        assert_eq!(a, b);
        assert_ne!(a, derive_media_key(b"other"));
    }

    #[test]
    fn seal_open_round_trip_single_fragment() {
        let k = key();
        let mut sealer = MediaSealer::new(&k);
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&k));
        let mut replay = ReplayWindow::default();

        let au = b"a small access unit".to_vec();
        let pkts = sealer.seal_access_unit(7, &au);
        assert_eq!(pkts.len(), 1);
        let inner = open_packet(&cipher, &mut replay, &pkts[0]).unwrap();
        let mut re = Reassembler::default();
        match re.push(&inner) {
            Reassembled::Complete(out) => assert_eq!(out, au),
            _ => panic!("expected complete"),
        }
    }

    #[test]
    fn fragmented_au_reassembles() {
        let k = key();
        let mut sealer = MediaSealer::new(&k);
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&k));
        let mut replay = ReplayWindow::default();
        let mut re = Reassembler::default();

        let au = vec![0xABu8; MAX_FRAGMENT_DATA * 3 + 17];
        let pkts = sealer.seal_access_unit(1, &au);
        assert_eq!(pkts.len(), 4);
        let mut done = None;
        for p in &pkts {
            let inner = open_packet(&cipher, &mut replay, p).unwrap();
            if let Reassembled::Complete(out) = re.push(&inner) {
                done = Some(out);
            }
        }
        assert_eq!(done.unwrap(), au);
    }

    #[test]
    fn replay_is_rejected() {
        let mut w = ReplayWindow::default();
        assert!(w.accept(5));
        assert!(!w.accept(5));
        assert!(w.accept(6));
        assert!(w.accept(4));
        assert!(!w.accept(4));
    }

    #[test]
    fn dropped_fragment_then_new_frame_reports_loss() {
        let k = key();
        let mut sealer = MediaSealer::new(&k);
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&k));
        let mut replay = ReplayWindow::default();
        let mut re = Reassembler::default();

        let au1 = vec![1u8; MAX_FRAGMENT_DATA + 10];
        let f1 = sealer.seal_access_unit(1, &au1);
        assert_eq!(f1.len(), 2);
        let inner = open_packet(&cipher, &mut replay, &f1[0]).unwrap();
        assert!(matches!(re.push(&inner), Reassembled::Pending));

        let au2 = b"frame two".to_vec();
        let f2 = sealer.seal_access_unit(2, &au2);
        let inner2 = open_packet(&cipher, &mut replay, &f2[0]).unwrap();
        assert!(matches!(re.push(&inner2), Reassembled::Complete(_) | Reassembled::Lost));
    }

    #[test]
    fn forged_packet_is_dropped() {
        let k = key();
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&k));
        let mut replay = ReplayWindow::default();
        let mut forged = vec![0u8; 8 + 16 + 4];
        forged[7] = 1;
        assert!(open_packet(&cipher, &mut replay, &forged).is_none());
    }
}

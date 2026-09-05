use std::sync::Arc;
use std::time::Duration;

use futures::future::BoxFuture;
use tokio::sync::{watch, Mutex};
use tracing::{info, warn};

use crate::core::audio_op::{AudioOp, AudioOpFrame, RejectReason, Stage};
use crate::core::audio_switch::{
    confirm_audio_disconnected, connect_audio, disconnect_audio_initiate, SwitchError,
};
use crate::core::audio_switch_persistence as persist;
use crate::core::storage::peers::PeerStore;

pub type Sender = Arc<
    dyn Fn([u8; 32], AudioOpFrame) -> futures::future::BoxFuture<'static, Result<(), String>>
        + Send
        + Sync,
>;

#[derive(Debug, Clone)]
pub enum Acceptance {
    Allow,
    Reject(RejectReason),
}

pub type AcceptanceProvider = Arc<dyn Fn() -> Acceptance + Send + Sync>;

pub trait BtControl: Send + Sync {
    fn connect<'a>(&'a self, mac: &'a str) -> BoxFuture<'a, Result<(), SwitchError>>;
    fn disconnect_initiate<'a>(&'a self, mac: &'a str) -> BoxFuture<'a, Result<(), SwitchError>>;
    fn confirm_disconnected<'a>(&'a self, mac: &'a str, timeout: Duration) -> BoxFuture<'a, bool>;
    fn is_connected<'a>(&'a self, mac: &'a str) -> BoxFuture<'a, bool>;
}

pub struct BluerBt {
    adapter: bluer::Adapter,
}

impl BluerBt {
    pub fn new(adapter: bluer::Adapter) -> Self {
        Self { adapter }
    }
}

impl BtControl for BluerBt {
    fn connect<'a>(&'a self, mac: &'a str) -> BoxFuture<'a, Result<(), SwitchError>> {
        Box::pin(connect_audio(&self.adapter, mac))
    }
    fn disconnect_initiate<'a>(&'a self, mac: &'a str) -> BoxFuture<'a, Result<(), SwitchError>> {
        Box::pin(disconnect_audio_initiate(&self.adapter, mac))
    }
    fn confirm_disconnected<'a>(&'a self, mac: &'a str, timeout: Duration) -> BoxFuture<'a, bool> {
        Box::pin(confirm_audio_disconnected(&self.adapter, mac, timeout))
    }
    fn is_connected<'a>(&'a self, mac: &'a str) -> BoxFuture<'a, bool> {
        Box::pin(async move {
            let Ok(addr) = mac.parse::<bluer::Address>() else {
                return false;
            };
            match self.adapter.device(addr) {
                Ok(device) => device.is_connected().await.unwrap_or(false),
                Err(_) => false,
            }
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SwitchState {
    Idle,
    Preparing,
    WaitingApproval,
    WaitingReleased,
    Connecting,
    AlmostDone,
    Failed(String),
}

#[derive(Debug, Clone)]
struct ActiveFlow {
    peer_pub: [u8; 32],
    mac: String,
}

impl ActiveFlow {
    fn matches(&self, peer: &[u8; 32], mac: &str) -> bool {
        &self.peer_pub == peer && self.mac == mac
    }
}

pub struct SwitchOrchestrator {
    bt: Arc<dyn BtControl>,
    peer_store: Arc<dyn PeerStore>,
    sender: Sender,
    acceptance: AcceptanceProvider,
    state_tx: watch::Sender<SwitchState>,
    state_rx: watch::Receiver<SwitchState>,
    initiator: Arc<Mutex<Option<ActiveFlow>>>,
    responder: Arc<Mutex<Option<ActiveFlow>>>,
    current_peer: Arc<Mutex<Option<[u8; 32]>>>,
    current_mac: Arc<Mutex<Option<String>>>,
    last_complete: Arc<Mutex<Option<(String, tokio::time::Instant)>>>,
}

impl SwitchOrchestrator {
    pub fn new(
        bt: Arc<dyn BtControl>,
        peer_store: Arc<dyn PeerStore>,
        sender: Sender,
        acceptance: AcceptanceProvider,
    ) -> Self {
        let (state_tx, state_rx) = watch::channel(SwitchState::Idle);
        Self {
            bt,
            peer_store,
            sender,
            acceptance,
            state_tx,
            state_rx,
            initiator: Arc::new(Mutex::new(None)),
            responder: Arc::new(Mutex::new(None)),
            current_peer: Arc::new(Mutex::new(None)),
            current_mac: Arc::new(Mutex::new(None)),
            last_complete: Arc::new(Mutex::new(None)),
        }
    }

    pub fn state(&self) -> watch::Receiver<SwitchState> {
        self.state_rx.clone()
    }

    pub async fn current_mac(&self) -> Option<String> {
        self.current_mac.lock().await.clone()
    }

    pub async fn request(self: &Arc<Self>, peer_pub: [u8; 32], mac: String) -> Result<(), String> {
        if self.is_recent_duplicate(&mac).await {
            info!(%mac, "request: duplicate reclaim within window; buds already ours — Done + no-op");
            if let Some(nonce) = next_out_nonce(&self.peer_store, peer_pub).await {
                let _ = (self.sender)(
                    peer_pub,
                    AudioOpFrame { nonce, op: AudioOp::Done, mac: mac.clone(), ts: now_sec() },
                )
                .await;
            }
            return Ok(());
        }
        *self.current_peer.lock().await = Some(peer_pub);
        *self.current_mac.lock().await = Some(mac.clone());
        if !self.cas_state(SwitchState::Idle, SwitchState::Preparing) {
            *self.current_peer.lock().await = None;
            *self.current_mac.lock().await = None;
            return Err(format!("already in {:?}", *self.state_rx.borrow()));
        }
        *self.initiator.lock().await = Some(ActiveFlow { peer_pub, mac: mac.clone() });

        let me = self.clone();
        tokio::spawn(async move { me.run_initiator(peer_pub, mac).await });
        Ok(())
    }

    pub async fn send_claim(self: &Arc<Self>, peer_pub: [u8; 32], mac: String) {
        let Some(nonce) = next_out_nonce(&self.peer_store, peer_pub).await else {
            warn!("send_claim: nonce unavailable; peer not asked to claim (the audio_claim_request heartbeat flag is the fallback)");
            return;
        };
        let _ =
            (self.sender)(peer_pub, AudioOpFrame { nonce, op: AudioOp::Claim, mac, ts: now_sec() })
                .await;
    }

    pub async fn recover_on_start(self: &Arc<Self>) {
        match persist::recover() {
            persist::Action::None => {}
            persist::Action::Rollback(reason) => {
                warn!(%reason, "recover: rollback");
                let _ = self.state_tx.send(SwitchState::Failed(reason.clone()));
                let _ = persist::clear();
                let state_tx = self.state_tx.clone();
                tokio::spawn(async move {
                    tokio::time::sleep(Duration::from_millis(FAILED_RESET_MS)).await;
                    let _ = state_tx.send(SwitchState::Idle);
                });
            }
            persist::Action::ResumeConnect { peer_pub, mac } => {
                info!(?peer_pub, %mac, "recover: resume Connecting");
                *self.current_peer.lock().await = Some(peer_pub);
                *self.current_mac.lock().await = Some(mac.clone());
                *self.initiator.lock().await = Some(ActiveFlow { peer_pub, mac: mac.clone() });
                let _ = self.state_tx.send(SwitchState::Connecting);
                self.arm_flow_watchdog();
                let me = self.clone();
                tokio::spawn(async move { me.attempt_connect(peer_pub, mac).await });
            }
        }
    }

    pub async fn on_incoming(
        self: Arc<Self>,
        peer_pub: [u8; 32],
        frame: AudioOpFrame,
    ) -> Result<(), String> {
        let accepted = {
            let ps = self.peer_store.clone();
            let nonce = frame.nonce;
            tokio::task::spawn_blocking(move || ps.try_accept_audio_in_nonce(&peer_pub, nonce))
                .await
                .map_err(|e| format!("try_accept join: {e}"))?
                .map_err(|e| format!("try_accept_audio_in_nonce: {e}"))?
        };
        if !accepted {
            warn!(nonce = frame.nonce, "audio_op nonce replay; dropping");
            return Ok(());
        }

        match frame.op {
            AudioOp::Request => self.start_responder(peer_pub, frame.mac).await,
            AudioOp::Claim => self.on_claim(peer_pub, frame.mac).await,
            AudioOp::Approve => self.on_approve(peer_pub, frame.mac).await,
            AudioOp::Released => self.on_released(peer_pub, frame.mac).await,
            AudioOp::Reject { reason } => self.on_reject(peer_pub, reason).await,
            AudioOp::Done => self.on_done(peer_pub).await,
            AudioOp::Failed { stage, message } => {
                self.on_peer_failed(peer_pub, stage, message).await
            }
        }
        Ok(())
    }

    async fn on_claim(self: Arc<Self>, peer_pub: [u8; 32], mac: String) {
        if *self.state_rx.borrow() != SwitchState::Idle {
            info!("on_claim: already in flow; dropping");
            return;
        }
        info!(?peer_pub, %mac, "on_claim: peer asked us to claim");
        if let Err(e) = self.request(peer_pub, mac).await {
            warn!("on_claim: request() rejected: {e}");
        }
    }

    async fn run_initiator(self: Arc<Self>, peer_pub: [u8; 32], mac: String) {
        self.arm_flow_watchdog();

        let Some(nonce) = next_out_nonce(&self.peer_store, peer_pub).await else {
            self.fail_initiator("nonce store unavailable".to_string());
            return;
        };
        info!(?peer_pub, %mac, nonce, "initiator: requesting buds");

        // NOTE: we deliberately do NOT fire a separate background

        let me_send = self.clone();
        let peer_send = peer_pub;
        let mac_send = mac.clone();
        tokio::spawn(async move {
            let frame = AudioOpFrame { nonce, op: AudioOp::Request, mac: mac_send, ts: now_sec() };
            if let Err(e) = (me_send.sender)(peer_send, frame).await {
                warn!("send Request: {e}");
            }
        });

        if !self.cas_state(SwitchState::Preparing, SwitchState::Connecting) {
            return;
        }
        self.attempt_connect(peer_pub, mac).await;
    }

    async fn on_approve(self: Arc<Self>, peer_pub: [u8; 32], _mac: String) {
        let g = self.initiator.lock().await;
        let Some(ref f) = *g else { return };
        if f.peer_pub != peer_pub {
            return;
        }
        info!("informational: peer Approve received");
    }

    async fn on_released(self: Arc<Self>, peer_pub: [u8; 32], _mac: String) {
        let g = self.initiator.lock().await;
        let Some(ref f) = *g else { return };
        if f.peer_pub != peer_pub {
            return;
        }
        drop(g);
        info!("peer Released received; optimistic UI → AlmostDone");
        let _ = self.cas_state(SwitchState::Connecting, SwitchState::AlmostDone);
    }

    async fn on_reject(&self, peer_pub: [u8; 32], reason: RejectReason) {
        let g = self.initiator.lock().await;
        let Some(ref f) = *g else { return };
        if f.peer_pub != peer_pub {
            return;
        }
        drop(g);
        self.fail_initiator(format!("peer rejected: {reason:?}"));
    }

    async fn attempt_connect(self: Arc<Self>, peer_pub: [u8; 32], mac: String) {
        let mut last_err = String::from("connect not attempted");
        for attempt in 1..=CONNECT_RETRY_COUNT {
            let s = self.state_rx.borrow().clone();
            if matches!(s, SwitchState::Failed(_) | SwitchState::Idle) {
                tracing::debug!("attempt_connect: state already terminal; stopping retries");
                return;
            }
            match self.bt.connect(&mac).await {
                Ok(()) => {
                    info!(?peer_pub, attempt, "initiator: switch complete");
                    self.state_tx.send_if_modified(|current| {
                        if matches!(*current, SwitchState::Connecting | SwitchState::AlmostDone) {
                            *current = SwitchState::Idle;
                            true
                        } else {
                            false
                        }
                    });
                    *self.initiator.lock().await = None;
                    *self.last_complete.lock().await =
                        Some((mac.clone(), tokio::time::Instant::now()));
                    let sender = self.sender.clone();
                    let peer_store = self.peer_store.clone();
                    let mac_for_done = mac.clone();
                    tokio::spawn(async move {
                        if let Some(nonce) = next_out_nonce(&peer_store, peer_pub).await {
                            let _ = sender(
                                peer_pub,
                                AudioOpFrame {
                                    nonce,
                                    op: AudioOp::Done,
                                    mac: mac_for_done,
                                    ts: now_sec(),
                                },
                            )
                            .await;
                        }
                    });
                    return;
                }
                Err(e) => {
                    last_err = format!("attempt#{attempt}: {e}");
                    tracing::debug!(%last_err, "connect retry");
                    if attempt < CONNECT_RETRY_COUNT {
                        tokio::time::sleep(Duration::from_millis(CONNECT_RETRY_PAUSE_MS)).await;
                    }
                }
            }
        }
        if let Some(nonce) = next_out_nonce(&self.peer_store, peer_pub).await {
            let _ = (self.sender)(
                peer_pub,
                AudioOpFrame {
                    nonce,
                    op: AudioOp::Failed { stage: Stage::Connect, message: last_err.clone() },
                    mac: mac.clone(),
                    ts: now_sec(),
                },
            )
            .await;
        }
        self.fail_initiator(last_err);
    }

    fn fail_initiator(&self, reason: String) {
        warn!(reason, "initiator: failed");
        let failed = SwitchState::Failed(reason.clone());
        let _ = self.state_tx.send(failed.clone());
        let peer = self.current_peer.try_lock().ok().and_then(|g| *g);
        let mac = self.current_mac.try_lock().ok().and_then(|g| g.clone());
        let saved = persist::Saved {
            discriminator: persist::disc::FAILED.to_string(),
            reason: Some(reason.clone()),
            enter_ms: now_ms(),
            peer_pub_hex: peer.map(|p| persist::pub_to_hex(&p)).unwrap_or_default(),
            mac: mac.unwrap_or_default(),
        };
        if let Err(e) = persist::save(&saved) {
            warn!("audio_switch persist save (failed state): {e}");
        }
        let initiator = self.initiator.clone();
        let state_tx = self.state_tx.clone();
        let current_peer = self.current_peer.clone();
        let current_mac = self.current_mac.clone();
        tokio::spawn(async move {
            *initiator.lock().await = None;
            tokio::time::sleep(Duration::from_millis(FAILED_RESET_MS)).await;
            if matches!(*state_tx.borrow(), SwitchState::Failed(ref r) if r == &reason) {
                let _ = state_tx.send(SwitchState::Idle);
                *current_peer.lock().await = None;
                *current_mac.lock().await = None;
                let _ = persist::clear();
            }
        });
    }

    async fn start_responder(self: Arc<Self>, peer_pub: [u8; 32], mac: String) {
        match (self.acceptance)() {
            Acceptance::Reject(reason) => {
                if let Some(nonce) = next_out_nonce(&self.peer_store, peer_pub).await {
                    let _ = (self.sender)(
                        peer_pub,
                        AudioOpFrame { nonce, op: AudioOp::Reject { reason }, mac, ts: now_sec() },
                    )
                    .await;
                }
                return;
            }
            Acceptance::Allow => {}
        }
        if *self.state_rx.borrow() != SwitchState::Idle {
            if let Some(nonce) = next_out_nonce(&self.peer_store, peer_pub).await {
                let _ = (self.sender)(
                    peer_pub,
                    AudioOpFrame {
                        nonce,
                        op: AudioOp::Reject { reason: RejectReason::Busy },
                        mac,
                        ts: now_sec(),
                    },
                )
                .await;
            }
            return;
        }
        *self.responder.lock().await = Some(ActiveFlow { peer_pub, mac: mac.clone() });
        let me = self.clone();
        tokio::spawn(async move { me.run_responder(peer_pub, mac).await });
    }

    async fn run_responder(self: Arc<Self>, peer_pub: [u8; 32], mac: String) {
        let approve_self = self.clone();
        let approve_peer = peer_pub;
        let approve_mac = mac.clone();
        tokio::spawn(async move {
            if let Some(approve_nonce) =
                next_out_nonce(&approve_self.peer_store, approve_peer).await
            {
                let _ = (approve_self.sender)(
                    approve_peer,
                    AudioOpFrame {
                        nonce: approve_nonce,
                        op: AudioOp::Approve,
                        mac: approve_mac,
                        ts: now_sec(),
                    },
                )
                .await;
            }
        });

        match self.bt.disconnect_initiate(&mac).await {
            Ok(()) => match next_out_nonce(&self.peer_store, peer_pub).await {
                Some(released_nonce) => {
                    let _ = (self.sender)(
                        peer_pub,
                        AudioOpFrame {
                            nonce: released_nonce,
                            op: AudioOp::Released,
                            mac: mac.clone(),
                            ts: now_sec(),
                        },
                    )
                    .await;
                }
                None => warn!(
                    %mac,
                    "responder: nonce unavailable; Released NOT sent — phone connect will be delayed"
                ),
            },
            Err(e) => {
                if let Some(nonce) = next_out_nonce(&self.peer_store, peer_pub).await {
                    let _ = (self.sender)(
                        peer_pub,
                        AudioOpFrame {
                            nonce,
                            op: AudioOp::Failed { stage: stage_from(&e), message: e.to_string() },
                            mac: mac.clone(),
                            ts: now_sec(),
                        },
                    )
                    .await;
                }
                *self.responder.lock().await = None;
                return;
            }
        }

        let confirm_bt = self.bt.clone();
        let confirm_mac = mac.clone();
        tokio::spawn(async move {
            if !confirm_bt
                .confirm_disconnected(&confirm_mac, Duration::from_millis(DISCONNECT_TIMEOUT_MS))
                .await
            {
                tracing::warn!(%confirm_mac, "release confirm timed out (phone likely already grabbed the buds)");
            }
        });

        tokio::time::sleep(Duration::from_millis(DONE_WAIT_MS)).await;
        let mut g = self.responder.lock().await;
        if let Some(ref f) = *g {
            if f.matches(&peer_pub, &mac) {
                tracing::warn!("responder Done never arrived; freeing the slot (watchdog)");
                *g = None;
            }
        }
    }

    async fn on_done(&self, peer_pub: [u8; 32]) {
        let g = self.responder.lock().await;
        let Some(ref f) = *g else { return };
        if f.peer_pub != peer_pub {
            return;
        }
        drop(g);
        *self.responder.lock().await = None;
    }

    async fn on_peer_failed(&self, peer_pub: [u8; 32], _stage: Stage, message: String) {
        warn!(?peer_pub, %message, "peer reported failure");
        let g = self.initiator.lock().await;
        let same = g.as_ref().map(|f| f.peer_pub == peer_pub).unwrap_or(false);
        drop(g);
        if same {
            self.fail_initiator(format!("peer: {message}"));
        }
        *self.responder.lock().await = None;
    }

    async fn is_recent_duplicate(&self, mac: &str) -> bool {
        let recent = {
            let g = self.last_complete.lock().await;
            matches!(
                &*g,
                Some((m, t)) if m == mac
                    && t.elapsed() < Duration::from_millis(DUP_CLAIM_WINDOW_MS)
            )
        };
        if !recent {
            return false;
        }
        self.bt.is_connected(mac).await
    }

    fn arm_flow_watchdog(&self) {
        let mut rx = self.state_rx.clone();
        let state_tx = self.state_tx.clone();
        let initiator = self.initiator.clone();
        let current_peer = self.current_peer.clone();
        let current_mac = self.current_mac.clone();
        tokio::spawn(async move {
            let deadline = tokio::time::sleep(Duration::from_millis(FLOW_WATCHDOG_MS));
            tokio::pin!(deadline);
            loop {
                tokio::select! {
                    _ = &mut deadline => {
                        let s = rx.borrow().clone();
                        if matches!(
                            s,
                            SwitchState::Preparing
                                | SwitchState::Connecting
                                | SwitchState::AlmostDone
                        ) {
                            warn!(?s, "flow watchdog: in-flight too long; forcing Idle");
                            let _ = state_tx.send(SwitchState::Idle);
                            *initiator.lock().await = None;
                            *current_peer.lock().await = None;
                            *current_mac.lock().await = None;
                            let _ = persist::clear();
                        }
                        return;
                    }
                    changed = rx.changed() => {
                        if changed.is_err() {
                            return;
                        }
                        if matches!(
                            *rx.borrow(),
                            SwitchState::Idle | SwitchState::Failed(_)
                        ) {
                            return;
                        }
                    }
                }
            }
        });
    }

    fn cas_state(&self, from: SwitchState, to: SwitchState) -> bool {
        let mut changed = false;
        self.state_tx.send_if_modified(|current| {
            if *current == from {
                *current = to.clone();
                changed = true;
                true
            } else {
                false
            }
        });
        if changed {
            let peer = self.current_peer.try_lock().ok().and_then(|g| *g);
            let mac = self.current_mac.try_lock().ok().and_then(|g| g.clone());
            if to == SwitchState::Idle {
                let _ = persist::clear();
                if let Ok(mut g) = self.current_peer.try_lock() {
                    *g = None;
                }
                if let Ok(mut g) = self.current_mac.try_lock() {
                    *g = None;
                }
            } else {
                let saved = persist::Saved {
                    discriminator: discriminator_for(&to).to_string(),
                    reason: if let SwitchState::Failed(r) = &to { Some(r.clone()) } else { None },
                    enter_ms: now_ms(),
                    peer_pub_hex: peer.map(|p| persist::pub_to_hex(&p)).unwrap_or_default(),
                    mac: mac.unwrap_or_default(),
                };
                if let Err(e) = persist::save(&saved) {
                    warn!("audio_switch persist save failed: {e}");
                }
            }
        }
        changed
    }
}

async fn next_out_nonce(peer_store: &Arc<dyn PeerStore>, peer_pub: [u8; 32]) -> Option<u64> {
    let ps = peer_store.clone();
    match tokio::task::spawn_blocking(move || ps.next_audio_out_nonce(&peer_pub)).await {
        Ok(Ok(n)) => Some(n),
        Ok(Err(e)) => {
            warn!("audio nonce store error: {e}; dropping outbound frame");
            None
        }
        Err(e) => {
            warn!("audio nonce task join error: {e}; dropping outbound frame");
            None
        }
    }
}

fn stage_from(e: &SwitchError) -> Stage {
    match e {
        SwitchError::Bluer(_) | SwitchError::Internal(_) => Stage::Connect,
        SwitchError::Timeout(_) => Stage::WaitReady,
        SwitchError::BadAddress(_) | SwitchError::NotPaired => Stage::Disconnect,
    }
}

fn now_sec() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn discriminator_for(s: &SwitchState) -> &'static str {
    match s {
        SwitchState::Idle => persist::disc::IDLE,
        SwitchState::Preparing => persist::disc::PREPARING,
        SwitchState::WaitingApproval => persist::disc::WAITING_APPROVAL,
        SwitchState::WaitingReleased => persist::disc::WAITING_RELEASED,
        SwitchState::Connecting => persist::disc::CONNECTING,
        SwitchState::AlmostDone => persist::disc::CONNECTING,
        SwitchState::Failed(_) => persist::disc::FAILED,
    }
}

const CONNECT_RETRY_COUNT: u32 = 2;
const CONNECT_RETRY_PAUSE_MS: u64 = 280;
const DONE_WAIT_MS: u64 = 4_000;
const DISCONNECT_TIMEOUT_MS: u64 = 1_000;
const FAILED_RESET_MS: u64 = 3_000;
const FLOW_WATCHDOG_MS: u64 = 14_000;
const DUP_CLAIM_WINDOW_MS: u64 = 4_000;

#[cfg(test)]
#[allow(clippy::await_holding_lock)]
mod tests {

    use super::*;
    use crate::core::audio_op::AudioOp;
    use crate::core::storage::peers::TrustedPeer;
    use crate::core::storage::StorageError;
    use std::collections::{HashMap, VecDeque};
    use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
    use std::sync::Mutex as StdMutex;

    const PEER: [u8; 32] = [7u8; 32];
    const MAC: &str = "AC:47:1B:25:71:C2";

    #[derive(Clone)]
    enum Outcome {
        Ok,
        Fail(String),
        Hang,
    }

    struct FakeBt {
        connected: bool,
        is_connected_calls: StdMutex<Vec<String>>,
        connect_script: StdMutex<VecDeque<Outcome>>,
        connect_fallback: Outcome,
        connect_calls: AtomicU32,
        disconnect_ok: bool,
    }

    impl FakeBt {
        fn new(connected: bool) -> Self {
            Self {
                connected,
                is_connected_calls: StdMutex::new(Vec::new()),
                connect_script: StdMutex::new(VecDeque::new()),
                connect_fallback: Outcome::Ok,
                connect_calls: AtomicU32::new(0),
                disconnect_ok: true,
            }
        }
        fn with_connect(mut self, o: Outcome) -> Self {
            self.connect_fallback = o;
            self
        }
        fn with_connect_script(self, outs: impl IntoIterator<Item = Outcome>) -> Self {
            *self.connect_script.lock().unwrap() = outs.into_iter().collect();
            self
        }
        fn connects(&self) -> u32 {
            self.connect_calls.load(Ordering::SeqCst)
        }
    }

    impl BtControl for FakeBt {
        fn connect<'a>(&'a self, _mac: &'a str) -> BoxFuture<'a, Result<(), SwitchError>> {
            self.connect_calls.fetch_add(1, Ordering::SeqCst);
            let outcome = self
                .connect_script
                .lock()
                .unwrap()
                .pop_front()
                .unwrap_or_else(|| self.connect_fallback.clone());
            Box::pin(async move {
                match outcome {
                    Outcome::Ok => Ok(()),
                    Outcome::Fail(m) => Err(SwitchError::Internal(m)),
                    Outcome::Hang => std::future::pending().await,
                }
            })
        }
        fn disconnect_initiate<'a>(
            &'a self,
            _mac: &'a str,
        ) -> BoxFuture<'a, Result<(), SwitchError>> {
            let ok = self.disconnect_ok;
            Box::pin(async move {
                if ok {
                    Ok(())
                } else {
                    Err(SwitchError::Internal("disconnect failed".into()))
                }
            })
        }
        fn confirm_disconnected<'a>(&'a self, _mac: &'a str, _t: Duration) -> BoxFuture<'a, bool> {
            Box::pin(async { true })
        }
        fn is_connected<'a>(&'a self, mac: &'a str) -> BoxFuture<'a, bool> {
            self.is_connected_calls.lock().unwrap().push(mac.to_string());
            let connected = self.connected;
            Box::pin(async move { connected })
        }
    }

    struct FakePeerStore {
        out_nonce: AtomicU64,
        seen_in: StdMutex<HashMap<[u8; 32], u64>>,
        out_fails: AtomicBool,
    }

    impl FakePeerStore {
        fn new() -> Self {
            Self {
                out_nonce: AtomicU64::new(0),
                seen_in: StdMutex::new(HashMap::new()),
                out_fails: AtomicBool::new(false),
            }
        }
    }

    impl PeerStore for FakePeerStore {
        fn save(&self, _peer: &TrustedPeer) -> Result<(), StorageError> {
            Ok(())
        }
        fn load(&self, _p: &[u8; 32]) -> Result<TrustedPeer, StorageError> {
            Err(StorageError::NotFound)
        }
        fn list(&self) -> Result<Vec<TrustedPeer>, StorageError> {
            Ok(Vec::new())
        }
        fn forget(&self, _p: &[u8; 32]) -> Result<(), StorageError> {
            Ok(())
        }
        fn next_audio_out_nonce(&self, _p: &[u8; 32]) -> Result<u64, StorageError> {
            if self.out_fails.load(Ordering::SeqCst) {
                return Err(StorageError::Backend("forced nonce failure".into()));
            }
            Ok(self.out_nonce.fetch_add(1, Ordering::SeqCst) + 1)
        }
        fn load_audio_in_nonce(&self, p: &[u8; 32]) -> Result<u64, StorageError> {
            Ok(*self.seen_in.lock().unwrap().get(p).unwrap_or(&0))
        }
        fn commit_audio_in_nonce(&self, p: &[u8; 32], nonce: u64) -> Result<(), StorageError> {
            let mut m = self.seen_in.lock().unwrap();
            let e = m.entry(*p).or_insert(0);
            if nonce > *e {
                *e = nonce;
            }
            Ok(())
        }
    }

    type Captured = Arc<StdMutex<Vec<AudioOpFrame>>>;

    fn make_orch(bt: Arc<FakeBt>) -> (Arc<SwitchOrchestrator>, Captured) {
        let (orch, cap, _store) = make_orch_full(bt, Arc::new(FakePeerStore::new()), allow());
        (orch, cap)
    }

    fn make_orch_full(
        bt: Arc<FakeBt>,
        store: Arc<FakePeerStore>,
        acceptance: AcceptanceProvider,
    ) -> (Arc<SwitchOrchestrator>, Captured, Arc<FakePeerStore>) {
        let captured: Captured = Arc::new(StdMutex::new(Vec::new()));
        let cap = captured.clone();
        let sender: Sender = Arc::new(move |_peer: [u8; 32], frame: AudioOpFrame| {
            let cap = cap.clone();
            Box::pin(async move {
                cap.lock().unwrap().push(frame);
                Ok(())
            })
        });
        let orch = Arc::new(SwitchOrchestrator::new(bt, store.clone(), sender, acceptance));
        (orch, captured, store)
    }

    fn allow() -> AcceptanceProvider {
        Arc::new(|| Acceptance::Allow)
    }
    fn reject(reason: RejectReason) -> AcceptanceProvider {
        Arc::new(move || Acceptance::Reject(reason))
    }

    fn frame(nonce: u64, op: AudioOp) -> AudioOpFrame {
        AudioOpFrame { nonce, op, mac: MAC.to_string(), ts: 0 }
    }

    fn isolate_persist() -> std::sync::MutexGuard<'static, ()> {
        static ENV_LOCK: StdMutex<()> = StdMutex::new(());
        let g = ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = std::env::temp_dir().join("vortex_orch_test_state");
        let _ = std::fs::create_dir_all(&dir);
        std::env::set_var("XDG_STATE_HOME", &dir);
        let _ = persist::clear();
        g
    }

    async fn wait_state(
        rx: &mut watch::Receiver<SwitchState>,
        pred: impl FnMut(&SwitchState) -> bool,
    ) -> SwitchState {
        tokio::time::timeout(Duration::from_secs(60), rx.wait_for(pred))
            .await
            .expect("timed out waiting for state")
            .expect("state channel closed")
            .clone()
    }

    async fn wait_frame(cap: &Captured, pred: impl Fn(&AudioOpFrame) -> bool) -> AudioOpFrame {
        let fut = async {
            loop {
                let found = cap.lock().unwrap().iter().find(|f| pred(f)).cloned();
                if let Some(f) = found {
                    return f;
                }
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        };
        tokio::time::timeout(Duration::from_secs(60), fut)
            .await
            .expect("timed out waiting for frame")
    }

    fn count_frames(cap: &Captured, pred: impl Fn(&AudioOpFrame) -> bool) -> usize {
        cap.lock().unwrap().iter().filter(|f| pred(f)).count()
    }

    #[tokio::test]
    async fn recent_reclaim_with_buds_still_ours_is_a_duplicate() {
        let bt = Arc::new(FakeBt::new(true));
        let (orch, _cap) = make_orch(bt.clone());
        *orch.last_complete.lock().await = Some((MAC.to_string(), tokio::time::Instant::now()));
        assert!(orch.is_recent_duplicate(MAC).await);
        assert_eq!(bt.is_connected_calls.lock().unwrap().as_slice(), &[MAC.to_string()]);
    }

    #[tokio::test]
    async fn recent_reclaim_after_peer_grabbed_back_is_not_a_duplicate() {
        let bt = Arc::new(FakeBt::new(false));
        let (orch, _cap) = make_orch(bt.clone());
        *orch.last_complete.lock().await = Some((MAC.to_string(), tokio::time::Instant::now()));
        assert!(!orch.is_recent_duplicate(MAC).await);
        assert_eq!(bt.is_connected_calls.lock().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn no_prior_completion_is_never_a_duplicate() {
        let bt = Arc::new(FakeBt::new(true));
        let (orch, _cap) = make_orch(bt.clone());
        assert!(!orch.is_recent_duplicate(MAC).await);
        assert!(bt.is_connected_calls.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn different_mac_is_not_a_duplicate() {
        let bt = Arc::new(FakeBt::new(true));
        let (orch, _cap) = make_orch(bt.clone());
        *orch.last_complete.lock().await =
            Some(("00:11:22:33:44:55".to_string(), tokio::time::Instant::now()));
        assert!(!orch.is_recent_duplicate(MAC).await);
        assert!(bt.is_connected_calls.lock().unwrap().is_empty());
    }

    #[tokio::test(start_paused = true)]
    async fn reclaim_after_window_expires_is_not_a_duplicate() {
        let bt = Arc::new(FakeBt::new(true));
        let (orch, _cap) = make_orch(bt.clone());
        *orch.last_complete.lock().await = Some((MAC.to_string(), tokio::time::Instant::now()));
        tokio::time::advance(Duration::from_millis(DUP_CLAIM_WINDOW_MS + 1)).await;
        assert!(!orch.is_recent_duplicate(MAC).await);
        assert!(bt.is_connected_calls.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn duplicate_request_acks_with_done_and_stays_idle() {
        let bt = Arc::new(FakeBt::new(true));
        let (orch, captured) = make_orch(bt.clone());
        *orch.last_complete.lock().await = Some((MAC.to_string(), tokio::time::Instant::now()));

        let r = orch.request(PEER, MAC.to_string()).await;
        assert!(r.is_ok());
        assert_eq!(*orch.state_rx.borrow(), SwitchState::Idle);
        let frames = captured.lock().unwrap();
        assert_eq!(frames.len(), 1);
        assert!(matches!(frames[0].op, AudioOp::Done));
        assert_eq!(frames[0].mac, MAC);
    }

    #[tokio::test(start_paused = true)]
    async fn initiator_happy_path_completes_and_sends_request_then_done() {
        let _env = isolate_persist();
        let bt = Arc::new(FakeBt::new(false));
        let (orch, cap) = make_orch(bt.clone());
        let mut rx = orch.state();

        orch.request(PEER, MAC.to_string()).await.unwrap();
        wait_state(&mut rx, |s| *s == SwitchState::Idle).await;

        assert_eq!(bt.connects(), 1, "happy path connects exactly once");
        wait_frame(&cap, |f| matches!(f.op, AudioOp::Request)).await;
        let done = wait_frame(&cap, |f| matches!(f.op, AudioOp::Done)).await;
        assert!(done.nonce >= 1, "Done must carry a non-zero (replay-safe) nonce");
    }

    #[tokio::test(start_paused = true)]
    async fn initiator_retries_once_then_succeeds() {
        let _env = isolate_persist();
        let bt = Arc::new(
            FakeBt::new(false)
                .with_connect_script([Outcome::Fail("phone still holds".into()), Outcome::Ok]),
        );
        let (orch, cap) = make_orch(bt.clone());
        let mut rx = orch.state();

        orch.request(PEER, MAC.to_string()).await.unwrap();
        wait_state(&mut rx, |s| *s == SwitchState::Idle).await;

        assert_eq!(bt.connects(), 2, "first attempt fails, second wins");
        wait_frame(&cap, |f| matches!(f.op, AudioOp::Done)).await;
    }

    #[tokio::test(start_paused = true)]
    async fn initiator_exhausts_retries_then_fails_then_resets_to_idle() {
        let _env = isolate_persist();
        let bt = Arc::new(FakeBt::new(false).with_connect(Outcome::Fail("never connects".into())));
        let (orch, cap) = make_orch(bt.clone());
        let mut rx = orch.state();

        orch.request(PEER, MAC.to_string()).await.unwrap();
        wait_state(&mut rx, |s| matches!(s, SwitchState::Failed(_))).await;
        assert_eq!(bt.connects(), CONNECT_RETRY_COUNT, "all retries consumed");
        wait_frame(&cap, |f| matches!(f.op, AudioOp::Failed { .. })).await;

        wait_state(&mut rx, |s| *s == SwitchState::Idle).await;
    }

    #[tokio::test(start_paused = true)]
    async fn initiator_peer_reject_transitions_to_failed() {
        let _env = isolate_persist();
        let bt = Arc::new(FakeBt::new(false).with_connect(Outcome::Hang));
        let (orch, _cap) = make_orch(bt.clone());
        let mut rx = orch.state();

        orch.request(PEER, MAC.to_string()).await.unwrap();
        wait_state(&mut rx, |s| *s == SwitchState::Connecting).await;
        orch.clone()
            .on_incoming(PEER, frame(1, AudioOp::Reject { reason: RejectReason::InCall }))
            .await
            .unwrap();

        let st = wait_state(&mut rx, |s| matches!(s, SwitchState::Failed(_))).await;
        if let SwitchState::Failed(r) = st {
            assert!(r.contains("rejected"), "reason should mention the peer reject: {r}");
        }
    }

    #[tokio::test(start_paused = true)]
    async fn released_moves_connecting_to_almost_done() {
        let _env = isolate_persist();
        let bt = Arc::new(FakeBt::new(false).with_connect(Outcome::Hang));
        let (orch, _cap) = make_orch(bt.clone());
        let mut rx = orch.state();

        orch.request(PEER, MAC.to_string()).await.unwrap();
        wait_state(&mut rx, |s| *s == SwitchState::Connecting).await;
        orch.clone().on_incoming(PEER, frame(1, AudioOp::Released)).await.unwrap();

        wait_state(&mut rx, |s| *s == SwitchState::AlmostDone).await;
    }

    #[tokio::test(start_paused = true)]
    async fn initiator_fails_loud_when_nonce_store_errors() {
        let _env = isolate_persist();
        let bt = Arc::new(FakeBt::new(false));
        let store = Arc::new(FakePeerStore::new());
        store.out_fails.store(true, Ordering::SeqCst);
        let (orch, _cap, _store) = make_orch_full(bt.clone(), store, allow());
        let mut rx = orch.state();

        orch.request(PEER, MAC.to_string()).await.unwrap();
        let st = wait_state(&mut rx, |s| matches!(s, SwitchState::Failed(_))).await;
        if let SwitchState::Failed(r) = st {
            assert!(r.contains("nonce"), "fail-loud reason should mention the nonce store: {r}");
        }
        assert_eq!(bt.connects(), 0, "must not attempt a BT connect without a valid nonce");
    }

    #[tokio::test(start_paused = true)]
    async fn flow_watchdog_forces_idle_on_wedge() {
        let _env = isolate_persist();
        let bt = Arc::new(FakeBt::new(false).with_connect(Outcome::Hang));
        let (orch, _cap) = make_orch(bt.clone());
        let mut rx = orch.state();

        orch.request(PEER, MAC.to_string()).await.unwrap();
        wait_state(&mut rx, |s| *s == SwitchState::Connecting).await;
        let st = wait_state(&mut rx, |s| *s == SwitchState::Idle).await;
        assert_eq!(st, SwitchState::Idle);
    }

    #[tokio::test(start_paused = true)]
    async fn responder_allow_sends_approve_and_released() {
        let _env = isolate_persist();
        let bt = Arc::new(FakeBt::new(false));
        let (orch, cap) = make_orch(bt.clone());

        orch.clone().on_incoming(PEER, frame(1, AudioOp::Request)).await.unwrap();

        let approve = wait_frame(&cap, |f| matches!(f.op, AudioOp::Approve)).await;
        let released = wait_frame(&cap, |f| matches!(f.op, AudioOp::Released)).await;
        assert!(approve.nonce >= 1 && released.nonce >= 1);
        assert_ne!(approve.nonce, released.nonce, "each outbound frame gets a fresh nonce");
    }

    #[tokio::test(start_paused = true)]
    async fn responder_reject_when_acceptance_denies() {
        let _env = isolate_persist();
        let bt = Arc::new(FakeBt::new(false));
        let (orch, cap, _store) = make_orch_full(
            bt.clone(),
            Arc::new(FakePeerStore::new()),
            reject(RejectReason::InCall),
        );

        orch.clone().on_incoming(PEER, frame(1, AudioOp::Request)).await.unwrap();

        let r = wait_frame(&cap, |f| matches!(f.op, AudioOp::Reject { .. })).await;
        assert!(matches!(r.op, AudioOp::Reject { reason: RejectReason::InCall }));
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert_eq!(
            count_frames(&cap, |f| matches!(f.op, AudioOp::Released)),
            0,
            "a rejected request must never release the buds"
        );
    }

    #[tokio::test(start_paused = true)]
    async fn responder_rejects_busy_when_a_flow_is_active() {
        let _env = isolate_persist();
        let bt = Arc::new(FakeBt::new(false).with_connect(Outcome::Hang));
        let (orch, cap) = make_orch(bt.clone());
        let mut rx = orch.state();

        orch.request(PEER, MAC.to_string()).await.unwrap();
        wait_state(&mut rx, |s| *s == SwitchState::Connecting).await;
        orch.clone().on_incoming(PEER, frame(1, AudioOp::Request)).await.unwrap();

        let r =
            wait_frame(&cap, |f| matches!(f.op, AudioOp::Reject { reason: RejectReason::Busy }))
                .await;
        assert!(matches!(r.op, AudioOp::Reject { reason: RejectReason::Busy }));
    }

    #[tokio::test(start_paused = true)]
    async fn replayed_nonce_is_dropped() {
        let _env = isolate_persist();
        let bt = Arc::new(FakeBt::new(false));
        let (orch, cap) = make_orch(bt.clone());

        orch.clone().on_incoming(PEER, frame(5, AudioOp::Request)).await.unwrap();
        wait_frame(&cap, |f| matches!(f.op, AudioOp::Approve)).await;

        orch.clone().on_incoming(PEER, frame(5, AudioOp::Request)).await.unwrap();
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert_eq!(
            count_frames(&cap, |f| matches!(f.op, AudioOp::Approve)),
            1,
            "a replayed frame must not trigger a second responder run"
        );
    }

    #[tokio::test(start_paused = true)]
    async fn recover_resumes_fresh_connecting_and_completes() {
        let _env = isolate_persist();
        let saved = persist::Saved {
            discriminator: persist::disc::CONNECTING.to_string(),
            reason: None,
            enter_ms: now_ms(),
            peer_pub_hex: persist::pub_to_hex(&PEER),
            mac: MAC.to_string(),
        };
        persist::save(&saved).unwrap();

        let bt = Arc::new(FakeBt::new(false));
        let (orch, cap) = make_orch(bt.clone());
        let mut rx = orch.state();

        orch.recover_on_start().await;
        wait_state(&mut rx, |s| *s == SwitchState::Idle).await;
        assert_eq!(bt.connects(), 1, "resume drives exactly one connect attempt");
        wait_frame(&cap, |f| matches!(f.op, AudioOp::Done)).await;
    }
}

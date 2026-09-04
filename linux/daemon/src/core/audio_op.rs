use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum AudioOp {
    Request,
    Claim,
    Approve,
    Reject { reason: RejectReason },
    Released,
    Done,
    Failed { stage: Stage, message: String },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RejectReason {
    Busy,
    InCall,
    RecentSwitch,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Stage {
    Disconnect,
    Connect,
    WaitReady,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AudioOpFrame {
    pub nonce: u64,
    pub op: AudioOp,
    pub mac: String,
    pub ts: u64,
}

impl AudioOpFrame {
    pub fn to_json(&self) -> Result<Vec<u8>, serde_json::Error> {
        serde_json::to_vec(self)
    }

    pub fn from_json(bytes: &[u8]) -> Result<Self, serde_json::Error> {
        serde_json::from_slice(bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_request() {
        let f = AudioOpFrame {
            nonce: 42,
            op: AudioOp::Request,
            mac: "AC:47:1B:25:71:C2".to_string(),
            ts: 1_700_000_000,
        };
        let bytes = f.to_json().unwrap();
        let back = AudioOpFrame::from_json(&bytes).unwrap();
        assert_eq!(back.nonce, 42);
        assert!(matches!(back.op, AudioOp::Request));
        assert_eq!(back.mac, f.mac);
    }

    #[test]
    fn roundtrip_reject_with_reason() {
        let f = AudioOpFrame {
            nonce: 100,
            op: AudioOp::Reject { reason: RejectReason::InCall },
            mac: "AA:BB:CC:DD:EE:FF".to_string(),
            ts: 0,
        };
        let bytes = f.to_json().unwrap();
        let back = AudioOpFrame::from_json(&bytes).unwrap();
        match back.op {
            AudioOp::Reject { reason } => assert_eq!(reason, RejectReason::InCall),
            _ => panic!("expected Reject"),
        }
    }

    #[test]
    fn roundtrip_failed() {
        let f = AudioOpFrame {
            nonce: 7,
            op: AudioOp::Failed { stage: Stage::Connect, message: "A2DP profile refused".into() },
            mac: "AA:BB:CC:DD:EE:FF".to_string(),
            ts: 1,
        };
        let bytes = f.to_json().unwrap();
        let back = AudioOpFrame::from_json(&bytes).unwrap();
        match back.op {
            AudioOp::Failed { stage, ref message } => {
                assert_eq!(stage, Stage::Connect);
                assert_eq!(message, "A2DP profile refused");
            }
            _ => panic!("expected Failed"),
        }
    }

    #[test]
    fn unknown_reject_reason_deserializes_to_unknown_or_error() {
        let bad =
            br#"{"nonce":1,"op":{"kind":"reject","reason":"future_reason"},"mac":"x","ts":0}"#;
        let r = AudioOpFrame::from_json(bad);
        assert!(r.is_err(), "unknown reason should error in V1");
    }
}

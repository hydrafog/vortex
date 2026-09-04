use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CallEvent {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub phase: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub number: String,
    #[serde(default)]
    pub started_at: i64,
    #[serde(default)]
    pub outgoing: bool,
    #[serde(default)]
    pub connected: bool,
    #[serde(default)]
    pub app_id: String,
    #[serde(default)]
    pub sent_at: i64,
    #[serde(default)]
    pub muted: bool,
    #[serde(default)]
    pub speaker: bool,
    #[serde(default)]
    pub has_earbuds: bool,
}

impl CallEvent {
    pub const PHASE_RINGING: &'static str = "ringing";
    pub const PHASE_ACTIVE: &'static str = "active";
    pub const PHASE_ENDED: &'static str = "ended";

    pub fn from_json(bytes: &[u8]) -> Option<Self> {
        let ev: CallEvent = serde_json::from_slice(bytes).ok()?;
        if ev.id.is_empty() || ev.phase.is_empty() {
            return None;
        }
        Some(ev)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CallControl {
    pub id: String,
    pub action: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub arg: String,
    #[serde(default, skip_serializing_if = "is_zero_i64")]
    pub seq: i64,
}

fn is_zero_i64(v: &i64) -> bool {
    *v == 0
}

impl CallControl {
    pub fn to_json(&self) -> Vec<u8> {
        serde_json::to_vec(self).unwrap_or_default()
    }
}

pub mod action {
    pub const ACCEPT: &str = "accept";
    pub const DECLINE: &str = "decline";
    pub const END: &str = "end";
    pub const MUTE: &str = "mute";
    pub const UNMUTE: &str = "unmute";
    pub const SPEAKER_ON: &str = "speaker_on";
    pub const SPEAKER_OFF: &str = "speaker_off";
    pub const SILENCE: &str = "silence";
    pub const SMS_REJECT: &str = "sms_reject";
    pub const ORIGINATE_CALL: &str = "originate_call";
    pub const SEND_SMS: &str = "send_sms";
    pub const MARK_READ: &str = "mark_read";
    pub const LOAD_THREAD: &str = "load_thread";
}

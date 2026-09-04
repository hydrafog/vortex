use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct NotificationMirror {
    #[serde(default)]
    pub app: String,
    #[serde(default)]
    pub app_id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub ts: u64,
    #[serde(default)]
    pub key: String,
    #[serde(default)]
    pub dismiss: bool,
    #[serde(default)]
    pub actions: Vec<String>,
    #[serde(default = "neg_one")]
    pub reply_index: i32,
    #[serde(default = "neg_one")]
    pub invoke_index: i32,
    #[serde(default)]
    pub reply: String,
    #[serde(default)]
    pub seq: u64,
    #[serde(default)]
    pub resync: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub known_keys: Vec<String>,
}

fn neg_one() -> i32 {
    -1
}

impl Default for NotificationMirror {
    fn default() -> Self {
        Self {
            app: String::new(),
            app_id: String::new(),
            title: String::new(),
            text: String::new(),
            ts: 0,
            key: String::new(),
            dismiss: false,
            actions: Vec::new(),
            reply_index: -1,
            invoke_index: -1,
            reply: String::new(),
            seq: 0,
            resync: false,
            known_keys: Vec::new(),
        }
    }
}

impl NotificationMirror {
    pub fn catch_up_signal() -> Self {
        Self { resync: true, ..Self::default() }
    }
}

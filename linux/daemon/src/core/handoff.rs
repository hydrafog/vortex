use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct HandoffEvent {
    pub url: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub app_id: String,
    #[serde(default)]
    pub open_now: bool,
}

impl HandoffEvent {
    pub fn from_json(bytes: &[u8]) -> Option<Self> {
        serde_json::from_slice(bytes).ok()
    }

    pub fn to_json(&self) -> Vec<u8> {
        serde_json::to_vec(self).unwrap_or_default()
    }
}

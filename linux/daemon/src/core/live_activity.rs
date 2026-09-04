use serde::{Deserialize, Serialize};

fn neg_one() -> i32 {
    -1
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LiveActivity {
    #[serde(default)]
    pub key: String,
    #[serde(default)]
    pub app: String,
    #[serde(default)]
    pub app_id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub sub: String,
    #[serde(default = "neg_one")]
    pub progress: i32,
    #[serde(default)]
    pub started_at: i64,
    #[serde(default)]
    pub muted: bool,
    #[serde(default)]
    pub speaker: bool,
    #[serde(default)]
    pub has_earbuds: bool,
    #[serde(default)]
    pub ended: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub playing: Option<bool>,
}

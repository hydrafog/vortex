use super::kdf::hmac_sha256;

pub const PRESENCE_LABEL: &[u8] = b"vortex/v1/presence";
pub const PRESENCE_TOKEN_LEN: usize = 8;

pub fn derive_presence_token(prs: &[u8], bucket: u64) -> [u8; PRESENCE_TOKEN_LEN] {
    let mut data = Vec::with_capacity(PRESENCE_LABEL.len() + 8);
    data.extend_from_slice(PRESENCE_LABEL);
    data.extend_from_slice(&bucket.to_be_bytes());
    let token32 = hmac_sha256(prs, &data);
    let mut out = [0u8; PRESENCE_TOKEN_LEN];
    out.copy_from_slice(&token32[..PRESENCE_TOKEN_LEN]);
    out
}

pub fn current_bucket(unix_seconds: u64, rotation_window_seconds: u64) -> u64 {
    assert!(rotation_window_seconds > 0, "rotation window must be > 0");
    unix_seconds / rotation_window_seconds
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adjacent_buckets_differ() {
        let prs = [0x55u8; 32];
        let a = derive_presence_token(&prs, 1000);
        let b = derive_presence_token(&prs, 1001);
        assert_ne!(a, b);
    }

    #[test]
    fn different_prs_differ() {
        let bucket = 1234u64;
        let a = derive_presence_token(&[0u8; 32], bucket);
        let b = derive_presence_token(&[1u8; 32], bucket);
        assert_ne!(a, b);
    }

    #[test]
    fn bucket_arithmetic() {
        assert_eq!(current_bucket(0, 60), 0);
        assert_eq!(current_bucket(59, 60), 0);
        assert_eq!(current_bucket(60, 60), 1);
        assert_eq!(current_bucket(125, 60), 2);
    }
}

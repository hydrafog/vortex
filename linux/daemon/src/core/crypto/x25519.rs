use x25519_dalek::{PublicKey, StaticSecret};

pub type X25519SecBytes = [u8; 32];
pub type X25519PubBytes = [u8; 32];

#[derive(Clone)]
pub struct X25519Sec(pub X25519SecBytes);

impl Drop for X25519Sec {
    fn drop(&mut self) {
        for b in self.0.iter_mut() {
            unsafe { std::ptr::write_volatile(b, 0) };
        }
    }
}

impl std::fmt::Debug for X25519Sec {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "X25519Sec(<redacted>)")
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct X25519Pub(pub X25519PubBytes);

pub fn public_from_private(private: &X25519SecBytes) -> X25519PubBytes {
    let sk = StaticSecret::from(*private);
    PublicKey::from(&sk).to_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc_7748_alice() {
        let priv_bytes: [u8; 32] = [
            0x77, 0x07, 0x6d, 0x0a, 0x73, 0x18, 0xa5, 0x7d, 0x3c, 0x16, 0xc1, 0x72, 0x51, 0xb2,
            0x66, 0x45, 0xdf, 0x4c, 0x2f, 0x87, 0xeb, 0xc0, 0x99, 0x2a, 0xb1, 0x77, 0xfb, 0xa5,
            0x1d, 0xb9, 0x2c, 0x2a,
        ];
        let pub_expected: [u8; 32] = [
            0x85, 0x20, 0xf0, 0x09, 0x89, 0x30, 0xa7, 0x54, 0x74, 0x8b, 0x7d, 0xdc, 0xb4, 0x3e,
            0xf7, 0x5a, 0x0d, 0xbf, 0x3a, 0x0d, 0x26, 0x38, 0x1a, 0xf4, 0xeb, 0xa4, 0xa9, 0x8e,
            0xaa, 0x9b, 0x4e, 0x6a,
        ];
        assert_eq!(public_from_private(&priv_bytes), pub_expected);
    }

    #[test]
    fn debug_redacts_private() {
        let s = X25519Sec([0xAA; 32]);
        let formatted = format!("{:?}", s);
        assert!(formatted.contains("redacted"));
        assert!(!formatted.contains("aa"));
    }
}

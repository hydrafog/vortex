pub const FRAME_HEADER_LEN: usize = 4;

pub const MAX_FRAME_PAYLOAD: usize = 63 * 1024;

pub mod ty {
    pub const PAIRING_HANDSHAKE: u8 = 0x10;
    pub const PAIRING_APPROVAL: u8 = 0x11;
    pub const PAIRING_TRUSTED_INFO: u8 = 0x12;
    pub const RECONNECT_HANDSHAKE: u8 = 0x20;
    #[allow(dead_code)]
    pub const LAN_JOIN_PROOF_RESERVED_V2: u8 = 0x21;
    pub const TRANSPORT_KEEPALIVE: u8 = 0x30;
    pub const TRANSPORT_APP_DATA: u8 = 0x31;
    pub const AUDIO_OP: u8 = 0x32;
    pub const STATE: u8 = 0x33;
    pub const NOTIFICATION: u8 = 0x34;
    pub const LIVE_ACTIVITY: u8 = 0x35;
    pub const ICON: u8 = 0x36;
    pub const CALL: u8 = 0x37;
    pub const CALL_CONTROL: u8 = 0x38;
    pub const CONTACTS: u8 = 0x39;
    pub const CALL_LOG: u8 = 0x3A;
    pub const SMS: u8 = 0x3B;
    pub const SMS_THREAD: u8 = 0x3C;
    pub const BULK_SYNC: u8 = 0x3D;
    pub const CALL_LOG_HISTORY: u8 = 0x3E;
    pub const SMS_IDS: u8 = 0x3F;
    pub const CLIPBOARD: u8 = 0x40;
    pub const CLIPBOARD_IMAGE: u8 = 0x41;
    pub const CLIPBOARD_IMAGE_OFFER: u8 = 0x42;
    pub const CLIPBOARD_TEXT: u8 = 0x43;
    pub const CLIPBOARD_FILE: u8 = 0x45;
    pub const WIFI_DIRECT_OFFER: u8 = 0x46;
    pub const HANDOFF: u8 = 0x4C;
    pub const FILE_PUSH_OFFER: u8 = 0x49;
    pub const FILE_PUSH: u8 = 0x4A;
    pub const FILE_PUSH_DECISION: u8 = 0x4B;
    /// — converges in ≤2 rounds. Mirrors Kotlin `FrameType.NOTES_SYNC`.
    pub const NOTES_SYNC: u8 = 0x4D;
    pub const FRAG: u8 = 0x4E;
    pub const ERROR: u8 = 0x7F;
}

pub mod sub {
    pub const PING: u8 = 0x01;
    pub const PONG: u8 = 0x02;
    pub const ECHO_REQUEST: u8 = 0x01;
    pub const ECHO_RESPONSE: u8 = 0x02;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub ty: u8,
    pub sub: u8,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrameDecodeError {
    Short(usize),
    LengthTooLarge(usize),
    LengthMismatch { declared: usize, actual: usize },
}

impl std::fmt::Display for FrameDecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Short(n) => write!(f, "frame too short: {n} bytes"),
            Self::LengthTooLarge(n) => write!(f, "declared length {n} exceeds max"),
            Self::LengthMismatch { declared, actual } => {
                write!(f, "length mismatch: declared {declared}, actual {actual}")
            }
        }
    }
}

impl std::error::Error for FrameDecodeError {}

impl Frame {
    pub fn new(ty: u8, sub: u8, payload: Vec<u8>) -> Self {
        Self { ty, sub, payload }
    }

    pub fn echo_request(payload: Vec<u8>) -> Self {
        Self::new(ty::TRANSPORT_KEEPALIVE, sub::ECHO_REQUEST, payload)
    }

    pub fn echo_response(payload: Vec<u8>) -> Self {
        Self::new(ty::TRANSPORT_KEEPALIVE, sub::ECHO_RESPONSE, payload)
    }

    pub fn encode(&self) -> Vec<u8> {
        assert!(self.payload.len() <= MAX_FRAME_PAYLOAD);
        let mut out = Vec::with_capacity(FRAME_HEADER_LEN + self.payload.len());
        out.push(self.ty);
        out.push(self.sub);
        out.extend_from_slice(&(self.payload.len() as u16).to_be_bytes());
        out.extend_from_slice(&self.payload);
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, FrameDecodeError> {
        if bytes.len() < FRAME_HEADER_LEN {
            return Err(FrameDecodeError::Short(bytes.len()));
        }
        let length = u16::from_be_bytes([bytes[2], bytes[3]]) as usize;
        if length > MAX_FRAME_PAYLOAD {
            return Err(FrameDecodeError::LengthTooLarge(length));
        }
        let total = FRAME_HEADER_LEN + length;
        if bytes.len() != total {
            return Err(FrameDecodeError::LengthMismatch { declared: total, actual: bytes.len() });
        }
        Ok(Self { ty: bytes[0], sub: bytes[1], payload: bytes[FRAME_HEADER_LEN..].to_vec() })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_payload_round_trip() {
        let f = Frame::new(0x11, 0x01, vec![]);
        let bytes = f.encode();
        assert_eq!(bytes, vec![0x11, 0x01, 0x00, 0x00]);
        assert_eq!(Frame::decode(&bytes).unwrap(), f);
    }

    #[test]
    fn small_payload_round_trip() {
        let f = Frame::echo_request(vec![0xAA, 0xBB, 0xCC]);
        let bytes = f.encode();
        assert_eq!(bytes, vec![0x30, 0x01, 0x00, 0x03, 0xAA, 0xBB, 0xCC]);
        assert_eq!(Frame::decode(&bytes).unwrap(), f);
    }

    #[test]
    fn rejects_short_header() {
        assert!(matches!(Frame::decode(&[0x10, 0x01]), Err(FrameDecodeError::Short(2))));
    }

    #[test]
    fn rejects_length_mismatch() {
        let bytes = [0x10, 0x01, 0x00, 0x05, 0xAA, 0xBB, 0xCC];
        assert!(matches!(Frame::decode(&bytes), Err(FrameDecodeError::LengthMismatch { .. })));
    }

    #[test]
    fn rejects_oversize_length() {
        let mut bytes = vec![0x10, 0x01];
        bytes.extend_from_slice(&((MAX_FRAME_PAYLOAD as u16) + 1).to_be_bytes());
        assert!(matches!(Frame::decode(&bytes), Err(FrameDecodeError::LengthTooLarge(_))));
    }

    #[test]
    fn type_constants_match_spec() {
        assert_eq!(ty::TRANSPORT_KEEPALIVE, 0x30);
        assert_eq!(ty::PAIRING_HANDSHAKE, 0x10);
        assert_eq!(ty::ERROR, 0x7F);
    }
}

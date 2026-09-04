pub mod audio_signal;
pub mod client;
pub mod frame;
pub mod scanner;

pub const V1_VERSION: u8 = 0x01;

pub const VORTEX_SERVICE_UUID: uuid::Uuid = uuid::uuid!("53ffc983-45f6-4891-a826-094ac749c063");

pub const PAIRING_CONTROL_UUID: uuid::Uuid = uuid::uuid!("b68f4442-adad-4d7c-a944-95c57b5094c7");

pub const RECONNECT_CONTROL_UUID: uuid::Uuid = uuid::uuid!("bd2e76f0-d216-4f3b-9704-70cc272c3072");

pub const CAPABILITY_UUID: uuid::Uuid = uuid::uuid!("e78510a3-b39e-459f-8957-864cdb301282");

pub const AUDIO_SIGNAL_UUID: uuid::Uuid = uuid::uuid!("c2e1c97f-3a4b-4d7e-9f0c-1e6a8b3d9c5f");

pub const ADV_PAYLOAD_LEN: usize = 10;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AdvFlags(pub u8);

impl AdvFlags {
    pub const PAIRABLE: u8 = 0b0000_0001;
    pub const TRUSTED_PRESENCE: u8 = 0b0000_0010;
    pub const RESERVED_MASK: u8 = 0b1111_1100;

    pub fn pairable() -> Self {
        Self(Self::PAIRABLE)
    }
    pub fn trusted_presence() -> Self {
        Self(Self::TRUSTED_PRESENCE)
    }
    pub fn is_pairable(self) -> bool {
        self.0 & Self::PAIRABLE != 0
    }
    pub fn is_trusted_presence(self) -> bool {
        self.0 & Self::TRUSTED_PRESENCE != 0
    }
    pub fn is_well_formed(self) -> bool {
        if self.0 & Self::RESERVED_MASK != 0 {
            return false;
        }
        let lo = self.0 & 0b11;
        lo == Self::PAIRABLE || lo == Self::TRUSTED_PRESENCE
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AdvPayload {
    pub version: u8,
    pub flags: AdvFlags,
    pub payload_8: [u8; 8],
}

impl AdvPayload {
    pub fn encode(&self) -> [u8; ADV_PAYLOAD_LEN] {
        let mut out = [0u8; ADV_PAYLOAD_LEN];
        out[0] = self.version;
        out[1] = self.flags.0;
        out[2..].copy_from_slice(&self.payload_8);
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, AdvDecodeError> {
        if bytes.len() != ADV_PAYLOAD_LEN {
            return Err(AdvDecodeError::WrongLength(bytes.len()));
        }
        if bytes[0] != V1_VERSION {
            return Err(AdvDecodeError::WrongVersion(bytes[0]));
        }
        let flags = AdvFlags(bytes[1]);
        if !flags.is_well_formed() {
            return Err(AdvDecodeError::BadFlags(bytes[1]));
        }
        let mut payload_8 = [0u8; 8];
        payload_8.copy_from_slice(&bytes[2..]);
        Ok(Self { version: bytes[0], flags, payload_8 })
    }

    pub fn pairable(instance_id: [u8; 8]) -> Self {
        Self { version: V1_VERSION, flags: AdvFlags::pairable(), payload_8: instance_id }
    }

    pub fn trusted_presence(token: [u8; 8]) -> Self {
        Self { version: V1_VERSION, flags: AdvFlags::trusted_presence(), payload_8: token }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AdvDecodeError {
    WrongLength(usize),
    WrongVersion(u8),
    BadFlags(u8),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uuid_contract_pins() {
        assert_eq!(V1_VERSION, 0x01);
        assert_eq!(VORTEX_SERVICE_UUID.to_string(), "53ffc983-45f6-4891-a826-094ac749c063");
        assert_eq!(PAIRING_CONTROL_UUID.to_string(), "b68f4442-adad-4d7c-a944-95c57b5094c7");
        assert_eq!(RECONNECT_CONTROL_UUID.to_string(), "bd2e76f0-d216-4f3b-9704-70cc272c3072");
        assert_eq!(CAPABILITY_UUID.to_string(), "e78510a3-b39e-459f-8957-864cdb301282");
        assert_eq!(AUDIO_SIGNAL_UUID.to_string(), "c2e1c97f-3a4b-4d7e-9f0c-1e6a8b3d9c5f");
    }

    #[test]
    fn pairable_round_trip() {
        let id: [u8; 8] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef];
        let p = AdvPayload::pairable(id);
        let encoded = p.encode();
        assert_eq!(encoded.len(), 10);
        assert_eq!(encoded[0], 0x01);
        assert_eq!(encoded[1], 0x01);
        assert_eq!(&encoded[2..], &id);

        let decoded = AdvPayload::decode(&encoded).unwrap();
        assert_eq!(decoded, p);
        assert!(decoded.flags.is_pairable());
        assert!(!decoded.flags.is_trusted_presence());
    }

    #[test]
    fn trusted_presence_round_trip() {
        let token: [u8; 8] = [0xAA; 8];
        let p = AdvPayload::trusted_presence(token);
        let encoded = p.encode();
        assert_eq!(encoded[1], 0x02);

        let decoded = AdvPayload::decode(&encoded).unwrap();
        assert!(decoded.flags.is_trusted_presence());
        assert!(!decoded.flags.is_pairable());
    }

    #[test]
    fn rejects_wrong_length() {
        assert!(matches!(AdvPayload::decode(&[0u8; 9]), Err(AdvDecodeError::WrongLength(9))));
        assert!(matches!(AdvPayload::decode(&[0u8; 11]), Err(AdvDecodeError::WrongLength(11))));
    }

    #[test]
    fn rejects_wrong_version() {
        let mut bytes = AdvPayload::pairable([0; 8]).encode();
        bytes[0] = 0x02;
        assert!(matches!(AdvPayload::decode(&bytes), Err(AdvDecodeError::WrongVersion(2))));
    }

    #[test]
    fn rejects_reserved_bits_set() {
        let mut bytes = AdvPayload::pairable([0; 8]).encode();
        bytes[1] = 0x05;
        assert!(matches!(AdvPayload::decode(&bytes), Err(AdvDecodeError::BadFlags(0x05))));
    }

    #[test]
    fn rejects_both_modes_set() {
        let mut bytes = AdvPayload::pairable([0; 8]).encode();
        bytes[1] = 0x03;
        assert!(matches!(AdvPayload::decode(&bytes), Err(AdvDecodeError::BadFlags(0x03))));
    }

    #[test]
    fn rejects_no_mode_set() {
        let mut bytes = AdvPayload::pairable([0; 8]).encode();
        bytes[1] = 0x00;
        assert!(matches!(AdvPayload::decode(&bytes), Err(AdvDecodeError::BadFlags(0x00))));
    }
}

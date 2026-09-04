use snow::{params::NoiseParams, Builder};

pub const NOISE_XX: &str = "Noise_XX_25519_ChaChaPoly_SHA256";
pub const NOISE_IK: &str = "Noise_IK_25519_ChaChaPoly_SHA256";

pub const PROLOGUE_XX: &[u8] = b"vortex/v1/pairing";
pub const PROLOGUE_IK: &[u8] = b"vortex/v1/reconnect";

#[derive(Debug, Clone)]
pub struct HandshakeResult {
    pub messages: Vec<Vec<u8>>,
    pub initiator_handshake_hash: Vec<u8>,
    pub responder_handshake_hash: Vec<u8>,
}

pub fn run_xx_deterministic(
    initiator_static_priv: &[u8],
    responder_static_priv: &[u8],
    initiator_ephemeral_priv: &[u8],
    responder_ephemeral_priv: &[u8],
) -> Result<HandshakeResult, snow::Error> {
    let params: NoiseParams = NOISE_XX.parse()?;
    let mut initiator = Builder::new(params.clone())
        .local_private_key(initiator_static_priv)?
        .fixed_ephemeral_key_for_testing_only(initiator_ephemeral_priv)
        .prologue(PROLOGUE_XX)?
        .build_initiator()?;
    let mut responder = Builder::new(params)
        .local_private_key(responder_static_priv)?
        .fixed_ephemeral_key_for_testing_only(responder_ephemeral_priv)
        .prologue(PROLOGUE_XX)?
        .build_responder()?;

    let mut buffer = vec![0u8; 1024];
    let mut tmp = vec![0u8; 1024];
    let mut messages = Vec::with_capacity(3);

    let len = initiator.write_message(&[], &mut buffer)?;
    messages.push(buffer[..len].to_vec());
    responder.read_message(&messages[0], &mut tmp)?;

    let len = responder.write_message(&[], &mut buffer)?;
    messages.push(buffer[..len].to_vec());
    initiator.read_message(&messages[1], &mut tmp)?;

    let len = initiator.write_message(&[], &mut buffer)?;
    messages.push(buffer[..len].to_vec());
    responder.read_message(&messages[2], &mut tmp)?;

    Ok(HandshakeResult {
        messages,
        initiator_handshake_hash: initiator.get_handshake_hash().to_vec(),
        responder_handshake_hash: responder.get_handshake_hash().to_vec(),
    })
}

pub fn run_ik_deterministic(
    initiator_static_priv: &[u8],
    responder_static_priv: &[u8],
    initiator_ephemeral_priv: &[u8],
    responder_ephemeral_priv: &[u8],
    responder_static_pub: &[u8],
) -> Result<HandshakeResult, snow::Error> {
    let params: NoiseParams = NOISE_IK.parse()?;
    let mut initiator = Builder::new(params.clone())
        .local_private_key(initiator_static_priv)?
        .remote_public_key(responder_static_pub)?
        .fixed_ephemeral_key_for_testing_only(initiator_ephemeral_priv)
        .prologue(PROLOGUE_IK)?
        .build_initiator()?;
    let mut responder = Builder::new(params)
        .local_private_key(responder_static_priv)?
        .fixed_ephemeral_key_for_testing_only(responder_ephemeral_priv)
        .prologue(PROLOGUE_IK)?
        .build_responder()?;

    let mut buffer = vec![0u8; 1024];
    let mut tmp = vec![0u8; 1024];
    let mut messages = Vec::with_capacity(2);

    let len = initiator.write_message(&[], &mut buffer)?;
    messages.push(buffer[..len].to_vec());
    responder.read_message(&messages[0], &mut tmp)?;

    let len = responder.write_message(&[], &mut buffer)?;
    messages.push(buffer[..len].to_vec());
    initiator.read_message(&messages[1], &mut tmp)?;

    Ok(HandshakeResult {
        messages,
        initiator_handshake_hash: initiator.get_handshake_hash().to_vec(),
        responder_handshake_hash: responder.get_handshake_hash().to_vec(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const INIT_S: &[u8; 32] = b"\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f\x20\x21\x22\x23\x24\x25\x26\x27\x28\x29\x2a\x2b\x2c\x2d\x2e\x2f";
    const RESP_S: &[u8; 32] = b"\x30\x31\x32\x33\x34\x35\x36\x37\x38\x39\x3a\x3b\x3c\x3d\x3e\x3f\x40\x41\x42\x43\x44\x45\x46\x47\x48\x49\x4a\x4b\x4c\x4d\x4e\x4f";
    const INIT_E: &[u8; 32] = b"\x50\x51\x52\x53\x54\x55\x56\x57\x58\x59\x5a\x5b\x5c\x5d\x5e\x5f\x60\x61\x62\x63\x64\x65\x66\x67\x68\x69\x6a\x6b\x6c\x6d\x6e\x6f";
    const RESP_E: &[u8; 32] = b"\x70\x71\x72\x73\x74\x75\x76\x77\x78\x79\x7a\x7b\x7c\x7d\x7e\x7f\x80\x81\x82\x83\x84\x85\x86\x87\x88\x89\x8a\x8b\x8c\x8d\x8e\x8f";

    fn x25519_pub(priv_bytes: &[u8; 32]) -> [u8; 32] {
        let sk = x25519_dalek::StaticSecret::from(*priv_bytes);
        x25519_dalek::PublicKey::from(&sk).to_bytes()
    }

    #[test]
    fn xx_byte_counts_match_spec() {
        let r = run_xx_deterministic(INIT_S, RESP_S, INIT_E, RESP_E).unwrap();
        assert_eq!(r.messages[0].len(), 32, "XX msg1 = e (32 bytes)");
        assert_eq!(
            r.messages[1].len(),
            96,
            "XX msg2 = e + s_ct + s_tag + payload_tag (no payload) = 96"
        );
        assert_eq!(
            r.messages[2].len(),
            64,
            "XX msg3 = s_ct + s_tag + payload_tag (no payload) = 64"
        );
        assert_eq!(
            r.initiator_handshake_hash, r.responder_handshake_hash,
            "transcript hashes must match"
        );
        assert_eq!(r.initiator_handshake_hash.len(), 32);
    }

    #[test]
    fn ik_byte_counts_and_transcript() {
        let resp_pub = x25519_pub(RESP_S);
        let r = run_ik_deterministic(INIT_S, RESP_S, INIT_E, RESP_E, &resp_pub).unwrap();
        assert_eq!(r.messages[0].len(), 96);
        assert_eq!(r.messages[1].len(), 48);
        assert_eq!(r.initiator_handshake_hash, r.responder_handshake_hash);
    }

    #[test]
    fn xx_is_deterministic() {
        let a = run_xx_deterministic(INIT_S, RESP_S, INIT_E, RESP_E).unwrap();
        let b = run_xx_deterministic(INIT_S, RESP_S, INIT_E, RESP_E).unwrap();
        assert_eq!(a.messages, b.messages);
        assert_eq!(a.initiator_handshake_hash, b.initiator_handshake_hash);
    }

    #[test]
    fn wrong_prologue_fails_responder() {
        let params: NoiseParams = NOISE_XX.parse().unwrap();
        let mut initiator = Builder::new(params.clone())
            .local_private_key(INIT_S)
            .unwrap()
            .fixed_ephemeral_key_for_testing_only(INIT_E)
            .prologue(PROLOGUE_XX)
            .unwrap()
            .build_initiator()
            .unwrap();
        let mut responder = Builder::new(params)
            .local_private_key(RESP_S)
            .unwrap()
            .fixed_ephemeral_key_for_testing_only(RESP_E)
            .prologue(b"different-prologue")
            .unwrap()
            .build_responder()
            .unwrap();

        let mut buf = vec![0u8; 1024];
        let mut tmp = vec![0u8; 1024];

        let len = initiator.write_message(&[], &mut buf).unwrap();
        responder.read_message(&buf[..len], &mut tmp).unwrap();
        let len = responder.write_message(&[], &mut buf).unwrap();
        let result = initiator.read_message(&buf[..len], &mut tmp);
        assert!(result.is_err(), "mismatched prologue must fail AEAD");
    }
}

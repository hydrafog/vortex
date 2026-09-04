use hmac::{Hmac, Mac};
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

pub const HMAC_OUTPUT_LEN: usize = 32;

pub fn hmac_sha256(key: &[u8], data: &[u8]) -> [u8; HMAC_OUTPUT_LEN] {
    let mut mac = HmacSha256::new_from_slice(key).expect("HMAC accepts any key length");
    mac.update(data);
    mac.finalize().into_bytes().into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc4231_vector_1() {
        let key = [0x0bu8; 20];
        let data = b"Hi There";
        let mac = hmac_sha256(&key, data);
        let expected =
            hex::decode("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
                .unwrap();
        assert_eq!(mac.as_slice(), expected.as_slice());
    }
}

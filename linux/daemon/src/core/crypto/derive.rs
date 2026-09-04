use super::kdf::hmac_sha256;

pub const PRS_LABEL: &[u8] = b"vortex/v1/prs";
pub const SES_LABEL: &[u8] = b"vortex/v1/ses";

pub fn derive_prs(transcript_hash: &[u8]) -> [u8; 32] {
    hmac_sha256(PRS_LABEL, transcript_hash)
}

pub fn derive_ses(transcript_hash: &[u8]) -> [u8; 32] {
    hmac_sha256(SES_LABEL, transcript_hash)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prs_and_ses_are_independent() {
        let bytes = [0xAAu8; 32];
        let prs = derive_prs(&bytes);
        let ses = derive_ses(&bytes);
        assert_ne!(prs, ses, "labels must yield different outputs");
    }

    #[test]
    fn sas_prs_ses_labels_are_pairwise_distinct() {
        use super::super::sas::SAS_LABEL;
        use crate::core::crypto::sas::derive_sas;

        for seed in 0..16u8 {
            let mut h = [0u8; 32];
            for b in h.iter_mut() {
                *b = b.wrapping_add(seed);
            }

            let prs = derive_prs(&h);
            let ses = derive_ses(&h);
            let sas_hmac = hmac_sha256(SAS_LABEL, &h);

            assert_ne!(prs, ses, "prs vs ses collision at seed {seed}");
            assert_ne!(prs, sas_hmac, "prs vs sas-hmac collision at seed {seed}");
            assert_ne!(ses, sas_hmac, "ses vs sas-hmac collision at seed {seed}");

            let (v, s) = derive_sas(&h);
            assert!(v < 1_000_000);
            assert_eq!(s.len(), 6);
        }
    }

    #[test]
    fn one_byte_change_changes_prs() {
        let mut ck = [0u8; 32];
        let a = derive_prs(&ck);
        ck[31] = 1;
        let b = derive_prs(&ck);
        assert_ne!(a, b);
    }
}

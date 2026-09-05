use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SmsMessage {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub address: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub r#type: i32,
    #[serde(default)]
    pub date: i64,
    #[serde(default)]
    pub thread: i64,
    #[serde(default)]
    pub read: i32,
}

pub fn parse_chunk(plain: &[u8]) -> Option<(u16, u16, Vec<u8>)> {
    if plain.len() < 4 {
        return None;
    }
    let total = u16::from_be_bytes([plain[0], plain[1]]);
    let idx = u16::from_be_bytes([plain[2], plain[3]]);
    Some((total, idx, plain[4..].to_vec()))
}

pub const MAX_CHUNKS: u16 = 2048;

#[derive(Default)]
pub struct SmsAssembler {
    total: u16,
    chunks: Vec<Option<Vec<u8>>>,
}

impl SmsAssembler {
    pub fn add(&mut self, total: u16, idx: u16, data: Vec<u8>) -> Option<Vec<u8>> {
        if total == 0 || total > MAX_CHUNKS || idx >= total {
            return None;
        }
        if self.total != total {
            self.total = total;
            self.chunks = vec![None; total as usize];
        }
        self.chunks[idx as usize] = Some(data);
        if self.chunks.iter().any(|c| c.is_none()) {
            return None;
        }
        let mut bytes = Vec::new();
        for c in &self.chunks {
            bytes.extend_from_slice(c.as_ref().unwrap());
        }
        self.total = 0;
        self.chunks = Vec::new();
        Some(bytes)
    }
}

const OTP_MIN: usize = 4;
const OTP_MAX: usize = 8;

const OTP_HINT_WINDOW: usize = 48;

const OTP_HINTS: &[&str] = &[
    "code",
    "otp",
    "pin",
    "password",
    "verification",
    "verify",
    "one-time",
    "kod",
    "kodi",
    "kodingiz",
    "parol",
    "tasdiq",
    "bir martalik",
    "код",
    "коды",
    "пароль",
    "подтверж",
    "одноразов",
];

pub fn extract_otp(body: &str) -> Option<String> {
    let lower = body.to_lowercase();
    let hints: Vec<(usize, usize)> = OTP_HINTS
        .iter()
        .flat_map(|h| lower.match_indices(h).map(|(i, m)| (i, i + m.len())))
        .collect();
    if hints.is_empty() {
        return None;
    }

    let b = body.as_bytes();
    let is_sep = |c: u8| matches!(c, b'.' | b',' | b':' | b'-' | b'/');
    let mut best: Option<(i32, &str)> = None;
    let mut i = 0usize;
    while i < b.len() {
        if !b[i].is_ascii_digit() {
            i += 1;
            continue;
        }
        let start = i;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
        let (end, len) = (i, i - start);
        if !(OTP_MIN..=OTP_MAX).contains(&len) {
            continue;
        }
        let prev = start.checked_sub(1).map(|p| b[p]);
        let next = b.get(end).copied();
        if prev == Some(b'+')
            || prev.is_some_and(|c| c.is_ascii_alphabetic())
            || next.is_some_and(|c| c.is_ascii_alphabetic())
        {
            continue;
        }
        if prev.is_some_and(is_sep) && start >= 2 && b[start - 2].is_ascii_digit() {
            continue;
        }
        if next.is_some_and(is_sep) && b.get(end + 1).is_some_and(|c| c.is_ascii_digit()) {
            continue;
        }

        let mut proximity = 0;
        for &(hs, he) in &hints {
            let (distance, weight) = if he <= start {
                (start - he, 14)
            } else if hs >= end {
                (hs - end, 8)
            } else {
                (0, 14)
            };
            if distance <= OTP_HINT_WINDOW {
                proximity = proximity.max(weight + (OTP_HINT_WINDOW - distance) as i32 / 4);
            }
        }
        let mut score = match len {
            6 => 3,
            5 => 2,
            4 | 7 | 8 => 1,
            _ => 0,
        };
        score += proximity;
        let candidate = &body[start..end];
        if best.map_or(true, |(s, _)| score > s) {
            best = Some((score, candidate));
        }
    }
    best.map(|(_, c)| c.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_codes_in_the_three_languages_a_phone_here_receives() {
        assert_eq!(extract_otp("Your code is 483920").as_deref(), Some("483920"));
        assert_eq!(extract_otp("Tasdiqlash kodi: 4821").as_deref(), Some("4821"));
        assert_eq!(extract_otp("Ваш код подтверждения: 90210").as_deref(), Some("90210"));
        assert_eq!(extract_otp("123456 is your verification code").as_deref(), Some("123456"));
    }

    #[test]
    fn picks_the_number_the_hint_word_describes() {
        assert_eq!(extract_otp("Xarid: 50000 sum. Tasdiqlash kodi 1234").as_deref(), Some("1234"));
    }

    #[test]
    fn ignores_messages_with_no_hint_word() {
        assert_eq!(extract_otp("Balansingiz 45000 so'm"), None);
        assert_eq!(extract_otp("See you at 1830"), None);
    }

    #[test]
    fn skips_phone_numbers_amounts_dates_and_ids() {
        assert_eq!(extract_otp("Kod uchun qo'ng'iroq: +998901234567"), None);
        assert_eq!(extract_otp("kod: 1.234.567"), None);
        assert_eq!(extract_otp("kodi 2026-07-28"), None);
        assert_eq!(extract_otp("kod ID4821X"), None);
        assert_eq!(extract_otp("kod 8600123412341234"), None);
    }

    #[test]
    fn rejects_total_above_cap() {
        let mut asm = SmsAssembler::default();
        assert!(asm.add(MAX_CHUNKS + 1, 0, b"x".to_vec()).is_none());
        assert_eq!(asm.add(1, 0, b"ok".to_vec()), Some(b"ok".to_vec()));
    }
}

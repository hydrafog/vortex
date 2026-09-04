use std::fs;
use std::path::PathBuf;

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeviceClass {
    Unknown = 0,
    Laptop = 1,
    Phone = 2,
    Tablet = 3,
    Earbuds = 4,
}

impl DeviceClass {
    pub fn from_byte(b: u8) -> Self {
        match b {
            1 => DeviceClass::Laptop,
            2 => DeviceClass::Phone,
            3 => DeviceClass::Tablet,
            4 => DeviceClass::Earbuds,
            _ => DeviceClass::Unknown,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BatteryPct(pub Option<u8>);

impl BatteryPct {
    pub fn to_byte(self) -> u8 {
        self.0.unwrap_or(0xFF)
    }
    pub fn from_byte(b: u8) -> Self {
        if b == 0xFF || b > 100 {
            BatteryPct(None)
        } else {
            BatteryPct(Some(b))
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct LocalStatus {
    pub battery: BatteryPct,
    pub class: DeviceClass,
}

impl LocalStatus {
    pub fn append_to(&self, out: &mut Vec<u8>) {
        out.push(self.battery.to_byte());
        out.push(self.class as u8);
    }
}

pub fn read_local_battery() -> BatteryPct {
    let dir = match fs::read_dir("/sys/class/power_supply") {
        Ok(d) => d,
        Err(_) => return BatteryPct(None),
    };
    for entry in dir.flatten() {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if !name.starts_with("BAT") {
            continue;
        }
        let mut path: PathBuf = entry.path();
        path.push("capacity");
        if let Ok(s) = fs::read_to_string(&path) {
            if let Ok(v) = s.trim().parse::<u8>() {
                if v <= 100 {
                    return BatteryPct(Some(v));
                }
            }
        }
    }
    BatteryPct(None)
}

pub fn read_local_charging() -> bool {
    let dir = match fs::read_dir("/sys/class/power_supply") {
        Ok(d) => d,
        Err(_) => return false,
    };
    let mut bat_charging = false;
    for entry in dir.flatten() {
        let path: PathBuf = entry.path();
        let ty = fs::read_to_string(path.join("type")).unwrap_or_default();
        if ty.trim().eq_ignore_ascii_case("Mains") {
            if fs::read_to_string(path.join("online")).map(|o| o.trim() == "1").unwrap_or(false) {
                return true;
            }
            continue;
        }
        let name = entry.file_name();
        if name.to_string_lossy().starts_with("BAT") {
            if let Ok(s) = fs::read_to_string(path.join("status")) {
                let s = s.trim();
                if s.eq_ignore_ascii_case("Charging") || s.eq_ignore_ascii_case("Full") {
                    bat_charging = true;
                }
            }
        }
    }
    bat_charging
}

pub fn local_laptop_status() -> LocalStatus {
    LocalStatus { battery: read_local_battery(), class: DeviceClass::Laptop }
}

pub fn decode_peer_status(payload: &[u8]) -> Option<(BatteryPct, DeviceClass)> {
    if payload.len() < 10 {
        return None;
    }
    let battery = BatteryPct::from_byte(payload[8]);
    let class = DeviceClass::from_byte(payload[9]);
    Some((battery, class))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn battery_roundtrip() {
        for v in [0, 1, 50, 99, 100] {
            assert_eq!(BatteryPct(Some(v)).to_byte(), v);
            assert_eq!(BatteryPct::from_byte(v), BatteryPct(Some(v)));
        }
        assert_eq!(BatteryPct(None).to_byte(), 0xFF);
        assert_eq!(BatteryPct::from_byte(0xFF), BatteryPct(None));
        assert_eq!(BatteryPct::from_byte(101), BatteryPct(None));
    }

    #[test]
    fn class_byte() {
        assert_eq!(DeviceClass::from_byte(1), DeviceClass::Laptop);
        assert_eq!(DeviceClass::from_byte(2), DeviceClass::Phone);
        assert_eq!(DeviceClass::from_byte(99), DeviceClass::Unknown);
    }

    #[test]
    fn decode_short_payload_returns_none() {
        assert!(decode_peer_status(&[0u8; 8]).is_none());
        assert!(decode_peer_status(&[0u8; 9]).is_none());
    }

    #[test]
    fn decode_full_payload() {
        let mut p = vec![0u8; 8];
        p.push(75);
        p.push(2);
        let (bat, cls) = decode_peer_status(&p).unwrap();
        assert_eq!(bat, BatteryPct(Some(75)));
        assert_eq!(cls, DeviceClass::Phone);
    }
}

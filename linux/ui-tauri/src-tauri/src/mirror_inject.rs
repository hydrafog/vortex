use std::io::Write;
use std::net::TcpStream;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::Mutex;
use std::thread::JoinHandle;
use std::time::Duration;

const BINARY: &[u8] = include_bytes!("../assets/vortex_inject");
const REMOTE_PATH: &str = "/data/local/tmp/vortex_inject";
const SOCKET_NAME: &str = "localabstract:vortex_inject";
const LOCAL_PORT: u16 = 28250;

static ACTIVE_PORT: AtomicU16 = AtomicU16::new(LOCAL_PORT);

static INJECT: Mutex<Option<Injector>> = Mutex::new(None);

static STOPPING: AtomicBool = AtomicBool::new(false);

enum Cmd {
    Line(String),
    Quit,
}

struct Injector {
    child: Child,
    tx: Sender<Cmd>,
    worker: Option<JoinHandle<()>>,
}

static ADB_SERIAL: Mutex<Option<String>> = Mutex::new(None);

const WIRELESS_PORT: u16 = 5555;

static LAST_ADB_PORT: Mutex<Option<u16>> = Mutex::new(None);

fn adb_port_path() -> Option<std::path::PathBuf> {
    let mut p = std::path::PathBuf::from(std::env::var_os("HOME")?);
    p.push(".cache/vortex/last_adb_port");
    Some(p)
}

fn remember_adb_port(port: u16) {
    let changed = {
        let mut g = LAST_ADB_PORT.lock().unwrap_or_else(|e| e.into_inner());
        let changed = *g != Some(port);
        *g = Some(port);
        changed
    };
    if changed {
        if let Some(p) = adb_port_path() {
            let _ =
                vortex_l3_daemon::core::fs_private::write_private(&p, port.to_string().as_bytes());
        }
        tracing::debug!(port, "mirror inject: remembered wireless adb port");
    }
}

fn redial_ports() -> Vec<u16> {
    let remembered = LAST_ADB_PORT.lock().ok().and_then(|g| *g).or_else(|| {
        adb_port_path()
            .and_then(|p| std::fs::read_to_string(p).ok())
            .and_then(|s| s.trim().parse::<u16>().ok())
    });
    redial_port_order(remembered)
}

fn redial_port_order(remembered: Option<u16>) -> Vec<u16> {
    match remembered {
        Some(p) if p != WIRELESS_PORT => vec![p, WIRELESS_PORT],
        Some(p) => vec![p],
        None => vec![WIRELESS_PORT],
    }
}

const REDIAL_COOLDOWN: Duration = Duration::from_secs(10);

static LAST_REDIAL: Mutex<Option<std::time::Instant>> = Mutex::new(None);

fn scan_transports() -> Option<String> {
    let out = Command::new("adb").arg("devices").output().ok()?;
    let text = String::from_utf8_lossy(&out.stdout);
    let (mut usb, mut net) = (None, None);
    for line in text.lines().skip(1) {
        let mut it = line.split_whitespace();
        let (Some(serial), Some("device")) = (it.next(), it.next()) else {
            continue;
        };
        let slot = if serial.contains(':') { &mut net } else { &mut usb };
        slot.get_or_insert_with(|| serial.to_string());
    }
    if let Some(port) =
        net.as_deref().and_then(|n| n.rsplit(':').next()).and_then(|p| p.parse::<u16>().ok())
    {
        remember_adb_port(port);
    }
    usb.or(net)
}

fn redial() -> bool {
    {
        let mut last = LAST_REDIAL.lock().unwrap_or_else(|e| e.into_inner());
        if last.is_some_and(|t| t.elapsed() < REDIAL_COOLDOWN) {
            return false;
        }
        *last = Some(std::time::Instant::now());
    }
    let Some(ip) = *crate::lan::LAST_GOOD_PEER_IP.lock().unwrap_or_else(|e| e.into_inner()) else {
        return false;
    };
    for port in redial_ports() {
        let target = format!("{ip}:{port}");
        let ok = Command::new("adb")
            .args(["connect", &target])
            .output()
            .is_ok_and(|o| String::from_utf8_lossy(&o.stdout).contains("connected to"));
        if ok {
            remember_adb_port(port);
            tracing::info!("mirror inject: wireless adb redialled at {target}");
            return true;
        }
        tracing::debug!("mirror inject: no answer on {target}");
    }
    tracing::debug!(
        "mirror inject: wireless adb unreachable — needs `adb tcpip 5555` once, or \
         Wireless debugging paired (Android 11+, works with USB debugging OFF)"
    );
    false
}

fn adb_serial() -> Option<String> {
    if let Some(s) = ADB_SERIAL.lock().ok()?.clone() {
        return Some(s);
    }
    let pick = match scan_transports() {
        Some(s) => s,
        None if redial() => scan_transports()?,
        None => return None,
    };
    *ADB_SERIAL.lock().ok()? = Some(pick.clone());
    tracing::debug!("mirror inject: adb target {pick}");
    Some(pick)
}

fn forget_adb_serial() {
    if let Ok(mut g) = ADB_SERIAL.lock() {
        *g = None;
    }
}

fn adb_command(args: &[&str]) -> Command {
    let mut cmd = Command::new("adb");
    if let Some(s) = adb_serial() {
        cmd.arg("-s").arg(s);
    }
    cmd.args(args);
    cmd
}

fn adb(args: &[&str]) -> bool {
    match adb_command(args).output() {
        Ok(o) if o.status.success() => true,
        Ok(o) => {
            tracing::debug!(
                ?args,
                "adb command failed: {}",
                String::from_utf8_lossy(&o.stderr).trim()
            );
            forget_adb_serial();
            false
        }
        Err(e) => {
            tracing::debug!(?args, "adb not runnable: {e}");
            forget_adb_serial();
            false
        }
    }
}

pub(crate) fn display_size() -> Option<(i32, i32)> {
    let out = adb_capture(&["shell", "wm", "size"])?;
    let pick = out
        .lines()
        .find(|l| l.contains("Override size:"))
        .or_else(|| out.lines().find(|l| l.contains("Physical size:")))?;
    let (w, h) = pick.rsplit_once(':')?.1.trim().split_once('x')?;
    let (w, h) = (w.trim().parse().ok()?, h.trim().parse().ok()?);
    if w > 0 && h > 0 {
        Some((w, h))
    } else {
        None
    }
}

static ROTATION: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
static ROTATION_BUSY: AtomicBool = AtomicBool::new(false);

pub(crate) fn rotation_cached() -> u32 {
    ROTATION.load(Ordering::Relaxed)
}

pub(crate) fn refresh_rotation() {
    if ROTATION_BUSY.swap(true, Ordering::SeqCst) {
        return;
    }
    std::thread::spawn(|| {
        if let Some(r) = rotation() {
            ROTATION.store(r, Ordering::Relaxed);
        }
        ROTATION_BUSY.store(false, Ordering::SeqCst);
    });
}

#[derive(Clone, Copy)]
pub(crate) struct PointerCurve {
    pub scale: f32,
    pub low: f32,
    pub high: f32,
    pub accel: f32,
}

impl PointerCurve {
    const DEFAULT: Self = Self { scale: 1.0, low: 500.0, high: 3000.0, accel: 3.0 };

    pub fn saturated(&self) -> f32 {
        self.scale * self.accel
    }

    pub fn undo(&self, want: f32, dt: f32) -> f32 {
        if want <= 0.0 || dt <= 0.0 || self.scale <= 0.0 {
            return want;
        }
        let q = want / dt;
        let k =
            if self.high > self.low { (self.accel - 1.0) / (self.high - self.low) } else { 0.0 };
        let w = if q <= self.low || k <= 0.0 {
            q
        } else if q >= self.high * self.accel {
            q / self.accel
        } else {
            let b = 1.0 - k * self.low;
            (-b + (b * b + 4.0 * k * q).sqrt()) / (2.0 * k)
        };
        w * dt / self.scale
    }
}

static POINTER_CURVE: [std::sync::atomic::AtomicU32; 4] = [
    std::sync::atomic::AtomicU32::new(0),
    std::sync::atomic::AtomicU32::new(0),
    std::sync::atomic::AtomicU32::new(0),
    std::sync::atomic::AtomicU32::new(0),
];

pub(crate) fn pointer_curve() -> PointerCurve {
    let f = |i: usize| f32::from_bits(POINTER_CURVE[i].load(Ordering::Relaxed));
    let (scale, low, high, accel) = (f(0), f(1), f(2), f(3));
    if scale <= 0.0 || accel < 1.0 || high <= low {
        return PointerCurve::DEFAULT;
    }
    PointerCurve { scale, low, high, accel }
}

pub(crate) fn refresh_pointer_curve() {
    std::thread::spawn(|| {
        if let Some(c) = read_pointer_curve() {
            for (slot, v) in POINTER_CURVE.iter().zip([c.scale, c.low, c.high, c.accel]) {
                slot.store(v.to_bits(), Ordering::Relaxed);
            }
            tracing::info!(
                "mirror inject: phone pointer curve scale={:.3} {:.0}→{:.0}px/s accel={:.3} \
                 (saturated {:.3}×)",
                c.scale,
                c.low,
                c.high,
                c.accel,
                c.saturated()
            );
        }
    });
}

fn read_pointer_curve() -> Option<PointerCurve> {
    let out = adb_capture(&["shell", "dumpsys input | grep -m1 PointerVelocityControl"])?;
    let field = |name: &str| -> Option<f32> {
        out.split(&format!("{name}="))
            .nth(1)?
            .split(|c: char| c != '.' && !c.is_ascii_digit())
            .next()?
            .parse()
            .ok()
    };
    let c = PointerCurve {
        scale: field("scale")?,
        low: field("lowThreshold")?,
        high: field("highThreshold")?,
        accel: field("acceleration")?,
    };
    (c.scale > 0.0 && c.accel >= 1.0 && c.high > c.low && c.low >= 0.0).then_some(c)
}

pub(crate) fn rotation() -> Option<u32> {
    let out = adb_capture(&["shell", "dumpsys display | grep mCurrentOrientation"])?;
    let line = out.lines().find(|l| l.contains("mCurrentOrientation"))?;
    line.rsplit_once('=')?.1.trim().parse().ok()
}

fn adb_capture(args: &[&str]) -> Option<String> {
    let out = adb_command(args).output().ok()?;
    if !out.status.success() {
        tracing::debug!(
            ?args,
            "adb (capture) failed: {}",
            String::from_utf8_lossy(&out.stderr).trim()
        );
        forget_adb_serial();
        return None;
    }
    Some(String::from_utf8_lossy(&out.stdout).trim().to_string())
}

fn ensure_pushed() -> bool {
    let tmp = std::env::temp_dir().join("vortex_inject");
    if std::fs::write(&tmp, BINARY).is_err() {
        tracing::warn!("mirror inject: couldn't stage injector to temp");
        return false;
    }
    let Some(tmp_str) = tmp.to_str() else { return false };
    let _ = adb(&["shell", "pkill", "-f", "vortex_inject"]);
    let _ = adb(&["shell", "rm", "-f", REMOTE_PATH]);
    if !adb(&["push", tmp_str, REMOTE_PATH]) {
        tracing::warn!("mirror inject: adb push failed (device offline / unauthorized?)");
        return false;
    }
    adb(&["shell", "chmod", "755", REMOTE_PATH]);
    true
}

static TOUCH_OK: AtomicBool = AtomicBool::new(false);

pub fn touch_available() -> bool {
    TOUCH_OK.load(Ordering::SeqCst)
}

fn soft_keyboard_survives_hardware() -> bool {
    let on = adb_capture(&["shell", "settings", "get", "secure", "show_ime_with_hard_keyboard"])
        .is_some_and(|v| v.trim() == "1");
    tracing::info!("mirror inject: phone keeps its on-screen keyboard with a hardware one: {on}");
    on
}

pub fn start() -> bool {
    stop();
    STOPPING.store(false, Ordering::SeqCst);
    if !ensure_pushed() {
        tracing::warn!("mirror inject: adb push failed — using accessibility fallback");
        return false;
    }
    let _ = adb(&["forward", "--remove", &format!("tcp:{}", ACTIVE_PORT.load(Ordering::SeqCst))]);
    let port =
        match adb_capture(&["forward", "tcp:0", SOCKET_NAME]).and_then(|s| s.parse::<u16>().ok()) {
            Some(p) => p,
            None => {
                tracing::warn!("mirror inject: adb forward failed — accessibility fallback");
                return false;
            }
        };
    ACTIVE_PORT.store(port, Ordering::SeqCst);
    let touch = adb_capture(&["shell", "test -w /dev/uinput && echo yes || echo no"])
        .is_some_and(|v| v.trim() == "yes");
    TOUCH_OK.store(touch, Ordering::SeqCst);
    if !touch {
        tracing::info!(
            "mirror inject: no /dev/uinput for shell — cursor and keyboard over UHID, \
             no touch injection"
        );
    }
    let mut argv = vec!["shell", REMOTE_PATH];
    if soft_keyboard_survives_hardware() {
        argv.push("--keep-keys");
    }
    let spawn =
        adb_command(&argv).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn();
    let child = match spawn {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!("mirror inject: spawn failed: {e} — accessibility fallback");
            return false;
        }
    };
    let Some(stream) = connect_socket() else {
        tracing::warn!("mirror inject: socket connect failed — accessibility fallback");
        let mut child = child;
        let _ = child.kill();
        return false;
    };
    let (tx, rx) = mpsc::channel::<Cmd>();
    let worker = std::thread::spawn(move || writer_loop(stream, rx));
    if let Ok(mut g) = INJECT.lock() {
        *g = Some(Injector { child, tx, worker: Some(worker) });
    }
    refresh_pointer_curve();
    tracing::info!("mirror inject: uinput injector connected (real-touch, scrcpy-style)");
    true
}

fn connect_socket() -> Option<TcpStream> {
    for _ in 0..30 {
        std::thread::sleep(Duration::from_millis(80));
        if let Ok(s) = TcpStream::connect(("127.0.0.1", ACTIVE_PORT.load(Ordering::SeqCst))) {
            let _ = s.set_nodelay(true);
            return Some(s);
        }
    }
    None
}

fn write_line(stream: &mut TcpStream, line: &str) -> bool {
    stream.write_all(line.as_bytes()).and_then(|_| stream.write_all(b"\n")).is_ok()
}

fn writer_loop(mut stream: TcpStream, rx: Receiver<Cmd>) {
    loop {
        let line = match rx.recv() {
            Ok(Cmd::Line(l)) => l,
            Ok(Cmd::Quit) => {
                let _ = stream.write_all(b"Q\n");
                let _ = stream.flush();
                return;
            }
            Err(_) => return,
        };
        if write_line(&mut stream, &line) {
            continue;
        }
        let mut recovered = false;
        for _ in 0..20 {
            std::thread::sleep(Duration::from_millis(100));
            if let Ok(s) = TcpStream::connect(("127.0.0.1", ACTIVE_PORT.load(Ordering::SeqCst))) {
                let _ = s.set_nodelay(true);
                stream = s;
                let _ = write_line(&mut stream, &line);
                recovered = true;
                break;
            }
        }
        if recovered {
            tracing::info!("mirror inject: control socket reconnected");
            continue;
        }
        tracing::warn!(
            "mirror inject: control socket lost — accessibility fallback + re-establish"
        );
        on_injector_lost();
        return;
    }
}

fn on_injector_lost() {
    if let Some(mut inj) = INJECT.lock().ok().and_then(|mut g| g.take()) {
        inj.worker = None;
        let _ = inj.child.kill();
        let _ = inj.child.wait();
    }
    let _ = adb(&["forward", "--remove", &format!("tcp:{}", ACTIVE_PORT.load(Ordering::SeqCst))]);
    std::thread::spawn(|| {
        std::thread::sleep(Duration::from_secs(1));
        if !STOPPING.load(Ordering::SeqCst) && !active() {
            let _ = start();
        }
    });
}

pub fn active() -> bool {
    INJECT.lock().map(|g| g.is_some()).unwrap_or(false)
}

pub fn send(line: &str) {
    if let Ok(g) = INJECT.lock() {
        if let Some(inj) = g.as_ref() {
            let _ = inj.tx.send(Cmd::Line(line.to_string()));
        }
    }
}

pub fn stop() {
    STOPPING.store(true, Ordering::SeqCst);
    let taken = INJECT.lock().ok().and_then(|mut g| g.take());
    if let Some(mut inj) = taken {
        let _ = inj.tx.send(Cmd::Quit);
        if let Some(w) = inj.worker.take() {
            let _ = w.join();
        }
        let _ = inj.child.kill();
        let _ = inj.child.wait();
    }
    let _ = adb(&["forward", "--remove", &format!("tcp:{}", ACTIVE_PORT.load(Ordering::SeqCst))]);
}

#[cfg(test)]
mod tests {
    use super::{redial_port_order, PointerCurve, WIRELESS_PORT};

    #[test]
    fn redial_tries_the_remembered_port_before_5555() {
        assert_eq!(redial_port_order(Some(37129)), vec![37129, WIRELESS_PORT]);
        assert_eq!(redial_port_order(None), vec![WIRELESS_PORT]);
        assert_eq!(redial_port_order(Some(WIRELESS_PORT)), vec![WIRELESS_PORT]);
    }

    fn applied(c: &PointerCurve, sent: f32, dt: f32) -> f32 {
        let speed = sent / dt * c.scale;
        let f = if speed <= c.low {
            1.0
        } else if speed >= c.high {
            c.accel
        } else {
            1.0 + (speed - c.low) / (c.high - c.low) * (c.accel - 1.0)
        };
        sent * c.scale * f
    }

    #[test]
    fn undo_is_the_inverse_of_the_curve() {
        let c = PointerCurve::DEFAULT;
        for dt in [0.002f32, 0.008, 0.05] {
            for want in [0.5f32, 1.0, 3.0, 10.0, 40.0, 200.0] {
                let got = applied(&c, c.undo(want, dt), dt);
                assert!(
                    (got - want).abs() <= want * 0.01 + 0.01,
                    "want {want} at dt {dt}: sent {} → {got}",
                    c.undo(want, dt)
                );
            }
        }
    }

    #[test]
    fn slow_movement_passes_through_untouched() {
        let c = PointerCurve::DEFAULT;
        assert!((c.undo(1.0, 0.008) - 1.0).abs() < 0.001);
    }

    #[test]
    fn fast_movement_divides_by_the_saturated_factor() {
        let c = PointerCurve::DEFAULT;
        assert!((c.undo(100.0, 0.002) - 100.0 / c.saturated()).abs() < 0.01);
    }

    #[test]
    fn degenerate_asks_stay_finite() {
        let c = PointerCurve::DEFAULT;
        for (want, dt) in [(0.0f32, 0.008f32), (10.0, 0.0), (0.0, 0.0), (-5.0, 0.008)] {
            let got = c.undo(want, dt);
            assert!(got.is_finite(), "undo({want}, {dt}) = {got}");
            assert_eq!(got, want, "undo({want}, {dt}) should pass through");
        }
        let dead = PointerCurve { scale: 0.0, ..PointerCurve::DEFAULT };
        assert!(dead.undo(10.0, 0.008).is_finite());
    }

    #[test]
    fn scale_is_undone_as_well() {
        let c = PointerCurve { scale: 2.0, ..PointerCurve::DEFAULT };
        assert!((c.undo(1.0, 0.05) - 0.5).abs() < 0.001);
        assert!((applied(&c, c.undo(30.0, 0.002), 0.002) - 30.0).abs() < 0.3);
    }
}

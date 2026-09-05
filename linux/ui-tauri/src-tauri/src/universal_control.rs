
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use ashpd::desktop::input_capture::{Barrier, Capabilities, InputCapture};
use futures::StreamExt;
use reis::ei::{self, button::ButtonState, keyboard::KeyState};
use reis::event::{DeviceCapability, EiEvent};
use reis::tokio::{EiConvertEventStream, EiEventStream};

static RUNNING: AtomicBool = AtomicBool::new(false);
static STOP: AtomicBool = AtomicBool::new(false);

const RETURN_MARGIN: f32 = 60.0;

const PUSH_THROUGH: f32 = 10.0;

const PUSH_THROUGH_FAST: f32 = 5.0;

const PUSH_SLOW: f32 = 40.0;
const PUSH_FAST: f32 = 500.0;

// NOTE. Do not put the phone on an edge something else already claims — an

const PUSH_INWARD_RATIO: f32 = 0.6;

const PUSH_IDLE: Duration = Duration::from_millis(60);

#[derive(Clone, Copy, PartialEq)]
enum Segment {
    Full,
    Start,
    End,
}

const SEGMENT_LEN: i32 = 400;

#[derive(Clone, Copy)]
enum Edge {
    Left,
    Right,
    Top,
    Bottom,
}

fn placement() -> (Edge, Segment) {
    let p = std::env::var_os("HOME").map(|h| {
        std::path::PathBuf::from(h).join(".local/share/vortex/universal_control/placement")
    });
    let s = p
        .and_then(|p| std::fs::read_to_string(p).ok())
        .unwrap_or_default();
    parse_placement(s.trim())
}

fn parse_placement(s: &str) -> (Edge, Segment) {
    let s = s.to_lowercase();
    let (edge_s, end_s) = s.split_once('-').unwrap_or((s.as_str(), ""));
    let edge = match edge_s {
        "left" => Edge::Left,
        "top" => Edge::Top,
        "bottom" => Edge::Bottom,
        _ => Edge::Right,
    };
    let seg = match end_s {
        "left" | "top" => Segment::Start,
        "right" | "bottom" => Segment::End,
        _ => Segment::Full,
    };
    (edge, seg)
}

#[tauri::command]
pub(crate) fn uc_set_placement(edge: String) -> Result<(), String> {
    let edge = edge.trim().to_lowercase();
    let (head, tail) = edge.split_once('-').unwrap_or((edge.as_str(), ""));
    if !matches!(head, "left" | "right" | "top" | "bottom")
        || !matches!(tail, "" | "left" | "right" | "top" | "bottom")
    {
        return Err(format!("bad placement: {edge}"));
    }
    let home = std::env::var_os("HOME").ok_or("no HOME")?;
    let dir = std::path::PathBuf::from(home).join(".local/share/vortex/universal_control");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let target = dir.join("placement");
    let _ = std::fs::remove_file(&target);
    std::fs::write(target, edge).map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub(crate) fn uc_get_placement() -> String {
    let (edge, seg) = placement();
    let end = match (seg, edge) {
        (Segment::Full, _) => return edge_name(edge).to_string(),
        (Segment::Start, Edge::Top | Edge::Bottom) => "left",
        (Segment::Start, _) => "top",
        (Segment::End, Edge::Top | Edge::Bottom) => "right",
        (Segment::End, _) => "bottom",
    };
    format!("{}-{end}", edge_name(edge))
}

fn enabled_path() -> Option<std::path::PathBuf> {
    let home = std::env::var_os("HOME")?;
    Some(std::path::PathBuf::from(home).join(".local/share/vortex/universal_control/enabled"))
}

fn remember_enabled(on: bool) {
    let Some(p) = enabled_path() else { return };
    if on {
        if let Some(dir) = p.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let _ = std::fs::write(p, "1");
    } else {
        let _ = std::fs::remove_file(p);
    }
}

pub(crate) fn restore(app: tauri::AppHandle) {
    if !enabled_path().is_some_and(|p| p.exists()) {
        return;
    }
    tracing::info!("universal-control: was left on — arming the edge again");
    tauri::async_runtime::spawn(async move {
        let _ = arm(app, false);
    });
}

#[tauri::command]
pub(crate) async fn uc_start(app: tauri::AppHandle) -> Result<(), String> {
    if STOP.load(Ordering::SeqCst) {
        for _ in 0..50 {
            if !RUNNING.load(Ordering::SeqCst) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
    let armed = arm(app, true);
    if armed.is_ok() {
        remember_enabled(true);
    }
    armed
}

fn arm(app: tauri::AppHandle, require_injector: bool) -> Result<(), String> {
    if RUNNING.swap(true, Ordering::SeqCst) {
        return Ok(());
    }
    STOP.store(false, Ordering::SeqCst);
    ensure_cursor_publisher();
    if require_injector && !crate::mirror_inject::active() && !crate::mirror_inject::start() {
        RUNNING.store(false, Ordering::SeqCst);
        return Err("no_injector".into());
    }
    std::thread::spawn(move || {
        struct RunningGuard;
        impl Drop for RunningGuard {
            fn drop(&mut self) {
                RUNNING.store(false, Ordering::SeqCst);
            }
        }
        let _guard = RunningGuard;

        let rt = match tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
        {
            Ok(rt) => rt,
            Err(e) => {
                tracing::warn!("universal-control: runtime: {e}");
                return;
            }
        };
        rt.block_on(async {
            if let Err(e) = capture_loop().await {
                tracing::warn!("universal-control: capture loop ended: {e}");
                let _ = tauri::Emitter::emit(&app, "vortex:uc-stopped", e.to_string());
                remember_enabled(false);
            }
        });
    });
    Ok(())
}

#[tauri::command]
pub(crate) fn uc_stop() {
    remember_enabled(false);
    STOP.store(true, Ordering::SeqCst);
}

#[tauri::command]
pub(crate) fn uc_running() -> bool {
    RUNNING.load(Ordering::SeqCst)
}

async fn capture_loop() -> Result<(), Box<dyn std::error::Error>> {
    let (edge, seg) = placement();
    let phys = crate::mirror_inject::display_size().ok_or("no_display_size")?;
    let (mut pw, mut ph) = phys;
    tracing::info!("universal-control: phone physical bounds {}x{}", phys.0, phys.1);
    let ic = InputCapture::new()
        .await
        .map_err(|e| format!("no_portal|{e}"))?;
    let (session, _cap) = ic
        .create_session(
            &ashpd::WindowIdentifier::default(),
            Capabilities::Keyboard | Capabilities::Pointer,
        )
        .await
        .map_err(|e| format!("session_refused|{e}"))?;

    let fd = ic.connect_to_eis(&session).await?;
    let stream = std::os::unix::net::UnixStream::from(fd);
    stream.set_nonblocking(true)?;
    let context = ei::Context::new(stream)?;
    context.flush().ok();

    let mut event_stream = EiEventStream::new(context.clone())?;
    let resp = reis::tokio::ei_handshake(
        &mut event_stream,
        "vortex-uc",
        ei::handshake::ContextType::Receiver,
    )
    .await?;
    let mut ei_events = EiConvertEventStream::new(event_stream, resp);

    let zones = ic.zones(&session).await?.response()?;
    let regions = zones.regions();
    let idx = match edge {
        Edge::Right => regions
            .iter()
            .enumerate()
            .max_by_key(|(_, r)| r.x_offset() + r.width() as i32)
            .map(|(i, _)| i),
        Edge::Left => regions
            .iter()
            .enumerate()
            .min_by_key(|(_, r)| r.x_offset())
            .map(|(i, _)| i),
        Edge::Bottom => regions
            .iter()
            .enumerate()
            .max_by_key(|(_, r)| r.y_offset() + r.height() as i32)
            .map(|(i, _)| i),
        Edge::Top => regions
            .iter()
            .enumerate()
            .min_by_key(|(_, r)| r.y_offset())
            .map(|(i, _)| i),
    }
    .unwrap_or(0);
    let Some(r) = regions.get(idx) else {
        return Err("no_zones".into());
    };
    let (x, y) = (r.x_offset(), r.y_offset());
    let (w, h) = (r.width() as i32, r.height() as i32);
    let full = match edge {
        Edge::Top | Edge::Bottom => (x, w),
        Edge::Left | Edge::Right => (y, h),
    };
    let mut span = barrier_span(seg, full);
    let mut pos = barrier_pos(edge, (x, y, w, h), span);
    tracing::info!(
        "universal-control: barrier at {pos:?} ({} edge, {} of it)",
        edge_name(edge),
        match seg {
            Segment::Full => "all",
            Segment::Start => "the low end",
            Segment::End => "the high end",
        }
    );
    let mut set = ic
        .set_pointer_barriers(&session, &[Barrier::new(BARRIER_ID, pos)], zones.zone_set())
        .await?
        .response()?;
    if !set.failed_barriers().is_empty() && seg != Segment::Full {
        tracing::warn!(
            "universal-control: compositor refused the partial {} barrier {pos:?}; \
             retrying across the whole edge",
            edge_name(edge)
        );
        span = full;
        pos = barrier_pos(edge, (x, y, w, h), span);
        set = ic
            .set_pointer_barriers(&session, &[Barrier::new(BARRIER_ID, pos)], zones.zone_set())
            .await?
            .response()?;
    }
    if !set.failed_barriers().is_empty() {
        return Err(format!("barrier_refused|{} {pos:?}", edge_name(edge)).into());
    }
    ic.enable(&session).await?;
    let mut activated = ic.receive_activated().await?;
    crate::mirror_inject::refresh_rotation();
    tracing::info!("universal-control: armed on {} edge", edge_name(edge));

    let mut active = false;
    let mut acc_dx = 0f32;
    let mut acc_dy = 0f32;
    let (mut px, mut py) = (0f32, 0f32);
    let mut overpush = 0f32;
    let (mut send_x, mut send_y) = (0f32, 0f32);
    let mut last_motion = Instant::now();
    let mut entry: (f32, f32) = (0.0, 0.0);
    let mut pending = false;
    let mut push = 0f32;
    let mut slide = 0f32;
    let mut last_progress = Instant::now();
    let mut push_started = Instant::now();
    let mut captured = false;
    let mut scroll_acc = 0f32;
    let mut scroll_dx = 0f32;
    let mut scroll_dy = 0f32;
    const SCROLL_GAIN: f32 = 1.4;
    const SCROLL_ACCEL_KNEE: f32 = 250.0;
    const SCROLL_ACCEL_SPAN: f32 = 700.0;
    const SCROLL_ACCEL_MAX: f32 = 3.0;
    const SCROLL_LIFT: Duration = Duration::from_millis(70);
    const SCROLL_FOLLOW: f32 = 0.5;
    const SCROLL_TICK: Duration = Duration::from_millis(8);
    let mut last_touch = Instant::now();
    const SCROLL_SLOT: u8 = 9;
    const SCROLL_MARGIN: f32 = 0.05;
    let mut scroll_finger: Option<(f32, f32)> = None;
    let mut scroll_target = (0f32, 0f32);
    let mut last_scroll = Instant::now();
    let mut last_discrete = Instant::now() - Duration::from_secs(1);
    let mut rot = 0u32;
    let mut activation_id: Option<u32> = None;
    let mut last_input = Instant::now();
    let mut flush = tokio::time::interval(Duration::from_millis(2));
    const KEEPALIVE_GAP: Duration = Duration::from_millis(10);
    let mut last_tx = Instant::now();

    loop {
        tokio::select! {
            a = activated.next() => match a {
                Some(act) => {
                    entry = act.cursor_position().unwrap_or((0.0, 0.0));
                    activation_id = act.activation_id();
                    pending = true;
                    push_started = Instant::now();
                    crate::mirror_inject::refresh_rotation();
                    captured = true;
                    if active {
                        crate::mirror_inject::send("V 0");
                    }
                    active = false;
                    push = 0.0;
                    slide = 0.0;
                    last_progress = Instant::now();
                    last_input = Instant::now();
                    acc_dx = 0.0;
                    acc_dy = 0.0;
                    tracing::info!("universal-control: barrier touched at {entry:?}");
                }
                None => break,
            },

            ev = ei_events.next() => {
                let ev = match ev {
                    Some(Ok(ev)) => ev,
                    Some(Err(e)) => {
                        tracing::warn!("universal-control: EI stream error: {e} → releasing");
                        break;
                    }
                    None => {
                        tracing::warn!("universal-control: EI stream ended → releasing");
                        break;
                    }
                };
                last_input = Instant::now();
                match ev {
                    EiEvent::SeatAdded(s) => {
                        s.seat.bind_capabilities(&[
                            DeviceCapability::Pointer,
                            DeviceCapability::Keyboard,
                            DeviceCapability::Button,
                            DeviceCapability::Scroll,
                        ]);
                        context.flush().ok();
                    }
                    EiEvent::PointerMotion(m) => {
                        acc_dx += m.dx;
                        acc_dy += m.dy;
                    }
                    EiEvent::Button(b) => {
                        if active {
                            if let Some(btn) = match b.button {
                                0x110 => Some(0),
                                0x111 => Some(1),
                                0x112 => Some(2),
                                _ => None,
                            } {
                                let v = if b.state == ButtonState::Press { 1 } else { 0 };
                                crate::mirror_inject::send(&format!("B {btn} {v}"));
                            }
                        }
                    }
                    EiEvent::ScrollDiscrete(sd) => {
                        if active {
                            last_discrete = Instant::now();
                            scroll_acc += sd.discrete_dy as f32 / 120.0;
                            let notches = scroll_acc.trunc();
                            scroll_acc -= notches;
                            let dy = -(notches as i32).clamp(-3, 3);
                            if dy != 0 {
                                crate::mirror_inject::send(&format!("W {dy}"));
                            }
                        }
                    }
                    EiEvent::ScrollDelta(sd) => {
                        if !active {
                        } else if !crate::mirror_inject::touch_available() {
                            scroll_acc += sd.dy / 15.0;
                            let notches = scroll_acc.trunc();
                            scroll_acc -= notches;
                            let dy = -(notches as i32).clamp(-3, 3);
                            if dy != 0 {
                                crate::mirror_inject::send(&format!("W {dy}"));
                            }
                        } else if last_discrete.elapsed() >= Duration::from_millis(200) {
                            scroll_dx += sd.dx;
                            scroll_dy += sd.dy;
                            last_scroll = Instant::now();
                        }
                    }
                    EiEvent::KeyboardKey(k) => {
                        let v = if k.state == KeyState::Press { 1 } else { 0 };
                        if k.key == 1 && v == 1 && (active || pending) {
                            tracing::info!("universal-control: Esc → return to laptop");
                            if active {
                                crate::mirror_inject::send("V 0");
                            }
                            match ic
                                .release(&session, activation_id, Some(return_pos(edge, entry)))
                                .await
                            {
                                Ok(()) => captured = false,
                                Err(e) => tracing::warn!("universal-control: release: {e}"),
                            }
                            active = false;
                            pending = false;
                            overpush = 0.0;
                            set_cursor(false);
                            continue;
                        }
                        if active {
                            crate::mirror_inject::send(&format!("E {} {v}", k.key));
                        }
                    }
                    EiEvent::Disconnected(_) => {
                        tracing::warn!("universal-control: EIS disconnected → releasing");
                        break;
                    }
                    _ => {}
                }
            },

            _ = flush.tick() => {
                if STOP.load(Ordering::SeqCst) {
                    break;
                }
                let dx = acc_dx.round() as i32;
                let dy = acc_dy.round() as i32;
                if dx != 0 || dy != 0 {
                    acc_dx -= dx as f32;
                    acc_dy -= dy as f32;
                    if pending {
                        push += inward_delta(edge, dx, dy);
                        slide += along_delta(edge, dx, dy);
                        if inward_delta(edge, dx, dy) > 0.0 {
                            last_progress = Instant::now();
                        }
                        let secs = push_started.elapsed().as_secs_f32().max(0.001);
                        let t = ((push / secs - PUSH_SLOW) / (PUSH_FAST - PUSH_SLOW))
                            .clamp(0.0, 1.0);
                        let needed = PUSH_THROUGH + (PUSH_THROUGH_FAST - PUSH_THROUGH) * t;
                        let deliberate = push >= needed && push >= slide.abs() * PUSH_INWARD_RATIO;
                        if deliberate && !crate::mirror_inject::active()
                            && !crate::mirror_inject::start()
                        {
                            tracing::warn!(
                                "universal-control: injector unavailable — staying on the laptop"
                            );
                            push = 0.0;
                        } else if deliberate {
                            rot = crate::mirror_inject::rotation_cached();
                            (pw, ph) = match rot {
                                1 | 3 => (phys.1, phys.0),
                                _ => phys,
                            };
                            let (tx, ty) = entry_point(edge, pw, ph, entry, span);
                            let (ox, oy, sx, sy) = home_vectors(edge, pw, ph, tx, ty);
                            tracing::info!(
                                "universal-control: pushed through → phone ({tx},{ty}) of {pw}x{ph}"
                            );
                            pending = false;
                            active = true;
                            overpush = 0.0;
                            set_cursor(true);
                            crate::mirror_inject::send(&format!("V 1 {ox} {oy} {sx} {sy}"));
                            px = tx as f32;
                            py = ty as f32;
                            last_motion = Instant::now();
                            send_x = 0.0;
                            send_y = 0.0;
                        }
                    } else if active {
                        let curve = crate::mirror_inject::pointer_curve();
                        let dt = last_motion.elapsed().as_secs_f32();
                        let want = (dx as f32).hypot(dy as f32);
                        let ratio = curve.undo(want, dt) / want.max(f32::EPSILON);
                        send_x += dx as f32 * ratio;
                        send_y += dy as f32 * ratio;
                        let (ex, ey) = (send_x.round(), send_y.round());
                        if ex != 0.0 || ey != 0.0 {
                            send_x -= ex;
                            send_y -= ey;
                            last_motion = Instant::now();
                            crate::mirror_inject::send(&format!("P {ex} {ey}", ex = ex as i32, ey = ey as i32));
                            last_tx = Instant::now();
                        }
                        let (nx, ny) = (px + dx as f32, py + dy as f32);
                        px = nx.clamp(0.0, (pw - 1) as f32);
                        py = ny.clamp(0.0, (ph - 1) as f32);
                        entry = laptop_point(edge, (px, py), (pw, ph), span, (x, y, w, h));
                        let over = match edge {
                            Edge::Right => -nx.min(0.0),
                            Edge::Left => (nx - (pw - 1) as f32).max(0.0),
                            Edge::Bottom => -ny.min(0.0),
                            Edge::Top => (ny - (ph - 1) as f32).max(0.0),
                        };
                        overpush = if over > 0.0 { overpush + over } else { 0.0 };
                        if overpush >= RETURN_MARGIN {
                            tracing::info!("universal-control: return → laptop (edge push)");
                            lift_scroll(&mut scroll_finger, SCROLL_SLOT);
                            crate::mirror_inject::send("V 0");
                            match ic
                                .release(&session, activation_id, Some(return_pos(edge, entry)))
                                .await
                            {
                                Ok(()) => captured = false,
                                Err(e) => tracing::warn!("universal-control: release: {e}"),
                            }
                            active = false;
                            overpush = 0.0;
                            set_cursor(false);
                        }
                    }
                }
                if scroll_finger.is_some() && !active {
                    lift_scroll(&mut scroll_finger, SCROLL_SLOT);
                } else if active
                    && (scroll_finger.is_some() || scroll_dx != 0.0 || scroll_dy != 0.0)
                    && last_touch.elapsed() >= SCROLL_TICK
                {
                    let (mx, my) = (pw as f32 * SCROLL_MARGIN, ph as f32 * SCROLL_MARGIN);
                    let (lo_x, hi_x) = (mx, pw as f32 - mx);
                    let (lo_y, hi_y) = (my, ph as f32 - my);
                    let plant = |x: f32, y: f32| {
                        let (rx, ry) = touch_raw(rot, x, y, phys);
                        crate::mirror_inject::send(&format!("D {SCROLL_SLOT} {rx} {ry}"));
                    };
                    if scroll_finger.is_none() {
                        let f = (px.clamp(lo_x, hi_x), py.clamp(lo_y, hi_y));
                        plant(f.0, f.1);
                        scroll_finger = Some(f);
                        scroll_target = f;
                    }
                    let (mut fx, mut fy) = scroll_finger.unwrap_or((px, py));
                    let dt = last_touch.elapsed().as_secs_f32().max(0.001);
                    let speed = (scroll_dx * scroll_dx + scroll_dy * scroll_dy).sqrt() / dt;
                    let gain = SCROLL_GAIN
                        * (1.0 + (speed - SCROLL_ACCEL_KNEE).max(0.0) / SCROLL_ACCEL_SPAN)
                            .min(SCROLL_ACCEL_MAX);
                    scroll_target.0 -= scroll_dx * gain;
                    scroll_target.1 -= scroll_dy * gain;
                    scroll_dx = 0.0;
                    scroll_dy = 0.0;
                    fx += (scroll_target.0 - fx) * SCROLL_FOLLOW;
                    fy += (scroll_target.1 - fy) * SCROLL_FOLLOW;
                    if fx < lo_x || fx > hi_x || fy < lo_y || fy > hi_y {
                        let nx = if fx < lo_x {
                            hi_x
                        } else if fx > hi_x {
                            lo_x
                        } else {
                            fx
                        };
                        let ny = if fy < lo_y {
                            hi_y
                        } else if fy > hi_y {
                            lo_y
                        } else {
                            fy
                        };
                        scroll_target.0 += nx - fx;
                        scroll_target.1 += ny - fy;
                        fx = nx;
                        fy = ny;
                        lift_scroll(&mut scroll_finger, SCROLL_SLOT);
                        plant(fx, fy);
                    } else {
                        let (rx, ry) = touch_raw(rot, fx, fy, phys);
                        crate::mirror_inject::send(&format!("M {SCROLL_SLOT} {rx} {ry}"));
                    }
                    scroll_finger = Some((fx, fy));
                    last_touch = Instant::now();
                    if last_scroll.elapsed() >= SCROLL_LIFT {
                        lift_scroll(&mut scroll_finger, SCROLL_SLOT);
                    }
                }
                if active && !crate::mirror_inject::active() {
                    tracing::warn!("universal-control: injector gone → returning to laptop");
                    match ic
                        .release(&session, activation_id, Some(return_pos(edge, entry)))
                        .await
                    {
                        Ok(()) => captured = false,
                        Err(e) => tracing::warn!("universal-control: release: {e}"),
                    }
                    active = false;
                    overpush = 0.0;
                    set_cursor(false);
                }
                if pending && last_progress.elapsed() >= PUSH_IDLE {
                    tracing::info!("universal-control: push abandoned ({push}) → laptop");
                    match ic
                        .release(
                            &session,
                            activation_id,
                            Some(abandon_pos(edge, entry, slide, (x, y, w, h))),
                        )
                        .await
                    {
                        Ok(()) => captured = false,
                        Err(e) => tracing::warn!("universal-control: release: {e}"),
                    }
                    pending = false;
                    set_cursor(false);
                }
                if captured && last_input.elapsed() >= Duration::from_secs(6) {
                    tracing::info!("universal-control: idle release → laptop (at {px},{py})");
                    if active {
                        crate::mirror_inject::send("V 0");
                    }
                    match ic
                        .release(&session, activation_id, Some(return_pos(edge, entry)))
                        .await
                    {
                        Ok(()) => captured = false,
                        Err(e) => tracing::warn!("universal-control: release: {e}"),
                    }
                    active = false;
                    pending = false;
                    overpush = 0.0;
                    set_cursor(false);
                }
                if (active || pending) && last_tx.elapsed() >= KEEPALIVE_GAP {
                    crate::mirror_inject::send("");
                    last_tx = Instant::now();
                }
            },
        }
    }
    lift_scroll(&mut scroll_finger, SCROLL_SLOT);
    if active {
        crate::mirror_inject::send("V 0");
    }
    if let Err(e) = ic
        .release(&session, activation_id, Some(return_pos(edge, entry)))
        .await
    {
        tracing::debug!("universal-control: final release: {e}");
    }
    let _ = ic.disable(&session).await;
    let _ = session.close().await;
    set_cursor(false);
    Ok(())
}

fn lift_scroll(finger: &mut Option<(f32, f32)>, slot: u8) {
    if finger.take().is_some() {
        crate::mirror_inject::send(&format!("U {slot}"));
    }
}

fn touch_raw(rot: u32, x: f32, y: f32, nat: (i32, i32)) -> (u16, u16) {
    let (nw, nh) = (nat.0 as f32, nat.1 as f32);
    let (rx, ry) = match rot {
        1 => (nw - y, x),
        2 => (nw - x, nh - y),
        3 => (y, nh - x),
        _ => (x, y),
    };
    let n = |v: f32, span: f32| (v / span.max(1.0) * 65535.0).round().clamp(0.0, 65535.0) as u16;
    (n(rx, nw), n(ry, nh))
}

fn home_vectors(edge: Edge, pw: i32, ph: i32, tx: i32, ty: i32) -> (i32, i32, i32, i32) {
    let far = (pw.max(ph) * 2).max(20000);
    let g = crate::mirror_inject::pointer_curve().saturated();
    let step = |v: i32| (v as f32 / g).round() as i32;
    let (tx, ty) = (step(tx), step(ty));
    match edge {
        Edge::Right => (-far, -far, 0, ty),
        Edge::Left => (far, -far, 0, ty),
        Edge::Bottom => (-far, -far, tx, 0),
        Edge::Top => (-far, far, tx, 0),
    }
}

fn inward_delta(edge: Edge, dx: i32, dy: i32) -> f32 {
    match edge {
        Edge::Right => dx as f32,
        Edge::Left => -dx as f32,
        Edge::Bottom => dy as f32,
        Edge::Top => -dy as f32,
    }
}

fn along_delta(edge: Edge, dx: i32, dy: i32) -> f32 {
    match edge {
        Edge::Right | Edge::Left => dy as f32,
        Edge::Top | Edge::Bottom => dx as f32,
    }
}

fn abandon_pos(
    edge: Edge,
    entry: (f32, f32),
    slide: f32,
    region: (i32, i32, i32, i32),
) -> (f64, f64) {
    let (rx, ry, rw, rh) = region;
    let moved = match edge {
        Edge::Right | Edge::Left => (
            entry.0,
            (entry.1 + slide).clamp(ry as f32, (ry + rh - 1) as f32),
        ),
        Edge::Top | Edge::Bottom => (
            (entry.0 + slide).clamp(rx as f32, (rx + rw - 1) as f32),
            entry.1,
        ),
    };
    return_pos(edge, moved)
}

const BARRIER_ID: u32 = 1;

fn barrier_pos(edge: Edge, rect: (i32, i32, i32, i32), span: (i32, i32)) -> (i32, i32, i32, i32) {
    let (x, y, w, h) = rect;
    let (s0, sl) = span;
    match edge {
        Edge::Left => (x, s0, x, s0 + sl - 1),
        Edge::Right => (x + w, s0, x + w, s0 + sl - 1),
        Edge::Top => (s0, y, s0 + sl - 1, y),
        Edge::Bottom => (s0, y + h, s0 + sl - 1, y + h),
    }
}

fn barrier_span(seg: Segment, full: (i32, i32)) -> (i32, i32) {
    if seg == Segment::Full {
        return full;
    }
    let len = SEGMENT_LEN.min(full.1 / 2).max(1);
    match seg {
        Segment::Start => (full.0, len),
        _ => (full.0 + full.1 - len, len),
    }
}

fn entry_point(edge: Edge, pw: i32, ph: i32, entry: (f32, f32), span: (i32, i32)) -> (i32, i32) {
    let (origin, len) = span;
    let frac = |v: f32| {
        if len <= 0 {
            0.5
        } else {
            ((v - origin as f32) / len as f32).clamp(0.0, 1.0)
        }
    };
    match edge {
        Edge::Right => (0, (frac(entry.1) * (ph - 1) as f32) as i32),
        Edge::Left => (pw - 1, (frac(entry.1) * (ph - 1) as f32) as i32),
        Edge::Bottom => ((frac(entry.0) * (pw - 1) as f32) as i32, 0),
        Edge::Top => ((frac(entry.0) * (pw - 1) as f32) as i32, ph - 1),
    }
}

fn laptop_point(
    edge: Edge,
    p: (f32, f32),
    phone: (i32, i32),
    span: (i32, i32),
    rect: (i32, i32, i32, i32),
) -> (f32, f32) {
    let (pw, ph) = phone;
    let (origin, len) = span;
    let (rx, ry, rw, rh) = rect;
    let along = |v: f32, size: i32| {
        let f = if size > 1 {
            (v / (size - 1) as f32).clamp(0.0, 1.0)
        } else {
            0.5
        };
        origin as f32 + f * (len - 1).max(0) as f32
    };
    match edge {
        Edge::Right => ((rx + rw) as f32, along(p.1, ph)),
        Edge::Left => (rx as f32, along(p.1, ph)),
        Edge::Bottom => (along(p.0, pw), (ry + rh) as f32),
        Edge::Top => (along(p.0, pw), ry as f32),
    }
}

fn return_pos(edge: Edge, entry: (f32, f32)) -> (f64, f64) {
    let (x, y) = (entry.0 as f64, entry.1 as f64);
    match edge {
        Edge::Right => (x - 2.0, y),
        Edge::Left => (x + 2.0, y),
        Edge::Top => (x, y + 2.0),
        Edge::Bottom => (x, y - 2.0),
    }
}

fn edge_name(edge: Edge) -> &'static str {
    match edge {
        Edge::Left => "left",
        Edge::Right => "right",
        Edge::Top => "top",
        Edge::Bottom => "bottom",
    }
}


struct UcDbus {
    cursor_hidden: bool,
}

#[zbus::interface(name = "org.vortex.UniversalControl1")]
impl UcDbus {
    #[zbus(property)]
    async fn cursor_hidden(&self) -> bool {
        self.cursor_hidden
    }
}

static CURSOR_TX: std::sync::OnceLock<tokio::sync::mpsc::UnboundedSender<bool>> =
    std::sync::OnceLock::new();

fn set_cursor(hidden: bool) {
    if let Some(tx) = CURSOR_TX.get() {
        let _ = tx.send(hidden);
    }
}

fn ensure_cursor_publisher() {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<bool>();
    if CURSOR_TX.set(tx).is_err() {
        return;
    }
    tokio::spawn(async move {
        let conn = match zbus::connection::Builder::session()
            .and_then(|b| b.name("org.vortex.UniversalControl"))
            .and_then(|b| {
                b.serve_at(
                    "/org/vortex/UniversalControl",
                    UcDbus { cursor_hidden: false },
                )
            }) {
            Ok(b) => match b.build().await {
                Ok(c) => c,
                Err(e) => {
                    tracing::warn!("universal-control: cursor dbus build: {e}");
                    return;
                }
            },
            Err(e) => {
                tracing::warn!("universal-control: cursor dbus: {e}");
                return;
            }
        };
        let iface_ref = match conn
            .object_server()
            .interface::<_, UcDbus>("/org/vortex/UniversalControl")
            .await
        {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("universal-control: cursor dbus iface: {e}");
                return;
            }
        };
        tracing::info!("universal-control: cursor-hide D-Bus up (org.vortex.UniversalControl)");
        let _conn = conn;
        while let Some(hidden) = rx.recv().await {
            let mut iface = iface_ref.get_mut().await;
            if iface.cursor_hidden != hidden {
                iface.cursor_hidden = hidden;
                let _ = iface.cursor_hidden_changed(iface_ref.signal_emitter()).await;
                tracing::info!("universal-control: CursorHidden → {hidden} (emitted)");
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::{barrier_span, parse_placement, touch_raw, Edge, Segment, SEGMENT_LEN};

    const NAT: (i32, i32) = (1080, 2340);

    #[test]
    fn unrotated_maps_straight_through() {
        assert_eq!(touch_raw(0, 0.0, 0.0, NAT), (0, 0));
        assert_eq!(touch_raw(0, 1080.0, 2340.0, NAT), (65535, 65535));
    }

    #[test]
    fn ninety_degrees_lands_on_the_right_corner() {
        assert_eq!(touch_raw(1, 0.0, 0.0, NAT), (65535, 0));
        assert_eq!(touch_raw(1, 2340.0, 1080.0, NAT), (0, 65535));
    }

    #[test]
    fn two_seventy_is_the_other_way_round() {
        assert_eq!(touch_raw(3, 0.0, 0.0, NAT), (0, 65535));
        assert_eq!(touch_raw(3, 2340.0, 1080.0, NAT), (65535, 0));
    }

    #[test]
    fn upside_down_flips_both_axes() {
        assert_eq!(touch_raw(2, 0.0, 0.0, NAT), (65535, 65535));
        assert_eq!(touch_raw(2, 1080.0, 2340.0, NAT), (0, 0));
    }

    #[test]
    fn centre_is_rotation_invariant() {
        for (rot, x, y) in [
            (0u32, 540.0, 1170.0),
            (1, 1170.0, 540.0),
            (2, 540.0, 1170.0),
            (3, 1170.0, 540.0),
        ] {
            let (rx, ry) = touch_raw(rot, x, y, NAT);
            assert!((rx as i32 - 32768).abs() <= 1, "rot {rot}: x {rx}");
            assert!((ry as i32 - 32768).abs() <= 1, "rot {rot}: y {ry}");
        }
    }

    #[test]
    fn placement_reads_edge_and_end() {
        for (s, edge, seg) in [
            ("bottom", Edge::Bottom, Segment::Full),
            ("bottom-right", Edge::Bottom, Segment::End),
            ("bottom-left", Edge::Bottom, Segment::Start),
            ("right-bottom", Edge::Right, Segment::End),
            ("right-top", Edge::Right, Segment::Start),
            ("LEFT-Top", Edge::Left, Segment::Start),
        ] {
            let got = parse_placement(s);
            assert!(
                got.0 as u8 == edge as u8 && got.1 == seg,
                "{s} read as the wrong placement"
            );
        }
    }

    #[test]
    fn nonsense_placement_falls_back() {
        assert!(parse_placement("").1 == Segment::Full);
        assert!(parse_placement("sideways").1 == Segment::Full);
        assert!(parse_placement("bottom-sideways").1 == Segment::Full);
        assert!(parse_placement("bottom-").1 == Segment::Full);
    }

    #[test]
    fn segment_sits_at_the_end_it_names() {
        let full = (0, 2560);
        assert_eq!(barrier_span(Segment::Full, full), full);
        assert_eq!(barrier_span(Segment::Start, full), (0, SEGMENT_LEN));
        assert_eq!(
            barrier_span(Segment::End, full),
            (2560 - SEGMENT_LEN, SEGMENT_LEN)
        );
        let off = (1920, 1080);
        assert_eq!(barrier_span(Segment::Start, off).0, 1920);
        let end = barrier_span(Segment::End, off);
        assert_eq!(end.0 + end.1, 1920 + 1080);
    }

    #[test]
    fn segment_never_takes_more_than_half_the_edge() {
        for len in [1, 2, 17, 400, 799, 800, 801, 4096] {
            for seg in [Segment::Start, Segment::End] {
                let (o, l) = barrier_span(seg, (0, len));
                assert!(l >= 1, "len {len}: zero-length barrier");
                assert!(l <= len.max(1), "len {len}: longer than the edge");
                assert!(l * 2 <= len || l == 1, "len {len}: took more than half ({l})");
                assert!(o >= 0 && o + l <= len.max(1), "len {len}: {o}+{l} off the edge");
            }
        }
    }
}

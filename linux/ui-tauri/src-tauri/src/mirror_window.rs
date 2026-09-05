use std::cell::RefCell;
use std::sync::atomic::{AtomicU32, Ordering};

use gstreamer as gst;
// NOTE: only gtk's prelude belongs at module scope. GStreamer's rides glib 0.20
use gtk::prelude::*;
use gtk::{gdk, gdk_pixbuf, glib};

const LOGO_PNG: &[u8] = include_bytes!("../../src/assets/vortex_logo.png");

static VIDEO_W: AtomicU32 = AtomicU32::new(0);
static VIDEO_H: AtomicU32 = AtomicU32::new(0);

struct Win {
    window: gtk::Window,
    loading: gtk::EventBox,
    holder: gtk::Box,
    video: Option<gtk::Widget>,
}

thread_local! {
    static WIN: RefCell<Option<Win>> = const { RefCell::new(None) };
}

fn lang() -> String {
    std::env::var_os("HOME")
        .map(|h| std::path::PathBuf::from(h).join(".local/share/vortex/voice/lang"))
        .and_then(|p| std::fs::read_to_string(p).ok())
        .map(|s| s.trim().to_lowercase())
        .filter(|s| matches!(s.as_str(), "en" | "uz" | "ru"))
        .unwrap_or_else(|| "en".to_string())
}

fn connecting_text(lang: &str, name: &str) -> String {
    match lang {
        "uz" => format!("{name}ga ulanilmoqda…"),
        "ru" => format!("Подключение к {name}…"),
        _ => format!("Connecting to {name}…"),
    }
}

fn stop_tooltip(lang: &str) -> &'static str {
    match lang {
        "uz" => "Ekranni uzatishni to'xtatish",
        "ru" => "Остановить трансляцию",
        _ => "Stop mirroring",
    }
}

fn logo_image(px: i32) -> gtk::Image {
    let loader = gdk_pixbuf::PixbufLoader::new();
    let pixbuf = loader
        .write(LOGO_PNG)
        .ok()
        .and_then(|_| loader.close().ok())
        .and_then(|_| loader.pixbuf())
        .and_then(|p| p.scale_simple(px, px, gdk_pixbuf::InterpType::Bilinear));
    match pixbuf {
        Some(p) => gtk::Image::from_pixbuf(Some(&p)),
        None => gtk::Image::new(),
    }
}

fn apply_css(window: &gtk::Window) {
    let css = gtk::CssProvider::new();
    let _ = css.load_from_data(
        b"window.vortex-mirror, .vortex-mirror headerbar { background: #16161a; }
          .vortex-mirror headerbar { border: none; box-shadow: none; min-height: 38px; }
          .vortex-mirror .mirror-title { color: #f2f2f7; font-weight: 600; }
          .vortex-mirror .mirror-status { color: #a1a1aa; }
          .vortex-mirror .mirror-stage, .vortex-mirror .mirror-cover { background: #000000; }",
    );
    if let Some(screen) = WidgetExt::screen(window) {
        gtk::StyleContext::add_provider_for_screen(
            &screen,
            &css,
            gtk::STYLE_PROVIDER_PRIORITY_APPLICATION,
        );
    }
}

fn video_rect(alloc_w: f64, alloc_h: f64) -> (f64, f64, f64, f64) {
    let vw = VIDEO_W.load(Ordering::Relaxed).max(1) as f64;
    let vh = VIDEO_H.load(Ordering::Relaxed).max(1) as f64;
    let scale = (alloc_w / vw).min(alloc_h / vh);
    let w = (vw * scale).max(1.0);
    let h = (vh * scale).max(1.0);
    (((alloc_w - w) / 2.0), ((alloc_h - h) / 2.0), w, h)
}

fn to_frame(widget: &gtk::Widget, ex: f64, ey: f64) -> (u16, u16) {
    let alloc = widget.allocation();
    let (x0, y0, w, h) = video_rect(alloc.width() as f64, alloc.height() as f64);
    let nx = ((ex - x0) / w * 65535.0).clamp(0.0, 65535.0) as u16;
    let ny = ((ey - y0) / h * 65535.0).clamp(0.0, 65535.0) as u16;
    (nx, ny)
}

pub(crate) fn open(title: String, frame_w: u32, frame_h: u32, win_w: i32, win_h: i32) {
    VIDEO_W.store(frame_w, Ordering::Relaxed);
    VIDEO_H.store(frame_h, Ordering::Relaxed);
    glib::MainContext::default().invoke(move || {
        destroy_now();
        let lang = lang();

        let window = gtk::Window::new(gtk::WindowType::Toplevel);
        window.set_title(&title);
        window.set_default_size(win_w.max(240), win_h.max(320));
        window.style_context().add_class("vortex-mirror");
        apply_css(&window);

        let header = gtk::HeaderBar::new();
        header.set_show_close_button(false);
        let title_label = gtk::Label::new(Some(&title));
        title_label.style_context().add_class("mirror-title");
        header.set_custom_title(Some(&title_label));

        let close_btn =
            gtk::Button::from_icon_name(Some("window-close-symbolic"), gtk::IconSize::Menu);
        close_btn.set_relief(gtk::ReliefStyle::None);
        close_btn.set_tooltip_text(Some(stop_tooltip(&lang)));
        close_btn.set_can_focus(false);
        close_btn.connect_clicked(|_| crate::mirror::request_stop());
        header.pack_end(&close_btn);
        window.set_titlebar(Some(&header));

        let holder = gtk::Box::new(gtk::Orientation::Vertical, 0);
        holder.style_context().add_class("mirror-stage");
        holder.set_hexpand(true);
        holder.set_vexpand(true);

        let loading = gtk::EventBox::new();
        loading.style_context().add_class("mirror-cover");
        loading.set_valign(gtk::Align::Fill);
        loading.set_halign(gtk::Align::Fill);
        let inner = gtk::Box::new(gtk::Orientation::Vertical, 18);
        inner.set_valign(gtk::Align::Center);
        inner.set_halign(gtk::Align::Center);
        inner.set_vexpand(true);
        inner.add(&logo_image(96));
        let spinner = gtk::Spinner::new();
        spinner.set_size_request(24, 24);
        spinner.start();
        inner.add(&spinner);
        let status = gtk::Label::new(Some(&connecting_text(&lang, &title)));
        status.style_context().add_class("mirror-status");
        inner.add(&status);
        loading.add(&inner);

        let overlay = gtk::Overlay::new();
        overlay.add(&holder);
        overlay.add_overlay(&loading);
        window.add(&overlay);

        let aspect = frame_w.max(1) as f64 / frame_h.max(1) as f64;
        window.set_geometry_hints(
            Some(&holder),
            Some(&gdk::Geometry::new(
                200,
                (200.0 / aspect) as i32,
                -1,
                -1,
                0,
                0,
                0,
                0,
                0.0,
                0.0,
                gdk::Gravity::NorthWest,
            )),
            gdk::WindowHints::MIN_SIZE,
        );

        {
            let win = window.clone();
            let ratio = frame_w.max(1) as f64 / frame_h.max(1) as f64;
            holder.connect_size_allocate(move |_, alloc| {
                if alloc.width() <= 0 || alloc.height() <= 0 {
                    return;
                }
                let (win_w, win_h) = win.size();
                let chrome_h = win_h - alloc.height();
                let want_h = (alloc.width() as f64 / ratio).round() as i32 + chrome_h;
                if (want_h - win_h).abs() > 2 {
                    win.resize(win_w, want_h);
                }
            });
        }

        wire_keys(&window);

        window.connect_delete_event(|_, _| {
            crate::mirror::request_stop();
            glib::Propagation::Stop
        });

        window.show_all();

        if let Some(cover) = loading.window() {
            cover.ensure_native();
            cover.raise();
        }

        WIN.with(|w| *w.borrow_mut() = Some(Win { window, loading, holder, video: None }));
    });
}

fn wire_keys(window: &gtk::Window) {
    window.connect_key_press_event(|_, ev| {
        if let Some(name) = ev.keyval().name() {
            crate::mirror::on_key(true, name.as_str());
        }
        glib::Propagation::Stop
    });
    window.connect_key_release_event(|_, ev| {
        if let Some(name) = ev.keyval().name() {
            crate::mirror::on_key(false, name.as_str());
        }
        glib::Propagation::Stop
    });
}

fn wire_pointer(video: &gtk::Widget) {
    video.add_events(
        gdk::EventMask::BUTTON_PRESS_MASK
            | gdk::EventMask::BUTTON_RELEASE_MASK
            | gdk::EventMask::POINTER_MOTION_MASK
            | gdk::EventMask::SCROLL_MASK
            | gdk::EventMask::SMOOTH_SCROLL_MASK,
    );
    video.connect_button_press_event(|w, ev| {
        if ev.button() == 1 {
            let (nx, ny) = to_frame(w, ev.position().0, ev.position().1);
            crate::mirror::on_button(true, nx, ny);
        }
        glib::Propagation::Stop
    });
    video.connect_button_release_event(|w, ev| {
        if ev.button() == 1 {
            let (nx, ny) = to_frame(w, ev.position().0, ev.position().1);
            crate::mirror::on_button(false, nx, ny);
        }
        glib::Propagation::Stop
    });
    video.connect_motion_notify_event(|w, ev| {
        let (nx, ny) = to_frame(w, ev.position().0, ev.position().1);
        crate::mirror::on_motion(nx, ny);
        glib::Propagation::Stop
    });
    video.connect_scroll_event(|w, ev| {
        let (dx, dy) = match ev.direction() {
            gdk::ScrollDirection::Up => (0.0, -1.0),
            gdk::ScrollDirection::Down => (0.0, 1.0),
            gdk::ScrollDirection::Left => (-1.0, 0.0),
            gdk::ScrollDirection::Right => (1.0, 0.0),
            _ => ev.delta(),
        };
        let (nx, ny) = to_frame(w, ev.position().0, ev.position().1);
        crate::mirror::on_scroll(nx, ny, dx, dy);
        glib::Propagation::Stop
    });
}

pub(crate) fn attach_video(pipeline: gst::Pipeline) {
    let (done_tx, done_rx) = std::sync::mpsc::channel::<()>();
    glib::MainContext::default().invoke(move || {
        attach_now(&pipeline);
        let _ = done_tx.send(());
    });
    if done_rx.recv_timeout(std::time::Duration::from_secs(3)).is_err() {
        tracing::warn!("mirror-window: widget attach timed out - video may open detached");
    }
}

fn attach_now(pipeline: &gst::Pipeline) {
    use gst::prelude::*;

    let Some(vsink) = pipeline.by_name("vsink") else {
        tracing::warn!("mirror-window: no vsink - video cannot be embedded");
        return;
    };
    let obj = vsink.property::<gst::glib::Object>("widget");
    let widget: gtk::Widget = unsafe {
        use gst::glib::translate::ToGlibPtr;
        let ptr: *mut gst::glib::gobject_ffi::GObject = obj.to_glib_none().0;
        gtk::glib::translate::from_glib_none(ptr as *mut gtk::ffi::GtkWidget)
    };

    WIN.with(|w| {
        let mut slot = w.borrow_mut();
        let Some(win) = slot.as_mut() else { return };
        for child in win.holder.children() {
            win.holder.remove(&child);
        }
        widget.set_hexpand(true);
        widget.set_vexpand(true);
        widget.set_can_focus(true);
        wire_pointer(&widget);
        win.holder.add(&widget);
        win.holder.show_all();
        if let Some(cover) = win.loading.window() {
            cover.raise();
        }
        win.video = Some(widget);
    });
}

pub(crate) fn show_video() {
    glib::MainContext::default().invoke(|| {
        WIN.with(|w| match w.borrow().as_ref() {
            Some(win) => {
                win.loading.hide();
                if let Some(v) = win.video.as_ref() {
                    v.grab_focus();
                }
                tracing::info!("mirror-window: first frame — revealing the video");
            }
            None => tracing::warn!("mirror-window: first frame but no window is up"),
        });
    });
}

pub(crate) fn close() {
    glib::MainContext::default().invoke(destroy_now);
}

fn destroy_now() {
    WIN.with(|w| {
        if let Some(win) = w.borrow_mut().take() {
            unsafe {
                win.window.destroy();
            }
        }
    });
}

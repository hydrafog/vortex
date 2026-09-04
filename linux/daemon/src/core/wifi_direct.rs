use std::sync::OnceLock;

type Hook = Box<dyn Fn(String, String) + Send + Sync>;

static HOOK: OnceLock<Hook> = OnceLock::new();

pub fn set_hook(hook: Hook) {
    let _ = HOOK.set(hook);
}

pub fn report(ssid: String, pass: String) {
    if let Some(h) = HOOK.get() {
        h(ssid, pass);
    }
}

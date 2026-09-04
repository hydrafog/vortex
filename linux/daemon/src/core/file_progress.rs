use std::sync::OnceLock;

type Hook = Box<dyn Fn(u32, u32) + Send + Sync>;

static HOOK: OnceLock<Hook> = OnceLock::new();

pub fn set_hook(hook: Hook) {
    let _ = HOOK.set(hook);
}

pub fn report(received_chunks: u32, total_chunks: u32) {
    if let Some(h) = HOOK.get() {
        h(received_chunks, total_chunks);
    }
}

pub mod peers;
pub mod secret_service;

use std::sync::{Arc, Mutex, OnceLock};

use crate::core::crypto::x25519::{X25519Sec, X25519SecBytes};
use crate::core::identity::{IdentityPublicView, IdentityRecord, Platform};

static SECRET_RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

fn secret_rt() -> &'static tokio::runtime::Runtime {
    SECRET_RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .thread_name("vortex-secret-store")
            .enable_all()
            .build()
            .expect("secret-store runtime build")
    })
}

pub(crate) fn secret_block_on<F>(fut: F) -> F::Output
where
    F: std::future::Future + Send,
    F::Output: Send,
{
    use tokio::runtime::{Handle, RuntimeFlavor};
    match Handle::try_current() {
        Ok(h) if h.runtime_flavor() == RuntimeFlavor::MultiThread => {
            tokio::task::block_in_place(|| secret_rt().block_on(fut))
        }
        Ok(_) => std::thread::scope(|s| {
            s.spawn(|| secret_rt().block_on(fut))
                .join()
                .expect("secret-store scoped thread panicked")
        }),
        Err(_) => secret_rt().block_on(fut),
    }
}

#[derive(Debug)]
pub enum StorageError {
    NotFound,
    Backend(String),
    Locked(String),
}

impl std::fmt::Display for StorageError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotFound => write!(f, "not found"),
            Self::Backend(msg) => write!(f, "backend error: {msg}"),
            Self::Locked(msg) => write!(f, "keyring locked: {msg}"),
        }
    }
}

impl std::error::Error for StorageError {}

pub type StorageResult<T> = Result<T, StorageError>;

pub(crate) async fn unlocked_default_collection<'a>(
    service: &'a ::secret_service::SecretService<'a>,
) -> StorageResult<::secret_service::Collection<'a>> {
    let collection = service
        .get_default_collection()
        .await
        .map_err(|e| StorageError::Backend(format!("default collection: {e}")))?;
    if collection.is_locked().await.unwrap_or(false) {
        collection
            .unlock()
            .await
            .map_err(|e| StorageError::Locked(format!("unlock refused or unavailable: {e}")))?;
    }
    Ok(collection)
}

pub trait IdentityStore: Send + Sync {
    fn save(&self, record: &IdentityRecord) -> StorageResult<()>;
    fn load(&self) -> StorageResult<IdentityRecord>;
    fn forget(&self) -> StorageResult<()>;
    fn exists(&self) -> bool {
        self.load().is_ok()
    }
}

pub fn load_or_generate<S: IdentityStore + ?Sized>(
    store: &S,
    platform: Platform,
) -> StorageResult<IdentityRecord> {
    match store.load() {
        Ok(rec) => Ok(rec),
        Err(StorageError::NotFound) => {
            let rec = IdentityRecord::generate(platform);
            store.save(&rec)?;
            Ok(rec)
        }
        Err(err) => Err(err),
    }
}

#[derive(Default, Clone)]
pub struct InMemoryIdentityStore {
    inner: Arc<Mutex<Option<StoredIdentity>>>,
}

#[derive(Clone)]
struct StoredIdentity {
    public: IdentityPublicView,
    static_priv: X25519SecBytes,
}

impl InMemoryIdentityStore {
    pub fn new() -> Self {
        Self::default()
    }
}

impl IdentityStore for InMemoryIdentityStore {
    fn save(&self, record: &IdentityRecord) -> StorageResult<()> {
        let mut g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        *g = Some(StoredIdentity { public: record.into(), static_priv: record.static_priv.0 });
        Ok(())
    }

    fn load(&self) -> StorageResult<IdentityRecord> {
        let g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let stored = g.as_ref().ok_or(StorageError::NotFound)?;

        let mut device_id = [0u8; 16];
        device_id.copy_from_slice(&stored.public.device_id);
        let platform = stored.public.platform;
        Ok(IdentityRecord::from_private(
            platform,
            device_id,
            stored.static_priv,
            stored.public.created_at,
        ))
    }

    fn forget(&self) -> StorageResult<()> {
        let mut g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        *g = None;
        Ok(())
    }
}

#[allow(dead_code)]
fn _keep_x25519_sec_in_use(_: X25519Sec) {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_in_memory() {
        let store = InMemoryIdentityStore::new();
        assert!(!store.exists());

        let record = IdentityRecord::generate(Platform::Linux);
        store.save(&record).unwrap();

        let loaded = store.load().unwrap();
        assert_eq!(loaded.device_id, record.device_id);
        assert_eq!(loaded.static_priv.0, record.static_priv.0);
        assert_eq!(loaded.static_pub.0, record.static_pub.0);
        assert_eq!(loaded.platform, record.platform);
    }

    #[test]
    fn forget_clears_store() {
        let store = InMemoryIdentityStore::new();
        store.save(&IdentityRecord::generate(Platform::Linux)).unwrap();
        assert!(store.exists());
        store.forget().unwrap();
        assert!(!store.exists());
    }

    #[test]
    fn load_or_generate_creates_then_reuses() {
        let store = InMemoryIdentityStore::new();
        let first = load_or_generate(&store, Platform::Linux).unwrap();
        let second = load_or_generate(&store, Platform::Linux).unwrap();
        assert_eq!(first.device_id, second.device_id);
        assert_eq!(first.static_pub.0, second.static_pub.0);
    }
}

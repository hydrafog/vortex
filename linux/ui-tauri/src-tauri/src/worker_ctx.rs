
use std::sync::Arc;

use tauri::AppHandle;

use vortex_l3_daemon::core::audio_lan_session::SessionWriterMap;
use vortex_l3_daemon::core::audio_orchestrator::SwitchOrchestrator;
use vortex_l3_daemon::core::identity::IdentityRecord;
use vortex_l3_daemon::core::storage::peers::PeerStore;

pub(crate) struct WorkerCtx {
    pub app: AppHandle,
    pub adapter: bluer::Adapter,
    pub identity: IdentityRecord,
    pub peer_store: Arc<dyn PeerStore>,
    pub switch_orchestrator: Arc<SwitchOrchestrator>,
    pub session_writers: SessionWriterMap,
}

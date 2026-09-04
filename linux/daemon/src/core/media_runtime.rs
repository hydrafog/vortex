use std::sync::Arc;
use std::time::{Duration, SystemTime};

use tokio::sync::RwLock;
use tracing::{debug, info, warn};
use zbus::zvariant::OwnedValue;
use zbus::{Connection, Proxy};

const RESUME_TTL: Duration = Duration::from_secs(5 * 60);

#[derive(Debug, Clone)]
pub enum MediaState {
    Idle,
    PausedForCall { bus_names: Vec<String>, paused_at: SystemTime },
}

impl MediaState {
    pub fn is_paused(&self) -> bool {
        matches!(self, MediaState::PausedForCall { .. })
    }

    pub fn age(&self) -> Option<Duration> {
        if let MediaState::PausedForCall { paused_at, .. } = self {
            SystemTime::now().duration_since(*paused_at).ok()
        } else {
            None
        }
    }
}

pub type MediaStateStore = Arc<RwLock<MediaState>>;

pub fn new_media_state_store() -> MediaStateStore {
    Arc::new(RwLock::new(MediaState::Idle))
}

pub async fn pause_playing_for_call(store: &MediaStateStore) -> Vec<String> {
    {
        let s = store.read().await;
        if s.is_paused() {
            warn!("pause_playing_for_call: state already Paused; skipping");
            return Vec::new();
        }
    }

    let conn = match Connection::session().await {
        Ok(c) => c,
        Err(e) => {
            warn!("pause: session bus unavailable: {e}");
            return Vec::new();
        }
    };

    let players = match list_mpris_players(&conn).await {
        Ok(p) => p,
        Err(e) => {
            warn!("pause: list MPRIS failed: {e}");
            return Vec::new();
        }
    };

    let mut paused: Vec<String> = Vec::new();
    for name in players {
        match player_is_playing(&conn, &name).await {
            Ok(true) => {
                if call_player_method(&conn, &name, "Pause").await.is_ok() {
                    info!(player = %name, "paused for call");
                    paused.push(name);
                }
            }
            Ok(false) => {
                debug!(player = %name, "not playing, skip");
            }
            Err(e) => {
                debug!(player = %name, "playback-status read failed: {e}");
            }
        }
    }

    if !paused.is_empty() {
        let mut s = store.write().await;
        *s = MediaState::PausedForCall { bus_names: paused.clone(), paused_at: SystemTime::now() };
    }
    paused
}

pub async fn resume_paused_for_call(store: &MediaStateStore) -> Vec<String> {
    let (bus_names, paused_at) = {
        let s = store.read().await;
        match s.clone() {
            MediaState::PausedForCall { bus_names, paused_at } => (bus_names, paused_at),
            MediaState::Idle => return Vec::new(),
        }
    };

    let age = SystemTime::now().duration_since(paused_at).unwrap_or(Duration::ZERO);
    if age > RESUME_TTL {
        info!(age_s = age.as_secs(), "skip resume: pause record too old");
        let mut s = store.write().await;
        *s = MediaState::Idle;
        return Vec::new();
    }

    let conn = match Connection::session().await {
        Ok(c) => c,
        Err(e) => {
            warn!("resume: session bus unavailable: {e}");
            return Vec::new();
        }
    };

    let mut resumed: Vec<String> = Vec::new();
    for name in &bus_names {
        if call_player_method(&conn, name, "Play").await.is_ok() {
            info!(player = %name, "resumed after call");
            resumed.push(name.clone());
        } else {
            debug!(player = %name, "resume failed (player gone?)");
        }
    }

    tokio::time::sleep(Duration::from_millis(50)).await;
    for name in &bus_names {
        if let Ok(false) = player_is_playing(&conn, name).await {
            let _ = call_player_method(&conn, name, "Play").await;
        }
    }

    let mut s = store.write().await;
    *s = MediaState::Idle;
    drop(s);

    let bg_bus_names = bus_names.clone();
    tokio::spawn(async move {
        let bg_conn = match Connection::session().await {
            Ok(c) => c,
            Err(_) => return,
        };
        let deltas_ms: [u64; 10] = [40, 40, 40, 80, 300, 400, 600, 700, 800, 1000];
        let mut elapsed_ms = 0u64;
        for delta in deltas_ms {
            tokio::time::sleep(Duration::from_millis(delta)).await;
            elapsed_ms += delta;
            let mut replayed: Vec<String> = Vec::new();
            for name in &bg_bus_names {
                if let Ok(false) = player_is_playing(&bg_conn, name).await {
                    let _ = call_player_method(&bg_conn, name, "Play").await;
                    replayed.push(name.clone());
                }
            }
            if !replayed.is_empty() {
                info!(
                    checkpoint_ms = elapsed_ms,
                    ?replayed,
                    "safety-net: player(s) had paused — re-played"
                );
            }
        }
        info!("safety-net: window closed (4000ms)");
    });

    resumed
}

pub async fn clear(store: &MediaStateStore) {
    let mut s = store.write().await;
    *s = MediaState::Idle;
}

pub(crate) async fn pause_all_playing(conn: &Connection) -> Vec<String> {
    let mut paused = Vec::new();
    if let Ok(players) = list_mpris_players(conn).await {
        for p in players {
            if player_is_playing(conn, &p).await.unwrap_or(false)
                && call_player_method(conn, &p, "Pause").await.is_ok()
            {
                paused.push(p);
            }
        }
    }
    paused
}

pub(crate) async fn play_players(conn: &Connection, names: &[String]) {
    for n in names {
        let _ = call_player_method(conn, n, "Play").await;
    }
}

pub(crate) async fn playing_players(conn: &Connection) -> Vec<String> {
    let mut out = Vec::new();
    if let Ok(players) = list_mpris_players(conn).await {
        for p in players {
            if player_is_playing(conn, &p).await.unwrap_or(false) {
                out.push(p);
            }
        }
    }
    out
}

static SESSION_CONN: tokio::sync::OnceCell<Connection> = tokio::sync::OnceCell::const_new();

pub async fn session_conn() -> Option<&'static Connection> {
    match SESSION_CONN.get_or_try_init(Connection::session).await {
        Ok(c) => Some(c),
        Err(e) => {
            warn!("session bus unavailable for media remote: {e}");
            None
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct NowPlaying {
    pub title: String,
    pub artist: String,
    pub app: String,
    pub art_url: String,
    pub playing: bool,
}

pub async fn now_playing(conn: &Connection) -> Option<NowPlaying> {
    let players = list_mpris_players(conn).await.ok()?;
    let mut best: Option<NowPlaying> = None;
    for p in &players {
        let playing = player_is_playing(conn, p).await.unwrap_or(false);
        if !playing && best.is_some() {
            continue;
        }
        let (title, artist, art_url) = player_metadata(conn, p).await;
        if title.is_empty() {
            continue;
        }
        let np =
            NowPlaying { title, artist, app: player_identity(conn, p).await, art_url, playing };
        if playing {
            return Some(np);
        }
        best = Some(np);
    }
    best
}

async fn player_metadata(conn: &Connection, bus_name: &str) -> (String, String, String) {
    const NONE: (String, String, String) = (String::new(), String::new(), String::new());
    let Ok(proxy) =
        Proxy::new(conn, bus_name, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties")
            .await
    else {
        return NONE;
    };
    let Ok(value) =
        proxy.call::<_, _, OwnedValue>("Get", &("org.mpris.MediaPlayer2.Player", "Metadata")).await
    else {
        return NONE;
    };
    let Ok(dict) = <std::collections::HashMap<String, OwnedValue>>::try_from(value) else {
        return NONE;
    };
    let str_of = |key: &str| -> String {
        dict.get(key).and_then(|v| String::try_from(v.try_clone().ok()?).ok()).unwrap_or_default()
    };
    let title = str_of("xesam:title");
    let artist = dict
        .get("xesam:artist")
        .and_then(|v| Vec::<String>::try_from(v.try_clone().ok()?).ok())
        .and_then(|a| a.into_iter().find(|s| !s.is_empty()))
        .unwrap_or_default();
    let art = art_url_of(&str_of("mpris:artUrl"), &str_of("xesam:url"));
    (title.trim().to_string(), artist.trim().to_string(), art)
}

fn art_url_of(art_url: &str, page_url: &str) -> String {
    if art_url.starts_with("http://") || art_url.starts_with("https://") {
        return art_url.to_string();
    }
    youtube_thumb(page_url).unwrap_or_default()
}

fn youtube_thumb(page_url: &str) -> Option<String> {
    let rest = page_url.strip_prefix("https://").or_else(|| page_url.strip_prefix("http://"))?;
    let (host, path) = rest.split_once('/')?;
    let host = host.strip_prefix("www.").unwrap_or(host);
    let id = match host {
        "youtu.be" => path.split(['?', '&', '/']).next()?,
        "youtube.com" | "m.youtube.com" | "music.youtube.com" => {
            if let Some(short) = path.strip_prefix("shorts/") {
                short.split(['?', '&', '/']).next()?
            } else {
                path.split_once('?')?.1.split('&').find_map(|kv| kv.strip_prefix("v="))?
            }
        }
        _ => return None,
    };
    if id.len() == 11 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
        Some(format!("https://i.ytimg.com/vi/{id}/hqdefault.jpg"))
    } else {
        None
    }
}

async fn player_identity(conn: &Connection, bus_name: &str) -> String {
    let ident = async {
        let proxy = Proxy::new(
            conn,
            bus_name,
            "/org/mpris/MediaPlayer2",
            "org.freedesktop.DBus.Properties",
        )
        .await
        .ok()?;
        let value = proxy
            .call::<_, _, OwnedValue>("Get", &("org.mpris.MediaPlayer2", "Identity"))
            .await
            .ok()?;
        String::try_from(value).ok().filter(|s| !s.is_empty())
    }
    .await;
    ident.unwrap_or_else(|| {
        bus_name
            .strip_prefix("org.mpris.MediaPlayer2.")
            .unwrap_or(bus_name)
            .split('.')
            .next()
            .unwrap_or_default()
            .to_string()
    })
}

pub async fn media_control(conn: &Connection, cmd: &str) {
    let method: &'static str = match cmd {
        "media_play_pause" => "PlayPause",
        "media_next" => "Next",
        "media_prev" => "Previous",
        _ => {
            warn!(cmd, "unknown media-control command; ignoring");
            return;
        }
    };
    let Ok(players) = list_mpris_players(conn).await else {
        return;
    };
    let mut target: Option<String> = players.first().cloned();
    for p in &players {
        if player_is_playing(conn, p).await.unwrap_or(false) {
            target = Some(p.clone());
            break;
        }
    }
    let Some(t) = target else {
        info!(cmd, "media-control but no MPRIS player is up");
        return;
    };
    match call_player_method(conn, &t, method).await {
        Ok(()) => info!(cmd, player = %t, "media-control executed"),
        Err(e) => warn!(cmd, player = %t, "media-control failed: {e}"),
    }
}

pub(crate) async fn list_mpris_players(conn: &Connection) -> zbus::Result<Vec<String>> {
    let proxy =
        Proxy::new(conn, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus")
            .await?;
    let names: Vec<String> = proxy.call("ListNames", &()).await?;
    Ok(names
        .into_iter()
        .filter(|n| n.starts_with("org.mpris.MediaPlayer2.") && !is_mpris_proxy(n))
        .collect())
}

fn is_mpris_proxy(bus_name: &str) -> bool {
    bus_name == "org.mpris.MediaPlayer2.playerctld"
}

pub(crate) async fn player_is_playing(conn: &Connection, bus_name: &str) -> zbus::Result<bool> {
    let proxy =
        Proxy::new(conn, bus_name, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties")
            .await?;
    let value: OwnedValue =
        proxy.call("Get", &("org.mpris.MediaPlayer2.Player", "PlaybackStatus")).await?;
    let s: String = String::try_from(value).unwrap_or_default();
    Ok(s == "Playing")
}

async fn call_player_method(
    conn: &Connection,
    bus_name: &str,
    method: &'static str,
) -> zbus::Result<()> {
    let proxy =
        Proxy::new(conn, bus_name, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player")
            .await?;
    proxy.call::<_, _, ()>(method, &()).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn art_url_prefers_a_real_http_art_url() {
        let art = "https://i.scdn.co/image/abc";
        assert_eq!(art_url_of(art, "https://youtu.be/aaaaaaaaaaa"), art);
    }

    #[test]
    fn art_url_rejects_art_the_phone_cannot_fetch() {
        assert_eq!(art_url_of("file:///tmp/cover.png", ""), "");
        assert_eq!(art_url_of("data:image/png;base64,iVBOR", ""), "");
    }

    #[test]
    fn youtube_thumb_covers_the_shapes_a_browser_publishes() {
        let want = Some("https://i.ytimg.com/vi/u3SgSdVOFQw/hqdefault.jpg".to_string());
        assert_eq!(youtube_thumb("https://www.youtube.com/watch?v=u3SgSdVOFQw"), want);
        assert_eq!(youtube_thumb("https://m.youtube.com/watch?t=42&v=u3SgSdVOFQw"), want);
        assert_eq!(youtube_thumb("https://youtu.be/u3SgSdVOFQw?t=42"), want);
        assert_eq!(youtube_thumb("https://www.youtube.com/shorts/u3SgSdVOFQw"), want);
    }

    #[test]
    fn youtube_thumb_declines_what_it_did_not_parse() {
        assert_eq!(youtube_thumb("https://vimeo.com/watch?v=u3SgSdVOFQw"), None);
        assert_eq!(youtube_thumb("https://www.youtube.com/feed/subscriptions"), None);
        assert_eq!(youtube_thumb("https://www.youtube.com/watch?v=abc"), None);
        assert_eq!(youtube_thumb("file:///home/u/song.mp3"), None);
    }

    #[tokio::test]
    async fn idle_initial_state() {
        let store = new_media_state_store();
        let s = store.read().await;
        assert!(matches!(*s, MediaState::Idle));
        assert!(!s.is_paused());
        assert!(s.age().is_none());
    }

    #[tokio::test]
    async fn paused_state_age_grows() {
        let store = new_media_state_store();
        {
            let mut s = store.write().await;
            *s = MediaState::PausedForCall {
                bus_names: vec!["org.mpris.MediaPlayer2.spotify".into()],
                paused_at: SystemTime::now() - Duration::from_secs(2),
            };
        }
        let s = store.read().await;
        assert!(s.is_paused());
        let age = s.age().expect("paused state must have age");
        assert!(age >= Duration::from_secs(2));
    }

    #[tokio::test]
    async fn clear_drops_pause_record() {
        let store = new_media_state_store();
        {
            let mut s = store.write().await;
            *s = MediaState::PausedForCall {
                bus_names: vec!["a".into()],
                paused_at: SystemTime::now(),
            };
        }
        clear(&store).await;
        let s = store.read().await;
        assert!(matches!(*s, MediaState::Idle));
    }

    #[tokio::test]
    async fn resume_skips_stale() {
        let store = new_media_state_store();
        {
            let mut s = store.write().await;
            *s = MediaState::PausedForCall {
                bus_names: vec!["org.mpris.MediaPlayer2.imaginary".into()],
                paused_at: SystemTime::now() - Duration::from_secs(6 * 60),
            };
        }
        let resumed = resume_paused_for_call(&store).await;
        assert!(resumed.is_empty());
        let s = store.read().await;
        assert!(matches!(*s, MediaState::Idle));
    }
}

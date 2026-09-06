import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export interface IdentityInfo {
  ready: boolean;
}

export interface ScanHit {
  addr: string;
  rssi: number;
  instance: string;
  name: string | null;
}

export interface TrustedPeer {
  peer_static_pub: string;
  paired_at: number;
  peer_name?: string | null;
}

export interface PeerState {
  peer_static_pub: string;
  battery: number | null;
  class: "unknown" | "laptop" | "phone" | "tablet" | "earbuds";
  name: string | null;
  locale: string | null;
  theme: string | null;
  earbuds: { name: string; battery: number | null; connected: boolean } | null;
  charging: boolean;
  ts: number;
}

export interface PairingStartedEvent {
  peer_addr: string;
}

export type PairingResultEvent =
  | { ok: true; message: string }
  | { ok: false; error: string };

export async function startScan(): Promise<void> {
  await invoke("start_scan");
}

export async function startPair(addr: string): Promise<void> {
  await invoke("start_pair", { addr });
}

export async function removeBond(addr: string): Promise<void> {
  await invoke("remove_bond", { addr });
}

export async function pairDecision(approve: boolean): Promise<void> {
  await invoke("pair_decision", { approve });
}

export async function forgetPeer(peerStaticPub: string): Promise<void> {
  await invoke("forget_peer", { peerStaticPub });
}

export async function forgetAll(): Promise<void> {
  await invoke("forget_all");
}

export async function refreshState(): Promise<void> {
  await invoke("refresh_state");
}

export async function refreshLocalEarbuds(): Promise<void> {
  await invoke("refresh_local_earbuds");
}

export async function setSmartSwitchEnabled(enabled: boolean): Promise<void> {
  await invoke("set_smart_switch_enabled", { enabled });
}

export async function getSmartSwitchEnabled(): Promise<boolean> {
  return await invoke<boolean>("get_smart_switch_enabled");
}

export interface ProximitySettings {
  auto_lock: boolean;
  auto_unlock: boolean;
}

export async function getProximitySettings(): Promise<ProximitySettings> {
  return await invoke<ProximitySettings>("get_proximity_settings");
}

export async function setProximitySettings(s: ProximitySettings): Promise<void> {
  await invoke("set_proximity_settings", {
    autoLock: s.auto_lock,
    autoUnlock: s.auto_unlock,
  });
}

export async function setNotifMirrorShow(show: boolean): Promise<void> {
  await invoke("set_notif_mirror_show", { show });
}

export async function getNotifMirrorShow(): Promise<boolean> {
  return await invoke<boolean>("get_notif_mirror_show");
}

export async function setNotifMirrorSend(send: boolean): Promise<void> {
  await invoke("set_notif_mirror_send", { send });
}

export async function getNotifMirrorSend(): Promise<boolean> {
  return await invoke<boolean>("get_notif_mirror_send");
}

export interface BluetoothDeviceRow {
  address: string;
  name: string;
  rssi: number | null;
  connected: boolean;
  is_audio: boolean;
}

export async function scanBluetoothDevices(): Promise<BluetoothDeviceRow[]> {
  return await invoke<BluetoothDeviceRow[]>("scan_bluetooth_devices");
}

export async function saveEarbuds(address: string, name: string): Promise<void> {
  await invoke("save_earbuds", { address, name });
}

export async function clearEarbuds(): Promise<void> {
  await invoke("clear_earbuds");
}

export interface SavedEarbuds {
  address: string;
  name: string;
}

export async function getSavedEarbuds(): Promise<SavedEarbuds | null> {
  return await invoke<SavedEarbuds | null>("get_saved_earbuds");
}

export function onIdentity(cb: (id: IdentityInfo) => void): Promise<UnlistenFn> {
  return listen<IdentityInfo>("vortex:identity", e => cb(e.payload));
}

export function onPeers(cb: (peers: TrustedPeer[]) => void): Promise<UnlistenFn> {
  return listen<TrustedPeer[]>("vortex:peers", e => cb(e.payload));
}

export function onScanResult(cb: (hit: ScanHit) => void): Promise<UnlistenFn> {
  return listen<ScanHit>("vortex:scan_result", e => cb(e.payload));
}

export function onScanDone(cb: () => void): Promise<UnlistenFn> {
  return listen<null>("vortex:scan_done", () => cb());
}

export function onPairingStarted(cb: (e: PairingStartedEvent) => void): Promise<UnlistenFn> {
  return listen<PairingStartedEvent>("vortex:pairing_started", e => cb(e.payload));
}

export function onPairingResult(cb: (e: PairingResultEvent) => void): Promise<UnlistenFn> {
  return listen<PairingResultEvent>("vortex:pairing_result", e => cb(e.payload));
}

export function onPairingSas(cb: (sas: string) => void): Promise<UnlistenFn> {
  return listen<string>("vortex:pairing_sas", e => cb(e.payload));
}

export function onPeerStoreError(cb: (msg: string) => void): Promise<UnlistenFn> {
  return listen<string>("vortex:peer_store_error", e => cb(e.payload));
}

export function onFatal(cb: (msg: string) => void): Promise<UnlistenFn> {
  return listen<string>("vortex:fatal", e => cb(e.payload));
}

export interface EarbudsSnapshot {
  name: string;
  battery: number | null;
  connected: boolean;
}
export function onLocalEarbuds(
  cb: (e: EarbudsSnapshot | null) => void,
): Promise<UnlistenFn> {
  return listen<EarbudsSnapshot | null>("vortex:local_earbuds", e => cb(e.payload));
}

export function onPeerState(cb: (state: PeerState) => void): Promise<UnlistenFn> {
  return listen<PeerState>("vortex:peer_state", e => cb(e.payload));
}

export async function getPeerStates(): Promise<PeerState[]> {
  return await invoke<PeerState[]>("get_peer_states");
}

export function onMirrorPlayer(cb: (message: string) => void): Promise<UnlistenFn> {
  return listen<{ message: string }>("mirror-player", e => cb(e.payload?.message ?? ""));
}

export async function stopScreenMirror(): Promise<void> {
  await invoke("stop_screen_mirror");
}

export function onBusy(cb: (busy: boolean) => void): Promise<UnlistenFn> {
  return listen<boolean>("vortex:busy", e => cb(e.payload));
}


export type SwitchState =
  | { kind: "idle" }
  | { kind: "preparing" }
  | { kind: "waiting_approval" }
  | { kind: "waiting_released" }
  | { kind: "connecting" }
  | { kind: "almost_done" }
  | { kind: "failed"; reason: string };

export function onSwitchState(cb: (s: SwitchState) => void): Promise<UnlistenFn> {
  return listen<SwitchState>("vortex:switch_state", e => cb(e.payload));
}

export async function requestEarbudsSwitch(peerStaticPub: string, mac: string): Promise<void> {
  await invoke("request_earbuds_switch", { peerStaticPub, mac });
}

export async function sendEarbudsClaim(peerStaticPub: string, mac: string): Promise<void> {
  await invoke("send_earbuds_claim", { peerStaticPub, mac });
}

export async function getSystemAccentColor(): Promise<string | null> {
  return await invoke<string | null>("get_system_accent_color");
}

export async function getLocalDeviceName(): Promise<string> {
  try {
    return await invoke<string>("get_local_device_name");
  } catch {
    return "Linux";
  }
}

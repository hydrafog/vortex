import { ref, computed, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import type { UnlistenFn } from "@tauri-apps/api/event";
import { SolarBattery, SolarBatteryLow, SolarBatteryHalf, SolarBatteryFull, SolarBatteryCharge } from "@/lib/solarIcons";
import {
  startScan, startPair, forgetPeer, refreshState, refreshLocalEarbuds,
  requestEarbudsSwitch, sendEarbudsClaim, getSavedEarbuds, onSwitchState,
  type SwitchState, scanBluetoothDevices, saveEarbuds, clearEarbuds,
  onScanResult, onScanDone, onPairingStarted, onPairingResult, onPairingSas,
  pairDecision, onLocalEarbuds, onBusy,
  type ScanHit, type TrustedPeer, type PairingResultEvent, type PeerState,
  type EarbudsSnapshot, type BluetoothDeviceRow,
} from "@/lib/bridge";
import { initSmartSwitch } from "@/lib/smartSwitch";
import { initNotifMirror } from "@/lib/notifMirror";
import { peers, peersLoaded, peerStates } from "@/lib/connectionStore";


export async function openBluetoothSettings() {
  const candidates = [
    ["gnome-control-center", "bluetooth"],
    ["blueberry"],
    ["blueman-manager"],
    ["systemsettings5", "bluetooth"],
  ];
  for (const argv of candidates) {
    try {
      void argv;
      await invoke("open_bluetooth_settings");
      return;
    } catch (_) {
    }
  }
}

export const scanHits = ref<ScanHit[]>([]);
export const scanning = ref(false);
export const pairingPeer = ref<string | null>(null);
export const pairingResult = ref<PairingResultEvent | null>(null);
export const pairingSas = ref<string | null>(null);
export const pairLocalRejected = ref(false);


export const localEarbuds = ref<EarbudsSnapshot | null>(null);

export const forgetTarget = ref<TrustedPeer | null>(null);

export const showPairPhoneModal = ref(false);

export const earbudsMenuOpen = ref(false);
export const earbudsAddOpen = ref(false);
export const earbudsScanning = ref(false);
export const earbudsScanResults = ref<BluetoothDeviceRow[]>([]);

export const switchState = ref<SwitchState>({ kind: "idle" });
export const isSwitching = computed(() => {
  const k = switchState.value.kind;
  return k !== "idle" && k !== "failed" && k !== "almost_done";
});
export const flowInProgress = computed(() => {
  const k = switchState.value.kind;
  return k !== "idle" && k !== "failed";
});
export async function openEarbudsPicker() {
  earbudsAddOpen.value = true;
  earbudsScanResults.value = [];
  earbudsScanning.value = true;
  try {
    earbudsScanResults.value = await scanBluetoothDevices();
  } catch (e) {
    console.warn("BT scan failed", e);
  } finally {
    earbudsScanning.value = false;
  }
}

export async function pickEarbud(d: BluetoothDeviceRow) {
  await saveEarbuds(d.address, d.name);
  earbudsAddOpen.value = false;
  refreshLocalEarbuds().catch(() => {});
}

export async function removeEarbuds() {
  await clearEarbuds();
  earbudsMenuOpen.value = false;
  refreshLocalEarbuds().catch(() => {});
}

export const canSwitch = computed(() =>
  primaryPeer.value != null && localEarbuds.value?.name != null
);

export async function macForSwitch(): Promise<string | null> {
  try {
    const saved = await getSavedEarbuds();
    return saved?.address ?? null;
  } catch (e) {
    console.warn("macForSwitch: getSavedEarbuds failed", e);
    return null;
  }
}

export async function startSwitch() {
  const peer = primaryPeer.value;
  if (!peer) return;
  const mac = await macForSwitch();
  if (!mac) {
    console.warn("startSwitch: no MAC available for switch");
    return;
  }
  const direction = activeEarbuds.value?.on === "local" ? "send" : "claim";
  try {
    if (direction === "send") {
      await sendEarbudsClaim(peer.peer_static_pub, mac);
    } else {
      await requestEarbudsSwitch(peer.peer_static_pub, mac);
    }
  } catch (e) {
    console.warn("startSwitch failed", e);
  }
}

export const mirrorStarting = ref(false);
export const mirrorActive = ref(false);

let mirrorListenerSet = false;
async function ensureMirrorListener() {
  if (mirrorListenerSet) return;
  mirrorListenerSet = true;
  const { onMirrorPlayer } = await import("@/lib/bridge");
  await onMirrorPlayer((msg) => {
    mirrorActive.value = msg.includes("opening");
  });
}

export async function stopMirror() {
  mirrorActive.value = false;
  try {
    const { stopScreenMirror } = await import("@/lib/bridge");
    await stopScreenMirror();
  } catch (e) {
    console.warn("stopMirror failed", e);
  }
}

export async function startMirror() {
  if (!primaryPeer.value || !phoneOnline.value || mirrorStarting.value) return;
  void ensureMirrorListener();
  mirrorStarting.value = true;
  const cfg = { width: 720, height: 1560, fps: 60, bitrate: 10_000_000, transport: "wifi" };
  try {
    await invoke("start_screen_mirror", cfg);
  } catch (e) {
    console.warn("startMirror failed", e);
  } finally {
    setTimeout(() => {
      mirrorStarting.value = false;
    }, 2500);
  }
}

export function onCardTap() {
  if (canSwitch.value && !flowInProgress.value) {
    startSwitch();
  }
}

export function sendToPeer() {
  earbudsMenuOpen.value = false;
  startSwitch();
}
export let earbudsPressTimer: ReturnType<typeof setTimeout> | null = null;
export function onEarbudsPressStart() {
  if (earbudsPressTimer) clearTimeout(earbudsPressTimer);
  earbudsPressTimer = setTimeout(() => {
    earbudsMenuOpen.value = true;
    earbudsPressTimer = null;
  }, 650);
}
export function onEarbudsPressEnd() {
  if (earbudsPressTimer) {
    clearTimeout(earbudsPressTimer);
    earbudsPressTimer = null;
  }
}

export const unlisten: UnlistenFn[] = [];
export let scanLoopActive = false;

export const primaryPeer = computed(() => peers.value[0] ?? null);
export const primaryPeerState = computed<PeerState | null>(() => {
  const p = primaryPeer.value;
  return p ? peerStates.value[p.peer_static_pub] ?? null : null;
});
export const peerEarbuds = computed(() => primaryPeerState.value?.earbuds ?? null);

export const activeEarbuds = computed<
  { name: string; battery: number | null; on: "local" | "peer"; connected: boolean } | null
>(() => {
  const local = localEarbuds.value;
  if (!local) return null;
  const peerHas =
    phoneOnline.value &&
    peerEarbuds.value?.connected === true &&
    peerEarbuds.value.name.toLowerCase() === local.name.toLowerCase();
  if (local.connected) {
    return { name: local.name, battery: local.battery, on: "local", connected: true };
  }
  if (peerHas) {
    return { name: local.name, battery: peerEarbuds.value!.battery, on: "peer", connected: true };
  }
  return { name: local.name, battery: null, on: "local", connected: false };
});

const nowTick = ref(Math.floor(Date.now() / 1000));
setInterval(() => (nowTick.value = Math.floor(Date.now() / 1000)), 10_000);

export const phoneOnline = computed(() => {
  const s = primaryPeerState.value;
  if (!s) return false;
  return nowTick.value - s.ts < 180;
});

export const justPairedAt = ref<number | null>(null);

export const phoneConnecting = computed(() => {
  if (phoneOnline.value || !primaryPeer.value) return false;
  const t0 = justPairedAt.value;
  return t0 != null && nowTick.value - t0 < 30;
});

export async function pairWith(addr: string) {
  showPairPhoneModal.value = false;
  pairingPeer.value = addr;
  pairingResult.value = null;
  pairingSas.value = null;
  pairLocalRejected.value = false;
  await startPair(addr);
}

export function dismissPairing() {
  pairingPeer.value = null;
  pairingResult.value = null;
  pairingSas.value = null;
  pairLocalRejected.value = false;
}

export async function approvePairing() {
  pairingSas.value = null;
  await pairDecision(true);
}

export async function rejectPairing() {
  pairingSas.value = null;
  pairLocalRejected.value = true;
  await pairDecision(false);
}

export async function confirmForget() {
  if (!forgetTarget.value) return;
  const pub = forgetTarget.value.peer_static_pub;
  forgetTarget.value = null;
  await forgetPeer(pub);
}

export function openPairPhoneModal() {
  showPairPhoneModal.value = true;
  if (!scanLoopActive) runScanLoop();
}

export function batteryIcon(pct: number | null, charging = false) {
  if (charging) return SolarBatteryCharge;
  if (pct == null) return SolarBattery;
  if (pct >= 80) return SolarBatteryFull;
  if (pct >= 40) return SolarBatteryHalf;
  return SolarBatteryLow;
}

export function batteryClass(pct: number | null, charging = false) {
  if (charging) return "text-blue-400";
  if (pct == null) return "text-muted-foreground";
  if (pct <= 15) return "text-red-500";
  if (pct <= 30) return "text-amber-500";
  return "text-emerald-500";
}

export let pressTimer: ReturnType<typeof setTimeout> | null = null;
export function onCardPressStart(peer: TrustedPeer) {
  if (pressTimer) clearTimeout(pressTimer);
  pressTimer = setTimeout(() => {
    forgetTarget.value = peer;
    pressTimer = null;
  }, 650);
}
export function onCardPressEnd() {
  if (pressTimer) {
    clearTimeout(pressTimer);
    pressTimer = null;
  }
}

export async function runScanLoop() {
  if (scanLoopActive) return;
  scanLoopActive = true;
  while (
    scanLoopActive &&
    peersLoaded.value &&
    (peers.value.length === 0 || showPairPhoneModal.value) &&
    true &&
    pairingPeer.value === null
  ) {
    scanHits.value = [];
    scanning.value = true;
    await startScan();
    await new Promise<void>(resolve => {
      const deadline = setTimeout(resolve, 11000);
      const tick = setInterval(() => {
        if (!scanning.value) {
          clearTimeout(deadline);
          clearInterval(tick);
          resolve();
        }
      }, 200);
    });
    if (!scanLoopActive) break;
    if (!showPairPhoneModal.value && peers.value.length > 0) break;
    await new Promise(r => setTimeout(r, 3000));
  }
  scanLoopActive = false;
}

export function maybeStartScanLoop() {
  if (
    peersLoaded.value &&
    (peers.value.length === 0 || showPairPhoneModal.value) &&
    true &&
    pairingPeer.value === null
  ) {
    runScanLoop();
  }
}

watch([peers, peersLoaded, pairingPeer, showPairPhoneModal], () => maybeStartScanLoop());

export const proximityHit = ref<ScanHit | null>(null);
const proximityDismissed = new Set<string>();
const PROX_RSSI = -55;

watch(
  scanHits,
  (hits) => {
    if (peers.value.length > 0) return;
    if (pairingPeer.value || showPairPhoneModal.value || proximityHit.value) return;
    const near = hits.find(
      (h) => (h.rssi ?? -100) > PROX_RSSI && !proximityDismissed.has(h.addr),
    );
    if (near) proximityHit.value = near;
  },
  { deep: true },
);
watch([pairingPeer, peers], () => {
  if (pairingPeer.value || peers.value.length > 0) proximityHit.value = null;
});

export function dismissProximity() {
  if (proximityHit.value) proximityDismissed.add(proximityHit.value.addr);
  proximityHit.value = null;
}
export async function pairFromProximity() {
  const addr = proximityHit.value?.addr;
  proximityHit.value = null;
  if (addr) await pairWith(addr);
}

export let _homeStarted = false;
export async function initHome() {
  if (_homeStarted) return;
  _homeStarted = true;
  unlisten.push(await onScanResult(hit => {
    const byAddr = scanHits.value.findIndex(h => h.addr === hit.addr);
    if (byAddr !== -1) {
      const cur = scanHits.value[byAddr];
      scanHits.value[byAddr] = {
        ...cur,
        rssi: hit.rssi,
        name: hit.name ?? cur.name,
        instance: hit.instance,
      };
      return;
    }
    if (hit.name) {
      const byName = scanHits.value.findIndex(h => h.name === hit.name);
      if (byName !== -1) {
        scanHits.value[byName] = hit;
        return;
      }
    }
    scanHits.value.push(hit);
  }));
  unlisten.push(await onScanDone(() => (scanning.value = false)));
  unlisten.push(await onPairingStarted(e => {
    pairingPeer.value = e.peer_addr;
    pairingResult.value = null;
  }));
  unlisten.push(await onPairingResult(r => {
    pairingResult.value = r;
    if (r.ok) justPairedAt.value = Math.floor(Date.now() / 1000);
  }));
  unlisten.push(await onPairingSas(sas => {
    pairingSas.value = sas;
    pairDecision(true).catch(() => {});
  }));
  unlisten.push(await onLocalEarbuds(snap => {
    localEarbuds.value = snap;
  }));
  unlisten.push(await onSwitchState(s => {
    const prev = switchState.value.kind;
    switchState.value = s;
    const wasActive = prev !== "idle" && prev !== "failed";
    const nowDone = s.kind === "idle" || s.kind === "failed";
    if (wasActive && nowDone) {
      [50, 400, 1000].forEach(ms =>
        setTimeout(() => refreshLocalEarbuds().catch(() => {}), ms),
      );
    }
  }));
  refreshLocalEarbuds().catch(() => {});
  unlisten.push(await onBusy(_b => {  }));
  await refreshState();
  initSmartSwitch();
  initNotifMirror();
  maybeStartScanLoop();
}


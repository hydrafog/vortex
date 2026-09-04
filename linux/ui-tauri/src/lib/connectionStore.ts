import { ref } from "vue";
import {
  onIdentity,
  onPeers,
  onPeerState,
  getPeerStates,
  type IdentityInfo,
  type TrustedPeer,
  type PeerState,
} from "@/lib/bridge";

export const identity = ref<IdentityInfo | null>(null);
export const peers = ref<TrustedPeer[]>([]);
export const peersLoaded = ref(false);
export const peerStates = ref<Record<string, PeerState>>({});

let started = false;

export async function initConnectionStore(): Promise<void> {
  if (started) return;
  started = true;
  await onIdentity((v) => (identity.value = v));
  await onPeers((v) => {
    peers.value = v;
    peersLoaded.value = true;
  });
  await onPeerState((s) => {
    peerStates.value = { ...peerStates.value, [s.peer_static_pub]: s };
  });
  const pollPeerStates = async () => {
    try {
      for (const s of await getPeerStates()) {
        peerStates.value = { ...peerStates.value, [s.peer_static_pub]: s };
      }
    } catch {
    }
  };
  await pollPeerStates();
  setInterval(pollPeerStates, 15_000);
}
